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
import java.util.Optional;
import java.util.Set;

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
 * CUISINE — (any-of over several keys, one call per key) recipes.cuisines is a Postgres text[] column, which standard
 * JPQL/Criteria can't query. We call our own text_array_contains() function
 * (registered by TextArrayFunctions) through cb.function(). It renders
 * {@code cuisines @> cast(array[:cuisine] as text[])}, which can use the GIN
 * index. Hibernate's built-in array_contains() can't be used here: it casts
 * the value to varchar[], and Postgres has no text[] @> varchar[] operator
 * (see TextArrayFunctions).
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

    /**
     * @param cuisineKeys resolved cuisine keys (see CuisineCatalog.resolveFilter):
     *                    empty Optional = no cuisine predicate; an empty set =
     *                    match nothing; otherwise a recipe matches if it has ANY key.
     */
    public static Specification<Recipe> withFilter(RecipeFilter f, Optional<Set<String>> cuisineKeys) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (cuisineKeys.isPresent()) {
                Set<String> keys = cuisineKeys.get();
                if (keys.isEmpty()) {
                    predicates.add(cb.disjunction()); // nothing can match
                } else {
                    // OR of text_array_contains(recipes.cuisines, :key) — see class javadoc
                    Predicate[] anyOf = keys.stream()
                            .map(key -> cb.isTrue(cb.function(
                                    TextArrayFunctions.TEXT_ARRAY_CONTAINS, Boolean.class,
                                    root.get("cuisines"),
                                    cb.literal(key))))
                            .toArray(Predicate[]::new);
                    predicates.add(cb.or(anyOf));
                }
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
