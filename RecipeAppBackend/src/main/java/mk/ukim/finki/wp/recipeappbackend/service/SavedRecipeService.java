package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.SavedItemDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.SavedRecipeStubDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.SavedRecipeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** A user's saved (bookmarked) recipes. Identity always arrives as a parameter from the JWT. */
@Service
public class SavedRecipeService {

    private final SavedRecipeRepository savedRepository;
    private final RecipeRepository recipeRepository;
    private final RecipeService recipeService;
    private final RecipeVisibility visibility;

    public SavedRecipeService(SavedRecipeRepository savedRepository,
                              RecipeRepository recipeRepository,
                              RecipeService recipeService,
                              RecipeVisibility visibility) {
        this.savedRepository = savedRepository;
        this.recipeRepository = recipeRepository;
        this.recipeService = recipeService;
        this.visibility = visibility;
    }

    /**
     * Idempotent: saving an already-saved recipe is a no-op. 404 if the recipe doesn't exist or isn't
     * visible to the user (an ever-approved hidden recipe throws the 404 that carries its approved title).
     */
    public void save(UUID userId, UUID recipeId) {
        Recipe recipe = recipeRepository.findById(recipeId)
                .orElseThrow(() -> new ResourceNotFoundException("Recipe", recipeId));
        visibility.requireVisible(recipe, userId);
        savedRepository.insertIgnore(userId, recipeId);
    }

    /** Idempotent: removing a recipe that isn't saved is a no-op. */
    public void unsave(UUID userId, UUID recipeId) {
        savedRepository.deleteByIdUserIdAndIdRecipeId(userId, recipeId);
    }

    /**
     * Newest saved first. Visible recipes come back as cards, assembled in one batch; an ever-approved
     * recipe the user can't see right now comes back as a stub in the same position. Rows whose recipe
     * was deleted, or was never approved and isn't the user's to see, are skipped.
     */
    @Transactional(readOnly = true)
    public Page<SavedItemDto> list(UUID userId, Pageable pageable) {
        // The repository query owns the ordering, so the page request must carry no Sort.
        Page<UUID> idPage = savedRepository.findRecipeIdsByUserId(
                userId, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        List<UUID> ids = idPage.getContent();
        Map<UUID, Recipe> byId = recipeRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Recipe::getId, Function.identity()));
        List<Recipe> ordered = ids.stream().map(byId::get).filter(r -> r != null).toList();

        List<Recipe> visible = ordered.stream().filter(r -> visibility.canSee(r, userId)).toList();
        Map<UUID, RecipeDisplayDto> cards = recipeService.toDisplayPage(new PageImpl<>(visible)).getContent()
                .stream().collect(Collectors.toMap(RecipeDisplayDto::id, Function.identity()));

        List<SavedItemDto> items = new ArrayList<>(ordered.size());
        for (Recipe recipe : ordered) {
            RecipeDisplayDto card = cards.get(recipe.getId());
            if (card != null) {
                items.add(SavedItemDto.ofRecipe(card));
            } else if (recipe.isEverApproved()) {
                items.add(SavedItemDto.ofStub(stubOf(recipe)));
            }
        }
        return new PageImpl<>(items, idPage.getPageable(), idPage.getTotalElements());
    }

    private static SavedRecipeStubDto stubOf(Recipe recipe) {
        String state = recipe.getStatus() == RecipeStatus.PENDING ? "under_review" : "unavailable";
        return new SavedRecipeStubDto(recipe.getId(), recipe.getApprovedTitle(), state);
    }

    @Transactional(readOnly = true)
    public List<UUID> ids(UUID userId) {
        return savedRepository.findAllRecipeIdsByUserId(userId);
    }
}
