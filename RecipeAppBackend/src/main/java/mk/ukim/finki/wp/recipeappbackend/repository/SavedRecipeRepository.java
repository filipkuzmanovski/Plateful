package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.entities.SavedRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.SavedRecipeId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface SavedRecipeRepository extends JpaRepository<SavedRecipe, SavedRecipeId> {

    // Saving twice is harmless and race-free: the primary key makes the second insert a no-op.
    // created_at comes from the column default.
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = "INSERT INTO saved_recipes (user_id, recipe_id) "
            + "VALUES (:userId, :recipeId) ON CONFLICT (user_id, recipe_id) DO NOTHING")
    void insertIgnore(@Param("userId") UUID userId, @Param("recipeId") UUID recipeId);

    // Bulk DELETE so it runs immediately and is a no-op when the pair is not saved.
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM SavedRecipe s WHERE s.id.userId = :userId AND s.id.recipeId = :recipeId")
    void deleteByIdUserIdAndIdRecipeId(@Param("userId") UUID userId, @Param("recipeId") UUID recipeId);

    // Newest saved first; recipe_id breaks ties so paging stays stable.
    @Query(value = "SELECT s.id.recipeId FROM SavedRecipe s WHERE s.id.userId = :userId "
            + "ORDER BY s.createdAt DESC, s.id.recipeId ASC",
            countQuery = "SELECT COUNT(s) FROM SavedRecipe s WHERE s.id.userId = :userId")
    Page<UUID> findRecipeIdsByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT s.id.recipeId FROM SavedRecipe s WHERE s.id.userId = :userId "
            + "ORDER BY s.createdAt DESC, s.id.recipeId ASC")
    List<UUID> findAllRecipeIdsByUserId(@Param("userId") UUID userId);
}
