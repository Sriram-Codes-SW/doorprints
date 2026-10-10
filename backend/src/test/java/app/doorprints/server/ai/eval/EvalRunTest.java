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
import app.doorprints.server.ai.eval.EvalScorer.Progress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The case loop and the scorecard file (S4b-BL-202): a killed run still leaves a scorecard of what was scored. */
class EvalRunTest {

    private static final class Manual extends Clock {
        Instant now = Instant.parse("2026-10-10T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static List<Map<String, Object>> cases(int n) {
        var out = new ArrayList<Map<String, Object>>();
        for (int i = 1; i <= n; i++) {
            var c = new HashMap<String, Object>();
            c.put("id", "e" + i);
            c.put("type", "extract");
            c.put("expected", Map.of("price", 100));
            out.add(c);
        }
        return out;
    }

    private static CaseResult scored(Map<String, Object> testCase) {
        return EvalScorer.scoreExtract(testCase, Map.of("price", 100), null);
    }

    private static String report(List<CaseResult> results, Progress progress) {
        return EvalScorer.markdown(EvalScorer.header(), EvalScorer.metrics(results, Map.of()), results, List.of(),
                List.of(), progress);
    }

    @Test
    void theScorecardFileExistsAfterEveryCaseAndNamesHowFarTheRunIs(@TempDir Path dir) throws IOException {
        var file = dir.resolve("target").resolve("ai-eval-report.md");
        var results = new ArrayList<CaseResult>();
        var seen = new ArrayList<String>();

        var timeStopped = EvalRun.run(cases(3), results, new Deadline(new Manual(), Duration.ofMinutes(35)), () -> { },
                EvalRunTest::scored, progress -> {
                    try {
                        EvalRun.writeAtomically(file, report(results, progress));
                        // The file is there, whole, before the next case starts.
                        seen.add(Files.readString(file, StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });

        assertThat(timeStopped).isFalse();
        assertThat(seen).hasSize(3);
        assertThat(seen.get(0)).contains("PARTIAL (1 of 3 cases)").contains("| Cases | 1 / 1 passed |");
        assertThat(seen.get(1)).contains("PARTIAL (2 of 3 cases)");
        assertThat(seen.get(2)).doesNotContain("PARTIAL");
        assertThat(Files.list(file.getParent()).toList()).containsExactly(file);
    }

    @Test
    void aSecondWriteReplacesTheFirstWithoutLeavingATemporaryFile(@TempDir Path dir) throws IOException {
        var file = dir.resolve("r.md");
        EvalRun.writeAtomically(file, "one");
        EvalRun.writeAtomically(file, "two");

        assertThat(Files.readString(file)).isEqualTo("two");
        assertThat(Files.list(dir).toList()).containsExactly(file);
    }

    @Test
    void theLoopStopsBetweenCasesWhenTheBudgetIsUsedUpAndTheRestAreNotRun() {
        var clock = new Manual();
        var results = new ArrayList<CaseResult>();
        var ran = new ArrayList<String>();

        var timeStopped = EvalRun.run(cases(5), results, new Deadline(clock, Duration.ofMinutes(35)), () -> { },
                c -> {
                    ran.add((String) c.get("id"));
                    clock.now = clock.now.plus(Duration.ofMinutes(20));
                    return scored(c);
                }, progress -> { });

        assertThat(timeStopped).isTrue();
        assertThat(ran).containsExactly("e1", "e2");
        assertThat(results).hasSize(2);
    }

    @Test
    void aCaseThatRunsOutOfTimeInsideIsNotScoredAndStopsTheRun() {
        var results = new ArrayList<CaseResult>();
        var calls = new ArrayList<String>();

        var timeStopped = EvalRun.run(cases(4), results, new Deadline(new Manual(), Duration.ofMinutes(35)), () -> { },
                c -> {
                    calls.add((String) c.get("id"));
                    if ("e2".equals(c.get("id"))) throw new Deadline.Expired(Duration.ofMinutes(35));
                    return scored(c);
                }, progress -> { });

        assertThat(timeStopped).isTrue();
        assertThat(calls).containsExactly("e1", "e2");
        assertThat(results).extracting(r -> r.id).containsExactly("e1");
    }

    @Test
    void thePauseBetweenCasesCountsTowardsTheBudget() {
        var clock = new Manual();
        var results = new ArrayList<CaseResult>();

        var timeStopped = EvalRun.run(cases(3), results, new Deadline(clock, Duration.ofSeconds(30)),
                () -> clock.now = clock.now.plusSeconds(60), EvalRunTest::scored, progress -> { });

        assertThat(timeStopped).isTrue();
        assertThat(results).hasSize(1);
    }

    @Test
    void anExpiredBudgetRunsNoCaseAtAll() {
        var clock = new Manual();
        var deadline = new Deadline(clock, Duration.ZERO);
        var results = new ArrayList<CaseResult>();

        assertThat(EvalRun.run(cases(2), results, deadline, () -> { }, EvalRunTest::scored, progress -> { })).isTrue();
        assertThat(results).isEmpty();
    }

    // ---- Repeats of the plan cases (S4b-BL-203) respect the time budget ----

    private static final String H1 = "11111111-1111-4111-8111-111111111111";

    private static List<Map<String, Object>> planCases(int n) {
        var out = new ArrayList<Map<String, Object>>();
        for (int i = 1; i <= n; i++) {
            var c = new HashMap<String, Object>();
            c.put("id", "p" + i);
            c.put("type", "plan");
            c.put("expected", Map.of("stopsSubsetOf", List.of(H1), "minStops", 1));
            c.put("input", Map.of());
            out.add(c);
        }
        return out;
    }

    private static CaseResult scoredPlan(Map<String, Object> testCase) {
        return EvalScorer.scorePlan(testCase, Map.of("stops", List.of(Map.of("houseId", H1)), "fallback", false), null,
                List.of(H1));
    }

    private static EvalScorer.Trials firstTrial(List<Map<String, Object>> cases) {
        var trials = new EvalScorer.Trials();
        cases.forEach(c -> trials.record(1, scoredPlan(c)));
        return trials;
    }

    @Test
    void repeatsRunEveryPlanCaseOnceMoreForEachTrialAndLeaveTheGatedResultsAlone() {
        var cases = planCases(2);
        var trials = firstTrial(cases);
        var ran = new ArrayList<String>();
        var rewrites = new int[1];

        var stopped = EvalRun.runRepeats(cases, 3, trials, new Deadline(new Manual(), Duration.ofMinutes(35)), () -> { },
                c -> { ran.add(String.valueOf(c.get("id"))); return scoredPlan(c); }, () -> rewrites[0]++);

        assertThat(stopped).isFalse();
        assertThat(ran).containsExactly("p1", "p2", "p1", "p2");
        assertThat(rewrites[0]).as("the scorecard is rewritten after every repeat").isEqualTo(4);
        assertThat(trials.gated()).hasSize(2);
        assertThat(trials.stability()).extracting(EvalScorer.Stability::label).containsExactly("3/3 passed", "3/3 passed");
    }

    @Test
    void repeatsStopWhenTheBudgetIsUsedUpBeforeATrialAndLeaveTheGatedTrialComplete() {
        var cases = planCases(2);
        var trials = firstTrial(cases);
        var clock = new Manual();
        var ran = new ArrayList<String>();

        // Each pause between trials costs 20 minutes of a 35 minute budget: one repeat fits, the second does not.
        var stopped = EvalRun.runRepeats(cases, 3, trials, new Deadline(clock, Duration.ofMinutes(35)),
                () -> clock.now = clock.now.plus(Duration.ofMinutes(20)),
                c -> { ran.add(String.valueOf(c.get("id"))); return scoredPlan(c); }, () -> { });

        assertThat(stopped).isTrue();
        assertThat(ran).containsExactly("p1");
        assertThat(trials.gated()).hasSize(2);
        assertThat(trials.stability()).extracting(EvalScorer.Stability::label).containsExactly("2/2 passed");
    }

    @Test
    void aRepeatThatRunsOutOfTimeIsNotRecordedAndStopsTheRepeats() {
        var cases = planCases(2);
        var trials = firstTrial(cases);

        var stopped = EvalRun.runRepeats(cases, 3, trials, new Deadline(new Manual(), Duration.ofMinutes(35)), () -> { },
                c -> { throw new Deadline.Expired(Duration.ofMinutes(35)); }, () -> { });

        assertThat(stopped).isTrue();
        assertThat(trials.stability()).isEmpty();
    }

    @Test
    void anExpiredBudgetRunsNoRepeatAtAll() {
        var clock = new Manual();
        var deadline = new Deadline(clock, Duration.ofMinutes(35));
        clock.now = clock.now.plus(Duration.ofMinutes(36));
        var trials = firstTrial(planCases(1));

        assertThat(EvalRun.runRepeats(planCases(1), 5, trials, deadline, () -> { }, EvalRunTest::scoredPlan, () -> { }))
                .isTrue();
        assertThat(trials.stability()).isEmpty();
    }

    @Test
    void thePartialScorecardCarriesTheStabilityTable() {
        var cases = planCases(1);
        var trials = firstTrial(cases);
        trials.record(2, EvalScorer.scorePlan(cases.get(0),
                Map.of("stops", List.of(), "fallback", false), null, List.of(H1)));
        var results = trials.gated();

        var md = EvalScorer.markdown(EvalScorer.header(), EvalScorer.metrics(results, Map.of()), results, List.of(),
                List.of(), new Progress(1, 3, false), trials);

        assertThat(md).contains("PARTIAL (1 of 3 cases)", "## Stability across repeats (informational, not gated)",
                "| p1 | 2 | 1/2 passed |");
    }
}
