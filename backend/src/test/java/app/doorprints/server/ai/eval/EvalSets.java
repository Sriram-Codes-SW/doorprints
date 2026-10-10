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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Additive eval sets next to the golden set (S4b-BL-236, docs/ai/ai-design.md 8.6): a file such as
 * {@code docs/ai/evals/hard-set.json} with its own cases and {@code fixturesFrom} naming the golden set, whose fixture
 * houses and visits it runs against. Such a set has no {@code thresholds}, so a run of it is informational by
 * construction: every metric "not gated", the verdict the golden set's default run's. {@code AI_EVAL_SET} picks one; unset,
 * blank, {@code default} or {@code golden-set} is the golden set as ever, and the set file is not even read.
 */
final class EvalSets {

    static final String GOLDEN = "golden-set";
    /** A set name is a file name without its extension: lower-case words joined by hyphens. */
    static final Pattern NAME = Pattern.compile("[a-z]+(-[a-z]+)*");

    private EvalSets() {
    }

    /** The set chosen for a run, with the golden set it is built on and the scorecard section it adds. */
    record Run(String name, String version, String description, GoldenSet golden) {
        String headerValue() {
            return name + " v" + version;
        }

        EvalScorer.Informational info() {
            return new EvalScorer.Informational("Eval set", name, description, null);
        }
    }

    static boolean isGolden(String name) {
        return name == null || name.isBlank() || GOLDEN.equals(name.strip()) || AddressVariants.DEFAULT_SET.equals(name.strip());
    }

    /**
     * Reads {@code <name>.json} beside the golden set and builds the set to run: the file's version, date, description
     * and cases, the golden set's fixtures and visits, no thresholds. Throws when the name is not a set name, the file is
     * missing, or it does not say it takes its fixtures from this golden set.
     */
    static Run select(GoldenSet golden, Path goldenPath, String name) throws IOException {
        var set = name == null ? "" : name.strip();
        if (!NAME.matcher(set).matches() || set.length() > 32) {
            throw new IllegalArgumentException("AI_EVAL_SET must be a set name (lower-case letters and hyphens), not '" + set + "'");
        }
        var path = goldenPath.toAbsolutePath().resolveSibling(set + ".json");
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("AI_EVAL_SET '" + set + "': no file " + path.normalize());
        return build(golden, goldenPath.getFileName().toString(), set, Files.readString(path, StandardCharsets.UTF_8));
    }

    static Run build(GoldenSet golden, String goldenFileName, String set, String json) {
        var file = GoldenSet.parse(json);
        var from = String.valueOf(file.root().get("fixturesFrom"));
        if (!goldenFileName.equals(from)) {
            throw new IllegalArgumentException("Eval set '" + set + "' takes its fixtures from '" + from + "', not from " + goldenFileName);
        }
        if (file.root().containsKey("thresholds")) {
            throw new IllegalArgumentException("Eval set '" + set + "' has a thresholds key: an additive set is informational and has none");
        }
        var root = new LinkedHashMap<String, Object>();
        root.put("version", file.version());
        root.put("date", file.date());
        root.put("description", file.root().get("description"));
        root.put("fixtureHouses", golden.root().get("fixtureHouses"));
        root.put("fixtureVisits", golden.root().get("fixtureVisits"));
        root.put("thresholds", Map.of());
        root.put("cases", file.root().get("cases"));
        return new Run(set, file.version(), String.valueOf(file.root().get("description")), new GoldenSet(root));
    }
}
