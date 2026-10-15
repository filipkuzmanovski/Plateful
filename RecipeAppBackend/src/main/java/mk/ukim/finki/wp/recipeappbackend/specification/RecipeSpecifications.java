package mk.ukim.finki.wp.recipeappbackend.specification;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Nulls;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeFilter;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeSort;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeComment;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeIngredient;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeRating;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
 * CUISINE — any-of: one text_array_contains(cuisines, key) call per resolved key, ORed
 * together. recipes.cuisines is a Postgres text[] column, which standard
 * JPQL/Criteria can't query. We call our own text_array_contains() function
 * (registered by TextArrayFunctions) through cb.function(). It renders
 * {@code cuisines @> cast(array[:cuisine] as text[])}, which can use the GIN
 * index. Hibernate's built-in array_contains() can't be used here: it casts
 * the value to varchar[], and Postgres has no text[] @> varchar[] operator
 * (see TextArrayFunctions).
 * <p>
 * SEARCH (q) — the query is trimmed, capped at 100 characters, lowercased and split on whitespace.
 * Every word must match somewhere: lower(title) LIKE %word% OR an EXISTS subquery over the recipe's
 * ingredients (lower(ingredient_name) LIKE %word%). EXISTS rather than a join, so a recipe with two
 * matching ingredients still appears once and the paging counts stay right. LIKE wildcards in the
 * user's text (backslash, percent, underscore) are escaped so they only match themselves.
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

            for (String word : searchWords(f.q())) {
                String pattern = "%" + escapeLike(word) + "%";
                Subquery<Integer> ingredientMatch = query.subquery(Integer.class);
                Root<RecipeIngredient> i = ingredientMatch.from(RecipeIngredient.class);
                ingredientMatch.select(cb.literal(1));
                ingredientMatch.where(
                        cb.equal(i.get("recipe"), root),
                        cb.like(cb.lower(i.get("ingredientName")), pattern, LIKE_ESCAPE));
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pattern, LIKE_ESCAPE),
                        cb.exists(ingredientMatch)));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static final char LIKE_ESCAPE = '\\';
    private static final int MAX_QUERY_LENGTH = 100;
    private static final int MAX_SEARCH_WORDS = 8;

    /**
     * Trim, cap at 100 characters, strip control characters (NUL would make Postgres reject the query;
     * tab and newline are kept because they separate words), lowercase, split on whitespace, drop
     * duplicates and keep at most 8 words. Blank or null gives no words.
     */
    static List<String> searchWords(String q) {
        if (q == null) {
            return List.of();
        }
        String cleaned = q.replaceAll("[\\p{Cntrl}&&[^\\s]]", "").trim();
        if (cleaned.length() > MAX_QUERY_LENGTH) {
            cleaned = cleaned.substring(0, MAX_QUERY_LENGTH);
        }
        return Arrays.stream(cleaned.toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(w -> !w.isEmpty())
                .distinct()
                .limit(MAX_SEARCH_WORDS)
                .toList();
    }

    /** Escapes the LIKE wildcards so user text matches literally (escape character is a backslash). */
    private static String escapeLike(String word) {
        return word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Sets the ORDER BY for the browse (spec 4.3). Rating count, average and comment count are
     * correlated subqueries, so nothing is denormalized onto recipes. It only sets query.orderBy and
     * adds no predicate; combine it with withFilter() through and(). The final tie-breaker is always
     * id asc so paging is stable. The count query (result type Long) is skipped: Spring clears its
     * orderBy anyway, this just avoids building subqueries nobody uses.
     */
    public static Specification<Recipe> withSort(RecipeSort sort) {
        return (root, query, cb) -> {
            if (query == null || Long.class.equals(query.getResultType())) {
                return cb.conjunction();
            }

            Subquery<Long> ratingCountQ = query.subquery(Long.class);
            Root<RecipeRating> rc = ratingCountQ.from(RecipeRating.class);
            ratingCountQ.select(cb.count(rc));
            ratingCountQ.where(cb.equal(rc.get("recipe"), root));

            Subquery<Double> avgQ = query.subquery(Double.class);
            Root<RecipeRating> ra = avgQ.from(RecipeRating.class);
            avgQ.select(cb.avg(ra.get("rating")));
            avgQ.where(cb.equal(ra.get("recipe"), root));

            Subquery<Long> commentCountQ = query.subquery(Long.class);
            Root<RecipeComment> cc = commentCountQ.from(RecipeComment.class);
            commentCountQ.select(cb.count(cc));
            commentCountQ.where(cb.equal(cc.get("recipe"), root));

            Expression<?> createdAt = root.get("createdAt");
            Order newestFirst = cb.desc(createdAt);
            Order idAsc = cb.asc(root.get("id"));

            List<Order> orders = switch (sort) {
                case NEWEST -> List.of(newestFirst, idAsc);
                case MOST_RATED -> List.of(cb.desc(ratingCountQ), cb.desc(avgQ, Nulls.LAST), newestFirst, idAsc);
                case TOP_RATED -> List.of(cb.desc(avgQ, Nulls.LAST), cb.desc(ratingCountQ), newestFirst, idAsc);
                case MOST_COMMENTS -> List.of(cb.desc(commentCountQ), newestFirst, idAsc);
            };
            query.orderBy(orders);
            return cb.conjunction();
        };
    }
}
