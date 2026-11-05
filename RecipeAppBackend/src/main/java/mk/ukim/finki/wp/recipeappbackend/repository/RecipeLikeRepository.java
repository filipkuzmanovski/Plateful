package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeLike;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeLikeId;
import mk.ukim.finki.wp.recipeappbackend.model.projection.RecipeLikeCount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface RecipeLikeRepository extends JpaRepository<RecipeLike, RecipeLikeId> {

    /**
     * Returns the rows inserted, 0 or 1. Liking twice is harmless and race-free, and selecting from recipes
     * means a recipe deleted in the meantime inserts nothing instead of breaking the foreign key.
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = "INSERT INTO recipe_likes (user_id, recipe_id) "
            + "SELECT CAST(:userId AS uuid), r.id FROM recipes r WHERE r.id = :recipeId "
            + "ON CONFLICT (user_id, recipe_id) DO NOTHING")
    int insertIgnore(@Param("userId") UUID userId, @Param("recipeId") UUID recipeId);

    // Bulk DELETE so it runs immediately and is a no-op when the pair is not liked.
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM RecipeLike l WHERE l.id.userId = :userId AND l.id.recipeId = :recipeId")
    void deleteByIdUserIdAndIdRecipeId(@Param("userId") UUID userId, @Param("recipeId") UUID recipeId);

    // Newest like first; recipe_id breaks ties so paging stays stable.
    @Query(value = "SELECT l.id.recipeId FROM RecipeLike l WHERE l.id.userId = :userId "
            + "ORDER BY l.createdAt DESC, l.id.recipeId ASC",
            countQuery = "SELECT COUNT(l) FROM RecipeLike l WHERE l.id.userId = :userId")
    Page<UUID> findRecipeIdsByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT l.id.recipeId FROM RecipeLike l WHERE l.id.userId = :userId "
            + "ORDER BY l.createdAt DESC, l.id.recipeId ASC")
    List<UUID> findAllRecipeIdsByUserId(@Param("userId") UUID userId);

    long countByIdRecipeId(UUID recipeId);

    // Recipes with no likes do not appear; treat missing as 0.
    @Query("""
            SELECT new mk.ukim.finki.wp.recipeappbackend.model.projection.RecipeLikeCount(
                l.id.recipeId, COUNT(l))
            FROM RecipeLike l
            WHERE l.id.recipeId IN :recipeIds
            GROUP BY l.id.recipeId
            """)
    List<RecipeLikeCount> findLikeCounts(@Param("recipeIds") Collection<UUID> recipeIds);

    @Query("SELECT l.id.recipeId FROM RecipeLike l WHERE l.id.userId = :userId AND l.id.recipeId IN :recipeIds")
    List<UUID> findLikedAmong(@Param("userId") UUID userId, @Param("recipeIds") Collection<UUID> recipeIds);
}
