package mk.ukim.finki.wp.recipeappbackend.controller;

import jakarta.validation.Valid;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeRatingCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeRatingDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserId;
import mk.ukim.finki.wp.recipeappbackend.service.RatingService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/recipes/{recipeId}/ratings")
public class RatingController {

    private final RatingService ratingService;

    public RatingController(RatingService ratingService) {
        this.ratingService = ratingService;
    }

    @GetMapping
    public Page<RecipeRatingDisplayDto> list(@PathVariable UUID recipeId, Pageable pageable) {
        return ratingService.getForRecipe(recipeId, pageable);
    }

    /** Rate or re-rate — the service upserts on (recipeId, userId), so PUT is naturally idempotent. */
    @PutMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rate(@CurrentUserId UUID userId,
                     @PathVariable UUID recipeId,
                     @Valid @RequestBody RecipeRatingCreateDto dto) {
        ratingService.rate(userId, recipeId, dto);
    }

    /** 404 = "you haven't rated this one" — the UI shows empty stars. */
    @GetMapping("/me")
    public Map<String, Integer> myRating(@CurrentUserId UUID userId, @PathVariable UUID recipeId) {
        Integer rating = ratingService.getMyRating(userId, recipeId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No rating by you for recipe " + recipeId));
        return Map.of("rating", rating);
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMyRating(@CurrentUserId UUID userId, @PathVariable UUID recipeId) {
        ratingService.deleteMyRating(userId, recipeId);
    }
}
