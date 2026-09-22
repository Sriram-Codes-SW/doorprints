package com.househunt.ai.embedding;

import com.househunt.ai.embedding.GeminiEmbeddingModel.GeminiEmbeddingException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** {@link GeminiEmbeddingModel} against a mocked Gemini API (no network, no key). */
class GeminiEmbeddingModelTest {

    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta";
    private static final String URL = BASE + "/models/gemini-embedding-2:batchEmbedContents";
    /** Built at runtime so secret scanners see no literal key. */
    private static final String KEY = "test-" + "key-" + 42;

    private MockRestServiceServer server;

    private GeminiEmbeddingModel model(int dimensions, String taskType, int maxRetries) {
        return model("gemini-embedding-2", dimensions, taskType, maxRetries);
    }

    private GeminiEmbeddingModel model(String name, int dimensions, String taskType, int maxRetries) {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new GeminiEmbeddingModel(builder, BASE + "/", KEY, name, dimensions, taskType, maxRetries, Duration.ZERO);
    }

    /** Waits the model asked for, recorded instead of slept. */
    private final List<Duration> waits = new ArrayList<>();

    private GeminiEmbeddingModel recordingModel(int maxRetries, Duration backoff) {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new GeminiEmbeddingModel(builder, BASE, KEY, "gemini-embedding-2", 1, null, maxRetries, backoff,
                waits::add);
    }

    private static RestClientResponseException tooManyRequests(HttpHeaders headers, String body) {
        return new RestClientResponseException("429", HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", headers,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static String response(String... vectors) {
        var items = new ArrayList<String>();
        for (var v : vectors) items.add("{\"values\":[" + v + "]}");
        return "{\"embeddings\":[" + String.join(",", items) + "]}";
    }

    @Test
    void batchEmbedsWithKeyInHeaderAndOutputDimensionality() {
        var model = model(3, null, 0);
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(GeminiEmbeddingModel.API_KEY_HEADER, KEY))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.requests.length()").value(2))
                .andExpect(jsonPath("$.requests[0].model").value("models/gemini-embedding-2"))
                .andExpect(jsonPath("$.requests[0].content.parts[0].text").value("house one"))
                .andExpect(jsonPath("$.requests[1].content.parts[0].text").value("house two"))
                .andExpect(jsonPath("$.requests[0].outputDimensionality").value(3))
                .andExpect(jsonPath("$.requests[0].taskType").doesNotExist())
                .andRespond(withSuccess(response("3, 4, 0", "0, 0, 2"), MediaType.APPLICATION_JSON));

        var out = model.call(new EmbeddingRequest(List.of("house one", "house two"), EmbeddingOptions.builder().build()));

        server.verify();
        assertThat(out.getResults()).hasSize(2);
        assertThat(out.getResults().get(0).getIndex()).isEqualTo(0);
        assertThat(out.getResults().get(1).getIndex()).isEqualTo(1);
        // L2-normalised (gemini-embedding-001 does not normalise reduced sizes itself).
        var first = out.getResults().get(0).getOutput();
        assertThat(first[0]).isCloseTo(0.6f, within(1e-6f));
        assertThat(first[1]).isCloseTo(0.8f, within(1e-6f));
        assertThat(out.getResults().get(1).getOutput()[2]).isCloseTo(1f, within(1e-6f));
        assertThat(out.getMetadata().getModel()).isEqualTo("gemini-embedding-2");
        assertThat(model.dimensions()).isEqualTo(3);
    }

    @Test
    void sendsTaskTypeWhenConfiguredAndAcceptsModelsPrefix() {
        var model = model("models/gemini-embedding-001", 2, "RETRIEVAL_DOCUMENT", 0);
        server.expect(requestTo(BASE + "/models/gemini-embedding-001:batchEmbedContents"))
                .andExpect(jsonPath("$.requests[0].model").value("models/gemini-embedding-001"))
                .andExpect(jsonPath("$.requests[0].taskType").value("RETRIEVAL_DOCUMENT"))
                .andRespond(withSuccess(response("1, 0"), MediaType.APPLICATION_JSON));

        var vector = model.embed(new Document("a house"));

        server.verify();
        assertThat(vector).containsExactly(1f, 0f);
    }

