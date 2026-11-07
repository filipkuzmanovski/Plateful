package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.nutrition.NutritionTarget;
import mk.ukim.finki.wp.recipeappbackend.nutrition.NutritionTotals;
import mk.ukim.finki.wp.recipeappbackend.nutrition.StoredEstimate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code nutrition_estimates} table: which recipe the estimator works on next, and the stored
 * whole-recipe totals. Plain SQL with bound parameters; every statement is one round trip and none
 * holds a connection while an AI call runs. Inserts keep the first row on conflict.
 */
@Repository
public class NutritionEstimateRepository {

    private static final RowMapper<StoredEstimate> STORED = (rs, i) -> new StoredEstimate(
            rs.getString("status"),
            rs.getBigDecimal("calories"),
            rs.getBigDecimal("protein_grams"),
            rs.getBigDecimal("fat_grams"),
            rs.getBigDecimal("carbs_grams"),
            rs.getString("ingredients_hash"));

    private static final String STORED_COLUMNS =
            "status, calories, protein_grams, fat_grams, carbs_grams, ingredients_hash";

    private static final String NEXT = "SELECT kind, id FROM ("
            + " SELECT 'recipe' AS kind, r.id, COALESCE(r.reviewed_at, r.created_at) AS dated_at"
            + "   FROM recipes r"
            + "  WHERE r.source = 'user' AND r.status = 'approved' AND r.hidden_at IS NULL AND r.servings >= 1"
            + "    AND NOT EXISTS (SELECT 1 FROM nutrition_estimates e WHERE e.recipe_id = r.id)"
            + " UNION ALL"
            + " SELECT 'generated', g.id, g.created_at"
            + "   FROM generated_recipes g"
            + "  WHERE (g.saved_at IS NOT NULL OR g.created_at >= :since)"
            + "    AND NOT EXISTS (SELECT 1 FROM nutrition_estimates e WHERE e.generated_recipe_id = g.id)"
            + ") candidates ORDER BY dated_at DESC LIMIT 1";

    private final NamedParameterJdbcTemplate jdbc;

    public NutritionEstimateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The newest recipe with no row yet: an approved upload with servings, or an AI recipe that is
     * saved or created at or after {@code generatedSince}.
     */
    public Optional<NutritionTarget> next(OffsetDateTime generatedSince) {
        return jdbc.query(NEXT, new MapSqlParameterSource().addValue("since", generatedSince),
                        (rs, i) -> new NutritionTarget(
                                "recipe".equals(rs.getString("kind"))
                                        ? NutritionTarget.Kind.RECIPE : NutritionTarget.Kind.GENERATED,
                                rs.getObject("id", UUID.class)))
                .stream().findFirst();
    }

    /** Rows created at or after {@code since}, failed ones included because they spent tokens. */
    public int createdSince(OffsetDateTime since) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM nutrition_estimates WHERE created_at >= :since",
                new MapSqlParameterSource().addValue("since", since), Integer.class);
        return n == null ? 0 : n;
    }

    public void saveReady(NutritionTarget target, NutritionTotals totals, String breakdownJson, String hash,
                          String provider, String model) {
        jdbc.update("INSERT INTO nutrition_estimates (" + ownerColumn(target) + ", status, calories, "
                        + "protein_grams, fat_grams, carbs_grams, breakdown, ingredients_hash, provider, model) "
                        + "VALUES (:owner, 'ready', :calories, :protein, :fat, :carbs, CAST(:breakdown AS jsonb), "
                        + ":hash, :provider, :model) ON CONFLICT DO NOTHING",
                new MapSqlParameterSource().addValue("owner", target.id())
                        .addValue("calories", totals.calories()).addValue("protein", totals.protein())
                        .addValue("fat", totals.fat()).addValue("carbs", totals.carbs())
                        .addValue("breakdown", breakdownJson).addValue("hash", hash)
                        .addValue("provider", provider).addValue("model", model));
    }

    public void saveFailed(NutritionTarget target, String hash) {
        jdbc.update("INSERT INTO nutrition_estimates (" + ownerColumn(target) + ", status, ingredients_hash) "
                        + "VALUES (:owner, 'failed', :hash) ON CONFLICT DO NOTHING",
                new MapSqlParameterSource().addValue("owner", target.id()).addValue("hash", hash));
    }

    public Optional<StoredEstimate> forRecipe(UUID recipeId) {
        return find("recipe_id", recipeId);
    }

    public Optional<StoredEstimate> forGenerated(UUID generatedRecipeId) {
        return find("generated_recipe_id", generatedRecipeId);
    }

    /** Drops the recipe's row (failed ones too) when its ingredients changed, so it is estimated again. */
    public void deleteForRecipeIfHashDiffers(UUID recipeId, String hash) {
        jdbc.update("DELETE FROM nutrition_estimates WHERE recipe_id = :id AND ingredients_hash <> :hash",
                new MapSqlParameterSource().addValue("id", recipeId).addValue("hash", hash));
    }

    private Optional<StoredEstimate> find(String column, UUID id) {
        return jdbc.query("SELECT " + STORED_COLUMNS + " FROM nutrition_estimates WHERE " + column + " = :id",
                new MapSqlParameterSource().addValue("id", id), STORED).stream().findFirst();
    }

    // Fixed column names chosen from the enum, never from input.
    private static String ownerColumn(NutritionTarget target) {
        return target.kind() == NutritionTarget.Kind.RECIPE ? "recipe_id" : "generated_recipe_id";
    }
}
