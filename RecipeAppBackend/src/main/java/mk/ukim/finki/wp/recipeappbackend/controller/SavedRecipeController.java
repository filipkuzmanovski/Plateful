package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.SavedItemDto;
import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserId;
import mk.ukim.finki.wp.recipeappbackend.service.SavedRecipeService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** The caller's saved recipes. Identity comes from the JWT only, never a path or body value. */
@RestController
@RequestMapping("/api/users/me/saved")
public class SavedRecipeController {

    private final SavedRecipeService savedRecipeService;

    public SavedRecipeController(SavedRecipeService savedRecipeService) {
        this.savedRecipeService = savedRecipeService;
    }

    @PutMapping("/{recipeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@CurrentUserId UUID userId, @PathVariable UUID recipeId) {
        savedRecipeService.save(userId, recipeId);
    }

    @DeleteMapping("/{recipeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsave(@CurrentUserId UUID userId, @PathVariable UUID recipeId) {
        savedRecipeService.unsave(userId, recipeId);
    }

    /** Size is capped by spring.data.web.pageable.max-page-size. */
    @GetMapping
    public Page<SavedItemDto> list(@CurrentUserId UUID userId, Pageable pageable) {
        return savedRecipeService.list(userId, pageable);
    }

    /** Every saved recipe id, so the UI can show bookmark state without paging. */
    @GetMapping("/ids")
    public List<UUID> ids(@CurrentUserId UUID userId) {
        return savedRecipeService.ids(userId);
    }
}
