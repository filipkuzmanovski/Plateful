package mk.ukim.finki.wp.recipeappbackend.repository;

import jakarta.persistence.LockModeType;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeComment;
import mk.ukim.finki.wp.recipeappbackend.model.projection.RecipeCommentCount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecipeCommentRepository extends JpaRepository<RecipeComment, UUID> {

    // Backs GET /api/recipes/{id}/comments — newest first by default
    // BUGFIX (review issue #6, N+1): every comment row gets mapped to a DTO
    // that calls getUser() for the author's name — JOIN-fetch the user in
    // the same query as the page instead of one SELECT per comment.
    @EntityGraph(attributePaths = "user")
    Page<RecipeComment> findByRecipeIdOrderByCreatedAtDesc(UUID recipeId, Pageable pageable);

    // The same list for anyone but an admin: a hidden comment is left out unless the viewer wrote it.
    // A null viewer (anonymous) matches no author, so it sees shown comments only.
    @EntityGraph(attributePaths = "user")
    @Query(value = """
            SELECT c FROM RecipeComment c
            WHERE c.recipe.id = :recipeId AND (c.hiddenAt IS NULL OR c.user.id = :viewerId)
            ORDER BY c.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(c) FROM RecipeComment c
            WHERE c.recipe.id = :recipeId AND (c.hiddenAt IS NULL OR c.user.id = :viewerId)
            """)
    Page<RecipeComment> findShownByRecipeId(@Param("recipeId") UUID recipeId,
                                            @Param("viewerId") UUID viewerId,
                                            Pageable pageable);

    // Row lock for writes that must not race each other on one comment: reports (hiding), edits and,
    // later, dismissals. Callers that also lock the recipe take the recipe lock FIRST (see ReportService).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RecipeComment> findForUpdateById(UUID id);

    // The comment's recipe id without loading the comment entity, so a later locked read of the comment
    // is not answered from a stale copy in the persistence context.
    @Query("SELECT c.recipe.id FROM RecipeComment c WHERE c.id = :id")
    Optional<UUID> findRecipeIdById(@Param("id") UUID id);

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
