package app.doorprints.server.ai.embedding;

import app.doorprints.server.ai.ProviderErrors;
import app.doorprints.server.ai.embedding.GeminiEmbeddingModel.GeminiEmbeddingException;
import app.doorprints.server.ai.vertex.AccessTokenSource;
import app.doorprints.server.ai.vertex.VertexEndpoints;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.http.HttpHeaders;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Contract tests: {@link VertexEmbeddingModel} against Vertex AI response bodies. Request shapes are the ones the
 * official google-genai Java SDK 1.65.0 builds in Vertex mode ({@code Models.embedContentParametersPrivateToVertex}:
 * {@code :embedContent} with {@code content} + {@code embedContentConfig} for Gemini embedding models except
 * gemini-embedding-001, {@code :predict} with {@code instances[].content} + {@code parameters} for the rest); response
 * shapes are the ones its Vertex converters read ({@code embedding.values} + {@code usageMetadata} + {@code truncated};
 * {@code predictions[].embeddings.values} + {@code statistics}). Error bodies follow google.rpc.Status as Vertex AI
 * returns it. Vector values are generated (768 per embedding, as in production).
 *
 * <p>Provenance: reconstructed from the SDK source and Google's reference docs, NOT captured from a live call (no Google
 * Cloud access in the build environment); docs/ai/vertex-setup.md step 9 records real ones on the first run.
 */
class VertexEmbeddingContractTest {

    private static final String PROJECT = "doorprints-ai";
    private static final String LOCATION = "asia-south1";
    private static final String BASE = VertexEndpoints.versioned("", LOCATION, "v1beta1");
    private static final String MODEL_URL = BASE + "/projects/" + PROJECT + "/locations/" + LOCATION
            + "/publishers/google/models/";
    /** Built at runtime so secret scanners see no literal token. */
    private static final String TOKEN = "ya29." + "contract-test-token";
    private static final int DIMS = 768;

    private MockRestServiceServer server;
    private final List<Duration> waits = new ArrayList<>();

    private VertexEmbeddingModel model(String model, int maxRetries, AccessTokenSource tokens) {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new VertexEmbeddingModel(builder, BASE, PROJECT, LOCATION, model, DIMS, null, maxRetries,
                Duration.ofSeconds(2), tokens, waits::add);
    }

    private VertexEmbeddingModel model(String model, int maxRetries) {
        return model(model, maxRetries, () -> TOKEN);
    }

    private static String values(int seed) {
        var j = new StringJoiner(",\n      ", "[\n      ", "\n    ]");
        for (int i = 0; i < DIMS; i++) {
            j.add(String.format(Locale.ROOT, "%.9f", Math.sin(seed * 31.0 + i * 0.37) * 0.08));
        }
        return j.toString();
    }

    /** models.embedContent 200 OK from gemini-embedding-2 on Vertex AI (outputDimensionality 768). */
    private static String embedContentResponse(int seed) {
        return """
                {
                  "embedding": {
                    "values": %s
                  },
                  "usageMetadata": {
                    "promptTokenCount": 11,
                    "totalTokenCount": 11,
                    "promptTokensDetails": [
                      {
                        "modality": "TEXT",
                        "tokenCount": 11
                      }
                    ]
                  },
                  "truncated": false
                }
                """.formatted(values(seed));
    }

    /** endpoints.predict 200 OK from gemini-embedding-001 on Vertex AI. */
    private static String predictResponse(int seed) {
        return """
                {
                  "predictions": [
                    {
                      "embeddings": {
                        "statistics": {
                          "truncated": false,
                          "token_count": 9
                        },
                        "values": %s
                      }
                    }
                  ],
                  "metadata": {
                    "billableCharacterCount": 41
                  }
                }
                """.formatted(values(seed));
    }

