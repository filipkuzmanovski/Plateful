package mk.ukim.finki.wp.recipeappbackend.specification;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * Registers text_array_contains(textArrayColumn, value) for Criteria queries.
 * <p>
 * BUGFIX (cuisine filter 500): Hibernate's built-in array_contains casts the
 * searched value to the column's Hibernate-side type. recipes.cuisines is
 * mapped as List&lt;String&gt;, which Hibernate types as a varchar array, but
 * the real column is text[]. The generated SQL was
 * {@code cuisines @> cast(array[?] as varchar array)}, and Postgres rejects
 * that with "operator does not exist: text[] @> character varying[]".
 * <p>
 * This function casts the value to text[] instead, so both sides of @> are
 * text[]. @> also keeps the GIN index on recipes.cuisines usable.
 * <p>
 * Discovered by Hibernate through
 * META-INF/services/org.hibernate.boot.model.FunctionContributor.
 */
public class TextArrayFunctions implements FunctionContributor {

    public static final String TEXT_ARRAY_CONTAINS = "text_array_contains";

    @Override
    public void contributeFunctions(FunctionContributions contributions) {
        contributions.getFunctionRegistry().registerPattern(
                TEXT_ARRAY_CONTAINS,
                "(?1 @> cast(array[?2] as text[]))",
                contributions.getTypeConfiguration().getBasicTypeRegistry().resolve(StandardBasicTypes.BOOLEAN)
        );
    }
}
