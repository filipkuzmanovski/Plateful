package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeComment;
import mk.ukim.finki.wp.recipeappbackend.model.projection.RecipeCommentCount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface RecipeCommentRepository extends JpaRepository<RecipeComment, UUID> {

    // Backs GET /api/recipes/{id}/comments — newest first by default
    // BUGFIX (review issue #6, N+1): every comment row gets mapped to a DTO
    // that calls getUser() for the author's name — JOIN-fetch the user in
    // the same query as the page instead of one SELECT per comment.
    @EntityGraph(attributePaths = "user")
    Page<RecipeComment> findByRecipeIdOrderByCreatedAtDesc(UUID recipeId, Pageable pageable);

    long countByRecipeId(UUID recipeId);

    // BUGFIX (review issue #6, N+1 aggregates): batched variant for recipe
    // LIST pages — comment counts for all visible recipes in one GROUP BY
    // query instead of one countByRecipeId() per recipe. Recipes with zero
    // comments won't appear — treat missing as 0 in the service layer.
    @Query("""
            SELECT new mk.ukim.finki.wp.recipeappbackend.model.projection.RecipeCommentCount(
                c.recipe.id, COUNT(c))
            FROM RecipeComment c
            WHERE c.recipe.id IN :recipeIds
            GROUP BY c.recipe.id
            """)
    List<RecipeCommentCount> findCommentCounts(@Param("recipeIds") Collection<UUID> recipeIds);
}
