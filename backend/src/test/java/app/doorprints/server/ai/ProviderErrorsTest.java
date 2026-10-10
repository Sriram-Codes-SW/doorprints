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
        assertThat(ProviderErrors.cause(a)).isNull();
    }

    // ---- S4b-BL-200: provider failure or model failure

    @Test
    void httpStatusTimeoutAndConnectErrorsAreProviderFailures() {
        assertThat(ProviderErrors.cause(wrapped(new ClientException(408, "Request Timeout", "x")))).isEqualTo("provider");
        assertThat(ProviderErrors.cause(wrapped(new ServerException(500, "Internal", "x")))).isEqualTo("provider");
        assertThat(ProviderErrors.cause(wrapped(new ServerException(503, "Service Unavailable", "")))).isEqualTo("provider");
        assertThat(ProviderErrors.cause(wrapped(new ClientException(429, "Too Many Requests", "x")))).isEqualTo("provider");
        assertThat(ProviderErrors.cause(wrapped(RateLimitException.builder().headers(Headers.builder().build()).build())))
                .isEqualTo("provider");
        assertThat(ProviderErrors.cause(wrapped(new GeminiEmbeddingException("HTTP 503", 503, "UNAVAILABLE"))))
                .isEqualTo("provider");
        assertThat(ProviderErrors.cause(wrapped(new GeminiEmbeddingException("I/O error")))).isEqualTo("provider");
        assertThat(ProviderErrors.cause(wrapped(HttpClientErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway",
                new HttpHeaders(), new byte[0], StandardCharsets.UTF_8)))).isEqualTo("provider");
        assertThat(ProviderErrors.cause(wrapped(new java.net.http.HttpTimeoutException("request timed out"))))
                .isEqualTo("provider");
        assertThat(ProviderErrors.cause(wrapped(new java.net.ConnectException("refused")))).isEqualTo("provider");
        assertThat(ProviderErrors.cause(wrapped(new java.net.SocketTimeoutException("read timed out"))))
                .isEqualTo("provider");
        assertThat(ProviderErrors.cause(wrapped(new java.util.concurrent.TimeoutException("slow")))).isEqualTo("provider");
        assertThat(ProviderErrors.cause(wrapped(new org.springframework.web.client.ResourceAccessException("I/O error",
                new java.io.IOException("reset"))))).isEqualTo("provider");
    }

    @Test
    void otherClientErrorsAreNotExcusedAsProviderFailures() {
        // 400 (request too long, malformed, schema rejected) and 401/403/404 are our request or setup, not an outage.
        for (int status : new int[] {400, 401, 403, 404, 413, 422}) {
            assertThat(ProviderErrors.cause(wrapped(new ClientException(status, "Client error", "x"))))
                    .as("google-genai %d", status).isNull();
            assertThat(ProviderErrors.cause(wrapped(new GeminiEmbeddingException("HTTP " + status, status, "X"))))
                    .as("embedding %d", status).isNull();
            assertThat(ProviderErrors.cause(wrapped(HttpClientErrorException.create(HttpStatus.valueOf(status), "x",
                    new HttpHeaders(), new byte[0], StandardCharsets.UTF_8)))).as("spring %d", status).isNull();
        }
        assertThat(ProviderErrors.cause(wrapped(BadRequestException.builder().headers(Headers.builder().build()).build())))
                .as("openai-java 400").isNull();
    }

    @Test
    void aJacksonParseErrorIsAModelFailureEvenThoughItIsAnIoException() {
        // JsonProcessingException extends IOException: the model check must come before the I/O check.
        var parse = new com.fasterxml.jackson.core.JsonParseException(null, "Unexpected character");
        assertThat(parse).isInstanceOf(java.io.IOException.class);
        assertThat(ProviderErrors.cause(parse)).isEqualTo("model");
        assertThat(ProviderErrors.cause(wrapped(parse))).isEqualTo("model");
    }

    @Test
    void unreadableOrEmptyModelOutputIsAModelFailure() {
        var converter = new org.springframework.ai.converter.BeanOutputConverter<>(
                app.doorprints.server.ai.agent.PlanModels.AgentPlan.class);
        // What ChatClient.responseEntity does with text that is not the requested JSON (the real converter, not a stub).
        var notJson = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> converter.convert("I could not decide."));
        assertThat(ProviderErrors.cause(wrapped(notJson))).isEqualTo("model");
        var truncated = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> converter.convert("{\"summary\":\"cut o"));
        assertThat(ProviderErrors.cause(wrapped(truncated))).isEqualTo("model");
        var empty = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> converter.convert(""));
        assertThat(ProviderErrors.cause(wrapped(empty))).isEqualTo("model");
        assertThat(ProviderErrors.cause(wrapped(
                new com.fasterxml.jackson.core.JsonParseException(null, "Unexpected character")))).isEqualTo("model");
    }

    @Test
    void anUnclassifiedFailureHasNoCauseAndMessagesAreNeverInspected() {
        assertThat(ProviderErrors.cause(wrapped(new IllegalStateException("boom")))).isNull();
        assertThat(ProviderErrors.cause(new RuntimeException("503 UNAVAILABLE timeout json parse"))).isNull();
        assertThat(ProviderErrors.cause(null)).isNull();
    }
}
