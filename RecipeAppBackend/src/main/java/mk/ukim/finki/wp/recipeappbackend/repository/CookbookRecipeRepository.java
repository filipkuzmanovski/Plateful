package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.entities.CookbookRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.CookbookRecipeId;
import mk.ukim.finki.wp.recipeappbackend.model.projection.CookbookRecipeCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CookbookRecipeRepository extends JpaRepository<CookbookRecipe, CookbookRecipeId> {

    @Query("SELECT c.id.recipeId FROM CookbookRecipe c WHERE c.id.cookbookId = :cookbookId ORDER BY c.position ASC")
    List<UUID> findRecipeIdsInOrder(@Param("cookbookId") UUID cookbookId);

    // Empty cookbooks do not appear; treat missing as 0.
    @Query("""
            SELECT new mk.ukim.finki.wp.recipeappbackend.model.projection.CookbookRecipeCount(
                c.id.cookbookId, COUNT(c))
            FROM CookbookRecipe c
            WHERE c.id.cookbookId IN :cookbookIds
            GROUP BY c.id.cookbookId
            """)
    List<CookbookRecipeCount> countByCookbookIds(@Param("cookbookIds") Collection<UUID> cookbookIds);
}
