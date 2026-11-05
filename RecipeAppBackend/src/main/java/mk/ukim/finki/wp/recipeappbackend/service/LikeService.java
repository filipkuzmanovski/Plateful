package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.SavedItemDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeLikeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * A user's likes of published recipes. Separate from saves: a like counts publicly and feeds cookbooks, a save
 * is a private bookmark. Identity always arrives as a parameter from the JWT.
 */
@Service
public class LikeService {

    private final RecipeLikeRepository likeRepository;
    private final RecipeRepository recipeRepository;
    private final RecipeVisibility visibility;
    private final SavedRecipeService savedRecipeService;

    public LikeService(RecipeLikeRepository likeRepository, RecipeRepository recipeRepository,
                       RecipeVisibility visibility, SavedRecipeService savedRecipeService) {
        this.likeRepository = likeRepository;
        this.recipeRepository = recipeRepository;
        this.visibility = visibility;
        this.savedRecipeService = savedRecipeService;
    }

    /**
     * Idempotent. Only an approved recipe can be liked; the visibility rule picks the error otherwise. A recipe
     * deleted between the check and the insert inserts nothing, which is told apart from "already liked" here.
     */
    public void like(UUID userId, UUID recipeId) {
        Recipe recipe = recipeRepository.findById(recipeId)
                .orElseThrow(() -> new ResourceNotFoundException("Recipe", recipeId));
        visibility.requireApprovedForInteraction(recipe, userId);
        int inserted = likeRepository.insertIgnore(userId, recipeId);
        if (inserted == 0 && !recipeRepository.existsById(recipeId)) {
            throw new ResourceNotFoundException("Recipe", recipeId);
        }
    }

    /** Idempotent, and allowed whatever state the recipe is in now. */
    public void unlike(UUID userId, UUID recipeId) {
        likeRepository.deleteByIdUserIdAndIdRecipeId(userId, recipeId);
    }

    /** Newest like first, with the same cards and stubs as the Saved list. */
    @Transactional(readOnly = true)
    public Page<SavedItemDto> list(UUID userId, Pageable pageable) {
        // The repository query owns the ordering, so the page request must carry no Sort.
        Page<UUID> idPage = likeRepository.findRecipeIdsByUserId(
                userId, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        List<SavedItemDto> items = savedRecipeService.assembleItems(idPage.getContent(), userId);
        return new PageImpl<>(items, idPage.getPageable(), idPage.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<UUID> ids(UUID userId) {
        return likeRepository.findAllRecipeIdsByUserId(userId);
    }
}
