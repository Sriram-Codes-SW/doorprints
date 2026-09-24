package app.doorprints.server.ai;

import com.google.genai.errors.ApiException;
import app.doorprints.server.ai.embedding.GeminiEmbeddingModel.GeminiEmbeddingException;
import app.doorprints.server.ai.rag.HouseIndexer.ReindexFailedException;
import com.openai.errors.OpenAIServiceException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

/**
 * Classifies model-provider failures the same way for both providers, by walking the cause chain (Spring AI and the
 * services wrap provider exceptions several times):
 * <ul>
 *   <li>AI Studio chat: openai-java {@link OpenAIServiceException} (e.g. {@code RateLimitException}, status 429);</li>
 *   <li>Vertex AI chat: google-genai {@link ApiException} ({@code ClientException} with {@code code()} 429);</li>
 *   <li>embeddings (both): {@link GeminiEmbeddingException} with {@code httpStatus()} 429 or reason
 *   {@code RESOURCE_EXHAUSTED}; a re-index that stopped on quota ({@link ReindexFailedException#quotaExhausted()});</li>
 *   <li>anything else Spring-based: {@link RestClientResponseException} with status 429.</li>
 * </ul>
 * Only types and numeric codes are inspected, never message text, so provider-echoed prompt content is irrelevant.
 */
public final class ProviderErrors {

    public static final int TOO_MANY_REQUESTS = 429;
    public static final String RESOURCE_EXHAUSTED = "RESOURCE_EXHAUSTED";
    private static final int MAX_DEPTH = 16;

    private ProviderErrors() {
    }

    /** True when the provider rejected the call because a rate limit or quota is exhausted (HTTP 429). */
    public static boolean isQuotaExhausted(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        for (Throwable t = error; t != null && depth < MAX_DEPTH && seen.add(t); t = t.getCause(), depth++) {
            if (quota(t)) return true;
            var suppressed = t.getSuppressed();
            if (suppressed != null) {
                for (var s : suppressed) {
                    if (quota(s)) return true;
                }
            }
        }
        return false;
    }

    /** Which call failed: chat (google-genai {@link ApiException}) or embeddings ({@link GeminiEmbeddingException}). */
    public enum Call { CHAT, EMBEDDING }

    /** First provider HTTP error in the cause chain: the call kind and the numeric HTTP status (never message text). */
    public record HttpFailure(Call call, int status) {
    }

    /**
     * The first google-genai {@link ApiException} (chat on Vertex AI) or {@link GeminiEmbeddingException} with an HTTP
     * status (embeddings) in the cause chain, used for setup hints such as "model not offered in this location".
     * AI Studio chat errors (openai-java) are not reported: their hints do not depend on a location.
     */
    public static Optional<HttpFailure> httpFailure(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        for (Throwable t = error; t != null && depth < MAX_DEPTH && seen.add(t); t = t.getCause(), depth++) {
            if (t instanceof ApiException a && a.code() > 0) return Optional.of(new HttpFailure(Call.CHAT, a.code()));
            if (t instanceof GeminiEmbeddingException g && g.httpStatus() > 0) {
                return Optional.of(new HttpFailure(Call.EMBEDDING, g.httpStatus()));
            }
        }
        return Optional.empty();
    }

    private static boolean quota(Throwable t) {
        return switch (t) {
            case ReindexFailedException r -> r.quotaExhausted();
            case GeminiEmbeddingException g -> g.httpStatus() == TOO_MANY_REQUESTS || RESOURCE_EXHAUSTED.equals(g.reason());
            case ApiException a -> a.code() == TOO_MANY_REQUESTS;
            case OpenAIServiceException o -> o.statusCode() == TOO_MANY_REQUESTS;
            case RestClientResponseException r -> r.getStatusCode().value() == TOO_MANY_REQUESTS;
            default -> false;
        };
    }
}
