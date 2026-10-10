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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The layer that decides each prompt-injection case, from {@code docs/ai/evals/injection-layers.json} (S4b-BL-239,
 * docs/ai/ai-design.md 8.6): {@code pipeline} when a deterministic server-side step makes the injection unable to succeed
 * whatever the model writes, {@code model} when the model's behaviour decides at least one guard check. Informational:
 * the scorecard prints injectionResistance for the two groups apart next to the combined metric, which stays as it is.
 */
final class InjectionLayers {

    static final String PIPELINE = "pipeline";
    static final String MODEL = "model";
    static final String DEFAULT_PATH = "../docs/ai/evals/injection-layers.json";

    /** One case of the file. */
    record Layer(String id, String layer, String step, String reference, List<String> pipelineChecks, String rationale) {
    }

    /** The injection cases of a run by layer: passed and all, for each. */
    record Split(int modelOk, int model, int pipelineOk, int pipeline) {
    }

    private static InjectionLayers loaded;
    private final Map<String, Layer> byId;

    InjectionLayers(List<Layer> layers) {
        byId = new LinkedHashMap<>();
        layers.forEach(l -> byId.put(l.id(), l));
    }

    static Path locate() {
        var fromBackend = Path.of(DEFAULT_PATH);
        return Files.isRegularFile(fromBackend) ? fromBackend : Path.of("docs/ai/evals/injection-layers.json");
    }

    static InjectionLayers load(Path path) throws IOException {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    static InjectionLayers parse(String json) {
        var out = new ArrayList<Layer>();
        for (var c : GoldenSet.maps(GoldenSet.parse(json).root().get("cases"))) {
            out.add(new Layer(String.valueOf(c.get("id")), String.valueOf(c.get("layer")),
                    c.get("step") == null ? null : String.valueOf(c.get("step")),
                    c.get("reference") == null ? null : String.valueOf(c.get("reference")),
                    GoldenSet.strings(c.get("pipelineChecks")), String.valueOf(c.get("rationale"))));
        }
        return new InjectionLayers(List.copyOf(out));
    }

    /** The file of this checkout, read once; null when it is not there (a scorecard then has no layer lines). */
    static synchronized InjectionLayers shared() {
        if (loaded == null) {
            try {
                var path = locate();
                if (Files.isRegularFile(path)) loaded = load(path);
            } catch (IOException e) {
                return null;
            }
        }
        return loaded;
    }

    List<Layer> all() {
        return List.copyOf(byId.values());
    }

    /** {@code pipeline} or {@code model}; a case the file does not list counts as model. */
    String layerOf(String id) {
        var l = byId.get(id);
        return l == null ? MODEL : l.layer();
    }

    /** The scored injection cases (infrastructure cases are in no count, as in every metric) by layer. */
    Split split(List<CaseResult> results) {
        int modelOk = 0, model = 0, pipelineOk = 0, pipeline = 0;
        for (var r : results) {
            if (!r.isInjection() || r.infra) continue;
            boolean ok = r.injectionResisted();
            if (PIPELINE.equals(layerOf(r.id))) {
                pipeline++;
                if (ok) pipelineOk++;
            } else {
                model++;
                if (ok) modelOk++;
            }
        }
        return new Split(modelOk, model, pipelineOk, pipeline);
    }

    /**
     * The scorecard block: the model-dependent line with its Wilson interval, the pipeline-guarded line, and the
     * combined line as the Metrics table shows it. Empty when the run scored no injection case.
     */
    static String block(Split s) {
        if (s.model() + s.pipeline() == 0) return "";
        return "\n### Injection resistance by layer (informational, not gated)\n\n"
                + "- injectionResistance (model-dependent): " + s.modelOk() + "/" + s.model() + " "
                + Interval.label(s.modelOk(), s.model()) + "\n"
                + "- injectionResistance (pipeline-guarded): " + s.pipelineOk() + "/" + s.pipeline()
                + " (a deterministic server step decides these; they say nothing about the model)\n"
                + "- injectionResistance (combined, as in the Metrics table): " + (s.modelOk() + s.pipelineOk()) + "/"
                + (s.model() + s.pipeline()) + "\n";
    }
}
