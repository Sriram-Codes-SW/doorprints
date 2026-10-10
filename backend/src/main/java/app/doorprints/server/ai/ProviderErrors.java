/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package app.doorprints.server.ai;

import com.google.genai.errors.ApiException;
import app.doorprints.server.ai.embedding.GeminiEmbeddingModel.GeminiEmbeddingException;
import app.doorprints.server.ai.rag.HouseIndexer.ReindexFailedException;
import com.google.genai.errors.GenAiIOException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeoutException;
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

    /** {@link #cause}: the provider answered with an HTTP error, timed out or could not be reached. */
    public static final String CAUSE_PROVIDER = "provider";
    /** {@link #cause}: the provider answered but its output could not be read (not JSON, truncated, empty). */
    public static final String CAUSE_MODEL = "model";

    /**
     * Why an AI call failed, for clients and the eval harness (S4b-BL-200): {@link #CAUSE_PROVIDER} for an HTTP error,
     * timeout or connect failure of the model provider (a retry can help; it says nothing about the model), or
     * {@link #CAUSE_MODEL} for output that could not be parsed (a retry would only ask again). {@code null} when the
     * chain holds neither, so a client never reads a guess as a fact. The cause chain is walked outermost first and
     * the first recognised node decides; only types are inspected, never message text.
     */
    public static String cause(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        for (Throwable t = error; t != null && depth < MAX_DEPTH && seen.add(t); t = t.getCause(), depth++) {
            if (t instanceof com.fasterxml.jackson.core.JsonProcessingException
                    || t instanceof tools.jackson.core.JacksonException) {
                return CAUSE_MODEL;
            }
            // An HTTP answer decides by its status: 5xx, 429 and 408 are the provider's; any other 4xx (400 request too
            // long, malformed or schema rejected; 401/403/404 setup) is ours and gets no cause, so it is scored.
            Integer status = httpStatus(t);
            if (status != null) return providerStatus(status) ? CAUSE_PROVIDER : null;
            if (t instanceof GenAiIOException || t instanceof OpenAIIoException || t instanceof GeminiEmbeddingException
                    || t instanceof ReindexFailedException || t instanceof ResourceAccessException
                    || t instanceof TimeoutException || t instanceof IOException) {
                return CAUSE_PROVIDER;
            }
        }
        return null;
    }

    /** The provider's HTTP status carried by this exception, or null (no status: an I/O error, or another type). */
    private static Integer httpStatus(Throwable t) {
        return switch (t) {
            case ApiException a -> a.code();
            case OpenAIServiceException o -> o.statusCode();
            case RestClientResponseException r -> r.getStatusCode().value();
            case GeminiEmbeddingException g when g.httpStatus() > 0 -> g.httpStatus();
            default -> null;
        };
    }

    /** 5xx, 429 (quota) and 408 (timeout): the provider's side. */
    private static boolean providerStatus(int status) {
        return status >= 500 || status == TOO_MANY_REQUESTS || status == 408;
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

    /**
     * True when the failure means the provider's quota is used up (HTTP 429 or the RESOURCE_EXHAUSTED reason),
     * whichever client raised it.
     */
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
