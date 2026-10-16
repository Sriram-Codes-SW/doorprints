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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The address variants of {@code docs/ai/evals/address-variants.json} (S4b-BL-225, docs/ai/ai-design.md 8.3b): a named
 * set changes the address-like text of the golden set's fixture houses, so the evals can be run with places the model
 * knows and places it cannot. {@link #apply} is pure (the golden set it is given is never changed) and the TypeScript
 * port {@code web/src/app/core/ai/address-variants.ts} does the same thing, pinned by the fingerprints in the file.
 *
 * <p>What a set may change, and nothing else (anything else is refused, not ignored):
 * <ul>
 *   <li>a house: {@code address}, {@code street}, {@code locality}, {@code label} and {@code notesAppend} (the house's
 *       notes become the original, a space and the appended text); ids, coordinates, status, price, bedrooms, rating,
 *       checklist, contact fields and visits are untouched by construction;</li>
 *   <li>a case: {@code input.text} (extract) or {@code input.question} (ask, plan), {@code expected.mustContain},
 *       {@code expected.locality}, and {@code expectedDelete: ["locality"]} (the text no longer holds a locality, so none
 *       is expected rather than guessed);</li>
 *   <li>{@code guards}: strings appended to {@code mustNotContain} (ask), {@code summaryMustNotContain} (plan) or
 *       {@code notesMustNotContain} (extract) of every case that runs.</li>
 * </ul>
 *
 * <p>The variant-safe rule: a case runs under a set when the set rewrites it, or when none of the place words the set
 * changed occurs in its input or expected strings; any other case is returned in {@link Applied#notApplicable()} and
 * is not run. A place word is a word of a house's old locality, label or city that the old text of the house held, that
 * its new text (address, street, locality, label, notes) no longer holds, that has a letter, three characters or more,
 * and is not one of the file's {@code genericWords}. Words are lower-cased runs of letters and digits.
 */
final class AddressVariants {

    static final String DEFAULT_SET = "default";
    /** Relative to the backend module, which is Maven's working directory for Surefire. */
    static final String DEFAULT_PATH = "../docs/ai/evals/address-variants.json";

    static final Set<String> HOUSE_KEYS = Set.of("address", "street", "locality", "label", "notesAppend");
    static final Set<String> CASE_KEYS = Set.of("input", "expected", "expectedDelete");
    static final Set<String> INPUT_KEYS = Set.of("text", "question");
    static final Set<String> EXPECTED_KEYS = Set.of("mustContain", "locality");
    static final Set<String> ANCHOR_RULES = Set.of("city-word-in-address", "city-word-in-notes", "none");

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern HAS_LETTER = Pattern.compile("\\p{L}");
    private static final List<String> HOUSE_TEXT = List.of("address", "street", "locality", "label", "notes");

    /** A set applied to a golden set: the golden set to run (the very same object for the default) and the case ids left out. */
    record Applied(String set, GoldenSet golden, List<String> notApplicable) {
    }

    /**
     * A named set chosen for a run (S4b-BL-226): the golden set to run, what was left out, and the line the scorecard
     * header shows. A run of a set is informational (the verdict is the default run's); {@code error} is a harness error
     * when the fingerprint of the applied set is not the one the file records, which means the file, the golden set or a
     * port has drifted.
     */
    record Run(String set, String description, String version, String fingerprint, GoldenSet golden,
               List<String> notApplicable, int total, String error) {
        /** {@code known-alt (address-variants v0.1, fingerprint 089350e09de9)}. */
        String headerValue() {
            return set + " (address-variants v" + version + ", fingerprint " + fingerprint.substring(0, 12) + ")";
        }
    }

    private final Map<String, Object> root;

    AddressVariants(Map<String, Object> root) {
        this.root = root;
    }

    /** {@code AI_EVAL_ADDRESS_VARIANTS} overrides the location; otherwise the backend-relative path, then repo-relative. */
    static Path locate() {
        var override = System.getenv("AI_EVAL_ADDRESS_VARIANTS");
        if (override != null && !override.isBlank()) return Path.of(override.strip());
        var fromBackend = Path.of(DEFAULT_PATH);
        if (Files.isRegularFile(fromBackend)) return fromBackend;
        return Path.of("docs/ai/evals/address-variants.json");
    }

    static AddressVariants load(Path path) throws IOException {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    static AddressVariants parse(String json) {
        return new AddressVariants(JsonParserFactory.getJsonParser().parseMap(json));
    }

    String version() {
        return String.valueOf(root.getOrDefault("version", "?"));
    }

    Map<String, Object> root() {
        return root;
    }

    List<String> genericWords() {
        return GoldenSet.strings(root.get("genericWords")).stream().map(w -> w.toLowerCase(Locale.ROOT)).toList();
    }

    /** The names of the sets in file order. */
    List<String> setNames() {
        return new ArrayList<>(GoldenSet.map(root.get("sets")).keySet());
    }

    boolean hasSet(String name) {
        return GoldenSet.map(root.get("sets")).containsKey(name);
    }

    Map<String, Object> set(String name) {
        if (!hasSet(name)) {
            throw new IllegalArgumentException("Unknown address set '" + name + "'; the sets are default, "
                    + String.join(", ", setNames()));
        }
        return GoldenSet.map(GoldenSet.map(root.get("sets")).get(name));
    }

    String anchorRule(String name) {
        return String.valueOf(set(name).get("anchorRule"));
    }

    /** The fingerprint the file records for a set, or null. */
    String recordedFingerprint(String name) {
        var v = GoldenSet.map(root.get("fingerprints")).get(name);
        return v == null ? null : String.valueOf(v);
    }

    /** True when {@code name} is the default run: unset, blank or {@code default}. */
    static boolean isDefault(String name) {
        return name == null || name.isBlank() || DEFAULT_SET.equals(name.strip());
    }

    /**
     * The golden set under {@code name}. {@code default} (or unset) returns {@code golden} itself, a set that changes
     * nothing ({@code known}) returns it too. A set that is not in the file, a row that names a house or a case that
     * is not in the golden set, or a key outside the lists in the class comment, throws {@link IllegalArgumentException}.
     */
    Applied apply(GoldenSet golden, String name) {
        if (isDefault(name)) return new Applied(DEFAULT_SET, golden, List.of());
        name = name.strip();
        var def = set(name);
        var houseRows = GoldenSet.map(def.get("houses"));
        var caseRows = GoldenSet.map(def.get("cases"));
        var guards = GoldenSet.strings(def.get("guards"));
        if (houseRows.isEmpty() && caseRows.isEmpty() && guards.isEmpty()) return new Applied(name, golden, List.of());

        var copy = deepCopy(golden.root());
        var houses = GoldenSet.maps(copy.get("fixtureHouses"));
        var byId = new LinkedHashMap<String, Map<String, Object>>();
        for (var h : houses) byId.put(String.valueOf(h.get("id")).toLowerCase(Locale.ROOT), h);

        var generic = new HashSet<>(genericWords());
        var tokens = new HashSet<String>();
        for (var entry : houseRows.entrySet()) {
            var house = byId.get(entry.getKey().toLowerCase(Locale.ROOT));
            if (house == null) {
                throw new IllegalArgumentException("Set '" + name + "' names house " + entry.getKey()
                        + ", which is not a fixture house");
            }
            var row = GoldenSet.map(entry.getValue());
            for (var key : row.keySet()) {
                if (!HOUSE_KEYS.contains(key)) {
                    throw new IllegalArgumentException("Set '" + name + "' may not change '" + key + "' of a house; allowed: "
                            + String.join(", ", new TreeSet<>(HOUSE_KEYS)));
                }
                if (!(row.get(key) instanceof String)) {
                    throw new IllegalArgumentException("Set '" + name + "': '" + key + "' of house " + entry.getKey()
                            + " must be a string");
                }
            }
            var old = new LinkedHashMap<>(house);
            for (var key : List.of("address", "street", "locality", "label")) {
                if (row.containsKey(key)) house.put(key, row.get(key));
            }
            if (row.containsKey("notesAppend")) {
                var notes = house.get("notes") == null ? "" : String.valueOf(house.get("notes"));
                var append = String.valueOf(row.get("notesAppend"));
                house.put("notes", notes.isBlank() ? append : notes + " " + append);
            }
            tokens.addAll(placeWordsLost(old, house, generic));
        }

        var inCases = GoldenSet.maps(copy.get("cases"));
        var caseIds = new HashSet<String>();
        for (var c : inCases) caseIds.add(String.valueOf(c.get("id")));
        for (var id : caseRows.keySet()) {
            if (!caseIds.contains(id)) {
                throw new IllegalArgumentException("Set '" + name + "' rewrites case " + id + ", which is not in the golden set");
            }
        }

        var kept = new ArrayList<Map<String, Object>>();
        var notApplicable = new ArrayList<String>();
        for (var c : inCases) {
            var id = String.valueOf(c.get("id"));
            var type = String.valueOf(c.get("type"));
            if (caseRows.containsKey(id)) {
                rewrite(name, c, type, GoldenSet.map(caseRows.get(id)));
            } else if (caseMentions(c, tokens)) {
                notApplicable.add(id);
                continue;
            }
            addGuards(c, type, guards);
            kept.add(c);
        }
        copy.put("cases", kept);
        return new Applied(name, new GoldenSet(copy), List.copyOf(notApplicable));
    }

    /**
     * The run of a named set over {@code golden}, or null for the default (unset, blank or {@code default}): a default
     * run does not even read the variants file. Throws like {@link #apply} for a set that is not in the file.
     */
    Run select(GoldenSet golden, String name) {
        if (isDefault(name)) return null;
        var applied = apply(golden, name);
        var fingerprint = fingerprint(applied.golden());
        var recorded = recordedFingerprint(applied.set());
        String error = null;
        if (!fingerprint.equals(recorded)) {
            error = "Address set '" + applied.set() + "': its fingerprint is " + fingerprint + " but address-variants.json records "
                    + recorded + "; the file, the golden set or a port has changed without the other (docs/ai/ai-design.md 8.3b)";
        }
        return new Run(applied.set(), String.valueOf(set(applied.set()).get("description")), version(), fingerprint,
                applied.golden(), applied.notApplicable(), golden.cases().size(), error);
    }

    /** The place words the house lost: see the class comment. */
    private static Set<String> placeWordsLost(Map<String, Object> old, Map<String, Object> now, Set<String> generic) {
        var before = new HashSet<String>();
        var after = new HashSet<String>();
        for (var key : HOUSE_TEXT) {
            before.addAll(words(old.get(key)));
            after.addAll(words(now.get(key)));
        }
        var candidates = new HashSet<String>();
        for (var key : List.of("locality", "label", "city")) candidates.addAll(words(old.get(key)));
        var lost = new HashSet<String>();
        for (var w : candidates) {
            if (before.contains(w) && !after.contains(w) && HAS_LETTER.matcher(w).find() && w.length() >= 3
                    && !generic.contains(w)) {
                lost.add(w);
            }
        }
        return lost;
    }

    /** Lower-cased runs of letters and digits. */
    static List<String> words(Object text) {
        var out = new ArrayList<String>();
        if (text == null) return out;
        for (var w : NON_WORD.split(String.valueOf(text).toLowerCase(Locale.ROOT))) {
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }

    /** Whether a string of the case's input or expected (not the free-text {@code note}) holds one of the words. */
    private static boolean caseMentions(Map<String, Object> testCase, Set<String> tokens) {
        if (tokens.isEmpty()) return false;
        return mentions(testCase.get("input"), tokens) || mentions(GoldenSet.map(testCase.get("expected")), tokens);
    }

    private static boolean mentions(Object value, Set<String> tokens) {
        if (value instanceof String s) {
            for (var w : words(s)) if (tokens.contains(w)) return true;
        } else if (value instanceof List<?> list) {
            for (var item : list) if (mentions(item, tokens)) return true;
        } else if (value instanceof Map<?, ?> map) {
            for (var e : map.entrySet()) {
                if ("note".equals(e.getKey())) continue;
                if (mentions(e.getValue(), tokens)) return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void rewrite(String set, Map<String, Object> testCase, String type, Map<String, Object> row) {
        var id = testCase.get("id");
        for (var key : row.keySet()) {
            if (!CASE_KEYS.contains(key)) {
                throw new IllegalArgumentException("Set '" + set + "' may not change '" + key + "' of case " + id);
            }
        }
        var input = GoldenSet.map(row.get("input"));
        var wanted = "extract".equals(type) ? "text" : "question";
        for (var key : input.keySet()) {
            if (!INPUT_KEYS.contains(key) || !wanted.equals(key)) {
                throw new IllegalArgumentException("Set '" + set + "' may change only input." + wanted + " of the " + type
                        + " case " + id + ", not input." + key);
            }
            if (!(input.get(key) instanceof String)) {
                throw new IllegalArgumentException("Set '" + set + "': input." + key + " of " + id + " must be a string");
            }
            ((Map<String, Object>) testCase.get("input")).put(key, input.get(key));
        }
        var expectedRow = GoldenSet.map(row.get("expected"));
        var expected = (Map<String, Object>) testCase.get("expected");
        for (var key : expectedRow.keySet()) {
            if (!EXPECTED_KEYS.contains(key)) {
                throw new IllegalArgumentException("Set '" + set + "' may not change expected." + key + " of case " + id
                        + "; allowed: mustContain, locality");
            }
            expected.put(key, deepCopyValue(expectedRow.get(key)));
        }
        for (var key : GoldenSet.strings(row.get("expectedDelete"))) {
            if (!"locality".equals(key)) {
                throw new IllegalArgumentException("Set '" + set + "' may delete only expected.locality of case " + id);
            }
            expected.remove(key);
        }
    }

    @SuppressWarnings("unchecked")
    private static void addGuards(Map<String, Object> testCase, String type, List<String> guards) {
        if (guards.isEmpty()) return;
        var key = switch (type) {
            case "ask" -> "mustNotContain";
            case "plan" -> "summaryMustNotContain";
            default -> "notesMustNotContain";
        };
        var expected = (Map<String, Object>) testCase.get("expected");
        var list = new ArrayList<String>(GoldenSet.strings(expected.get(key)));
        for (var g : guards) if (!list.contains(g)) list.add(g);
        expected.put(key, new ArrayList<Object>(list));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Fingerprint
    // ---------------------------------------------------------------------------------------------------------

    /**
     * SHA-256 (hex) of the canonical JSON of what a run uses: {@code fixtureHouses}, {@code fixtureVisits} and
     * {@code cases} of the (applied) golden set. Canonical: keys sorted, no white space, integral numbers without a
     * fraction, strings escaped as {@code JSON.stringify} does, UTF-8.
     */
    static String fingerprint(GoldenSet golden) {
        var body = new LinkedHashMap<String, Object>();
        body.put("fixtureHouses", golden.root().get("fixtureHouses"));
        body.put("fixtureVisits", golden.root().get("fixtureVisits"));
        body.put("cases", golden.root().get("cases"));
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson(body).getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (var b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String canonicalJson(Object value) {
        var out = new StringBuilder();
        canonical(value, out);
        return out.toString();
    }

    private static void canonical(Object v, StringBuilder out) {
        if (v == null) {
            out.append("null");
        } else if (v instanceof Boolean b) {
            out.append(b);
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) out.append((long) d);
            else out.append(Double.toString(d));
        } else if (v instanceof Number n) {
            out.append(n);
        } else if (v instanceof CharSequence s) {
            quote(s.toString(), out);
        } else if (v instanceof List<?> list) {
            out.append('[');
            boolean first = true;
            for (var item : list) {
                if (!first) out.append(',');
                first = false;
                canonical(item, out);
            }
            out.append(']');
        } else if (v instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((k, val) -> sorted.put(String.valueOf(k), val));
            out.append('{');
            boolean first = true;
            for (var e : sorted.entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(e.getKey(), out);
                out.append(':');
                canonical(e.getValue(), out);
            }
            out.append('}');
        } else {
            throw new IllegalArgumentException("not JSON: " + v.getClass());
        }
    }

    /** {@code JSON.stringify} for a string: only the quote, the backslash and the controls are escaped. */
    private static void quote(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    // ---------------------------------------------------------------------------------------------------------
    // Copying
    // ---------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Map<String, Object> deepCopy(Map<String, Object> map) {
        return (Map<String, Object>) deepCopyValue(map);
    }

    private static Object deepCopyValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            var out = new LinkedHashMap<String, Object>();
            m.forEach((k, val) -> out.put(String.valueOf(k), deepCopyValue(val)));
            return out;
        }
        if (v instanceof List<?> l) {
            var out = new ArrayList<Object>();
            for (var item : l) out.add(deepCopyValue(item));
            return out;
        }
        return v;
    }
}
