package mk.ukim.finki.wp.recipeappbackend.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import mk.ukim.finki.wp.recipeappbackend.ai.AiQuotaExceededException;
import mk.ukim.finki.wp.recipeappbackend.ai.AiReply;
import mk.ukim.finki.wp.recipeappbackend.ai.AiRequest;
import mk.ukim.finki.wp.recipeappbackend.ai.RecipeAi;
import mk.ukim.finki.wp.recipeappbackend.nutrition.NutritionEstimator.Failed;
import mk.ukim.finki.wp.recipeappbackend.nutrition.NutritionEstimator.Ready;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class NutritionEstimatorTest {

    private static final String VALID =
            "{\"items\":[{\"line\":1,\"grams\":100,\"calories\":400,\"protein\":20,\"fat\":20,\"carbs\":35}]}";
    // 400 kcal against 4*10 + 4*10 + 9*10 = 170 kcal from macros
    private static final String MISMATCH =
            "{\"items\":[{\"line\":1,\"grams\":100,\"calories\":400,\"protein\":10,\"fat\":10,\"carbs\":10}]}";
    // line 2 only, so line 1 is missing
    private static final String WRONG_LINE =
            "{\"items\":[{\"line\":2,\"grams\":100,\"calories\":400,\"protein\":20,\"fat\":20,\"carbs\":35}]}";

    private final Queue<String> replies = new LinkedList<>();
    private final List<AiRequest> calls = new ArrayList<>();
    private RuntimeException failure;

    private final RecipeAi ai = request -> {
        calls.add(request);
        if (failure != null) {
            throw failure;
        }
        return new AiReply(replies.remove(), "groq", "openai/gpt-oss-120b", null, null);
    };
    private final NutritionEstimator estimator = new NutritionEstimator(ai);

    private List<AiRequest> calls() {
        return calls;
    }

    private static String items(int n) {
        StringBuilder b = new StringBuilder("{\"items\":[");
        for (int i = 1; i <= n; i++) {
            b.append(i > 1 ? "," : "")
                    .append("{\"line\":").append(i).append(",\"grams\":10,\"calories\":40,")
                    .append("\"protein\":1,\"fat\":3,\"carbs\":2}");
        }
        return b.append("]}").toString();
    }

    @Test
    void firstTryPass() {
        replies.add(VALID);

        var out = estimator.estimate("Soup", 2, List.of("rice | 1 cup"));

        assertThat(out).isInstanceOf(Ready.class);
        Ready ready = (Ready) out;
        assertThat(ready.totals().calories()).isEqualTo(400);
        assertThat(ready.provider()).isEqualTo("groq");
        assertThat(ready.model()).isEqualTo("openai/gpt-oss-120b");
        assertThat(ready.items()).hasSize(1);
        assertThat(calls()).hasSize(1);
    }

    @Test
    void retriesOnceWithTheReasonInThePrompt() {
        replies.add(WRONG_LINE);
        replies.add(VALID);

        assertThat(estimator.estimate("Soup", 2, List.of("rice | 1 cup"))).isInstanceOf(Ready.class);

        assertThat(calls()).hasSize(2);
        assertThat(calls().get(0).userText()).doesNotContain("rejected");
        assertThat(calls().get(1).userText()).contains("rejected").contains("lines missing");
    }

    @Test
    void twoFailuresGiveFailedWithTheReason() {
        replies.add(MISMATCH);
        replies.add(MISMATCH);

        var out = estimator.estimate("Soup", 2, List.of("rice | 1 cup"));

        assertThat(out).isEqualTo(new Failed("calories do not match protein, fat and carbs"));
        assertThat(calls()).hasSize(2);
    }

    @Test
    void unreadableIsRetriedThenFailed() {
        replies.add("not json");
        replies.add("not json");

        assertThat(estimator.estimate("Soup", 2, List.of("rice | 1 cup"))).isEqualTo(new Failed("unreadable reply"));
        assertThat(calls()).hasSize(2);
    }

    @Test
    void over40LinesMakesNoCall() {
        assertThat(estimator.estimate("Soup", 2, Collections.nCopies(41, "x"))).isInstanceOf(Failed.class);
        assertThat(calls()).isEmpty();
    }

    @Test
    void exactly40LinesIsAttempted() {
        replies.add(items(40));

        assertThat(estimator.estimate("Soup", 1, Collections.nCopies(40, "x"))).isInstanceOf(Ready.class);
        assertThat(calls()).hasSize(1);
    }

    @Test
    void noLinesMakesNoCall() {
        assertThat(estimator.estimate("Soup", 2, List.of())).isInstanceOf(Failed.class);
        assertThat(calls()).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void noServingsMakesNoCall(Integer servings) {
        assertThat(estimator.estimate("Soup", servings, List.of("x"))).isInstanceOf(Failed.class);
        assertThat(calls()).isEmpty();
    }

    @Test
    void providerErrorsPropagate() {
        failure = new AiQuotaExceededException("busy");

        assertThatThrownBy(() -> estimator.estimate("Soup", 2, List.of("x")))
                .isInstanceOf(AiQuotaExceededException.class);
    }
}
