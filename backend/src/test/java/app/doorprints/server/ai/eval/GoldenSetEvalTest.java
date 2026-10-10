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

package app.doorprints.server.ai.eval;

import app.doorprints.server.ai.eval.EvalScorer.CaseResult;
import app.doorprints.server.ai.eval.EvalScorer.Metric;
import app.doorprints.server.ai.web.AiExceptionHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static app.doorprints.server.ai.eval.EvalScorer.ASK;
import static app.doorprints.server.ai.eval.EvalScorer.EXTRACT;
import static app.doorprints.server.ai.eval.EvalScorer.PLAN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end evaluation of the AI features against a real model, driven by {@code docs/ai/evals/golden-set.json}.
 *
 * <p>Skipped unless a provider is configured ({@code AI_API_KEY} for AI Studio, or {@code AI_PROVIDER=vertex} with
 * {@code GCP_PROJECT_ID} and Application Default Credentials), so the normal build never calls a model (costs quota,
 * and the answers are not deterministic). Run it with {@code .github/workflows/ai-evals.yml} (manual) or locally:
 * <pre>
 * AI_API_KEY=... DB_URL=... mvn -Dtest=GoldenSetEvalTest -Dsurefire.failIfNoSpecifiedTests=false test
 * AI_PROVIDER=vertex GCP_PROJECT_ID=... DB_URL=... mvn -Dtest=GoldenSetEvalTest -Dsurefire.failIfNoSpecifiedTests=false test
 * </pre>
 * <p>Quota-aware: when the provider's quota is exhausted (the app answers 503 with {@code code: AI_QUOTA_EXHAUSTED},
 * i.e. HTTP 429 / RESOURCE_EXHAUSTED from AI Studio or Vertex AI) and one wait of Retry-After does not help, the run
 * stops, the scorecard says {@code STOPPED: provider quota exhausted} and the test fails, instead of recording every
 * remaining case as an error. To save calls, saves are not embedded one by one ({@code app.ai.index-on-change=false});
 * the single re-index embeds each fixture house once.
 * Flow: seed the fixture houses and visits through the public API ({@code PUT /api/houses/{id}},
 * {@code PUT /api/visits/{id}}), rebuild the vector index ({@code POST /api/ai/reindex}), run every case through the
 * real HTTP endpoints (API-key filter, validation and sanitizers included), score it with {@link EvalScorer}, write a
 * markdown scorecard to {@code target/ai-eval-report.md} and fail if a metric misses the golden set's thresholds.
 * A case that fails because of the provider (not the model) is listed as an infrastructure error, left out of every
 * metric, and makes the verdict INCOMPLETE (the test still fails; it never reads PASS).
 *
 * <p>Use an empty database: other saved houses change what retrieval returns (the report warns when it sees any).
 * Optional environment: {@code AI_EVAL_TYPES} (default {@code extract,ask,plan}), {@code AI_EVAL_DELAY_MS} (pause
 * between cases for free-tier rate limits, default 4000), {@code AI_EVAL_GOLDEN_SET} (another golden set file) and
 * {@code AI_EVAL_REPEATS} (S4b-BL-203: trials per plan case, default 1, at most {@link EvalScorer#MAX_REPEATS}; trial 1
 * is scored and gated as ever, trials 2 and later only fill the report's stability table; each repeat is one more
 * plan-only pass, so run it with {@code AI_EVAL_TYPES=plan}).
 */
@Tag("llm-eval")
@EnabledIf(value = "providerConfigured",
        disabledReason = "LLM eval: needs AI_API_KEY (AI Studio) or AI_PROVIDER=vertex + GCP_PROJECT_ID (Vertex AI), "
                + "see .github/workflows/ai-evals.yml")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.ai.enabled=true",
                "app.mcp.enabled=false",
                // Only the explicit re-index embeds the fixtures (halves embedding calls, no async races).
                "app.ai.index-on-change=false",
                // Retrieval must be able to return every fixture house (production default 6, fixtures 7 since
                // golden set v0.6); GoldenSetEvalConfigTest pins this against the golden set file.
                "app.ai.rag.top-k=20",
                // The eval paces itself; the app's own AI limiter must not turn cases into 429s.
                "app.ai.rate-limit.requests-per-minute=1000",
                "app.ai.rate-limit.burst=1000"})
class GoldenSetEvalTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};
    /** Generated per run, never a literal (nothing for secret scanners); >= 32 characters as the key filter requires. */
    private static final String KEY = "eval-" + UUID.randomUUID();
    static final Path REPORT = Path.of("target", "ai-eval-report.md");
    /** Provider errors surface as 503 (retryable); transient ones often clear after a short wait. */
    private static final int MAX_ATTEMPTS = 3;
    /** Quota errors: one wait of Retry-After, then stop the whole run (each attempt already includes provider retries). */
    private static final int QUOTA_MAX_ATTEMPTS = 2;

    /** JUnit condition: AI Studio key present, or Vertex AI selected with a project id. */
    static boolean providerConfigured() {
        var provider = env("AI_PROVIDER", "aistudio").toLowerCase(Locale.ROOT);
        if (provider.equals("vertex")) return !env("GCP_PROJECT_ID", "").isEmpty();
        return !env("AI_API_KEY", "").isEmpty();
    }

    @DynamicPropertySource
    static void apiKey(DynamicPropertyRegistry registry) {
        registry.add("app.api-key", () -> KEY);
    }

    @Value("${local.server.port}")
    int port;

    @Value("${app.ai.provider:aistudio}")
    String provider;

    @Value("${spring.ai.openai.base-url:unknown}")
    String baseUrl;

    @Value("${spring.ai.openai.chat.model:unknown}")
    String openAiChatModel;

    @Value("${spring.ai.google.genai.chat.model:unknown}")
    String vertexChatModel;

    @Value("${spring.ai.google.genai.chat.thinking-level:}")
    String vertexThinkingLevel;

    @Value("${spring.ai.openai.chat.reasoning-effort:}")
    String openAiReasoningEffort;

    @Value("${app.ai.vertex.location:}")
    String vertexLocation;

    @Value("${app.ai.vertex.embedding-location:}")
    String vertexEmbeddingLocation;

    @Value("${app.ai.embedding.model:unknown}")
    String embeddingModel;

    @Value("${app.ai.embedding.provider:unknown}")
    String embeddingProvider;

    private RestClient api;
    private final List<String> warnings = new ArrayList<>();
    /** Each distinct Vertex AI setup hint is reported once, not once per case. */
    private final Set<String> reportedSetupHints = new HashSet<>();
    private static final Pattern SETUP_HINT =
            Pattern.compile("\"" + AiExceptionHandler.SETUP_HINT_PROPERTY + "\"\\s*:\\s*\"([^\"\\\\]*)\"");
    /** Harness errors (seeding, re-indexing, anything that aborted the run): any entry makes the scorecard FAIL. */
    private final List<String> errors = new ArrayList<>();
    private final Map<String, String> header = EvalScorer.header();

    @Test
    void goldenSetMeetsThresholds() throws IOException {
        var path = GoldenSet.locate();
        var golden = GoldenSet.load(path);
        var requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofMinutes(3));
        api = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(requestFactory)
                .defaultHeader("X-API-Key", KEY)
                .build();

        var types = new HashSet<>(Arrays.asList(env("AI_EVAL_TYPES", "extract,ask,plan")
                .toLowerCase(Locale.ROOT).strip().split("\\s*,\\s*")));
        long delayMs = Math.max(0, Long.parseLong(env("AI_EVAL_DELAY_MS", "4000")));
        int repeats = EvalScorer.repeatsFrom(System.getenv("AI_EVAL_REPEATS"));
        var started = Instant.now();

        header.put("Golden set", "v" + golden.version() + " (" + golden.date() + "), " + path.normalize());
        boolean vertex = "vertex".equals(provider);
        header.put("Provider", vertex
                ? "vertex (Vertex AI, chat " + vertexLocation + ", embeddings "
                        + (vertexEmbeddingLocation.isBlank() ? vertexLocation : vertexEmbeddingLocation) + ")"
                : "aistudio (" + baseUrl + ")");
        header.put("Chat model", vertex ? vertexChatModel : openAiChatModel);
        header.put("Thinking level", EvalScorer.thinkingLabel(vertex, vertexThinkingLevel, openAiReasoningEffort));
        header.put("Embedding", embeddingProvider + " / " + embeddingModel);
        header.put("Case types", String.join(", ", types.stream().sorted().toList()));
        if (repeats > 1 && types.contains(PLAN)) header.put("Plan trials", repeats + " (trial 1 gated, the rest informational)");
        header.put("Started", started.toString());
        checkThresholdsDeclared(golden);

        var trials = new EvalScorer.Trials();
        var results = trials.gated();
        List<Metric> metrics = List.of();
        try {
            boolean seeded = true;
            if (types.contains(ASK) || types.contains(PLAN)) seeded = seed(golden);
            boolean first = true;
            for (var testCase : golden.cases()) {
                var type = String.valueOf(testCase.get("type"));
                if (!Set.of(EXTRACT, ASK, PLAN).contains(type)) {
                    warnings.add("Skipped case " + testCase.get("id") + ": unknown type '" + type + "'");
                    continue;
                }
                if (!types.contains(type)) continue;
                // Without fixtures and an index, ask/plan scores would only measure the seeding failure.
                if (!seeded && !EXTRACT.equals(type)) continue;
                if (!first) pause(delayMs);
                first = false;
                trials.record(1, run(testCase, golden));
            }
            // Trials 2..repeats of the plan cases: informational, never in the metrics or the verdict.
            if (seeded && types.contains(PLAN)) {
                for (int trial = 2; trial <= repeats; trial++) {
                    for (var testCase : golden.cases()) {
                        if (!PLAN.equals(String.valueOf(testCase.get("type")))) continue;
                        pause(delayMs);
                        trials.record(trial, run(testCase, golden));
                    }
                }
            }
        } catch (QuotaExhausted e) {
            errors.add(EvalScorer.QUOTA_STOPPED + " (" + e.getMessage() + "); " + results.size()
                    + " case(s) scored before the stop, the rest were not run");
        } catch (RuntimeException e) {
            errors.add("Eval aborted: " + describe(e));
        } finally {
            header.put("Duration", Duration.between(started, Instant.now()).toSeconds() + " s");
            metrics = EvalScorer.metrics(results, golden.thresholds());
            var markdown = EvalScorer.markdown(header, metrics, results, warnings, errors, trials);
            Files.createDirectories(REPORT.getParent());
            Files.writeString(REPORT, markdown, StandardCharsets.UTF_8);
            System.out.println(markdown);
        }

        // Fails on zero cases, any harness error (e.g. seeding / reindex 503) or a metric below its threshold.
        var verdict = EvalScorer.verdict(metrics, results, errors);
        assertThat(verdict.reasons()).as("AI eval failed; scorecard: %s", REPORT.toAbsolutePath()).isEmpty();
    }

    private static String describe(RuntimeException e) {
        if (e instanceof RestClientResponseException r) {
            return "HTTP " + r.getStatusCode().value() + ": " + EvalScorer.truncate(r.getResponseBodyAsString(), 300);
        }
        return e.getClass().getSimpleName() + ": " + EvalScorer.truncate(String.valueOf(e.getMessage()), 300);
    }

    /**
     * Upserts the fixtures through the public API, then rebuilds the vector index synchronously. Returns false (and
     * records a harness error, so the scorecard FAILs) when either step fails; a quota error stops the run.
     */
    private boolean seed(GoldenSet golden) {
        try {
            seedFixtures(golden);
        } catch (RuntimeException e) {
            errors.add("Seeding fixtures failed: " + describe(e));
            return false;
        }
        try {
            // Saves are not embedded one by one here (app.ai.index-on-change=false), so no settling pause is needed.
            var indexed = post("/api/ai/reindex", null);
            header.put("Indexed houses", String.valueOf(indexed.get("indexed")));
            // A partial index would lower the Ask and Plan scores for a reason that is not the model: harness error.
            var countError = FixtureSeeding.indexedCountError(indexed.get("indexed"), golden.fixtureHouses().size());
            if (countError != null) {
                errors.add(countError);
                return false;
            }
            return true;
        } catch (QuotaExhausted e) {
            throw e;
        } catch (RuntimeException e) {
            errors.add("POST /api/ai/reindex failed (embeddings unavailable; ask/plan cases skipped): " + describe(e));
            return false;
        }
    }

    private void seedFixtures(GoldenSet golden) {
        var nowInstant = Instant.now();
        var now = nowInstant.toString();
        var fixtureHouses = golden.fixtureHouses();
        for (int i = 0; i < fixtureHouses.size(); i++) {
            var house = fixtureHouses.get(i);
            // Distinct, ordered timestamps: the same list order, and so the same search results, every run.
            api.put().uri("/api/houses/{id}", house.get("id")).contentType(MediaType.APPLICATION_JSON)
                    .body(FixtureSeeding.seedBody(house, i, nowInstant)).retrieve().toBodilessEntity();
        }
        for (var visit : golden.fixtureVisits()) {
            var body = new LinkedHashMap<String, Object>(visit);
            body.put("updatedAt", now);
            body.put("deleted", false);
            api.put().uri("/api/visits/{id}", visit.get("id")).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().toBodilessEntity();
        }
        var fixtureIds = new HashSet<>(golden.fixtureHouseIds());
        var live = api.get().uri("/api/houses").retrieve().body(LIST);
        long others = live == null ? 0 : live.stream()
                .map(h -> String.valueOf(h.get("id")).toLowerCase(Locale.ROOT))
                .filter(id -> !fixtureIds.contains(id))
                .count();
        if (others > 0) {
            warnings.add(others + " saved house(s) besides the fixtures are in this database; they can change "
                    + "retrieval and citation scores. Run the eval against an empty database.");
        }
    }

    private CaseResult run(Map<String, Object> testCase, GoldenSet golden) {
        var type = String.valueOf(testCase.get("type"));
        var input = GoldenSet.map(testCase.get("input"));
        Map<String, Object> response = null;
        String error = null;
        String infra = null;
        long t0 = System.nanoTime();
        try {
            response = switch (type) {
                case EXTRACT -> post("/api/ai/extract-listing", Map.of("text", String.valueOf(input.get("text"))));
                case ASK -> post("/api/ai/ask", input);
                default -> post("/api/ai/plan-visits", input);
            };
        } catch (QuotaExhausted e) {
            throw e; // stops the run; this case is not scored
        } catch (InfraFailure e) {
            error = e.getMessage();
            infra = e.getMessage();
            response = null;
        } catch (RestClientResponseException e) {
            error = "HTTP " + e.getStatusCode().value() + ": " + EvalScorer.truncate(e.getResponseBodyAsString(), 300);
            response = null;
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName() + ": " + EvalScorer.truncate(String.valueOf(e.getMessage()), 300);
            response = null;
        }
        long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        var result = switch (type) {
            case EXTRACT -> EvalScorer.scoreExtract(testCase, response, error);
            case ASK -> EvalScorer.scoreAsk(testCase, response, error);
            default -> EvalScorer.scorePlan(testCase, response, error, golden.fixtureHouseIds());
        };
        // A provider failure that outlasted the retries says nothing about the model: not scored, run INCOMPLETE.
        if (infra != null) EvalScorer.markInfra(result, infra);
        result.latencyMs = ms;
        return result;
    }

    /**
     * POST with retries on 503/429, honouring Retry-After; {@link RetryPolicy} decides from the problem's {@code cause}:
     * a provider failure is retried and then throws {@link InfraFailure}; a model failure (unreadable output) is not
     * retried and is scored. A provider quota error (problem {@code code: AI_QUOTA_EXHAUSTED}) is retried once after
     * Retry-After and then throws {@link QuotaExhausted}, which stops the run.
     */
    private Map<String, Object> post(String path, Object body) {
        for (int attempt = 1; ; attempt++) {
            try {
                var spec = api.post().uri(path);
                if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
                var result = spec.retrieve().body(MAP);
                if (result == null) throw new IllegalStateException("empty response body from " + path);
                return result;
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                boolean quota = isQuotaError(e);
                if (quota && attempt >= QUOTA_MAX_ATTEMPTS) {
                    throw new QuotaExhausted("POST " + path + " still HTTP " + status + " "
                            + AiExceptionHandler.QUOTA_EXHAUSTED_CODE + " after " + attempt + " attempt(s)");
                }
                String hint = quota ? null : setupHint(e);
                if (hint != null) {
                    // Vertex AI 401/403/404 (credentials, IAM, model not in the location): retrying cannot help.
                    if (reportedSetupHints.add(hint)) warnings.add("POST " + path + " returned " + status + ", setup: " + hint);
                    throw e;
                }
                switch (RetryPolicy.decide(status, RetryPolicy.causeOf(bodyOf(e)), attempt, MAX_ATTEMPTS)) {
                    case SCORE -> throw e;
                    case INFRA -> throw new InfraFailure("POST " + path + " still HTTP " + status + " (cause "
                            + RetryPolicy.PROVIDER + ") after " + attempt + " attempt(s): "
                            + EvalScorer.truncate(String.valueOf(bodyOf(e)), 200));
                    case RETRY -> { }
                }
                long waitMs = retryAfterMs(e, attempt);
                warnings.add("POST " + path + " returned " + status + (quota ? " (provider quota exhausted)" : "")
                        + " (attempt " + attempt + "), retried after " + waitMs / 1000 + " s");
                pause(waitMs);
            }
        }
    }

    private static String bodyOf(RestClientResponseException e) {
        try {
            return e.getResponseBodyAsString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * The provider failed (problem {@code cause: provider}) on every attempt: the case is recorded as an infrastructure
     * error, left out of the metrics, and the run is INCOMPLETE ({@link RetryPolicy}).
     */
    static final class InfraFailure extends RuntimeException {
        InfraFailure(String message) {
            super(message);
        }
    }

    /** Problem-detail property {@code setupHint} (JSON string without quotes or escapes by construction), or null. */
    static String setupHint(RestClientResponseException e) {
        String body;
        try {
            body = e.getResponseBodyAsString();
        } catch (RuntimeException ignored) {
            return null;
        }
        if (body == null) return null;
        var m = SETUP_HINT.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** The app's problem detail for HTTP 429 / RESOURCE_EXHAUSTED from AI Studio or Vertex AI. */
    static boolean isQuotaError(RestClientResponseException e) {
        String body;
        try {
            body = e.getResponseBodyAsString();
        } catch (RuntimeException ignored) {
            return false;
        }
        return body != null && body.contains(AiExceptionHandler.QUOTA_EXHAUSTED_CODE);
    }

    /** Stops the eval: the provider's quota is exhausted, so every further case would only fail the same way. */
    static final class QuotaExhausted extends RuntimeException {
        QuotaExhausted(String message) {
            super(message);
        }
    }

    private static long retryAfterMs(RestClientResponseException e, int attempt) {
        var headers = e.getResponseHeaders();
        var retryAfter = headers == null ? null : headers.getFirst("Retry-After");
        if (retryAfter != null) {
            try {
                return Math.min(120, Math.max(1, Long.parseLong(retryAfter.strip()))) * 1000;
            } catch (NumberFormatException ignored) {
                // HTTP-date form: fall through to the default backoff.
            }
        }
        return 15_000L * attempt;
    }

    private void checkThresholdsDeclared(GoldenSet golden) {
        var declared = golden.thresholds().keySet();
        for (var m : EvalScorer.metrics(List.of(), Map.of())) {
            if (!declared.contains(m.name())) warnings.add("No threshold for metric " + m.name() + " in the golden set");
        }
    }

    private static String env(String name, String fallback) {
        var v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v.strip();
    }

    private static void pause(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
