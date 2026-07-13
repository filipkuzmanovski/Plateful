package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeIngredient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface RecipeIngredientRepository extends JpaRepository<RecipeIngredient, UUID> {

    // Ordered by sort_order — this is what preserves the ingredient list in
    // the order the user originally entered it; without the explicit order,
    // Postgres/JPA give no guarantee about row order on repeated fetches.
    List<RecipeIngredient> findByRecipeIdOrderBySortOrderAsc(UUID recipeId);

    // Batched variant for recipe LIST pages — ingredients for a whole page of
    // recipes in one query instead of one query per recipe (same N+1 medicine
    // as the aggregate summaries). Service groups the flat result by recipe id.
    List<RecipeIngredient> findByRecipeIdInOrderBySortOrderAsc(Collection<UUID> recipeIds);

    // Backs "replace ingredients" during recipe update: delete all rows for
    // the recipe, then insert the new list. Derived deletes need an active
    // transaction — the service method is @Transactional.
    void deleteByRecipeId(UUID recipeId);
}
