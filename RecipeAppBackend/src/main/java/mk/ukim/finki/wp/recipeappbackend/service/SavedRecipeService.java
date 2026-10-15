package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.SavedRecipeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public SavedRecipeService(SavedRecipeRepository savedRepository,
                              RecipeRepository recipeRepository,
                              RecipeService recipeService) {
        this.savedRepository = savedRepository;
        this.recipeRepository = recipeRepository;
        this.recipeService = recipeService;
    }

    /** Idempotent: saving an already-saved recipe is a no-op. 404 if the recipe doesn't exist. */
    public void save(UUID userId, UUID recipeId) {
        if (!recipeRepository.existsById(recipeId)) {
            throw new ResourceNotFoundException("Recipe", recipeId);
        }
        savedRepository.insertIgnore(userId, recipeId);
    }

    /** Idempotent: removing a recipe that isn't saved is a no-op. */
    public void unsave(UUID userId, UUID recipeId) {
        savedRepository.deleteByIdUserIdAndIdRecipeId(userId, recipeId);
    }

    /** Newest saved first. Rows whose recipe has since been deleted are skipped. */
    @Transactional(readOnly = true)
    public Page<RecipeDisplayDto> list(UUID userId, Pageable pageable) {
        // The repository query owns the ordering, so the page request must carry no Sort.
        Page<UUID> idPage = savedRepository.findRecipeIdsByUserId(
                userId, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        List<UUID> ids = idPage.getContent();
        Map<UUID, Recipe> byId = recipeRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Recipe::getId, Function.identity()));
        List<Recipe> ordered = ids.stream().map(byId::get).filter(r -> r != null).toList();
        return recipeService.toDisplayPage(
                new PageImpl<>(ordered, idPage.getPageable(), idPage.getTotalElements()));
    }

    @Transactional(readOnly = true)
    public List<UUID> ids(UUID userId) {
        return savedRepository.findAllRecipeIdsByUserId(userId);
    }
}
