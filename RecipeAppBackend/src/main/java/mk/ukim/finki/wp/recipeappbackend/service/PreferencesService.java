package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.exception.InvalidPreferencesException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.food.FoodRules;
import mk.ukim.finki.wp.recipeappbackend.food.IngredientNames;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.PreferencesDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.UserPreferences;
import mk.ukim.finki.wp.recipeappbackend.repository.UserPreferencesRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The signed-in user's food preferences. A stored row means the user has answered; the id always
 * comes from the verified JWT.
 */
@Service
public class PreferencesService {

    static final int MAX_DISLIKED = 30;
    static final int MAX_LIKED = 30;
    static final int MAX_CUISINES = 20;

    private final UserPreferencesRepository repository;
    private final FoodRules foodRules;
    private final CuisineCatalog cuisineCatalog;

    public PreferencesService(UserPreferencesRepository repository, FoodRules foodRules,
                              CuisineCatalog cuisineCatalog) {
        this.repository = repository;
        this.foodRules = foodRules;
        this.cuisineCatalog = cuisineCatalog;
    }

    @Transactional(readOnly = true)
    public PreferencesDto get(UUID userId) {
        return repository.findById(userId)
                .map(PreferencesService::toDto)
                .orElseThrow(() -> new ResourceNotFoundException("Preferences for user " + userId + " not found"));
    }

    /** Validates, normalises and de-duplicates {@code dto}, then creates or updates the user's one row. */
    @Transactional
    public PreferencesDto save(UUID userId, PreferencesDto dto) {
        List<String> allergens = keys(dto.allergens(), foodRules.allergenKeys(), "allergen");
        List<String> diets = keys(dto.diets(), foodRules.dietKeys(), "diet");
        List<String> disliked = ingredientNames(dto.dislikedIngredients(), MAX_DISLIKED, "disliked ingredients");
        List<String> liked = ingredientNames(dto.likedIngredients(), MAX_LIKED, "liked ingredients");
        List<String> cuisines = cuisineCatalog.normalizeAndValidate(dto.likedCuisines());
        if (cuisines.size() > MAX_CUISINES) {
            throw new InvalidPreferencesException("At most " + MAX_CUISINES + " cuisines");
        }

        UserPreferences preferences = repository.findById(userId)
                .orElseGet(() -> UserPreferences.builder().userId(userId).build());
        preferences.setAllergens(allergens);
        preferences.setDiets(diets);
        preferences.setDislikedIngredients(disliked);
        preferences.setLikedIngredients(liked);
        preferences.setLikedCuisines(cuisines);
        return toDto(repository.save(preferences));
    }

    private static List<String> keys(List<String> raw, Set<String> known, String label) {
        Set<String> result = new LinkedHashSet<>();
        if (raw != null) {
            for (String item : raw) {
                if (item == null || item.isBlank()) {
                    continue;
                }
                String key = item.strip().toLowerCase(Locale.ROOT);
                if (!known.contains(key)) {
                    throw new InvalidPreferencesException("Unknown " + label + ": " + item.strip());
                }
                result.add(key);
            }
        }
        return List.copyOf(result);
    }

    private static List<String> ingredientNames(List<String> raw, int max, String label) {
        Set<String> result = new LinkedHashSet<>();
        if (raw != null) {
            for (String item : raw) {
                IngredientNames.normalise(item).ifPresent(result::add);
            }
        }
        if (result.size() > max) {
            throw new InvalidPreferencesException("At most " + max + " " + label);
        }
        return List.copyOf(result);
    }

    private static PreferencesDto toDto(UserPreferences p) {
        return new PreferencesDto(p.getAllergens(), p.getDiets(), p.getDislikedIngredients(),
                p.getLikedIngredients(), p.getLikedCuisines());
    }
}
