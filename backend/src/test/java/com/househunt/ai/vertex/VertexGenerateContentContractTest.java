package com.househunt.ai.vertex;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.errors.ApiException;
import com.househunt.ai.ProviderErrors;
import com.househunt.ai.config.AiProperties;
import com.househunt.ai.rag.AskModels.ModelAnswer;
import com.househunt.ai.web.AiExceptionHandler;
import com.househunt.ai.web.AiUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Contract tests for chat on Vertex AI, end to end through the real stack the app uses with
 * {@code app.ai.provider=vertex}: ChatClient -> Spring AI 2.0.1 {@link GoogleGenAiChatModel} -> google-genai SDK
 * 1.65.0 {@code Client} in Vertex mode, built by {@link VertexAiConfiguration#client} -> HTTP -> a local server
 * answering {@code models.generateContent} bodies in Vertex AI's shape.
 *
 * <p>Payload provenance: the bodies follow the Vertex AI {@code GenerateContentResponse} reference and the field names
 * the SDK's Vertex converters read ({@code candidates[].content.parts[]} with {@code text} / {@code functionCall} /
 * {@code thoughtSignature}, {@code finishReason}, {@code usageMetadata} incl. {@code thoughtsTokenCount} and
 * {@code trafficType}, {@code modelVersion}, {@code createTime}, {@code responseId}) and the google.rpc error format
 * ({@code error.code/message/status/details[]}). They were NOT captured from a live Vertex call (no Google Cloud
 * access in the build environment); docs/ai/vertex-setup.md step 9 records real ones on the first run, and they should
 * replace these if anything differs.
 */
class VertexGenerateContentContractTest {

    private static final String PROJECT = "doorprints-ai";
    private static final String LOCATION = "asia-south1";
    private static final String MODEL = "gemini-3.5-flash";
    private static final String PATH = "/v1beta1/projects/" + PROJECT + "/locations/" + LOCATION
            + "/publishers/google/models/" + MODEL + ":generateContent";
    /** Built at runtime so secret scanners see no literal token. */
    private static final String TOKEN = "ya29." + "contract-test-token";
    /** A Gemini 3 thought signature (opaque base64 bytes); it must be sent back with the function call. */
    static final String THOUGHT_SIGNATURE = "CiQBjz1r9eGd26CkRhxPvg31x00tXXfWRRhw+ZyZIRV6hDf48J8SBQ==";

    static final String TEXT_RESPONSE = """
            {
              "candidates": [
                {
                  "content": {
                    "role": "model",
                    "parts": [
                      {
                        "text": "Blue gate in Indiranagar has 24x7 water [house:0b3f2c1e-5d6a-4f7b-9c8d-1e2f3a4b5c6d]."
                      }
                    ]
                  },
                  "finishReason": "STOP",
                  "avgLogprobs": -0.21474609375
                }
              ],
              "usageMetadata": {
                "promptTokenCount": 412,
                "candidatesTokenCount": 21,
                "totalTokenCount": 689,
                "trafficType": "ON_DEMAND",
                "promptTokensDetails": [
                  {
                    "modality": "TEXT",
                    "tokenCount": 412
                  }
                ],
                "candidatesTokensDetails": [
                  {
                    "modality": "TEXT",
                    "tokenCount": 21
                  }
                ],
                "thoughtsTokenCount": 256
              },
              "modelVersion": "gemini-3.5-flash",
              "createTime": "2026-09-22T06:12:31.482913Z",
              "responseId": "3_bRaNnYHdOq2PgP-L3P8Ag"
            }
            """;

    /** Structured output (the Ask feature's ModelAnswer) as plain JSON text. */
    static final String JSON_RESPONSE = """
            {
              "candidates": [
                {
                  "content": {
                    "role": "model",
                    "parts": [
                      {
                        "text": "{\\"answer\\": \\"Blue gate [house:0b3f2c1e-5d6a-4f7b-9c8d-1e2f3a4b5c6d].\\", \\"citedHouseIds\\": [\\"0b3f2c1e-5d6a-4f7b-9c8d-1e2f3a4b5c6d\\"]}"
                      }
                    ]
                  },
                  "finishReason": "STOP"
                }
              ],
              "usageMetadata": {
                "promptTokenCount": 530,
                "candidatesTokenCount": 48,
                "totalTokenCount": 578,
                "trafficType": "ON_DEMAND"
              },
              "modelVersion": "gemini-3.5-flash",
              "createTime": "2026-09-22T06:12:33.019250Z",
              "responseId": "4fbRaMbqAtOq2PgP-L3P8Ag"
            }
            """;

