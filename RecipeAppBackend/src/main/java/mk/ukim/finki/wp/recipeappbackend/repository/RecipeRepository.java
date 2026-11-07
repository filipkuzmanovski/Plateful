package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.projection.CuisineCount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
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

    // the same listing narrowed to one review state (the public profile shows APPROVED only)
    Page<Recipe> findByUserIdAndStatusAndHiddenAtIsNull(UUID userId, RecipeStatus status, Pageable pageable);

    // how many of a user's recipes are in a given review state (e.g. rejected, for the profile badge)
    long countByUserIdAndStatus(UUID userId, RecipeStatus status);

    // approve/reject take the row lock first, so two admins cannot both decide the same recipe
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Recipe> findForReviewById(UUID id);

    // the admin reports queue: one query for the page's recipes with their authors (imported recipes have none)
    @Query("SELECT r FROM Recipe r LEFT JOIN FETCH r.user WHERE r.id IN :ids")
    List<Recipe> findWithAuthorByIdIn(@Param("ids") Collection<UUID> ids);

    // approved recipe count per cuisine key (unnests the text[] column); callers map keys onto the catalogue
    @Query(value = "select c as key, count(*) as count from recipes, unnest(cuisines) c where status = 'approved' and hidden_at is null group by c", nativeQuery = true)
    List<CuisineCount> countCuisines();
}
