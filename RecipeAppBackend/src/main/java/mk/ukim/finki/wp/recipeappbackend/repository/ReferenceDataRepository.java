package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.food.IngredientNames;
import mk.ukim.finki.wp.recipeappbackend.model.projection.Pairing;
import mk.ukim.finki.wp.recipeappbackend.model.projection.ReferenceQuery;
import mk.ukim.finki.wp.recipeappbackend.model.projection.ReferenceRecipe;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the dataset tables ({@code reference_recipes}, {@code ingredient_stats}). They have no JPA
 * entities: the queries are array operators that JPQL cannot express, so plain SQL is used. All
 * SQL text is fixed or built from a fixed map; user input is only ever bound as a parameter.
 */
@Repository
public class ReferenceDataRepository {

    /** Diet key to its boolean column. The only source of column names in the generated SQL. */
    private static final Map<String, String> DIET_COLUMNS = Map.of(
            "vegetarian", "vegetarian",
            "vegan", "vegan",
            "gluten_free", "gluten_free",
            "dairy_free", "dairy_free");

    private static final RowMapper<ReferenceRecipe> REFERENCE_ROWS = (rs, rowNum) -> new ReferenceRecipe(
            rs.getLong("id"),
            rs.getString("title"),
            list(rs.getArray("ingredient_lines")),
            list(rs.getArray("ingredient_names")),
            list(rs.getArray("directions")),
            list(rs.getArray("meal_types")),
            rs.getInt("overlap"));

    private final NamedParameterJdbcTemplate jdbc;

    public ReferenceDataRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Ingredient names starting with {@code normalisedPrefix}, most common first. The prefix must
     * already be normalised; LIKE wildcards in it are escaped anyway.
     */
    public List<String> suggestIngredients(String normalisedPrefix, int limit) {
        String escaped = normalisedPrefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return jdbc.queryForList(
                "SELECT name FROM ingredient_stats WHERE name LIKE :p ESCAPE '\\' "
                        + "ORDER BY recipe_count DESC, name LIMIT :n",
                new MapSqlParameterSource().addValue("p", escaped + "%").addValue("n", limit),
                String.class);
    }

    /**
     * The dataset recipes sharing the most names with {@code q.wanted()}, filtered by meal, allergens,
     * diets and dislikes, fewest ingredients first on ties. The {@code wanted} and {@code dislikes}
     * names are normalised here with {@link IngredientNames#normalise} (so "Tomatoes" finds "tomato"),
     * and names that normalise to nothing are dropped. When no wanted name is left this returns an
     * empty list without querying; an unknown diet key throws {@link IllegalArgumentException}.
     */
    public List<ReferenceRecipe> findClosest(ReferenceQuery q) {
        Set<String> wanted = normalised(q.wanted());
        Set<String> dislikes = normalised(q.dislikes());
        if (wanted.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT id, title, ingredient_lines, ingredient_names, directions, meal_types,
                       cardinality(ARRAY(SELECT unnest(ingredient_names)
                                         INTERSECT SELECT unnest(CAST(:wanted AS text[])))) AS overlap
                FROM reference_recipes
                WHERE ingredient_names && CAST(:wanted AS text[])
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("wanted", textArray(wanted))
                .addValue("limit", q.limit());
        appendFilters(sql, params, q.mealType(), q.allergens(), q.diets(), dislikes);
        sql.append(" ORDER BY overlap DESC, cardinality(ingredient_names), id LIMIT :limit");

        return jdbc.query(sql.toString(), params, REFERENCE_ROWS);
    }

    /**
     * Random dataset recipes for {@code mealType} (null means any meal) with the same hard filters as
     * {@link #findClosest}: allergens, diets and dislikes (the dislikes are normalised here). Their
     * {@code overlap} is 0. The frozen tags are only a pre-filter, so callers re-check each recipe in Java.
     * {@code ORDER BY random()} scans the matching rows (about 15,000 per meal), which is fast enough here.
     */
    public List<ReferenceRecipe> randomForMeal(String mealType, Set<String> allergens, Set<String> diets,
                                               Set<String> dislikes, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, title, ingredient_lines, ingredient_names, directions, meal_types,
                       0 AS overlap
                FROM reference_recipes
                WHERE TRUE
                """);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        appendFilters(sql, params, mealType, allergens, diets, normalised(dislikes));
        sql.append(" ORDER BY random() LIMIT :limit");
        return jdbc.query(sql.toString(), params, REFERENCE_ROWS);
    }

    /**
     * The ingredients that most often share a recipe with {@code ingredient}, most common first, as the
     * share of the ingredient's recipes that also contain the partner (from {@code ingredient_pairings} and
     * {@code ingredient_stats}). The name is normalised here; a name with nothing left, or an unknown one,
     * gives an empty list.
     */
    public List<Pairing> pairings(String ingredient, int limit) {
        Optional<String> name = IngredientNames.normalise(ingredient);
        if (name.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                        SELECT p.partner, p.together_count, s.recipe_count
                        FROM ingredient_pairings p
                        JOIN ingredient_stats s ON s.name = p.ingredient
                        WHERE p.ingredient = :ingredient AND s.recipe_count > 0
                        ORDER BY p.together_count DESC, p.partner
                        LIMIT :limit
                        """,
                new MapSqlParameterSource().addValue("ingredient", name.get()).addValue("limit", limit),
                (rs, rowNum) -> new Pairing(
                        rs.getString("partner"),
                        (int) Math.min(100, Math.round(100.0 * rs.getInt("together_count") / rs.getInt("recipe_count")))));
    }

    /**
     * The filter clauses shared by {@link #findClosest} and {@link #randomForMeal}; {@code sql} must already
     * have a WHERE clause. {@code dislikes} must already be normalised. An unknown diet key throws
     * {@link IllegalArgumentException}.
     */
    private static void appendFilters(StringBuilder sql, MapSqlParameterSource params, String mealType,
                                      Set<String> allergens, Set<String> diets, Set<String> dislikes) {
        if (mealType != null) {
            sql.append(" AND CAST(:mealType AS text) = ANY(meal_types)\n");
            params.addValue("mealType", mealType);
        }
        if (!allergens.isEmpty()) {
            sql.append(" AND NOT (allergens && CAST(:allergens AS text[]))\n");
            params.addValue("allergens", textArray(allergens));
        }
        for (String diet : diets) {
            String column = DIET_COLUMNS.get(diet);
            if (column == null) {
                throw new IllegalArgumentException("Unknown diet: " + diet);
            }
            sql.append(" AND ").append(column).append('\n');
        }
        if (!dislikes.isEmpty()) {
            // Names hold only [a-z ], so the patterns need no LIKE escaping. Padding with spaces
            // makes the match a whole-word (or whole-phrase) match.
            sql.append(" AND NOT EXISTS (SELECT 1 FROM unnest(ingredient_names) AS n "
                    + "WHERE (' ' || n || ' ') LIKE ANY (CAST(:dislikePatterns AS text[])))\n");
            params.addValue("dislikePatterns",
                    dislikes.stream().map(d -> "% " + d + " %").toArray(String[]::new));
        }
    }

    private static Set<String> normalised(Collection<String> names) {
        Set<String> result = new LinkedHashSet<>();
        for (String name : names) {
            IngredientNames.normalise(name).ifPresent(result::add);
        }
        return result;
    }

    private static String[] textArray(Collection<String> values) {
        return values.toArray(String[]::new);
    }

    private static List<String> list(Array array) throws SQLException {
        return Arrays.asList((String[]) array.getArray());
    }
}