    /** A function call (the planner's tools), with the Gemini 3 thought signature on the functionCall part. */
    static final String FUNCTION_CALL_RESPONSE = """
            {
              "candidates": [
                {
                  "content": {
                    "role": "model",
                    "parts": [
                      {
                        "functionCall": {
                          "name": "countHouses",
                          "args": {
                            "locality": "Indiranagar"
                          }
                        },
                        "thoughtSignature": "%s"
                      }
                    ]
                  },
                  "finishReason": "STOP"
                }
              ],
              "usageMetadata": {
                "promptTokenCount": 188,
                "candidatesTokenCount": 17,
                "totalTokenCount": 301,
                "trafficType": "ON_DEMAND",
                "thoughtsTokenCount": 96
              },
              "modelVersion": "gemini-3.5-flash",
              "createTime": "2026-09-22T06:12:35.771502Z",
              "responseId": "4_bRaJ7aL9Oq2PgP-L3P8Ag"
            }
            """.formatted(THOUGHT_SIGNATURE);

    static final String AFTER_TOOL_RESPONSE = """
            {
              "candidates": [
                {
                  "content": {
                    "role": "model",
                    "parts": [
                      {
                        "text": "You have 3 saved houses in Indiranagar."
                      }
                    ]
                  },
                  "finishReason": "STOP"
                }
              ],
              "usageMetadata": {
                "promptTokenCount": 231,
                "candidatesTokenCount": 11,
                "totalTokenCount": 242,
                "trafficType": "ON_DEMAND"
              },
              "modelVersion": "gemini-3.5-flash",
              "createTime": "2026-09-22T06:12:37.104233Z",
              "responseId": "5fbRaKOyG9Oq2PgP-L3P8Ag"
            }
            """;

    /** Vertex AI's 429 (dynamic shared quota / per-minute limit). No RetryInfo detail, unlike the Gemini API. */
    static final String RESOURCE_EXHAUSTED = """
            {
              "error": {
                "code": 429,
                "message": "Resource exhausted. Please try again later. Please refer to https://cloud.google.com/vertex-ai/generative-ai/docs/error-code-429 for more details.",
                "status": "RESOURCE_EXHAUSTED"
              }
            }
            """;

    /** 403 when the service account lacks roles/aiplatform.user (IAM ErrorInfo detail). */
    static final String PERMISSION_DENIED = """
            {
              "error": {
                "code": 403,
                "message": "Permission 'aiplatform.endpoints.predict' denied on resource '//aiplatform.googleapis.com/projects/doorprints-ai/locations/asia-south1/publishers/google/models/gemini-3.5-flash' (or it may not exist).",
                "status": "PERMISSION_DENIED",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                    "reason": "IAM_PERMISSION_DENIED",
                    "domain": "aiplatform.googleapis.com",
                    "metadata": {
                      "permission": "aiplatform.endpoints.predict",
                      "resource": "projects/doorprints-ai/locations/asia-south1/publishers/google/models/gemini-3.5-flash"
                    }
                  }
                ]
              }
            }
            """;

    /** 404 when the chat model is not offered in the location (e.g. a new model not yet in asia-south1). */
    static final String MODEL_NOT_FOUND = """
            {
              "error": {
                "code": 404,
                "message": "Publisher Model `projects/doorprints-ai/locations/asia-south1/publishers/google/models/gemini-3.5-flash` was not found or your project does not have access to it. Please ensure you are using a valid model version. For more information, see: https://cloud.google.com/vertex-ai/generative-ai/docs/learn/model-versions",
                "status": "NOT_FOUND"
              }
            }
            """;

    private HttpServer server;
    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final List<String> requestPaths = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> apiKeyHeader = new AtomicReference<>();