    static final String RESOURCE_EXHAUSTED = """
            {
              "error": {
                "code": 429,
                "message": "Resource exhausted. Please try again later. Please refer to https://cloud.google.com/vertex-ai/generative-ai/docs/error-code-429 for more details.",
                "status": "RESOURCE_EXHAUSTED"
              }
            }
            """;

    /** Per-minute quota with a RetryInfo detail (Vertex sends it for some quota types). */
    static final String RESOURCE_EXHAUSTED_WITH_RETRY_INFO = """
            {
              "error": {
                "code": 429,
                "message": "Quota exceeded for aiplatform.googleapis.com/online_prediction_requests_per_base_model with base model: gemini-embedding. Please submit a quota increase request. https://cloud.google.com/vertex-ai/docs/generative-ai/quotas-genai.",
                "status": "RESOURCE_EXHAUSTED",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.RetryInfo",
                    "retryDelay": "23s"
                  }
                ]
              }
            }
            """;

    /** 403 when the Vertex AI API is not enabled in the project. */
    static final String SERVICE_DISABLED = """
            {
              "error": {
                "code": 403,
                "message": "Vertex AI API has not been used in project 123456789012 before or it is disabled. Enable it by visiting https://console.developers.google.com/apis/api/aiplatform.googleapis.com/overview?project=123456789012 then retry. If you enabled this API recently, wait a few minutes for the action to propagate to our systems and retry.",
                "status": "PERMISSION_DENIED",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                    "reason": "SERVICE_DISABLED",
                    "domain": "googleapis.com",
                    "metadata": {
                      "service": "aiplatform.googleapis.com",
                      "consumer": "projects/123456789012",
                      "activationUrl": "https://console.developers.google.com/apis/api/aiplatform.googleapis.com/overview?project=123456789012",
                      "containerInfo": "123456789012",
                      "serviceTitle": "Vertex AI API"
                    }
                  }
                ]
              }
            }
            """;

    /** 403 when the caller lacks roles/aiplatform.user. */
    static final String IAM_PERMISSION_DENIED = """
            {
              "error": {
                "code": 403,
                "message": "Permission 'aiplatform.endpoints.predict' denied on resource '//aiplatform.googleapis.com/projects/doorprints-ai/locations/asia-south1/publishers/google/models/gemini-embedding-2' (or it may not exist).",
                "status": "PERMISSION_DENIED",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                    "reason": "IAM_PERMISSION_DENIED",
                    "domain": "aiplatform.googleapis.com",
                    "metadata": {
                      "permission": "aiplatform.endpoints.predict",
                      "resource": "projects/doorprints-ai/locations/asia-south1/publishers/google/models/gemini-embedding-2"
                    }
                  }
                ]
              }
            }
            """;

    /** 404 when the model is not offered in the location. */
    static final String MODEL_NOT_FOUND = """
            {
              "error": {
                "code": 404,
                "message": "Publisher Model `projects/doorprints-ai/locations/asia-south1/publishers/google/models/gemini-embedding-2` was not found or your project does not have access to it. Please ensure you are using a valid model version. For more information, see: https://cloud.google.com/vertex-ai/generative-ai/docs/learn/model-versions",
                "status": "NOT_FOUND"
              }
            }
            """;

