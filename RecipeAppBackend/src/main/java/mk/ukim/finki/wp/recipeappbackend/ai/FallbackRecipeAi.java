package mk.ukim.finki.wp.recipeappbackend.ai;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tries the active providers in order. After a quota answer a provider is skipped for the
 * {@link AiQuotaExceededException#retryAfter()} it carries: the provider's {@code Retry-After} (1 to 300 s)
 * or 60 s. Every provider quota-limited or cooling down gives
 * {@link AiQuotaExceededException}; any other mix of failures gives {@link AiUnavailableException};
 * no provider at all gives {@link AiNotConfiguredException}.
 *
 * <p>Logs one INFO line per provider call and never the prompt, the reply or a key. A failed call's line
 * ends with the reason: the message of a quota or unavailable exception (key-free by design, for example
 * {@code reason="groq HTTP 413"}), or only the class name of any other exception.
 */
public class FallbackRecipeAi implements RecipeAi {

    private static final Logger log = LoggerFactory.getLogger(FallbackRecipeAi.class);

    /** A provider with the name and model shown in logs. */
    public record NamedRecipeAi(String name, RecipeAi ai, String model) {
    }

    private final List<NamedRecipeAi> providers;
    private final Clock clock;
    private final Map<String, Instant> cooldownUntil = new ConcurrentHashMap<>();

    public FallbackRecipeAi(List<NamedRecipeAi> providers, Clock clock) {
        this.providers = List.copyOf(providers);
        this.clock = clock;
    }

    @Override
    public AiReply complete(AiRequest request) {
        if (providers.isEmpty()) {
            throw new AiNotConfiguredException("no AI provider has an API key configured");
        }
        boolean unavailable = false;
        for (NamedRecipeAi provider : providers) {
            Instant until = cooldownUntil.get(provider.name());
            if (until != null && clock.instant().isBefore(until)) {
                continue;
            }
            long started = System.nanoTime();
            try {
                AiReply reply = provider.ai().complete(request);
                logCall(provider, reply.model(), "ok", started, reply.inputTokens(), reply.outputTokens(),
                        request.images().size());
                return reply;
            } catch (AiQuotaExceededException e) {
                cooldownUntil.put(provider.name(), clock.instant().plus(e.retryAfter()));
                logFailure(provider, "quota", started, e, request.images().size());
            } catch (RuntimeException e) {
                unavailable = true;
                logFailure(provider, "unavailable", started, e, request.images().size());
            }
        }
        if (unavailable) {
            throw new AiUnavailableException("no AI provider could answer");
        }
        throw new AiQuotaExceededException("every AI provider is over its quota");
    }

    /** The image count is appended for vision calls only. */
    private static String imagesSuffix(int images) {
        return images > 0 ? " images=" + images : "";
    }

    private static void logFailure(NamedRecipeAi provider, String outcome, long startedNanos, RuntimeException e,
                                   int images) {
        String reason = e instanceof AiQuotaExceededException || e instanceof AiUnavailableException
                ? e.getMessage()
                : e.getClass().getSimpleName();
        log.info("ai call provider={} model={} outcome={} ms={} in=- out=- reason=\"{}\"{}",
                provider.name(), provider.model(), outcome, (System.nanoTime() - startedNanos) / 1_000_000,
                reason, imagesSuffix(images));
    }

    private static void logCall(NamedRecipeAi provider, String model, String outcome, long startedNanos,
                                Integer in, Integer out, int images) {
        log.info("ai call provider={} model={} outcome={} ms={} in={} out={}{}",
                provider.name(), model, outcome, (System.nanoTime() - startedNanos) / 1_000_000,
                in == null ? "-" : in, out == null ? "-" : out, imagesSuffix(images));
    }
}
