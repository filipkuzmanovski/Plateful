package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeRatingCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeRatingDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeRating;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRatingRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Ratings. No ownership checks needed anywhere here — every operation is
 * inherently scoped to the CALLER's row by (recipeId, userId), so you can't
 * touch anyone else's rating by construction. Review status still applies (spec 4.2): reads need a visible recipe,
 * writes (deleting included) an approved one.
 */
@Service
public class RatingService {

    private final RecipeRatingRepository ratingRepository;
    private final RecipeRepository recipeRepository;
    private final RecipeVisibility visibility;

    public RatingService(RecipeRatingRepository ratingRepository,
                         RecipeRepository recipeRepository,
                         RecipeVisibility visibility) {
        this.ratingRepository = ratingRepository;
        this.recipeRepository = recipeRepository;
        this.visibility = visibility;
    }

    /**
     * Rate or re-rate — same endpoint, same code path. Uses the atomic
     * ON CONFLICT upsert (see the race-condition BUGFIX note on the
     * repository method), so concurrent first-time ratings can't 500.
     * The recipe existence check turns a would-be FK violation (ugly 500)
     * into a clean 404.
     */
    @Transactional
    public void rate(UUID userId, UUID recipeId, RecipeRatingCreateDto dto) {
        visibility.requireApprovedForInteraction(findRecipe(recipeId), userId);
        ratingRepository.upsertRating(recipeId, userId, dto.rating());
    }

    /** Lets the UI pre-fill the caller's stars on the recipe page. */
    @Transactional(readOnly = true)
    public Optional<Integer> getMyRating(UUID userId, UUID recipeId) {
        visibility.requireVisible(findRecipe(recipeId), userId);
        return ratingRepository.findByRecipeIdAndUserId(recipeId, userId)
                .map(RecipeRating::getRating);
    }

    @Transactional
    public void deleteMyRating(UUID userId, UUID recipeId) {
        visibility.requireApprovedForInteraction(findRecipe(recipeId), userId);
        ratingRepository.deleteByRecipeIdAndUserId(recipeId, userId);
    }

    /** Paged ratings list — user is JOIN-fetched by the @EntityGraph, no N+1. */
    @Transactional(readOnly = true)
    public Page<RecipeRatingDisplayDto> getForRecipe(UUID viewerId, UUID recipeId, Pageable pageable) {
        visibility.requireVisible(findRecipe(recipeId), viewerId);
        return ratingRepository.findByRecipeId(recipeId, pageable)
                .map(RecipeRatingDisplayDto::fromEntity);
    }

    private Recipe findRecipe(UUID recipeId) {
        return recipeRepository.findById(recipeId)
                .orElseThrow(() -> new ResourceNotFoundException("Recipe", recipeId));
    }
}
