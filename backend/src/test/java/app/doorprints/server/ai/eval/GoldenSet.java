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

import org.springframework.boot.json.JsonParserFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The golden set in {@code docs/ai/evals/golden-set.json}, read as plain maps and lists (the eval sends fixtures and
 * inputs to the API as JSON unchanged, so no typed model is needed).
 */
final class GoldenSet {

    /** Relative to the backend module, which is Maven's working directory for Surefire. */
    static final String DEFAULT_PATH = "../docs/ai/evals/golden-set.json";

    private final Map<String, Object> root;

    GoldenSet(Map<String, Object> root) {
        this.root = root;
    }

    /** {@code AI_EVAL_GOLDEN_SET} overrides the location; otherwise the backend-relative path, then repo-relative. */
    static Path locate() {
        var override = System.getenv("AI_EVAL_GOLDEN_SET");
        if (override != null && !override.isBlank()) return Path.of(override.strip());
        var fromBackend = Path.of(DEFAULT_PATH);
        if (Files.isRegularFile(fromBackend)) return fromBackend;
        return Path.of("docs/ai/evals/golden-set.json");
    }

    static GoldenSet load(Path path) throws IOException {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    static GoldenSet parse(String json) {
        return new GoldenSet(JsonParserFactory.getJsonParser().parseMap(json));
    }

    /** The parsed file as it is (AddressVariants copies it, never changes it). */
    Map<String, Object> root() {
        return root;
    }

    String version() {
        return String.valueOf(root.getOrDefault("version", "?"));
    }

    String date() {
        return String.valueOf(root.getOrDefault("date", "?"));
    }

    List<Map<String, Object>> fixtureHouses() {
        return maps(root.get("fixtureHouses"));
    }

    List<Map<String, Object>> fixtureVisits() {
        return maps(root.get("fixtureVisits"));
    }

    List<Map<String, Object>> cases() {
        return maps(root.get("cases"));
    }

    /** Metric name -> {"min": x} or {"max": x}. */
    Map<String, Map<String, Object>> thresholds() {
        var out = new LinkedHashMap<String, Map<String, Object>>();
        map(root.get("thresholds")).forEach((k, v) -> out.put(k, map(v)));
        return out;
    }

    List<String> fixtureHouseIds() {
        return fixtureHouses().stream().map(h -> String.valueOf(h.get("id")).toLowerCase(Locale.ROOT)).toList();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    static List<Map<String, Object>> maps(Object o) {
        var out = new ArrayList<Map<String, Object>>();
        if (o instanceof List<?> list) list.forEach(item -> out.add(map(item)));
        return out;
    }

    static List<String> strings(Object o) {
        var out = new ArrayList<String>();
        if (o instanceof List<?> list) list.forEach(item -> { if (item != null) out.add(String.valueOf(item)); });
        return out;
    }
}
