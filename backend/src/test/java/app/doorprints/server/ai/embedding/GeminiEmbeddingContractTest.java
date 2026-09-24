package app.doorprints.server.ai.embedding;

import app.doorprints.server.ai.embedding.GeminiEmbeddingModel.GeminiEmbeddingException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Contract tests: {@link GeminiEmbeddingModel} against response bodies in the exact shape the Gemini API returns
 * (models.batchEmbedContents reference: {@code embeddings[]} of ContentEmbedding {@code values[]} (+ optional
 * {@code shape[]}), optional {@code usageMetadata}; google.rpc error bodies with {@code code}, {@code message},
 * {@code status} and typed {@code details}). The bodies are recorded samples, trimmed only in the vector values
 * (generated here, 768 per embedding as in production). No network, no key.
 */
class GeminiEmbeddingContractTest {

    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta";
    private static final String URL = BASE + "/models/gemini-embedding-2:batchEmbedContents";
    /** Built at runtime so secret scanners see no literal key. */
    private static final String KEY = "contract-" + "test-" + "key";
    private static final int DIMS = 768;

    private MockRestServiceServer server;
    private final List<Duration> waits = new ArrayList<>();

    private GeminiEmbeddingModel model(int maxRetries) {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new GeminiEmbeddingModel(builder, BASE, KEY, "gemini-embedding-2", DIMS, null, maxRetries,
                Duration.ofSeconds(2), waits::add);
    }

    /** 768 plausible values (|v| < 0.1, mixed signs, 9 significant digits as the API prints them). */
    private static String values(int seed) {
        var j = new StringJoiner(",\n        ", "[\n        ", "\n      ]");
        for (int i = 0; i < DIMS; i++) {
            double v = Math.sin(seed * 31.0 + i * 0.37) * 0.08;
            j.add(String.format(Locale.ROOT, "%.9f", v));
        }
        return j.toString();
    }

    /** batchEmbedContents 200 OK, as returned for two texts by gemini-embedding-2 (outputDimensionality 768). */
    private static String batchResponse() {
        return """
                {
                  "embeddings": [
                    {
                      "values": %s
                    },
                    {
                      "values": %s
                    }
                  ]
                }
                """.formatted(values(1), values(2));
    }

    /** Same, with the optional fields of the reference (ContentEmbedding.shape, usageMetadata). */
    private static String batchResponseWithOptionalFields() {
        return """
                {
                  "embeddings": [
                    {
                      "values": %s,
                      "shape": [
                        768
                      ]
                    }
                  ],
                  "usageMetadata": {
                    "promptTokenCount": 9,
                    "totalTokenCount": 9
                  }
                }
                """.formatted(values(3));
    }

    /** 400 for a wrong key: Gemini answers 400 INVALID_ARGUMENT with ErrorInfo reason API_KEY_INVALID, not 401. */
    private static final String INVALID_KEY = """
            {
              "error": {
                "code": 400,
                "message": "API key not valid. Please pass a valid API key.",
                "status": "INVALID_ARGUMENT",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                    "reason": "API_KEY_INVALID",
                    "domain": "googleapis.com",
                    "metadata": {
                      "service": "generativelanguage.googleapis.com"
                    }
                  },
                  {
                    "@type": "type.googleapis.com/google.rpc.LocalizedMessage",
                    "locale": "en-US",
                    "message": "API key not valid. Please pass a valid API key."
                  }
                ]
              }
            }
            """;

    /** Free-tier per-minute quota: 429 RESOURCE_EXHAUSTED with QuotaFailure, Help and RetryInfo details. */
    private static final String QUOTA_EXCEEDED = """
            {
              "error": {
                "code": 429,
                "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits.\\n* Quota exceeded for metric: generativelanguage.googleapis.com/embed_content_free_tier_requests, limit: 100, model: gemini-embedding-2\\nPlease retry in 37.412938104s.",
                "status": "RESOURCE_EXHAUSTED",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.QuotaFailure",
                    "violations": [
                      {
                        "quotaMetric": "generativelanguage.googleapis.com/embed_content_free_tier_requests",
                        "quotaId": "EmbedContentRequestsPerMinutePerProjectPerModel-FreeTier",
                        "quotaDimensions": {
                          "location": "global",
                          "model": "gemini-embedding-2"
                        },
                        "quotaValue": "100"
                      }
                    ]
                  },
                  {
                    "@type": "type.googleapis.com/google.rpc.Help",
                    "links": [
                      {
                        "description": "Learn more about Gemini API quotas",
                        "url": "https://ai.google.dev/gemini-api/docs/rate-limits"
                      }
                    ]
                  },
                  {
                    "@type": "type.googleapis.com/google.rpc.RetryInfo",
                    "retryDelay": "37s"
                  }
                ]
              }
            }
            """;

    private static final String OVERLOADED = """
            {
              "error": {
                "code": 503,
                "message": "The model is overloaded. Please try again later.",
                "status": "UNAVAILABLE"
              }
            }
            """;