    record Reply(int status, String body) {
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try {
                requestPaths.add(exchange.getRequestURI().getPath());
                requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
                var reply = replies.poll();
                if (reply == null) reply = new Reply(500, "{\"error\":{\"code\":500,\"status\":\"INTERNAL\"}}");
                var bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** The production client factory, pointed at the local server, with fixed credentials (no ADC lookup). */
    private GoogleGenAiChatModel model(int maxRetries) {
        var props = new AiProperties(true, AiProperties.VERTEX, null, null, null, null, null, null, null,
                new AiProperties.Embedding(null, null, null, null, null, maxRetries, Duration.ofSeconds(10)),
                new AiProperties.Vertex(PROJECT, LOCATION, null, "http://127.0.0.1:" + server.getAddress().getPort(),
                        null));
        var credentials = GoogleCredentials.create(new AccessToken(TOKEN, Date.from(Instant.now().plusSeconds(3600))));
        var client = VertexAiConfiguration.client(props, new GoogleAccessTokenSource(credentials), 0.001);
        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .options(GoogleGenAiChatOptions.builder().model(MODEL).temperature(0.2).build())
                .build();
    }

    @Test
    void plainAnswerParsesAndTheRequestIsAVertexCall() {
        replies.add(new Reply(200, TEXT_RESPONSE));

        var response = model(0).call(new Prompt("Which house has 24x7 water?"));

        assertThat(response.getResult().getOutput().getText()).startsWith("Blue gate in Indiranagar");
        assertThat(response.getMetadata().getModel()).isEqualTo(MODEL);
        assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(412);
        assertThat(requestPaths).containsExactly(PATH);
        // OAuth bearer token from the credentials; never an API key on Vertex.
        assertThat(authorization.get()).isEqualTo("Bearer " + TOKEN);
        assertThat(apiKeyHeader.get()).isNull();
        assertThat(requestBodies.getFirst()).contains("\"contents\"").contains("Which house has 24x7 water?")
                .contains("\"temperature\"");
    }

    @Test
    void structuredOutputAsUsedByAskParses() {
        replies.add(new Reply(200, JSON_RESPONSE));

        var result = ChatClient.create(model(0)).prompt().user("Which house has 24x7 water?").call()
                .responseEntity(ModelAnswer.class);

        assertThat(result.entity()).isNotNull();
        assertThat(result.entity().citedHouseIds()).containsExactly("0b3f2c1e-5d6a-4f7b-9c8d-1e2f3a4b5c6d");
    }

    @Test
    void functionCallRoundTripSendsTheThoughtSignatureBack() {
        replies.add(new Reply(200, FUNCTION_CALL_RESPONSE));
        replies.add(new Reply(200, AFTER_TOOL_RESPONSE));
        var tools = new CountTools();

        var answer = ChatClient.create(model(0)).prompt().user("How many houses in Indiranagar?").tools(tools)
                .call().content();

        assertThat(answer).isEqualTo("You have 3 saved houses in Indiranagar.");
        assertThat(tools.locality.get()).isEqualTo("Indiranagar");
        assertThat(requestBodies).hasSize(2);
        assertThat(requestBodies.getFirst()).contains("functionDeclarations").contains("countHouses");
        // Gemini 3 rejects a follow-up turn whose functionCall part lacks its thought signature.
        assertThat(requestBodies.get(1)).contains("functionResponse").contains("countHouses")
                .contains(THOUGHT_SIGNATURE);
    }

    @Test
    void canaryMissingModelVersionBreaksSpringAi() {
        // GoogleGenAiChatModel 2.0.1 calls modelVersion().get(): Vertex always sends it; if it ever stops, chat breaks.
        replies.add(new Reply(200, TEXT_RESPONSE.replace("\"modelVersion\": \"gemini-3.5-flash\",", "")));

        assertThatThrownBy(() -> model(0).call(new Prompt("q"))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void resourceExhaustedIsRetriedBoundedThenClassifiedAsQuota() {
        replies.add(new Reply(429, RESOURCE_EXHAUSTED));
        replies.add(new Reply(429, RESOURCE_EXHAUSTED));

        var thrown = catchThrowable(() -> ChatClient.create(model(1)).prompt().user("q").call().content());

        assertThat(thrown).isNotNull();
        assertThat(requestBodies).hasSize(2); // AI_MAX_RETRIES=1 -> 2 attempts, never the SDK default of 5
        assertThat(rootApiException(thrown).code()).isEqualTo(429);
        assertThat(ProviderErrors.isQuotaExhausted(thrown)).isTrue();

        // What the app's clients and the eval harness see: 503 + code AI_QUOTA_EXHAUSTED + Retry-After.
        var response = new AiExceptionHandler().aiUnavailable(new AiUnavailableException("Answering failed", thrown));
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry("code", AiExceptionHandler.QUOTA_EXHAUSTED_CODE);
    }

    @Test
    void permissionDeniedIsNotRetriedAndNotAQuotaError() {
        replies.add(new Reply(403, PERMISSION_DENIED));

        var thrown = catchThrowable(() -> model(2).call(new Prompt("q")));

        assertThat(thrown).isNotNull();
        assertThat(requestBodies).hasSize(1);
        assertThat(rootApiException(thrown).code()).isEqualTo(403);
        assertThat(ProviderErrors.isQuotaExhausted(thrown)).isFalse();
        var response = new AiExceptionHandler(handlerProps())
                .aiUnavailable(new AiUnavailableException("Answering failed", thrown));
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).doesNotContainKey("code");
        // Setup hint for the owner: IAM / API first, then the chat location; never the project id or provider text.
        assertThat((String) response.getBody().getProperties().get(AiExceptionHandler.SETUP_HINT_PROPERTY))
                .contains("roles/aiplatform.user").contains("GCP_LOCATION=global").contains(LOCATION)
                .doesNotContain(PROJECT).doesNotContain("aiplatform.endpoints.predict");
    }

    @Test
    void modelNotInLocationGivesAChatLocationHint() {
        replies.add(new Reply(404, MODEL_NOT_FOUND));

        var thrown = catchThrowable(() -> ChatClient.create(model(2)).prompt().user("q").call().content());

        assertThat(thrown).isNotNull();
        assertThat(requestBodies).hasSize(1); // 404 is not retried
        assertThat(rootApiException(thrown).code()).isEqualTo(404);
        assertThat(ProviderErrors.isQuotaExhausted(thrown)).isFalse();
        assertThat(ProviderErrors.httpFailure(thrown))
                .contains(new ProviderErrors.HttpFailure(ProviderErrors.Call.CHAT, 404));

        var response = new AiExceptionHandler(handlerProps())
                .aiUnavailable(new AiUnavailableException("Answering failed", thrown));
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat((String) response.getBody().getProperties().get(AiExceptionHandler.SETUP_HINT_PROPERTY))
                .contains("chat model").contains("location " + LOCATION).contains("GCP_LOCATION=global")
                .doesNotContain("AI_VERTEX_EMBEDDING_LOCATION").doesNotContain(PROJECT);

        // AI Studio setups (or a handler without settings) get no Vertex hint.
        var aiStudio = new AiExceptionHandler(AiProperties.defaults())
                .aiUnavailable(new AiUnavailableException("Answering failed", thrown));
        assertThat(aiStudio.getBody()).isNotNull();
        assertThat(aiStudio.getBody().getProperties()).doesNotContainKey(AiExceptionHandler.SETUP_HINT_PROPERTY);
    }

    /** The settings the exception handler sees in production with {@code AI_PROVIDER=vertex}. */
    private static AiProperties handlerProps() {
        return new AiProperties(true, AiProperties.VERTEX, null, null, null, null, null, null, null, null,
                new AiProperties.Vertex(PROJECT, LOCATION, null, null, null));
    }

    private static ApiException rootApiException(Throwable t) {
        for (var c = t; c != null; c = c.getCause()) {
            if (c instanceof ApiException a) return a;
        }
        throw new AssertionError("no google-genai ApiException in the cause chain of " + t);
    }

    /** Must be public: Spring AI invokes tool methods reflectively. */
    public static class CountTools {
        final AtomicReference<String> locality = new AtomicReference<>();

        @Tool(name = "countHouses", description = "Counts the saved houses in a locality")
        public int countHouses(@ToolParam(description = "Locality name") String locality) {
            this.locality.set(locality);
            return 3;
        }
    }
}
