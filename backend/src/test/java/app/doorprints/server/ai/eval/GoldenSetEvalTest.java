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
import org.junit.jupiter.api.AfterEach;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
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
 * plan-only pass, so run it with {@code AI_EVAL_TYPES=plan}; the repeats also stop at the time budget, and the
 * stability table is in every partial scorecard) and
 * {@code AI_EVAL_DEADLINE_MS} (time budget, default 35 minutes) and
 * {@code AI_EVAL_ADDRESS_SET} (S4b-BL-226: a set of {@code docs/ai/evals/address-variants.json}, default {@code default}; the
 * run then uses that set's addresses, the cases the set cannot ask about are listed as not applicable, the scorecard says
 * {@code Address set} and every metric is "not gated": the verdict of the golden set is the default run's, and a run under a
 * set fails only on a harness error or a stop; the default run does not read the file and its scorecard is unchanged),
 * {@code AI_EVAL_SET} (S4b-BL-236: an additive set such as {@code hard-set} beside the golden set, its cases against the
 * golden set's fixtures, no thresholds, the scorecard says {@code Eval set} and nothing is gated) and {@code AI_EVAL_CANARY}
 * (S4b-BL-237: one live canary of {@code canaries.json}; its eval-only seam is on for this run, only the canary's case types
 * run, nothing is gated, and the scorecard prints {@code CANARY: <name> expected drop seen} or {@code NOT seen}).
 *
 * <p>Time budget (S4b-BL-202): the scorecard is rewritten after every case and marked {@code PARTIAL (n of N cases)}
 * until the run completes, so a killed job still leaves one. When the budget is used up between cases (or before a
 * retry) the run stops with {@code STOPPED: time budget}: the verdict is INCOMPLETE (never PASS), the cases not run are
 * not scored, and the test fails. One slow case overshoots the budget by at most one request (the read timeout).
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
    /** The run's time budget (AI_EVAL_DEADLINE_MS, default 35 min), fixed at the start of the test. */
    private Deadline deadline;
    /** Trial 1 (the gated results) and the repeats of the plan cases (S4b-BL-203). */
    private EvalScorer.Trials trials = new EvalScorer.Trials();
    private final List<String> warnings = new ArrayList<>();
    /** Each distinct Vertex AI setup hint is reported once, not once per case. */
    private final Set<String> reportedSetupHints = new HashSet<>();
    private static final Pattern SETUP_HINT =
            Pattern.compile("\"" + AiExceptionHandler.SETUP_HINT_PROPERTY + "\"\\s*:\\s*\"([^\"\\\\]*)\"");
    /** Harness errors (seeding, re-indexing, anything that aborted the run): any entry makes the scorecard FAIL. */
    private final List<String> errors = new ArrayList<>();
    private final Map<String, String> header = EvalScorer.header();
    /** The address set of this run (S4b-BL-226), or null for the default run, which is the only one with a verdict. */
    private AddressVariants.Run addressRun;
    /** The additive eval set of this run (S4b-BL-236, {@code AI_EVAL_SET}), or null for the golden set. Informational. */
    private EvalSets.Run evalSet;
    /** The live canary of this run (S4b-BL-237, {@code AI_EVAL_CANARY}), or null. Informational; its seam is on for this run only. */
    private Canaries.Canary canary;

    @AfterEach
    void canarySeamsOff() {
        Canaries.reset();
    }

    @Test
    void goldenSetMeetsThresholds() throws IOException {
        var path = GoldenSet.locate();
        var loaded = GoldenSet.load(path);
        // AI_EVAL_SET (S4b-BL-236): unset, "default" or "golden-set" is the golden set as ever (no other file is read); a
        // set name runs that file's cases against the golden set's fixtures, informational only (no thresholds, no verdict).
        var setName = env("AI_EVAL_SET", EvalSets.GOLDEN);
        evalSet = EvalSets.isGolden(setName) ? null : EvalSets.select(loaded, path, setName);
        if (evalSet != null) loaded = evalSet.golden();
        // AI_EVAL_CANARY (S4b-BL-237): one live canary of canaries.json; its eval-only seam is turned on here and off after
        // the test, the run is informational and the scorecard prints "CANARY: <name> expected drop seen / NOT seen".
        var canaryName = env("AI_EVAL_CANARY", Canaries.NONE);
        canary = Canaries.isNone(canaryName) ? null : Canaries.load(Canaries.locate()).byName(canaryName);
        if (canary != null) Canaries.apply(canary);
        // AI_EVAL_ADDRESS_SET (S4b-BL-226): unset, empty or "default" is the run as ever (the variants file is not even
        // read); a name from address-variants.json runs the golden set with that set's addresses, informational only.
        var addressName = env("AI_EVAL_ADDRESS_SET", AddressVariants.DEFAULT_SET);
        if (evalSet != null && !AddressVariants.isDefault(addressName)) {
            errors.add("AI_EVAL_SET and AI_EVAL_ADDRESS_SET cannot be combined (the address fingerprints are the golden set's); run them apart");
            addressName = AddressVariants.DEFAULT_SET;
        }
        addressRun = AddressVariants.isDefault(addressName) ? null
                : AddressVariants.load(AddressVariants.locate()).select(loaded, addressName);
        var golden = addressRun == null ? loaded : addressRun.golden();
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
        var budget = Duration.ofMillis(Deadline.parseMs(env("AI_EVAL_DEADLINE_MS", "")));
        deadline = new Deadline(Clock.systemUTC(), budget);
        header.put("Time budget", budget.toMinutes() + " min");

        header.put("Golden set", "v" + loaded.version() + " (" + loaded.date() + "), " + path.normalize());
        if (evalSet != null) header.put("Eval set", evalSet.headerValue() + " (fixtures of the golden set; not gated)");
        if (canary != null) {
            header.put("Canary", canary.name() + " (" + canary.kind() + "; not gated; types " + String.join(", ", canary.types()) + ")");
            types.retainAll(canary.types()); // a canary runs only the types its metric is made of
        }
        if (addressRun != null) header.put("Address set", addressRun.headerValue());
        boolean vertex = "vertex".equals(provider);
        header.put("Provider", vertex
                ? "vertex (Vertex AI, chat " + vertexLocation + ", embeddings "
                        + (vertexEmbeddingLocation.isBlank() ? vertexLocation : vertexEmbeddingLocation) + ")"
                : "aistudio (" + baseUrl + ")");
        header.put("Chat model", vertex ? vertexChatModel : openAiChatModel);
        header.put("Thinking level", EvalScorer.thinkingLabel(vertex, vertexThinkingLevel, openAiReasoningEffort));
        header.put("Embedding", embeddingProvider + " / " + embeddingModel);
        header.put("Case types", String.join(", ", types.stream().sorted().toList()));
        var repeatTypes = new java.util.LinkedHashSet<>(EvalScorer.repeatTypesFrom(System.getenv("AI_EVAL_REPEAT_TYPES")));
        repeatTypes.retainAll(types);
        if (repeats > 1 && repeatTypes.equals(Set.of(PLAN))) {
            header.put("Plan trials", repeats + " (trial 1 gated, the rest informational)");
        } else if (repeats > 1 && !repeatTypes.isEmpty()) {
            header.put("Trials", repeats + " of the " + String.join(", ", repeatTypes) + " cases (trial 1 gated, the rest informational)");
        }
        header.put("Started", started.toString());
        if (evalSet == null) checkThresholdsDeclared(golden); // an additive set has none, by design

        trials = new EvalScorer.Trials();
        var results = trials.gated();
        var planned = new ArrayList<Map<String, Object>>();
        for (var testCase : golden.cases()) {
            var type = String.valueOf(testCase.get("type"));
            if (!Set.of(EXTRACT, ASK, PLAN).contains(type)) {
                warnings.add("Skipped case " + testCase.get("id") + ": unknown type '" + type + "'");
                continue;
            }
            if (types.contains(type)) planned.add(testCase);
        }
        boolean timeStopped = false;
        try {
            writeReport(started, golden, results, new EvalScorer.Progress(0, planned.size(), false));
            boolean seeded = true;
            if (addressRun != null && addressRun.error() != null) {
                // The applied set is not the one the file records: nothing is seeded or run, the scorecard says why.
                errors.add(addressRun.error());
                planned.clear();
                seeded = false;
            } else if (types.contains(ASK) || types.contains(PLAN)) seeded = seed(golden);
            // Without fixtures and an index, ask/plan scores would only measure the seeding failure.
            if (!seeded) planned.removeIf(c -> !EXTRACT.equals(String.valueOf(c.get("type"))));
            // The scorecard is rewritten after every case, so a killed job still leaves one (S4b-BL-202).
            timeStopped = EvalRun.run(planned, results, deadline,
                    () -> pause(Math.min(delayMs, deadline.remaining().toMillis())),
                    c -> run(c, golden), progress -> writeReport(started, golden, results, progress));
            // Trials 2..repeats of the plan cases: informational, never in the metrics or the verdict, and also bound by
            // the time budget (a stop here leaves the gated trial untouched).
            // AI_EVAL_REPEAT_TYPES (S4b-BL-227) says which types: plan only unless asked otherwise.
            var repeatCases = planned.stream().filter(c -> repeatTypes.contains(String.valueOf(c.get("type")))).toList();
            if (!timeStopped && seeded && repeats > 1 && !repeatCases.isEmpty()) {
                var planCases = repeatCases;
                var progressNow = new EvalScorer.Progress(results.size(), planned.size(), false);
                if (EvalRun.runRepeats(planCases, repeats, trials, deadline,
                        () -> pause(Math.min(delayMs, deadline.remaining().toMillis())), c -> run(c, golden),
                        () -> writeReport(started, golden, results, progressNow))) {
                    warnings.add("The repeats of the " + (repeatTypes.equals(Set.of(PLAN)) ? "plan " : "") + "cases stopped at the time budget; the gated trial is complete, "
                            + "the stability table covers only the trials that ran.");
                }
            }
        } catch (QuotaExhausted e) {
            errors.add(EvalScorer.QUOTA_STOPPED + " (" + e.getMessage() + "); " + results.size()
                    + " case(s) scored before the stop, the rest were not run");
        } catch (Deadline.Expired e) {
            timeStopped = true; // the budget ran out during seeding
        } catch (RuntimeException e) {
            errors.add("Eval aborted: " + describe(e));
        }
        var progress = new EvalScorer.Progress(results.size(), planned.size(), timeStopped);
        var metrics = writeReport(started, golden, results, progress);

        // Fails on zero cases, any harness error (e.g. seeding / reindex 503), a time-budget stop or a metric below its threshold.
        // Under an address set the verdict is the default run's (S4b-BL-226): this run fails only on a harness error or a stop.
        // An eval set or a canary (S4b-BL-236, S4b-BL-237) is informational by construction, like an address set.
        var verdict = addressRun == null && informational(metrics) == null ? EvalScorer.verdict(metrics, results, errors, progress)
                : EvalScorer.variantVerdict(results, errors, progress);
        assertThat(verdict.reasons()).as("AI eval failed; scorecard: %s", REPORT.toAbsolutePath()).isEmpty();
    }

    /** The informational marker of this run: the canary's section with its result line, else the eval set's, else null. */
    private EvalScorer.Informational informational(List<Metric> metrics) {
        if (canary != null) {
            return new EvalScorer.Informational("Canary", canary.name(), canary.description(), Canaries.line(canary, metrics));
        }
        return evalSet == null ? null : evalSet.info();
    }

    /** Scores what has run so far and replaces the scorecard file (atomically); returns the metrics. */
    private List<Metric> writeReport(Instant started, GoldenSet golden, List<CaseResult> results,
                                     EvalScorer.Progress progress) {
        header.put("Duration", Duration.between(started, Instant.now()).toSeconds() + " s");
        var metrics = EvalScorer.metrics(results, golden.thresholds());
        var markdown = EvalScorer.markdown(header, metrics, results, warnings, errors, progress, trials, addressRun,
                informational(metrics));
        try {
            EvalRun.writeAtomically(REPORT, markdown);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + REPORT, e);
        }
        if (progress.complete() || progress.timeStopped() || !errors.isEmpty()) System.out.println(markdown);
        return metrics;
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
            // A case that keeps retrying cannot outlast the budget by more than one request (the read timeout).
            if (attempt > 1) deadline.check();
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
                pause(Math.min(waitMs, deadline.remaining().toMillis()));
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
