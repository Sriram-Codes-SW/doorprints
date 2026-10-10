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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The case loop and the scorecard file of the golden-set eval, apart from Spring and the network so they are
 * unit-tested ({@link EvalRunTest}). After every case the caller writes the scorecard, so a job that is killed still
 * leaves one (S4b-BL-202).
 */
final class EvalRun {

    private EvalRun() {
    }

    /**
     * Runs the cases in order, adding each result to {@code results} and calling {@code afterCase} with the progress. Before
     * each case (after the pause between cases) it stops when the deadline has passed, or when a case reports that
     * it ran out of time ({@link Deadline.Expired}); that case is not scored and the rest are not run.
     *
     * @return true when the run stopped because of the time budget
     */
    static boolean run(List<Map<String, Object>> cases, List<CaseResult> results, Deadline deadline, Runnable between,
                       Function<Map<String, Object>, CaseResult> runCase, Consumer<Progress> afterCase) {
        boolean first = true;
        for (var testCase : cases) {
            if (!first) between.run();
            first = false;
            if (deadline.expired()) return true; // also after the pause, which counts towards the budget
            CaseResult result;
            try {
                result = runCase.apply(testCase);
            } catch (Deadline.Expired e) {
                return true;
            }
            results.add(result);
            afterCase.accept(new Progress(results.size(), cases.size(), false));
        }
        return false;
    }

    /**
     * Trials 2..{@code repeats} of the plan cases (S4b-BL-203), informational: each result goes to {@code trials} under its
     * trial number, never to the gated results. Before every trial run it pauses, then stops when the deadline has passed
     * or a case runs out of time ({@link Deadline.Expired}); {@code afterTrial} rewrites the scorecard.
     *
     * @return true when the repeats stopped because of the time budget (the gated trial is unaffected)
     */
    static boolean runRepeats(List<Map<String, Object>> planCases, int repeats, EvalScorer.Trials trials,
                              Deadline deadline, Runnable between, Function<Map<String, Object>, CaseResult> runCase,
                              Runnable afterTrial) {
        for (int trial = 2; trial <= repeats; trial++) {
            for (var testCase : planCases) {
                between.run();
                if (deadline.expired()) return true;
                try {
                    trials.record(trial, runCase.apply(testCase));
                } catch (Deadline.Expired e) {
                    return true;
                }
                afterTrial.run();
            }
        }
        return false;
    }

    /** Writes to a temporary file beside the target, then moves it over: a reader never sees half a scorecard. */
    static void writeAtomically(Path target, String content) throws IOException {
        var dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        var tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
