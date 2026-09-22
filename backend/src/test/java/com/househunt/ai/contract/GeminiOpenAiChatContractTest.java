package com.househunt.ai.contract;

import com.househunt.ai.rag.AskModels.ModelAnswer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for chat through Gemini's OpenAI-compatible endpoint, end to end through the real stack the app uses:
 * ChatClient -> Spring AI 2.0.1 {@link OpenAiChatModel} -> openai-java SDK (4.49.0) over HTTP -> a local server that
 * answers with bodies in Gemini's {@code chat.completions} shape.
 *
 * <p>Why: Gemini's OpenAI-compatible {@code /embeddings} omits {@code data[].index}, which the SDK requires, and every
 * embedding call failed (docs/ai/ai-design.md 3.1). For chat the SDK's lazy accessors that Spring AI calls are
 * {@code id}, {@code model}, {@code choices[]}, {@code choices[].index}, {@code choices[].finish_reason},
 * {@code choices[].message} (required: missing -> {@code OpenAIInvalidDataException}); {@code created} is read
 * leniently (Spring AI returns 0), and {@code object} is only checked when response validation is on, which Spring
 * AI does not enable. Gemini's chat responses carry all of the required fields, so chat stays on the
 * OpenAI-compatible path; the "canary" tests below pin down what would break if Gemini ever dropped one.
 */
public class GeminiOpenAiChatContractTest {

    private static final String KEY = "contract-" + "test-" + "key";

    /** Plain answer, as Gemini returns it (keys in Gemini's alphabetical order, no system_fingerprint/logprobs). */
    static final String TEXT_COMPLETION = """
            {
              "choices": [
                {
                  "finish_reason": "stop",
                  "index": 0,
                  "message": {
                    "content": "Blue gate in Indiranagar has 24x7 water [house:0b3f2c1e-5d6a-4f7b-9c8d-1e2f3a4b5c6d].",
                    "role": "assistant"
                  }
                }
              ],
              "created": 1758528000,
              "id": "8XbRaPm4Ce-Bz7IP9pSX8Ag",
              "model": "gemini-3.5-flash",
              "object": "chat.completion",
              "usage": {
                "completion_tokens": 21,
                "prompt_tokens": 412,
                "total_tokens": 433
              }
            }
            """;

    /** Structured output (the app's ModelAnswer) inside a fenced block, as Gemini often wraps JSON. */
    static final String JSON_COMPLETION = """
            {
              "choices": [
                {
                  "finish_reason": "stop",
                  "index": 0,
                  "message": {
                    "content": "```json\\n{\\"answer\\": \\"Blue gate [house:0b3f2c1e-5d6a-4f7b-9c8d-1e2f3a4b5c6d].\\", \\"citedHouseIds\\": [\\"0b3f2c1e-5d6a-4f7b-9c8d-1e2f3a4b5c6d\\"]}\\n```",
                    "role": "assistant"
                  }
                }
              ],
              "created": 1758528001,
              "id": "9XbRaKq2Fe-Bz7IP3pSX8Ag",
              "model": "gemini-3.5-flash",
              "object": "chat.completion",
              "usage": {
                "completion_tokens": 48,
                "prompt_tokens": 530,
                "total_tokens": 578
              }
            }
            """;

    /** A function call: no content, finish_reason "tool_calls" ("stop" has been reported from Gemini too; both work). */
    static String toolCallCompletion(String finishReason) {
        return """
                {
                  "choices": [
                    {
                      "finish_reason": "%s",
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "tool_calls": [
                          {
                            "function": {
                              "arguments": "{\\"locality\\":\\"Indiranagar\\"}",
                              "name": "countHouses"
                            },
                            "id": "function-call-15469658207311565427",
                            "type": "function"
                          }
                        ]
                      }
                    }
                  ],
                  "created": 1758528002,
                  "id": "AXbRaLr7Ge-Bz7IP5pSX8Ag",
                  "model": "gemini-3.5-flash",
                  "object": "chat.completion",
                  "usage": {
                    "completion_tokens": 17,
                    "prompt_tokens": 188,
                    "total_tokens": 205
                  }
                }
                """.formatted(finishReason);
    }

    static final String AFTER_TOOL = """
            {
              "choices": [
                {
                  "finish_reason": "stop",
                  "index": 0,
                  "message": {
                    "content": "You have 3 saved houses in Indiranagar.",
                    "role": "assistant"
                  }
                }
              ],
              "created": 1758528003,
              "id": "BXbRaMs8He-Bz7IP6pSX8Ag",
              "model": "gemini-3.5-flash",
              "object": "chat.completion"
            }
            """;

    static final String RATE_LIMITED = """
            [{
              "error": {
                "code": 429,
                "message": "You exceeded your current quota, please check your plan and billing details.",
                "status": "RESOURCE_EXHAUSTED"
              }
            }]
            """;

    private HttpServer server;
    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final List<String> requestPaths = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

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
                var reply = replies.poll();
                if (reply == null) reply = new Reply(500, "{\"error\":{\"code\":500,\"status\":\"INTERNAL\"}}");
                var bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
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

    private OpenAiChatModel model() {
        var options = OpenAiChatOptions.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/openai/")
                .apiKey(KEY)
                .model("gemini-3.5-flash")
                .maxRetries(0)
                .timeout(Duration.ofSeconds(10))
                .build();
        return OpenAiChatModel.builder().options(options).build();
    }

