package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.food.Allergen;
import mk.ukim.finki.wp.recipeappbackend.food.FoodRules;
import mk.ukim.finki.wp.recipeappbackend.food.IngredientNames;
import mk.ukim.finki.wp.recipeappbackend.repository.ReferenceDataRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public lookups that feed the preference pickers. */
@RestController
public class FoodController {

    private static final int MAX_SUGGESTIONS = 10;
    private static final int MIN_QUERY_LENGTH = 2;

    private final FoodRules foodRules;
    private final ReferenceDataRepository referenceData;

    public FoodController(FoodRules foodRules, ReferenceDataRepository referenceData) {
        this.foodRules = foodRules;
        this.referenceData = referenceData;
    }

    @GetMapping("/api/food/allergens")
    public List<Allergen> allergens() {
        return foodRules.allergens();
    }

    @GetMapping("/api/ingredients/suggest")
    public List<String> suggest(@RequestParam(name = "q", defaultValue = "") String q) {
        return IngredientNames.normalise(q)
                .filter(prefix -> prefix.length() >= MIN_QUERY_LENGTH)
                .map(prefix -> referenceData.suggestIngredients(prefix, MAX_SUGGESTIONS))
                .orElse(List.of());
    }
}
