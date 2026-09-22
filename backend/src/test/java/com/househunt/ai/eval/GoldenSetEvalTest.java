package com.househunt.ai.eval;

import com.househunt.ai.eval.EvalScorer.CaseResult;
import com.househunt.ai.eval.EvalScorer.Metric;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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

import static com.househunt.ai.eval.EvalScorer.ASK;
import static com.househunt.ai.eval.EvalScorer.EXTRACT;
import static com.househunt.ai.eval.EvalScorer.PLAN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end evaluation of the AI features against a real model, driven by {@code docs/ai/evals/golden-set.json}.
 *
 * <p>Skipped unless {@code AI_API_KEY} is set, so the normal build never calls a model (costs quota, and the answers
 * are not deterministic). Run it with {@code .github/workflows/ai-evals.yml} (manual) or locally:
 * <pre>
 * AI_API_KEY=... DB_URL=... mvn -Dtest=GoldenSetEvalTest -Dsurefire.failIfNoSpecifiedTests=false test
 * </pre>
 * Flow: seed the fixture houses and visits through the public API ({@code PUT /api/houses/{id}},
 * {@code PUT /api/visits/{id}}), rebuild the vector index ({@code POST /api/ai/reindex}), run every case through the
 * real HTTP endpoints (API-key filter, validation and sanitizers included), score it with {@link EvalScorer}, write a
 * markdown scorecard to {@code target/ai-eval-report.md} and fail if a metric misses the golden set's thresholds.
 *
 * <p>Use an empty database: other saved houses change what retrieval returns (the report warns when it sees any).
 * Optional environment: {@code AI_EVAL_TYPES} (default {@code extract,ask,plan}), {@code AI_EVAL_DELAY_MS} (pause
 * between cases for free-tier rate limits, default 4000) and {@code AI_EVAL_GOLDEN_SET} (another golden set file).
 */
@Tag("llm-eval")
@EnabledIfEnvironmentVariable(named = "AI_API_KEY", matches = ".*\\S.*",
        disabledReason = "LLM eval: needs a real provider key in AI_API_KEY (see .github/workflows/ai-evals.yml)")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.ai.enabled=true",
                "app.mcp.enabled=false",
                // The eval paces itself; the app's own AI limiter must not turn cases into 429s.
                "app.ai.rate-limit.requests-per-minute=1000",
                "app.ai.rate-limit.burst=1000"})
class GoldenSetEvalTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};
    /** Generated per run, never a literal (nothing for secret scanners); >= 32 characters as the key filter requires. */
    private static final String KEY = "eval-" + UUID.randomUUID();
    static final Path REPORT = Path.of("target", "ai-eval-report.md");
    /** Provider errors surface as 503 (retryable); free-tier quota errors often clear after a short wait. */
    private static final int MAX_ATTEMPTS = 4;

    @DynamicPropertySource
    static void apiKey(DynamicPropertyRegistry registry) {
        registry.add("app.api-key", () -> KEY);
    }

    @Value("${local.server.port}")
    int port;

    @Value("${spring.ai.openai.base-url:unknown}")
    String baseUrl;

    @Value("${spring.ai.openai.chat.model:unknown}")
    String chatModel;

    @Value("${app.ai.embedding.model:unknown}")
    String embeddingModel;

    @Value("${app.ai.embedding.provider:unknown}")
    String embeddingProvider;

    private RestClient api;
    private final List<String> warnings = new ArrayList<>();
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
        var started = Instant.now();

        header.put("Golden set", "v" + golden.version() + " (" + golden.date() + "), " + path.normalize());
        header.put("Provider", baseUrl);
        header.put("Chat model", chatModel);
        header.put("Embedding", embeddingProvider + " / " + embeddingModel);
        header.put("Case types", String.join(", ", types.stream().sorted().toList()));
        header.put("Started", started.toString());
        checkThresholdsDeclared(golden);

        var results = new ArrayList<CaseResult>();
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
                results.add(run(testCase, golden));
            }
        } catch (RuntimeException e) {
            errors.add("Eval aborted: " + describe(e));
        } finally {
            header.put("Duration", Duration.between(started, Instant.now()).toSeconds() + " s");
            metrics = EvalScorer.metrics(results, golden.thresholds());
            var markdown = EvalScorer.markdown(header, metrics, results, warnings, errors);
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
     * records a harness error, so the scorecard FAILs) when either step fails.
     */
    private boolean seed(GoldenSet golden) {
        try {
            seedFixtures(golden);
        } catch (RuntimeException e) {
            errors.add("Seeding fixtures failed: " + describe(e));
            return false;
        }
        try {
            // Each save also triggers async indexing of that house; let it settle before the full rebuild.
            pause(5000);
            var indexed = post("/api/ai/reindex", null);
            header.put("Indexed houses", String.valueOf(indexed.get("indexed")));
            return true;
        } catch (RuntimeException e) {
            errors.add("POST /api/ai/reindex failed (embeddings unavailable; ask/plan cases skipped): " + describe(e));
            return false;
        }
    }

    private void seedFixtures(GoldenSet golden) {
        var now = Instant.now().toString();
        for (var house : golden.fixtureHouses()) {
            var body = new LinkedHashMap<String, Object>(house);
            body.put("updatedAt", now);
            body.put("deleted", false);
            api.put().uri("/api/houses/{id}", house.get("id")).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().toBodilessEntity();
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
        long t0 = System.nanoTime();
        try {
            response = switch (type) {
                case EXTRACT -> post("/api/ai/extract-listing", Map.of("text", String.valueOf(input.get("text"))));
                case ASK -> post("/api/ai/ask", input);
                default -> post("/api/ai/plan-visits", input);
            };
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
        result.latencyMs = ms;
        return result;
    }

    /** POST with retries on 503/429 (provider quota or transient failure), honouring Retry-After. */
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
                if ((status != 503 && status != 429) || attempt >= MAX_ATTEMPTS) throw e;
                long waitMs = retryAfterMs(e, attempt);
                warnings.add("POST " + path + " returned " + status + " (attempt " + attempt + "), retried after "
                        + waitMs / 1000 + " s");
                pause(waitMs);
            }
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