    @Test
    void geminiEmbedding2UsesEmbedContentOneTextPerCallWithBearerToken() {
        var model = model("gemini-embedding-2", 0);
        assertThat(model.embedContentApi()).isTrue();
        server.expect(ExpectedCount.once(), requestTo(MODEL_URL + "gemini-embedding-2:embedContent"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(headerDoesNotExist("x-goog-api-key"))
                .andExpect(headerDoesNotExist(VertexEmbeddingModel.USER_PROJECT_HEADER))
                .andExpect(jsonPath("$.content.parts[0].text").value("Blue gate, Indiranagar"))
                .andExpect(jsonPath("$.embedContentConfig.outputDimensionality").value(DIMS))
                .andExpect(jsonPath("$.embedContentConfig.taskType").doesNotExist())
                .andExpect(jsonPath("$.requests").doesNotExist())
                .andRespond(withSuccess(embedContentResponse(1), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(MODEL_URL + "gemini-embedding-2:embedContent"))
                .andExpect(jsonPath("$.content.parts[0].text").value("Green door, HSR Layout"))
                .andRespond(withSuccess(embedContentResponse(2), MediaType.APPLICATION_JSON));

        var response = model.call(new EmbeddingRequest(List.of("Blue gate, Indiranagar", "Green door, HSR Layout"),
                null));

        server.verify();
        assertThat(response.getResults()).hasSize(2);
        assertThat(response.getResults().get(0).getIndex()).isZero();
        assertThat(response.getResults().get(1).getIndex()).isEqualTo(1);
        var v = response.getResults().get(0).getOutput();
        assertThat(v).hasSize(DIMS);
        double norm = 0;
        for (float f : v) norm += (double) f * f;
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(1e-4));
        assertThat(response.getMetadata().getModel()).isEqualTo("gemini-embedding-2");
    }

    @Test
    void geminiEmbedding001UsesPredictWithSnakeCaseTaskType() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var model = new VertexEmbeddingModel(builder, BASE, PROJECT, LOCATION, "publishers/google/models/gemini-embedding-001",
                DIMS, "RETRIEVAL_DOCUMENT", 0, Duration.ZERO, new AccessTokenSource() {
                    @Override
                    public String accessToken() {
                        return TOKEN;
                    }

                    @Override
                    public String quotaProjectId() {
                        return "doorprints-billing";
                    }
                }, waits::add);
        assertThat(model.embedContentApi()).isFalse();
        server.expect(ExpectedCount.once(), requestTo(MODEL_URL + "gemini-embedding-001:predict"))
                .andExpect(header(VertexEmbeddingModel.USER_PROJECT_HEADER, "doorprints-billing"))
                .andExpect(jsonPath("$.instances.length()").value(1))
                .andExpect(jsonPath("$.instances[0].content").value("Blue gate"))
                .andExpect(jsonPath("$.instances[0].task_type").value("RETRIEVAL_DOCUMENT"))
                .andExpect(jsonPath("$.parameters.outputDimensionality").value(DIMS))
                .andRespond(withSuccess(predictResponse(3), MediaType.APPLICATION_JSON));

        var vector = model.embed("Blue gate");

        server.verify();
        assertThat(vector).hasSize(DIMS);
    }

    @Test
    void resourceExhaustedHonoursRetryInfoThenSucceeds() {
        var model = model("gemini-embedding-2", 2);
        server.expect(ExpectedCount.once(), requestTo(MODEL_URL + "gemini-embedding-2:embedContent"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body(RESOURCE_EXHAUSTED_WITH_RETRY_INFO));
        server.expect(ExpectedCount.once(), requestTo(MODEL_URL + "gemini-embedding-2:embedContent"))
                .andRespond(withSuccess(embedContentResponse(4), MediaType.APPLICATION_JSON));

        assertThat(model.embed("Blue gate")).hasSize(DIMS);
        server.verify();
        assertThat(waits).containsExactly(Duration.ofSeconds(23));
    }

    @Test
    void resourceExhaustedAfterRetriesIsAQuotaError() {
        var model = model("gemini-embedding-2", 1);
        server.expect(ExpectedCount.times(2), requestTo(MODEL_URL + "gemini-embedding-2:embedContent"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body(RESOURCE_EXHAUSTED));

        assertThatThrownBy(() -> model.embed("Blue gate"))
                .isInstanceOf(GeminiEmbeddingException.class)
                .hasMessage("Vertex AI embedding request failed: HTTP 429 (RESOURCE_EXHAUSTED)")
                .satisfies(e -> {
                    var g = (GeminiEmbeddingException) e;
                    assertThat(g.httpStatus()).isEqualTo(429);
                    assertThat(g.reason()).isEqualTo("RESOURCE_EXHAUSTED");
                    assertThat(ProviderErrors.isQuotaExhausted(e)).isTrue();
                });
        server.verify();
        assertThat(waits).containsExactly(Duration.ofSeconds(2)); // no RetryInfo: own backoff
    }

    @Test
    void serviceDisabledIsNotRetriedAndExplainsTheFix() {
        var model = model("gemini-embedding-2", 2);
        server.expect(ExpectedCount.once(), requestTo(MODEL_URL + "gemini-embedding-2:embedContent"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body(SERVICE_DISABLED));

        assertThatThrownBy(() -> model.embed("Blue gate"))
                .isInstanceOf(GeminiEmbeddingException.class)
                .hasMessageStartingWith("Vertex AI embedding request failed: HTTP 403 (SERVICE_DISABLED)")
                .hasMessageContaining("Vertex AI API is enabled")
                .satisfies(e -> assertThat(ProviderErrors.isQuotaExhausted(e)).isFalse());
        server.verify();
        assertThat(waits).isEmpty();
    }

    @Test
    void iamPermissionDeniedNamesTheRole() {
        var model = model("gemini-embedding-2", 2);
        server.expect(ExpectedCount.once(), requestTo(MODEL_URL + "gemini-embedding-2:embedContent"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body(IAM_PERMISSION_DENIED));

        assertThatThrownBy(() -> model.embed("Blue gate"))
                .hasMessageStartingWith("Vertex AI embedding request failed: HTTP 403 (IAM_PERMISSION_DENIED)")
                .hasMessageContaining("roles/aiplatform.user")
                // The error body (which names resources) never reaches the message.
                .hasMessageNotContaining("aiplatform.endpoints.predict");
        server.verify();
    }

    @Test
    void modelNotInLocationPointsToTheEmbeddingLocationSetting() {
        var model = model("gemini-embedding-2", 2);
        server.expect(ExpectedCount.once(), requestTo(MODEL_URL + "gemini-embedding-2:embedContent"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body(MODEL_NOT_FOUND));

        assertThatThrownBy(() -> model.embed("Blue gate"))
                .hasMessageStartingWith("Vertex AI embedding request failed: HTTP 404 (NOT_FOUND)")
                .hasMessageContaining("AI_VERTEX_EMBEDDING_LOCATION");
        server.verify();
    }

    @Test
    void wrongDimensionsAreRejected() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var model = new VertexEmbeddingModel(builder, BASE, PROJECT, LOCATION, "gemini-embedding-2", 3072, null, 0,
                Duration.ZERO, () -> TOKEN, waits::add);
        server.expect(ExpectedCount.once(), requestTo(MODEL_URL + "gemini-embedding-2:embedContent"))
                .andRespond(withSuccess(embedContentResponse(5), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> model.embed("Blue gate"))
                .hasMessage("Vertex AI returned an embedding of size 768, expected 3072");
    }

    @Test
    void missingCredentialsFailWithoutAnHttpCall() {
        var model = model("gemini-embedding-2", 2, () -> {
            throw new IllegalStateException("no ADC");
        });

        assertThatThrownBy(() -> model.embed("Blue gate"))
                .isInstanceOf(GeminiEmbeddingException.class)
                .hasMessage("Vertex AI embedding request failed: no access token (IllegalStateException)");
        server.verify();
    }

    @Test
    void embedContentRuleMatchesTheSdk() {
        assertThat(VertexEmbeddingModel.usesEmbedContentApi("gemini-embedding-2")).isTrue();
        assertThat(VertexEmbeddingModel.usesEmbedContentApi("gemini-embedding-2-preview")).isTrue();
        assertThat(VertexEmbeddingModel.usesEmbedContentApi("gemini-embedding-001")).isFalse();
        assertThat(VertexEmbeddingModel.usesEmbedContentApi("text-embedding-005")).isFalse();
    }
}
