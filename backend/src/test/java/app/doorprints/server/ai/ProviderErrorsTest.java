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

import com.google.genai.errors.ClientException;
import com.google.genai.errors.ServerException;
import app.doorprints.server.ai.embedding.GeminiEmbeddingModel.GeminiEmbeddingException;
import app.doorprints.server.ai.web.AiUnavailableException;
import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.RateLimitException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Quota detection must behave the same for AI Studio (openai-java) and Vertex AI (google-genai), however wrapped. */
class ProviderErrorsTest {

    private static RuntimeException wrapped(Throwable cause) {
        // Services -> Spring AI -> SDK, as in production.
        return new AiUnavailableException("Answering failed", new RuntimeException("Failed to generate content", cause));
    }

    @Test
    void vertexChat429IsQuota() {
        assertThat(ProviderErrors.isQuotaExhausted(wrapped(new ClientException(429, "Too Many Requests",
                "Resource exhausted. Please try again later.")))).isTrue();
        assertThat(ProviderErrors.isQuotaExhausted(wrapped(new ClientException(403, "Forbidden", "denied")))).isFalse();
        assertThat(ProviderErrors.isQuotaExhausted(wrapped(new ServerException(503, "Service Unavailable", "")))).isFalse();
    }

    @Test
    void aiStudioChat429IsQuota() {
        // The exceptions openai-java 4.49.0 throws for 429 / 400 (what OpenAiChatModel surfaces on AI Studio).
        var rateLimited = RateLimitException.builder().headers(Headers.builder().build()).build();
        var badRequest = BadRequestException.builder().headers(Headers.builder().build()).build();

        assertThat(ProviderErrors.isQuotaExhausted(wrapped(rateLimited))).isTrue();
        assertThat(ProviderErrors.isQuotaExhausted(wrapped(badRequest))).isFalse();
    }

    @Test
    void embeddings429OrResourceExhaustedIsQuota() {
        assertThat(ProviderErrors.isQuotaExhausted(wrapped(
                new GeminiEmbeddingException("HTTP 429 (RESOURCE_EXHAUSTED)", 429, "RESOURCE_EXHAUSTED")))).isTrue();
        assertThat(ProviderErrors.isQuotaExhausted(wrapped(
                new GeminiEmbeddingException("x", 400, "RESOURCE_EXHAUSTED")))).isTrue();
        assertThat(ProviderErrors.isQuotaExhausted(wrapped(new GeminiEmbeddingException("I/O error")))).isFalse();
    }

    @Test
    void plainSpringClient429IsQuota() {
        assertThat(ProviderErrors.isQuotaExhausted(
                HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", new HttpHeaders(),
                        new byte[0], StandardCharsets.UTF_8)))
                .isTrue();
    }

    @Test
    void httpFailureNamesTheCallAndStatusForSetupHints() {
        assertThat(ProviderErrors.httpFailure(wrapped(new ClientException(404, "Not Found", "no model"))))
                .contains(new ProviderErrors.HttpFailure(ProviderErrors.Call.CHAT, 404));
        assertThat(ProviderErrors.httpFailure(wrapped(new GeminiEmbeddingException("denied", 403, "SERVICE_DISABLED"))))
                .contains(new ProviderErrors.HttpFailure(ProviderErrors.Call.EMBEDDING, 403));
        // No HTTP status (I/O error, missing token) or no provider exception: nothing to hint.
        assertThat(ProviderErrors.httpFailure(wrapped(new GeminiEmbeddingException("I/O error")))).isEmpty();
        assertThat(ProviderErrors.httpFailure(new RuntimeException("404 NOT_FOUND"))).isEmpty();
        assertThat(ProviderErrors.httpFailure(null)).isEmpty();
    }

    @Test
    void messagesAreNeverInspected() {
        assertThat(ProviderErrors.isQuotaExhausted(new RuntimeException("429 RESOURCE_EXHAUSTED quota"))).isFalse();
        assertThat(ProviderErrors.isQuotaExhausted(null)).isFalse();
    }

    @Test
    void causeCyclesTerminate() {
        var a = new RuntimeException("a");
        var b = new RuntimeException("b", a);
        a.initCause(b);
        assertThat(ProviderErrors.isQuotaExhausted(a)).isFalse();
    }
}
