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
