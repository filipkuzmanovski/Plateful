package mk.ukim.finki.wp.recipeappbackend.nutrition;

import java.util.UUID;

/** One recipe the estimator can work on: an uploaded recipe or an AI generated one. */
public record NutritionTarget(Kind kind, UUID id) {

    public enum Kind { RECIPE, GENERATED }
}