    private static String withoutField(String json, String line) {
        var out = json.replace(line, "");
        assertThat(out).isNotEqualTo(json);
        return out;
    }

    @Test
    void plainGeminiCompletionParses() {
        replies.add(new Reply(200, TEXT_COMPLETION));

        var response = model().call(new Prompt("Which house has 24x7 water?"));

        assertThat(response.getResult().getOutput().getText()).startsWith("Blue gate in Indiranagar");
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualToIgnoringCase("stop");
        assertThat(response.getMetadata().getId()).isEqualTo("8XbRaPm4Ce-Bz7IP9pSX8Ag");
        assertThat(response.getMetadata().getModel()).isEqualTo("gemini-3.5-flash");
        assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(433);
        assertThat(requestPaths).containsExactly("/v1beta/openai/chat/completions");
        assertThat(authorization.get()).isEqualTo("Bearer " + KEY);
        assertThat(requestBodies.getFirst()).contains("\"model\":\"gemini-3.5-flash\"");
    }

    @Test
    void structuredOutputAsUsedByAskParses() {
        replies.add(new Reply(200, JSON_COMPLETION));

        var result = ChatClient.create(model()).prompt().user("Which house has 24x7 water?").call()
                .responseEntity(ModelAnswer.class);

        assertThat(result.entity()).isNotNull();
        assertThat(result.entity().citedHouseIds()).containsExactly("0b3f2c1e-5d6a-4f7b-9c8d-1e2f3a4b5c6d");
        assertThat(result.response()).isNotNull();
    }

    @Test
    void functionCallRoundTripAsUsedByThePlannerWorks() {
        for (var finishReason : List.of("tool_calls", "stop")) {
            replies.clear();
            requestBodies.clear();
            replies.add(new Reply(200, toolCallCompletion(finishReason)));
            replies.add(new Reply(200, AFTER_TOOL));
            var tools = new CountTools();

            var answer = ChatClient.create(model()).prompt().user("How many houses in Indiranagar?").tools(tools)
                    .call().content();

            assertThat(answer).isEqualTo("You have 3 saved houses in Indiranagar.");
            assertThat(tools.locality.get()).isEqualTo("Indiranagar");
            assertThat(requestBodies).hasSize(2);
            assertThat(requestBodies.getFirst()).contains("countHouses");
            // The tool result goes back with Gemini's call id.
            assertThat(requestBodies.get(1)).contains("function-call-15469658207311565427").contains("\"role\":\"tool\"");
        }
    }

    @Test
    void missingCreatedAndObjectAreTolerated() {
        var json = withoutField(withoutField(TEXT_COMPLETION, "\"created\": 1758528000,"),
                "\"object\": \"chat.completion\",");
        replies.add(new Reply(200, json));

        assertThat(model().call(new Prompt("q")).getResult().getOutput().getText()).startsWith("Blue gate");
    }

    // Canaries: what the SDK path needs. If Gemini ever drops one of these, chat must move off the
    // OpenAI-compatible endpoint like embeddings did (docs/ai/ai-design.md 3.1, contract section).

    @Test
    void canaryMissingChoiceIndexBreaksTheSdkPath() {
        replies.add(new Reply(200, withoutField(TEXT_COMPLETION, "\"index\": 0,")));

        assertThatThrownBy(() -> model().call(new Prompt("q"))).hasStackTraceContaining("`index` is not set");
    }

    @Test
    void canaryMissingFinishReasonBreaksTheSdkPath() {
        replies.add(new Reply(200, withoutField(TEXT_COMPLETION, "\"finish_reason\": \"stop\",")));

        assertThatThrownBy(() -> model().call(new Prompt("q"))).hasStackTraceContaining("`finish_reason` is not set");
    }

    @Test
    void canaryMissingIdBreaksTheSdkPath() {
        replies.add(new Reply(200, withoutField(TEXT_COMPLETION, "\"id\": \"8XbRaPm4Ce-Bz7IP9pSX8Ag\",")));

        assertThatThrownBy(() -> model().call(new Prompt("q"))).hasStackTraceContaining("`id` is not set");
    }

    @Test
    void canaryMissingToolCallIdBreaksTheSdkPath() {
        // The planner depends on it: OpenAiChatModel.buildGeneration reads tool_calls[].id (a required SDK field) and
        // sends it back with the tool result. Gemini's id format ("function-call-<digits>") is not confirmed live.
        replies.add(new Reply(200, withoutField(toolCallCompletion("tool_calls"),
                "\"id\": \"function-call-15469658207311565427\",")));

        assertThatThrownBy(() -> model().call(new Prompt("q"))).hasStackTraceContaining("`id` is not set");
    }

    @Test
    void rateLimitIsAnExceptionTheServicesTurnInto503() {
        // Error body as observed from Gemini's OpenAI-compatible endpoint (google.rpc error inside a JSON array);
        // whatever the body, the SDK raises an exception for a 429 and the services map it to 503.
        replies.add(new Reply(429, RATE_LIMITED));

        assertThatThrownBy(() -> model().call(new Prompt("q"))).isInstanceOf(RuntimeException.class);
        assertThat(requestBodies).hasSize(1); // maxRetries 0 here; production uses AI_MAX_RETRIES
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