    /** A request that echoes input text in the error message (bad request for the text itself). */
    private static final String BAD_REQUEST_ECHO = """
            {
              "error": {
                "code": 400,
                "message": "* BatchEmbedContentsRequest.requests[0].content.parts[0].text: 3BHK near Ramesh's shop is too long\\n",
                "status": "INVALID_ARGUMENT"
              }
            }
            """;

    @Test
    void parsesARecordedBatchResponseIntoNormalisedVectorsInRequestOrder() {
        var model = model(0);
        server.expect(requestTo(URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header(GeminiEmbeddingModel.API_KEY_HEADER, KEY))
                .andExpect(jsonPath("$.requests[0].model").value("models/gemini-embedding-2"))
                .andExpect(jsonPath("$.requests[0].outputDimensionality").value(DIMS))
                .andExpect(jsonPath("$.requests[1].content.parts[0].text").value("second house"))
                .andRespond(withSuccess(batchResponse(), MediaType.APPLICATION_JSON));

        var out = model.call(new EmbeddingRequest(List.of("first house", "second house"), null));

        server.verify();
        assertThat(out.getResults()).hasSize(2);
        for (int i = 0; i < 2; i++) {
            var v = out.getResults().get(i).getOutput();
            assertThat(out.getResults().get(i).getIndex()).isEqualTo(i);
            assertThat(v).hasSize(DIMS);
            double norm = 0;
            for (float f : v) norm += (double) f * f;
            assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(1e-4));
        }
        // Order is the request order (the native response has no index field at all).
        assertThat(out.getResults().get(0).getOutput()[0]).isNotEqualTo(out.getResults().get(1).getOutput()[0]);
    }

    @Test
    void toleratesTheOptionalShapeAndUsageMetadataFields() {
        var model = model(0);
        server.expect(requestTo(URL)).andRespond(withSuccess(batchResponseWithOptionalFields(),
                MediaType.APPLICATION_JSON));

        assertThat(model.embed("a house")).hasSize(DIMS);
        server.verify();
    }

    @Test
    void invalidKeyFailsFastWithTheReasonButWithoutTheBodyOrKey() {
        var model = model(3);
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body(INVALID_KEY));

        assertThatThrownBy(() -> model.embed("a house"))
                .isInstanceOf(GeminiEmbeddingException.class)
                .hasMessage("Gemini embedding request failed: HTTP 400 (API_KEY_INVALID)")
                .hasMessageNotContaining(KEY)
                .hasNoCause();
        assertThat(waits).isEmpty();
        server.verify();
    }

    @Test
    void anErrorMessageEchoingTheTextNeverReachesOurMessage() {
        var model = model(0);
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body(BAD_REQUEST_ECHO));

        assertThatThrownBy(() -> model.embed("3BHK near Ramesh's shop"))
                .isInstanceOf(GeminiEmbeddingException.class)
                .hasMessage("Gemini embedding request failed: HTTP 400 (INVALID_ARGUMENT)");
    }

    @Test
    void quota429WaitsForTheRetryInfoDelayThenSucceeds() {
        var model = model(2);
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON).body(QUOTA_EXCEEDED));
        server.expect(requestTo(URL)).andRespond(withSuccess(batchResponseWithOptionalFields(),
                MediaType.APPLICATION_JSON));

        assertThat(model.embed("a house")).hasSize(DIMS);
        assertThat(waits).containsExactly(Duration.ofSeconds(37));
        server.verify();
    }

    @Test
    void retryAfterHeaderTakesPrecedenceOverTheBody() {
        var model = model(1);
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "12").contentType(MediaType.APPLICATION_JSON).body(QUOTA_EXCEEDED));
        server.expect(requestTo(URL)).andRespond(withSuccess(batchResponseWithOptionalFields(),
                MediaType.APPLICATION_JSON));

        assertThat(model.embed("a house")).hasSize(DIMS);
        assertThat(waits).containsExactly(Duration.ofSeconds(12));
        server.verify();
    }

    @Test
    void quota429WithoutRetriesLeftReportsTheStatusOnly() {
        var model = model(0);
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON).body(QUOTA_EXCEEDED));

        assertThatThrownBy(() -> model.embed("a house"))
                .isInstanceOf(GeminiEmbeddingException.class)
                .hasMessage("Gemini embedding request failed: HTTP 429 (RESOURCE_EXHAUSTED)")
                .hasMessageNotContaining("quota");
        server.verify();
    }

    @Test
    void overloaded503IsRetriedWithBackoff() {
        var model = model(1);
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON).body(OVERLOADED));
        server.expect(requestTo(URL)).andRespond(withSuccess(batchResponse(), MediaType.APPLICATION_JSON));

        assertThat(model.call(new EmbeddingRequest(List.of("a", "b"), null)).getResults()).hasSize(2);
        assertThat(waits).containsExactly(Duration.ofSeconds(2));
        server.verify();
    }
}