    @Test
    void ignoresUnknownResponseFieldsAndMissingIndex() {
        // The OpenAI-compatible endpoint's missing data[].index broke the openai-java SDK; the native response has no
        // index at all (order = request order) and may carry extra fields.
        var model = model(2, null, 0);
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"embeddings\":[{\"values\":[0,1],\"statistics\":{\"tokenCount\":3}}],\"usageMetadata\":{}}",
                MediaType.APPLICATION_JSON));

        assertThat(model.embed("x")).containsExactly(0f, 1f);
    }

    @Test
    void splitsIntoBatchesOfAtMostOneHundred() {
        var model = model(1, null, 0);
        var texts = new ArrayList<String>();
        for (int i = 0; i < 150; i++) texts.add("t" + i);
        var first = new String[100];
        Arrays.fill(first, "1");
        var second = new String[50];
        Arrays.fill(second, "1");
        server.expect(requestTo(URL)).andExpect(jsonPath("$.requests.length()").value(100))
                .andRespond(withSuccess(response(first), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andExpect(jsonPath("$.requests.length()").value(50))
                .andExpect(jsonPath("$.requests[0].content.parts[0].text").value("t100"))
                .andRespond(withSuccess(response(second), MediaType.APPLICATION_JSON));

        var out = model.call(new EmbeddingRequest(texts, null));

        server.verify();
        assertThat(out.getResults()).hasSize(150);
        assertThat(out.getResults().get(149).getIndex()).isEqualTo(149);
    }

    @Test
    void retriesRateLimitAndServerErrorsThenSucceeds() {
        var model = model(1, null, 2);
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(withSuccess(response("5"), MediaType.APPLICATION_JSON));

        assertThat(model.embed("x")).containsExactly(1f);
        server.verify();
    }

    @Test
    void retriesIoErrors() {
        var model = model(1, null, 1);
        server.expect(requestTo(URL)).andRespond(withException(new IOException("connection reset")));
        server.expect(requestTo(URL)).andRespond(withSuccess(response("2"), MediaType.APPLICATION_JSON));

        assertThat(model.embed("x")).containsExactly(1f);
        server.verify();
    }

    @Test
    void clientErrorsFailFastWithoutBodyOrKeyInTheMessage() {
        var model = model(1, null, 3);
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"message\":\"secret listing text echoed\"}}"));

        assertThatThrownBy(() -> model.embed("secret listing text"))
                .isInstanceOf(GeminiEmbeddingException.class)
                .hasMessage("Gemini embedding request failed: HTTP 400")
                .hasNoCause();
        server.verify();
    }

    @Test
    void givesUpAfterMaxRetries() {
        var model = model(1, null, 1);
        server.expect(ExpectedCount.times(2), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> model.embed("x"))
                .isInstanceOf(GeminiEmbeddingException.class)
                .hasMessage("Gemini embedding request failed: HTTP 503");
        server.verify();
    }

    @Test
    void honoursRetryAfterHeaderOn429() {
        var model = recordingModel(2, Duration.ofSeconds(2));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "7"));
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(withSuccess(response("3"), MediaType.APPLICATION_JSON));

        assertThat(model.embed("x")).containsExactly(1f);
        // 429: the server's 7 s beats the 2 s backoff; 503 without a hint: backoff x attempt (4 s).
        assertThat(waits).containsExactly(Duration.ofSeconds(7), Duration.ofSeconds(4));
        server.verify();
    }

    @Test
    void honoursGeminiRetryInfoDelayInThe429Body() {
        var model = recordingModel(1, Duration.ofSeconds(1));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\",\"details\":[{\"@type\":"
                        + "\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\": \"12.5s\"}]}}"));
        server.expect(requestTo(URL)).andRespond(withSuccess(response("3"), MediaType.APPLICATION_JSON));

        assertThat(model.embed("x")).containsExactly(1f);
        assertThat(waits).containsExactly(Duration.ofMillis(12_500));
        server.verify();
    }

    @Test
    void failsAtOnceWhenTheServerAsksForALongerWaitThanTheCap() {
        var model = recordingModel(3, Duration.ZERO);
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "3600"));

        assertThatThrownBy(() -> model.embed("x"))
                .isInstanceOf(GeminiEmbeddingException.class)
                .hasMessage("Gemini embedding request failed: HTTP 429 (server asks to retry after 3600 s)");
        assertThat(waits).isEmpty();
        server.verify();
    }

    @Test
    void parsesRetryAfterHttpDateAndIgnoresGarbage() {
        var now = Instant.parse("2026-09-22T10:00:00Z");
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, DateTimeFormatter.RFC_1123_DATE_TIME
                .format(now.plusSeconds(30).atOffset(ZoneOffset.UTC)));
        assertThat(GeminiEmbeddingModel.serverRetryHint(tooManyRequests(headers, null), now))
                .isEqualTo(Duration.ofSeconds(30));

        var past = new HttpHeaders();
        past.set(HttpHeaders.RETRY_AFTER, DateTimeFormatter.RFC_1123_DATE_TIME
                .format(now.minusSeconds(30).atOffset(ZoneOffset.UTC)));
        assertThat(GeminiEmbeddingModel.serverRetryHint(tooManyRequests(past, null), now)).isEqualTo(Duration.ZERO);

        var garbage = new HttpHeaders();
        garbage.set(HttpHeaders.RETRY_AFTER, "soon");
        assertThat(GeminiEmbeddingModel.serverRetryHint(tooManyRequests(garbage, "{}"), now)).isNull();
        assertThat(GeminiEmbeddingModel.serverRetryHint(tooManyRequests(null, null), now)).isNull();
    }

    @Test
    void rejectsWrongDimensionsAndMissingEmbeddings() {
        var model = model(768, null, 0);
        server.expect(requestTo(URL)).andRespond(withSuccess(response("1, 2, 3"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> model.embed("x"))
                .isInstanceOf(GeminiEmbeddingException.class)
                .hasMessageContaining("size 3, expected 768");

        var model2 = model(1, null, 0);
        server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> model2.call(new EmbeddingRequest(List.of("a", "b"), null)))
                .isInstanceOf(GeminiEmbeddingException.class)
                .hasMessage("Gemini returned 0 embeddings for 2 texts");
    }

    @Test
    void validatesConfiguration() {
        var builder = RestClient.builder();
        assertThatThrownBy(() -> new GeminiEmbeddingModel(builder, BASE, " ", "gemini-embedding-2", 768, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(KEY);
        assertThatThrownBy(() -> new GeminiEmbeddingModel(builder, BASE, KEY, "../x", 768, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(KEY);
        assertThatThrownBy(() -> new GeminiEmbeddingModel(builder, "ftp://x", KEY, "m", 768, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeminiEmbeddingModel(builder, BASE, KEY, "m", 0, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizeLeavesZeroVectorAlone() {
        assertThat(GeminiEmbeddingModel.normalize(List.of(0.0, 0.0))).containsExactly(0f, 0f);
        assertThat(GeminiEmbeddingModel.normalize(List.of(2, 0))).containsExactly(1f, 0f);
    }
}
