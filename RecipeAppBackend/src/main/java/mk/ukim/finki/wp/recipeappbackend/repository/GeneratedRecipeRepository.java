package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.entities.GeneratedRecipe;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GeneratedRecipeRepository extends JpaRepository<GeneratedRecipe, UUID> {

    /** Scoped to the owner: another user's id looks exactly like a missing one. */
    Optional<GeneratedRecipe> findByIdAndUserId(UUID id, UUID userId);

    /** The user's saved generated recipes; callers pass a sort on {@code savedAt} descending. */
    Page<GeneratedRecipe> findByUserIdAndSavedAtIsNotNull(UUID userId, Pageable pageable);

    /** Titles of the user's daily meals created since {@code since}, to steer away from repeats. */
    @Query("SELECT g.title FROM GeneratedRecipe g WHERE g.user.id = :userId AND g.kind = 'daily' "
            + "AND g.createdAt >= :since ORDER BY g.createdAt DESC")
    List<String> recentDailyTitles(@Param("userId") UUID userId, @Param("since") OffsetDateTime since);
}
