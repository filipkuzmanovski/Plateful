package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipePhoto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface RecipePhotoRepository extends JpaRepository<RecipePhoto, UUID> {

    // Position 0 first: that is the cover photo.
    List<RecipePhoto> findByRecipeIdOrderByPositionAsc(UUID recipeId);

    // Batched variant for recipe LIST pages: one query for a whole page of recipes.
    // The service groups the flat result by recipe id.
    List<RecipePhoto> findByRecipeIdInOrderByPositionAsc(Collection<UUID> recipeIds);

    boolean existsByRecipeId(UUID recipeId);

    // Backs "replace photos" on update: delete all rows, then insert the new list. This must be a
    // bulk DELETE, not a derived delete. A derived delete loads the entities and removes them at
    // flush time, after the new inserts, which would violate UNIQUE (recipe_id, position). The bulk
    // query runs immediately; flush first so pending changes are not lost. The context is deliberately
    // NOT cleared: clearing would detach the Recipe the caller is still updating (lazy user proxy,
    // lost field changes). Nothing loads RecipePhoto entities in the same transaction before the
    // delete, so no stale photo rows remain in the context. The caller is @Transactional.
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM RecipePhoto p WHERE p.recipe.id = :recipeId")
    void deleteAllByRecipeId(@Param("recipeId") UUID recipeId);
}
