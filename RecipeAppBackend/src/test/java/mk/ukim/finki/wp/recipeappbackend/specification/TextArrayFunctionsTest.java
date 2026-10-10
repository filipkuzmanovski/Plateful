package mk.ukim.finki.wp.recipeappbackend.specification;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.query.sqm.function.SqmFunctionRegistry;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TextArrayFunctionsTest {

    @Test
    void registersTextArrayContainsFunction() {
        SqmFunctionRegistry registry = new SqmFunctionRegistry();
        FunctionContributions contributions = mock(FunctionContributions.class, RETURNS_DEEP_STUBS);
        when(contributions.getFunctionRegistry()).thenReturn(registry);

        new TextArrayFunctions().contributeFunctions(contributions);

        assertThat(registry.findFunctionDescriptor(TextArrayFunctions.TEXT_ARRAY_CONTAINS)).isNotNull();
    }

    @Test
    void isDiscoverableByHibernate() {
        // Hibernate finds FunctionContributors via ServiceLoader
        // (META-INF/services). Without that file the function silently
        // doesn't exist and the cuisine filter 500s at query time.
        assertThat(ServiceLoader.load(FunctionContributor.class))
                .anyMatch(contributor -> contributor instanceof TextArrayFunctions);
    }
}
