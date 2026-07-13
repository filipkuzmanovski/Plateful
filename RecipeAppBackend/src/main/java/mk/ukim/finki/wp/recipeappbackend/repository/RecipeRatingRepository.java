package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeRating;
import mk.ukim.finki.wp.recipeappbackend.model.projection.RecipeRatingSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecipeRatingRepository extends JpaRepository<RecipeRating, UUID> {

    // Backs GET /api/recipes/{id}/ratings
    // BUGFIX (review issue #6, N+1): every rating on a page gets mapped to a
    // DTO that calls getUser() — with a plain LAZY @ManyToOne that fired ONE
    // extra SELECT per row (20 ratings = 21 queries). @EntityGraph tells JPA
    // to JOIN-fetch the user in the same query as the page itself.
    @EntityGraph(attributePaths = "user")
    Page<RecipeRating> findByRecipeId(UUID recipeId, Pageable pageable);

    // Still useful for reads (e.g. "what did *I* rate this recipe?"), but no
    // longer the write path — see upsertRating() below.
    Optional<RecipeRating> findByRecipeIdAndUserId(UUID recipeId, UUID userId);

    long countByRecipeId(UUID recipeId);

    // Per-recipe versions — fine for a single recipe detail page.
    @Query("SELECT AVG(r.rating) FROM RecipeRating r WHERE r.recipe.id = :recipeId")
    Double findAverageRatingByRecipeId(@Param("recipeId") UUID recipeId);

    // BUGFIX (review issue #6, N+1 aggregates): batched variant for LIST
    // pages. A browse page of 20 recipes previously needed 40 queries
    // (AVG + COUNT each); this GROUP BY returns all of it in one. Recipes
    // with zero ratings simply won't appear in the result — treat missing
    // as count 0 / average null in the service layer.
    @Query("""
            SELECT new mk.ukim.finki.wp.recipeappbackend.model.projection.RecipeRatingSummary(
                r.recipe.id, AVG(r.rating), COUNT(r))
            FROM RecipeRating r
            WHERE r.recipe.id IN :recipeIds
            GROUP BY r.recipe.id
            """)
    List<RecipeRatingSummary> findRatingSummaries(@Param("recipeIds") Collection<UUID> recipeIds);

    // BUGFIX (review issue #4, upsert race): the old plan — findByRecipeIdAndUserId()
    // then insert-or-update in the service — has a race window: two concurrent
    // first-time ratings both find nothing, both INSERT, one dies on the
    // UNIQUE(recipe_id, user_id) constraint and surfaces as a 500. Postgres's
    // ON CONFLICT does the whole thing atomically in a single statement, so
    // there is no window at all. id/created_at/updated_at come from the
    // column DEFAULTs on insert; updated_at is set explicitly on the update
    // branch because @UpdateTimestamp only fires for Hibernate-managed
    // writes, never for native SQL.
    // NOTE: @Modifying queries need an active transaction — annotate the
    // calling service method with @Transactional.
    @Modifying
    @Query(value = """
            INSERT INTO recipe_ratings (recipe_id, user_id, rating)
            VALUES (:recipeId, :userId, :rating)
            ON CONFLICT (recipe_id, user_id)
            DO UPDATE SET rating = EXCLUDED.rating, updated_at = now()
            """, nativeQuery = true)
    void upsertRating(@Param("recipeId") UUID recipeId,
                      @Param("userId") UUID userId,
                      @Param("rating") int rating);

    // "Remove my rating" — scoped to one (recipe, user) pair so a user can
    // only ever delete their own row. Needs @Transactional at the call site.
    void deleteByRecipeIdAndUserId(UUID recipeId, UUID userId);
}
