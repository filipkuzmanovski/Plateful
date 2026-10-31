package mk.ukim.finki.wp.recipeappbackend.repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import mk.ukim.finki.wp.recipeappbackend.food.IngredientNames;
import mk.ukim.finki.wp.recipeappbackend.generation.ComparedRecipe;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Finds the approved Plateful recipes that look most like a draft, for the novelty check (spec section 5.4).
 * Plain SQL, like {@link ReferenceDataRepository}; user input is only ever bound as a parameter.
 */
@Repository
public class PlatefulComparisonRepository {

    /** How many candidate recipes the SQL step hands to the Java ranking. */
    static final int CANDIDATES = 50;

    private final NamedParameterJdbcTemplate jdbc;

    public PlatefulComparisonRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Up to {@code limit} approved recipes (never pending or rejected ones) sharing the most ingredient names
     * with {@code names}, most shared first; ties go to the recipe with fewer ingredients, then the title.
     * {@code names} may be raw; they are normalised here with {@link IngredientNames#normalise}. Each result
     * carries the title and the normalised ingredient names of the recipe. The SQL only narrows the field
     * (up to {@value #CANDIDATES} recipes whose ingredient text contains one of the names); the overlap is
     * counted on the normalised names in Java.
     */
    public List<ComparedRecipe> closestApproved(Set<String> names, int limit) {
        Set<String> wanted = new LinkedHashSet<>();
        for (String name : names) {
            IngredientNames.normalise(name).ifPresent(wanted::add);
        }
        if (wanted.isEmpty() || limit <= 0) {
            return List.of();
        }

        // Normalised names hold only [a-z ], so the patterns need no LIKE escaping.
        String[] patterns = wanted.stream().map(w -> "%" + w + "%").toArray(String[]::new);
        List<UUID> ids = jdbc.queryForList("""
                        SELECT r.id
                        FROM recipes r
                        JOIN recipe_ingredients i ON i.recipe_id = r.id
                        WHERE r.status = 'approved'
                          AND lower(i.ingredient_name) LIKE ANY (CAST(:patterns AS text[]))
                        GROUP BY r.id
                        ORDER BY count(*) DESC, r.id
                        LIMIT :candidates
                        """,
                new MapSqlParameterSource().addValue("patterns", patterns).addValue("candidates", CANDIDATES),
                UUID.class);
        if (ids.isEmpty()) {
            return List.of();
        }

        Map<UUID, String> titles = new LinkedHashMap<>();
        Map<UUID, Set<String>> ingredientNames = new LinkedHashMap<>();
        jdbc.query("""
                        SELECT r.id, COALESCE(r.approved_title, r.title) AS title, i.ingredient_name
                        FROM recipes r
                        JOIN recipe_ingredients i ON i.recipe_id = r.id
                        WHERE r.status = 'approved' AND r.id IN (:ids)
                        ORDER BY r.id, i.sort_order
                        """,
                new MapSqlParameterSource().addValue("ids", ids),
                rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    titles.putIfAbsent(id, rs.getString("title"));
                    Set<String> set = ingredientNames.computeIfAbsent(id, k -> new LinkedHashSet<>());
                    IngredientNames.normalise(rs.getString("ingredient_name")).ifPresent(set::add);
                });

        List<ComparedRecipe> ranked = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : titles.entrySet()) {
            ranked.add(new ComparedRecipe(entry.getValue(), ingredientNames.get(entry.getKey())));
        }
        ranked.sort(Comparator
                .comparingLong((ComparedRecipe r) -> r.names().stream().filter(wanted::contains).count())
                .reversed()
                .thenComparingInt(r -> r.names().size())
                .thenComparing(ComparedRecipe::title));
        return ranked.size() > limit ? List.copyOf(ranked.subList(0, limit)) : List.copyOf(ranked);
    }
}
