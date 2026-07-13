package mk.ukim.finki.wp.recipeappbackend.specification;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeFilter;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeRating;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the WHERE clause for the multi-criteria recipe browse, from a
 * RecipeFilter where every field is optional. This is the feature
 * JpaSpecificationExecutor was reserved for.
 * <p>
 * Everything is composed inside ONE lambda (a list of predicates ANDed
 * together) rather than chaining many small Specifications — fewer moving
 * parts and no dependence on Specification composition APIs that have
 * shifted between Spring Data versions.
 * <p>
 * Two criteria deserve extra explanation:
 * <p>
 * CUISINE — recipes.cuisines is a Postgres text[] column, which standard
 * JPQL/Criteria can't query. Hibernate 6.4+ ships an array_contains()
 * function that we invoke through cb.function(). If this ever errors on
 * your Hibernate version or produces slow plans (check EXPLAIN — ideally it
 * should use the GIN index), the fallback is a native query for this path.
 * <p>
 * MIN RATING — average rating isn't a column on recipes, so this is a
 * correlated subquery: "(SELECT AVG(r.rating) FROM recipe_ratings r WHERE
 * r.recipe_id = recipe.id) >= :minRating". Correct, but it runs the AVG per
 * candidate row — fine at this scale; if rating-filtering becomes the main
 * browse path someday, denormalize rating_avg onto recipes (the rating
 * upsert is the single choke point where it would be maintained).
 */
public final class RecipeSpecifications {

    private RecipeSpecifications() {
    }

    public static Specification<Recipe> withFilter(RecipeFilter f) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (f.cuisine() != null && !f.cuisine().isBlank()) {
                // array_contains(recipes.cuisines, :cuisine) — see class javadoc
                predicates.add(cb.isTrue(cb.function(
                        "array_contains", Boolean.class,
                        root.get("cuisines"),
                        cb.literal(f.cuisine().toLowerCase())
                )));
            }

            // Diet flags: only filter when the client asked (null = skip).
            // Note we filter with equal(flag, value) rather than isTrue() so
            // ?vegan=false ("show me non-vegan") also works.
            if (f.dairyFree() != null) {
                predicates.add(cb.equal(root.get("dairyFree"), f.dairyFree()));
            }
            if (f.glutenFree() != null) {
                predicates.add(cb.equal(root.get("glutenFree"), f.glutenFree()));
            }
            if (f.vegan() != null) {
                predicates.add(cb.equal(root.get("vegan"), f.vegan()));
            }
            if (f.vegetarian() != null) {
                predicates.add(cb.equal(root.get("vegetarian"), f.vegetarian()));
            }

            if (f.maxReadyInMinutes() != null) {
                predicates.add(cb.le(root.get("readyInMinutes"), f.maxReadyInMinutes()));
            }

            if (f.minRating() != null) {
                Subquery<Double> avg = query.subquery(Double.class);
                Root<RecipeRating> r = avg.from(RecipeRating.class);
                avg.select(cb.avg(r.get("rating")));
                avg.where(cb.equal(r.get("recipe"), root));
                predicates.add(cb.ge(avg, f.minRating()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
