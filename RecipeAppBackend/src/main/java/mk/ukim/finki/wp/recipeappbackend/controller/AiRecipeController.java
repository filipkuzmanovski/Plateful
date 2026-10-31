package mk.ukim.finki.wp.recipeappbackend.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import mk.ukim.finki.wp.recipeappbackend.generation.AiRecipeService;
import mk.ukim.finki.wp.recipeappbackend.generation.AiUsageService;
import mk.ukim.finki.wp.recipeappbackend.generation.RecipeGenerationService;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.GenerateRequestDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.AiUsageDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GenerateResultDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GeneratedRecipeDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GeneratedRecipeSummaryDto;
import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's AI recipes. Identity comes from the JWT only. {@code {id}} is a UUID, so a request for
 * {@code /usage} (an exact path, which Spring prefers anyway) can never be read as an id.
 */
@RestController
@RequestMapping("/api/users/me/ai-recipes")
public class AiRecipeController {

    private final RecipeGenerationService generation;
    private final AiRecipeService recipes;
    private final AiUsageService usage;

    public AiRecipeController(RecipeGenerationService generation, AiRecipeService recipes, AiUsageService usage) {
        this.generation = generation;
        this.recipes = recipes;
        this.usage = usage;
    }

    /** Can take up to a minute: the AI call happens inside. */
    @PostMapping("/generate")
    public GenerateResultDto generate(@CurrentUserId UUID userId, @Valid @RequestBody GenerateRequestDto body) {
        return generation.generate(userId, body);
    }

    /** The saved ones, newest saved first. Size is capped by spring.data.web.pageable.max-page-size. */
    @GetMapping
    public Page<GeneratedRecipeSummaryDto> saved(@CurrentUserId UUID userId, Pageable pageable) {
        return recipes.saved(userId, pageable);
    }

    @GetMapping("/usage")
    public AiUsageDto usage(@CurrentUserId UUID userId) {
        return usage.usage(userId);
    }

    @GetMapping("/{id}")
    public GeneratedRecipeDto get(@CurrentUserId UUID userId, @PathVariable UUID id) {
        return recipes.get(userId, id);
    }

    @PutMapping("/{id}/saved")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@CurrentUserId UUID userId, @PathVariable UUID id) {
        recipes.save(userId, id);
    }

    @DeleteMapping("/{id}/saved")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsave(@CurrentUserId UUID userId, @PathVariable UUID id) {
        recipes.unsave(userId, id);
    }
}
