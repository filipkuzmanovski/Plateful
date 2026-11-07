package mk.ukim.finki.wp.recipeappbackend.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard for native projection aliases: Postgres folds unquoted aliases to lowercase, but the tuple behind
 * a Spring Data projection looks the getter's name up case-sensitively. A camelCase alias must be quoted.
 */
class ContentReportNativeAliasTest {

    private static final Pattern ALIAS = Pattern.compile("(?i)\\bAS\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");

    @Test
    void nativeQueries_haveNoUnquotedCamelCaseAlias() {
        int checked = 0;
        for (Method m : ContentReportRepository.class.getMethods()) {
            Query q = m.getAnnotation(Query.class);
            if (q == null || !q.nativeQuery()) {
                continue;
            }
            Matcher matcher = ALIAS.matcher(q.value());
            while (matcher.find()) {
                String alias = matcher.group(1);
                assertThat(alias).as("alias in " + m.getName()).isEqualTo(alias.toLowerCase());
                checked++;
            }
        }
        assertThat(checked).isPositive();
    }

    @Test
    void queueRowCamelCaseGetter_isQuotedInTheQueueQuery() throws Exception {
        Query q = ContentReportRepository.class.getMethod("findQueue", org.springframework.data.domain.Pageable.class)
                .getAnnotation(Query.class);

        assertThat(q.value()).contains("AS \"openCount\"");
    }
}
