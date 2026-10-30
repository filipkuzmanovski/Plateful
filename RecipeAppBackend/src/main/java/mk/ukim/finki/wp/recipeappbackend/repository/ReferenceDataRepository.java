package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.projection.ReferenceQuery;
import mk.ukim.finki.wp.recipeappbackend.model.projection.ReferenceRecipe;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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
     * diets and dislikes, fewest ingredients first on ties. All names in {@code q} must already be
     * normalised. An empty {@code wanted} returns an empty list without querying; an unknown diet
     * key throws {@link IllegalArgumentException}.
     */
    public List<ReferenceRecipe> findClosest(ReferenceQuery q) {
        if (q.wanted().isEmpty()) {
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
                .addValue("wanted", textArray(q.wanted()))
                .addValue("limit", q.limit());
        if (q.mealType() != null) {
            sql.append(" AND CAST(:mealType AS text) = ANY(meal_types)\n");
            params.addValue("mealType", q.mealType());
        }
        if (!q.allergens().isEmpty()) {
            sql.append(" AND NOT (allergens && CAST(:allergens AS text[]))\n");
            params.addValue("allergens", textArray(q.allergens()));
        }
        for (String diet : q.diets()) {
            String column = DIET_COLUMNS.get(diet);
            if (column == null) {
                throw new IllegalArgumentException("Unknown diet: " + diet);
            }
            sql.append(" AND ").append(column).append('\n');
        }
        if (!q.dislikes().isEmpty()) {
            // Names hold only [a-z ], so the patterns need no LIKE escaping. Padding with spaces
            // makes the match a whole-word (or whole-phrase) match.
            sql.append(" AND NOT EXISTS (SELECT 1 FROM unnest(ingredient_names) AS n "
                    + "WHERE (' ' || n || ' ') LIKE ANY (CAST(:dislikePatterns AS text[])))\n");
            params.addValue("dislikePatterns",
                    q.dislikes().stream().map(d -> "% " + d + " %").toArray(String[]::new));
        }
        sql.append(" ORDER BY overlap DESC, cardinality(ingredient_names), id LIMIT :limit");

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new ReferenceRecipe(
                rs.getLong("id"),
                rs.getString("title"),
                list(rs.getArray("ingredient_lines")),
                list(rs.getArray("ingredient_names")),
                list(rs.getArray("directions")),
                list(rs.getArray("meal_types")),
                rs.getInt("overlap")));
    }

    private static String[] textArray(Collection<String> values) {
        return values.toArray(String[]::new);
    }

    private static List<String> list(Array array) throws SQLException {
        return Arrays.asList((String[]) array.getArray());
    }
}
