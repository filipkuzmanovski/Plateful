package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

/**
 * Extends JpaSpecificationExecutor even though nothing uses it yet — that's
 * the seam for the multi-criteria recipe filtering (cuisine + diet + cook
 * time + rating, all optional, combined) we talked about earlier. We'll
 * write the actual Specification classes when we build that feature; for
 * now this line costs nothing and saves a refactor later.
 */
public interface RecipeRepository extends JpaRepository<Recipe, UUID>, JpaSpecificationExecutor<Recipe> {

    // "recipes this user has published" — a profile page use case
    // BUGFIX (review issue #6): was an unpaged List<Recipe> — a user with
    // 500 recipes would load all 500 rows (plus their JSONB blobs) into
    // memory on every profile view. Now paginated like every other listing.
    Page<Recipe> findByUserId(UUID userId, Pageable pageable);

    // dedup key when importing/re-importing from Spoonacular
    Optional<Recipe> findBySpoonacularId(Long spoonacularId);

    boolean existsBySpoonacularId(Long spoonacularId);
}
