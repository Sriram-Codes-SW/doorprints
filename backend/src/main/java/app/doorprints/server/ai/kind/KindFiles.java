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

package app.doorprints.server.ai.kind;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The server's one reader of kind files (kinds PR 28, docs/03 ADR-36 §18.22). A kind file is the only thing a built-in kind
 * needs on the server: {@link #parse} reads one, checks it against the limits of {@code docs/schemas/kinds/kind.schema.json}
 * for everything the server acts on (the id, the core ids, the typed extras, the search ids, the {@code ai} slots and
 * words, the {@code capabilities}) and refuses it with a {@link KindFileException} naming the path of the first fault. It is
 * strict: an unknown key, a key of a prototype chain ({@code __proto__}, {@code constructor}, {@code prototype}), a
 * duplicate key, a wrong type, a value out of range, a document or a string over the size caps, and any map key
 * ({@link #FORBIDDEN_MAP_KEYS}, owner constraint: India's boundaries apply to every kind, ADR-22) anywhere in the file are
 * all refused. The rest of the file (labels, sections, statuses, the checklist, the questions) belongs to the apps and
 * is checked for shape only.
 *
 * <p>{@link #builtIn} reads the files packaged with the server ({@code kinds/<id>.json} on the class path, a byte-identical
 * copy of {@code docs/schemas/kinds}, held by {@code KindFileResourcesTest}). Nothing is read from the person's data.
 */
public final class KindFiles {

    /** The size of a kind file, in bytes: ten times the largest built-in one. */
    static final int MAX_BYTES = 262_144;
    private static final int MAX_STRING = 20_000;
    private static final int MAX_DEPTH = 16;

    /** The keys that configure a map; none is accepted anywhere in a kind file (the schema names none of them either). */
    public static final Set<String> FORBIDDEN_MAP_KEYS = Set.of("style", "tileUrl", "tiles", "sprite", "glyphs",
            "boundary", "boundaries", "geojson", "attribution", "maxzoom", "bounds", "center", "mapStyleUrl", "geo");
    /** The keys of a prototype chain, never an own key of a file. */
    static final Set<String> PROTOTYPE_KEYS = Set.of("__proto__", "constructor", "prototype");

    /** The core fields of every candidate. */
    static final Set<String> CORE_IDS = Set.of("label", "status", "rating", "money", "address", "street", "locality",
            "contactName", "contactPhone", "brokerId", "listingUrl", "notes", "location", "locationSource");
    /** The lines and sections of an AI text that the server writes by their id, for any kind whose slot names them. */
    static final Set<String> SECTION_IDS = Set.of("areaNotes", "distances", "visits", "rooms", "questions", "viewings",
            "moveIn", "checklist", "amenities");
    /**
     * The extras the server's AI knows by id (the house's fields, whose lines have their own words and rules): the type a
     * file must give each when it defines one, and then no {@code ai.words} are needed. Any other extra an {@code ai} slot
     * names is read by its type and its words alone.
     */
    static final Map<String, String> KNOWN_EXTRAS = Map.ofEntries(Map.entry("bedrooms", "number"),
            Map.entry("areaSqft", "number"), Map.entry("floor", "number"), Map.entry("deposit", "money"),
            Map.entry("depositMonths", "number"), Map.entry("maintenance", "money"),
            Map.entry("maintenanceIncluded", "bool"), Map.entry("brokerage", "money"),
            Map.entry("brokerageMonths", "number"), Map.entry("lockInMonths", "number"),
            Map.entry("noticeMonths", "number"), Map.entry("availableFrom", "date"), Map.entry("agreedPrice", "money"),
            Map.entry("myOffer", "money"));

    private static final Set<String> ROOT_KEYS = Set.of("format", "id", "version", "note", "labels", "words", "statuses",
            "sections", "core", "extras", "checklist", "questions", "search", "ai", "capabilities");
    private static final List<String> ROOT_REQUIRED = List.of("format", "id", "version", "labels", "statuses",
            "sections", "core", "extras", "checklist", "questions", "search", "ai");
    private static final Set<String> SECTION_NAMES = Set.of("details", "cost", "rooms", "questions", "viewings", "moveIn",
            "areaNotes", "distances", "location", "walks", "checklist", "visits", "photos");
    private static final Set<String> CADENCES = Set.of("once", "day", "week", "month", "quarter", "year");
    private static final Set<String> UNITS = Set.of("count", "level", "sqft", "sqm", "cm", "m", "km", "minute", "day",
            "month", "year", "percent");
    private static final Set<String> TYPES = Set.of("number", "money", "text", "enum", "date", "bool");
    private static final Set<String> BETTER = Set.of("lower", "higher");
    private static final Set<String> ICONS = Set.of("home", "school", "building", "apartment", "shop", "hospital",
            "office", "park", "car", "bike", "bus", "train", "cart", "hotel", "restaurant", "gym", "book", "heart",
            "star", "flag", "key", "pin", "briefcase", "paw");
    private static final Set<String> COLOURS = Set.of("terracotta", "indigo", "teal", "forest", "amber", "rose", "slate",
            "plum");

    private static final Pattern KIND_ID = Pattern.compile("^[a-z][a-z0-9_]{1,31}$");
    private static final Pattern FIELD_ID = Pattern.compile("^[a-z][A-Za-z0-9]{0,39}$");
    private static final Pattern CRITERION = Pattern.compile("^[a-z][A-Za-z0-9_]{0,39}$");
    private static final Pattern OPTION_ID = Pattern.compile("^[A-Z][A-Z0-9_]{0,31}$");
    private static final Pattern WORD = Pattern.compile("^[A-Za-z][A-Za-z0-9 .,'()/-]{0,59}$");
    private static final Pattern RECORDS = Pattern.compile("^[a-z]{2,24}$");

    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_DEPTH)
                            .maxStringLength(MAX_STRING).maxNameLength(64).maxDocumentLength(MAX_BYTES).build())
                    .build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private KindFiles() {
    }

    /** A kind file that the server refuses. */
    public static final class KindFileException extends IllegalArgumentException {
        KindFileException(String message) {
            super(message);
        }
    }

    private static KindFileException fault(String path, String message) {
        return new KindFileException(path + ": " + message);
    }

    // ---- The files packaged with the server ------------------------------------------------------------------

    /** The kind files on the class path, by id, in id order; read once. */
    public static Map<String, KindFile> builtIn() {
        return BuiltIn.FILES;
    }

    private static final class BuiltIn {
        static final Map<String, KindFile> FILES = load();

        private static Map<String, KindFile> load() {
            var out = new LinkedHashMap<String, KindFile>();
            try {
                var found = new ArrayList<Resource>(List.of(new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:kinds/*.json")));
                found.sort(java.util.Comparator.comparing(r -> String.valueOf(r.getFilename())));
                for (var r : found) {
                    var name = String.valueOf(r.getFilename());
                    // kinds/<id>.json is a kind file; kinds/<id>.prompts.json is a prompt pack (KindPrompts).
                    if (!name.endsWith(".json") || !KIND_ID.matcher(name.substring(0, name.length() - 5)).matches()) {
                        continue;
                    }
                    byte[] bytes;
                    try (var in = r.getInputStream()) {
                        bytes = in.readNBytes(MAX_BYTES + 1);
                    }
                    var file = parse(new String(bytes, StandardCharsets.UTF_8));
                    if (!name.equals(file.id() + ".json")) {
                        throw fault(name, "the file names kind " + file.id());
                    }
                    if (out.put(file.id(), file) != null) throw fault(name, "a second file of kind " + file.id());
                }
            } catch (IOException e) {
                throw new IllegalStateException("the kind files cannot be read", e);
            }
            return Collections.unmodifiableMap(out);
        }
    }

    /**
     * The cadences the money of a candidate of {@code kind} may have, from the kind file's core {@code money}; null when the
     * kind has no file or shows no money (then any cadence is kept, as the apps keep it).
     */
    public static java.util.Set<String> moneyCadences(String kind) {
        var f = kind == null ? null : builtIn().get(kind);
        return f == null || f.moneyCadences().isEmpty() ? null : java.util.Set.copyOf(f.moneyCadences());
    }

    // ---- Parsing ------------------------------------------------------------------------------------------------

    /** Reads and validates one kind file. */
    public static KindFile parse(String json) {
        if (json == null) throw fault("$", "no file");
        if (json.length() > MAX_BYTES) throw fault("$", "over " + MAX_BYTES + " bytes");
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (RuntimeException e) {
            throw fault("$", "not a single JSON document without duplicate keys within the size limits ("
                    + e.getClass().getSimpleName() + ")");
        }
        return parse(root);
    }

    /** Validates an already parsed tree (the tests and {@link #parse(String)}). */
    static KindFile parse(JsonNode root) {
        scanKeys(root, "$", 0);
        object(root, "$");
        only(root, "$", ROOT_KEYS);
        for (var k : ROOT_REQUIRED) {
            if (!root.has(k)) throw fault("$." + k, "required");
        }
        if (!"doorprints-kind/1".equals(text(root, "format", "$.format"))) throw fault("$.format", "not doorprints-kind/1");
        var id = matching(root, "id", "$.id", KIND_ID);
        var version = (int) integer(root, "version", "$.version", 1, 1000);
        if (root.has("note")) text(root, "note", "$.note");
        shape(root, "labels", false, 3);
        if (root.has("words")) shape(root, "words", false, 200);
        shape(root, "statuses", true, 20);
        shape(root, "sections", true, 20);
        shape(root, "checklist", true, 40);
        shape(root, "questions", false, 4);

        var moneyCadences = new ArrayList<String>();
        var core = core(root.get("core"), moneyCadences);
        if (core.size() < 2 || !core.contains("label") || !core.contains("location")) {
            throw fault("$.core", "must hold label and location");
        }
        var extras = extras(root.get("extras"));
        var search = idList(root.get("search"), "$.search", null);
        var ai = ai(root.get("ai"), core, extras);
        var capabilities = root.has("capabilities") ? capabilities(root.get("capabilities")) : KindFile.Capabilities.DEFAULTS;
        return new KindFile(id, version, List.copyOf(core), List.copyOf(moneyCadences), List.copyOf(extras), List.copyOf(search), ai, capabilities);
    }

    /** Every object key of the document, at every depth: no prototype key, no map key. */
    private static void scanKeys(JsonNode n, String path, int depth) {
        if (depth > MAX_DEPTH) throw fault(path, "nested too deep");
        if (n.isObject()) {
            for (var e : n.properties()) {
                var key = e.getKey();
                if (PROTOTYPE_KEYS.contains(key)) throw fault(path + "." + key, "a prototype key is not allowed");
                if (FORBIDDEN_MAP_KEYS.contains(key)) {
                    throw fault(path + "." + key, "a kind file carries no map configuration (India's boundaries apply to every kind)");
                }
                scanKeys(e.getValue(), path + "." + key, depth + 1);
            }
        } else if (n.isArray()) {
            int i = 0;
            for (var item : n) scanKeys(item, path + "[" + i++ + "]", depth + 1);
        }
    }

    // ---- The core fields -------------------------------------------------------------------------------------------

    private static final Set<String> CORE_KEYS = Set.of("id", "label", "section", "order", "visible", "forCadences", "was",
            "cadences");

    /** The ids of the core fields the kind shows; each entry is checked for shape (its labels are the apps'). */
    private static List<String> core(JsonNode n, List<String> moneyCadences) {
        array(n, "$.core", 40);
        var out = new ArrayList<String>();
        int i = 0;
        for (var c : n) {
            var p = "$.core[" + i++ + "]";
            object(c, p);
            only(c, p, CORE_KEYS);
            var id = oneOf(c, "id", p + ".id", CORE_IDS);
            if (out.contains(id)) throw fault(p + ".id", "repeated");
            if (!c.has("label")) throw fault(p + ".label", "required");
            object(c.get("label"), p + ".label");
            if (c.has("section")) oneOf(c, "section", p + ".section", SECTION_NAMES);
            if (c.has("order")) integer(c, "order", p + ".order", 0, 10_000);
            if (c.has("visible")) bool(c, "visible", p + ".visible");
            if (c.has("forCadences")) cadenceList(c.get("forCadences"), p + ".forCadences");
            if (c.has("was")) text(c, "was", p + ".was");
            if (id.equals("money") != c.has("cadences")) throw fault(p, "cadences belong to money, and money has them");
            if (c.has("cadences")) {
                var list = c.get("cadences");
                array(list, p + ".cadences", 6);
                if (list.isEmpty()) throw fault(p + ".cadences", "at least one");
                int j = 0;
                for (var item : list) {
                    var cp = p + ".cadences[" + j++ + "]";
                    object(item, cp);
                    only(item, cp, Set.of("id", "label", "was"));
                    var cadence = oneOf(item, "id", cp + ".id", CADENCES);
                    if (moneyCadences.contains(cadence)) throw fault(cp + ".id", "repeated");
                    if (!item.has("label")) throw fault(cp + ".label", "required");
                    object(item.get("label"), cp + ".label");
                    if (item.has("was")) text(item, "was", cp + ".was");
                    moneyCadences.add(cadence);
                }
            }
            out.add(id);
        }
        return out;
    }

    // ---- The extras ---------------------------------------------------------------------------------------------

    private static final Set<String> EXTRA_BASE = Set.of("id", "type", "label", "section", "order", "visible",
            "forCadences", "was");

    private static List<KindFile.Extra> extras(JsonNode n) {
        array(n, "$.extras", 60);
        var out = new ArrayList<KindFile.Extra>();
        var seen = new LinkedHashSet<String>();
        int i = 0;
        for (var e : n) {
            var p = "$.extras[" + i++ + "]";
            object(e, p);
            var id = matching(e, "id", p + ".id", FIELD_ID);
            if (!seen.add(id)) throw fault(p + ".id", "repeated");
            var type = oneOf(e, "type", p + ".type", TYPES);
            var allowed = new LinkedHashSet<>(EXTRA_BASE);
            switch (type) {
                case "number" -> allowed.addAll(Set.of("integer", "min", "max", "unit", "better"));
                case "money" -> allowed.addAll(Set.of("max", "better", "cadences", "cadenceFrom"));
                case "text" -> allowed.add("maxLength");
                case "enum" -> allowed.add("options");
                default -> {
                }
            }
            only(e, p, allowed);
            if (!e.has("label")) throw fault(p + ".label", "required");
            object(e.get("label"), p + ".label");
            if (e.has("section")) oneOf(e, "section", p + ".section", SECTION_NAMES);
            if (e.has("order")) integer(e, "order", p + ".order", 0, 10_000);
            if (e.has("visible")) bool(e, "visible", p + ".visible");
            if (e.has("forCadences")) cadenceList(e.get("forCadences"), p + ".forCadences");
            if (e.has("was")) text(e, "was", p + ".was");
            if (e.has("better")) oneOf(e, "better", p + ".better", BETTER);
            Boolean integer = null;
            Long min = null, max = null;
            Integer maxLength = null;
            String unit = null;
            var cadences = List.<String>of();
            boolean cadenceFrom = false;
            var options = List.<String>of();
            switch (type) {
                case "number" -> {
                    if (!e.has("integer")) throw fault(p + ".integer", "required");
                    if (!e.has("unit")) throw fault(p + ".unit", "required");
                    integer = bool(e, "integer", p + ".integer");
                    unit = oneOf(e, "unit", p + ".unit", UNITS);
                    min = e.has("min") ? number(e, "min", p + ".min") : null;
                    max = e.has("max") ? number(e, "max", p + ".max") : null;
                }
                case "money" -> {
                    if (!e.has("max")) throw fault(p + ".max", "required");
                    max = integer(e, "max", p + ".max", 1, 1_000_000_000_000L);
                    boolean hasCadences = e.has("cadences"), hasFrom = e.has("cadenceFrom");
                    if (hasCadences == hasFrom) throw fault(p, "exactly one of cadences and cadenceFrom");
                    if (hasCadences) {
                        cadences = cadenceList(e.get("cadences"), p + ".cadences");
                    } else {
                        if (!"money".equals(text(e, "cadenceFrom", p + ".cadenceFrom"))) {
                            throw fault(p + ".cadenceFrom", "only money");
                        }
                        cadenceFrom = true;
                    }
                }
                case "text" -> {
                    if (!e.has("maxLength")) throw fault(p + ".maxLength", "required");
                    maxLength = (int) integer(e, "maxLength", p + ".maxLength", 1, 2000);
                }
                case "enum" -> {
                    if (!e.has("options")) throw fault(p + ".options", "required");
                    array(e.get("options"), p + ".options", 30);
                    if (e.get("options").size() < 2) throw fault(p + ".options", "at least two");
                    var ids = new ArrayList<String>();
                    int j = 0;
                    for (var o : e.get("options")) {
                        var op = p + ".options[" + j++ + "]";
                        object(o, op);
                        only(o, op, Set.of("id", "label"));
                        var oid = matching(o, "id", op + ".id", OPTION_ID);
                        if (ids.contains(oid)) throw fault(op + ".id", "repeated");
                        if (!o.has("label")) throw fault(op + ".label", "required");
                        object(o.get("label"), op + ".label");
                        ids.add(oid);
                    }
                    options = List.copyOf(ids);
                }
                default -> {
                }
            }
            var known = KNOWN_EXTRAS.get(id);
            if (known != null && !known.equals(type)) {
                throw fault(p + ".type", id + " is a " + known + " here (the server's AI reads it by id)");
            }
            out.add(new KindFile.Extra(id, type, integer, min, max, maxLength, unit, List.copyOf(cadences), cadenceFrom,
                    options));
        }
        return out;
    }

    private static List<String> cadenceList(JsonNode n, String path) {
        array(n, path, 6);
        if (n.isEmpty()) throw fault(path, "at least one");
        var out = new ArrayList<String>();
        int i = 0;
        for (var c : n) {
            if (!c.isString() || !CADENCES.contains(c.asString())) throw fault(path + "[" + i + "]", "not a cadence");
            if (out.contains(c.asString())) throw fault(path + "[" + i + "]", "repeated");
            out.add(c.asString());
            i++;
        }
        return out;
    }

    // ---- The AI contract ----------------------------------------------------------------------------------------

    private static KindFile.Ai ai(JsonNode n, List<String> core, List<KindFile.Extra> extras) {
        object(n, "$.ai");
        only(n, "$.ai", Set.of("extract", "ask", "plan", "never", "words"));
        var allowed = new LinkedHashSet<String>(core);
        extras.forEach(e -> allowed.add(e.id()));
        allowed.addAll(SECTION_IDS);
        var slots = new LinkedHashMap<String, List<String>>();
        for (var k : List.of("extract", "ask", "plan", "never")) {
            if (!n.has(k)) throw fault("$.ai." + k, "required");
            slots.put(k, idList(n.get(k), "$.ai." + k, allowed));
        }
        for (var id : slots.get("never")) {
            if (slots.get("ask").contains(id) || slots.get("plan").contains(id)) {
                throw fault("$.ai.never", id + " is named by ask or plan, and a provider never sees it");
            }
        }
        var byId = new LinkedHashMap<String, KindFile.Extra>();
        extras.forEach(e -> byId.put(e.id(), e));
        // Every extra a slot names is readable by the server: by id (the house's), or by its type and its words.
        var named = new LinkedHashSet<String>();
        for (var k : List.of("extract", "ask", "plan")) {
            for (var id : slots.get(k)) {
                var e = byId.get(id);
                if (e == null || KNOWN_EXTRAS.containsKey(id)) continue;
                named.add(id);
                var p = "$.ai." + k + " (" + id + ")";
                switch (e.type()) {
                    case "enum", "text", "bool" -> {
                    }
                    case "money" -> {
                        if (e.cadences().size() != 1) throw fault(p, "a money the AI reads has exactly one cadence");
                    }
                    case "number" -> {
                        if (k.equals("extract")) throw fault(p, "the listing extract fills no number");
                        if (!Boolean.TRUE.equals(e.integer())) throw fault(p, "a number the AI reads is a whole number");
                    }
                    case "date" -> {
                        if (k.equals("extract")) throw fault(p, "the listing extract fills no date");
                    }
                    default -> throw fault(p, "the AI reads no " + e.type());
                }
            }
        }
        KindFile.Words words = null;
        if (n.has("words")) words = words(n.get("words"), named, byId);
        else if (!named.isEmpty()) throw fault("$.ai.words", "required: an ai slot names a typed extra the server reads by its words");
        return new KindFile.Ai(List.copyOf(slots.get("extract")), List.copyOf(slots.get("ask")),
                List.copyOf(slots.get("plan")), List.copyOf(slots.get("never")), words);
    }

    private static KindFile.Words words(JsonNode n, Set<String> named, Map<String, KindFile.Extra> byId) {
        var p = "$.ai.words";
        object(n, p);
        only(n, p, Set.of("noun", "records", "extras"));
        var noun = matching(n, "noun", p + ".noun", WORD);
        var records = matching(n, "records", p + ".records", RECORDS);
        var extras = new LinkedHashMap<String, KindFile.ExtraWords>();
        if (n.has("extras")) {
            var ex = n.get("extras");
            object(ex, p + ".extras");
            if (ex.size() > 60) throw fault(p + ".extras", "over 60");
            for (var e : ex.properties()) {
                var ep = p + ".extras." + e.getKey();
                if (!named.contains(e.getKey())) throw fault(ep, "not an extra an ai slot names");
                var w = e.getValue();
                object(w, ep);
                only(w, ep, Set.of("words", "options"));
                var words = matching(w, "words", ep + ".words", WORD);
                var options = new LinkedHashMap<String, String>();
                if (w.has("options")) {
                    var o = w.get("options");
                    object(o, ep + ".options");
                    if (o.size() > 30) throw fault(ep + ".options", "over 30");
                    for (var op : o.properties()) {
                        if (!OPTION_ID.matcher(op.getKey()).matches()) throw fault(ep + ".options." + op.getKey(), "not an option id");
                        options.put(op.getKey(), matching(o, op.getKey(), ep + ".options." + op.getKey(), WORD));
                    }
                }
                extras.put(e.getKey(), new KindFile.ExtraWords(words, Collections.unmodifiableMap(options)));
            }
        }
        for (var id : named) {
            var w = extras.get(id);
            if (w == null) throw fault(p + ".extras." + id, "required: an ai slot names this extra");
            var e = byId.get(id);
            if (e.type().equals("enum")) {
                if (!new ArrayList<>(w.options().keySet()).equals(e.options())) {
                    throw fault(p + ".extras." + id + ".options", "must give the words of every option, in the file's order");
                }
            } else if (!w.options().isEmpty()) {
                throw fault(p + ".extras." + id + ".options", "only an enum has options");
            }
        }
        return new KindFile.Words(noun, records, Collections.unmodifiableMap(extras));
    }

    // ---- Capabilities (docs/03 §18.19) --------------------------------------------------------------------------

    /** Reads a {@code capabilities} object strictly, as the schema and the other stacks' readers do. */
    public static KindFile.Capabilities capabilities(JsonNode n) {
        scanKeys(n, "$.capabilities", 0);
        var p = "$.capabilities";
        object(n, p);
        only(n, p, Set.of("map", "hunt", "rooms", "moveIn", "contacts", "viewings", "compare", "paths", "identity", "baseline",
                "filters"));
        var map = n.has("map") ? bool(n, "map", p + ".map") : true;
        var rooms = n.has("rooms") && bool(n, "rooms", p + ".rooms");
        var moveIn = n.has("moveIn") && bool(n, "moveIn", p + ".moveIn");
        var paths = n.has("paths") ? bool(n, "paths", p + ".paths") : true;
        var baseline = n.has("baseline") && bool(n, "baseline", p + ".baseline");
        var filters = new ArrayList<String>();
        if (n.has("filters")) {
            var list = n.get("filters");
            array(list, p + ".filters", 6);
            int fi = 0;
            for (var item : list) {
                var ip = p + ".filters[" + fi++ + "]";
                if (!item.isString() || !CRITERION.matcher(item.asString()).matches()) throw fault(ip, "not an extra id");
                if (filters.contains(item.asString())) throw fault(ip, "repeated");
                filters.add(item.asString());
            }
        }
        KindFile.Hunt hunt = null;
        if (n.has("hunt")) {
            var h = n.get("hunt");
            object(h, p + ".hunt");
            only(h, p + ".hunt", Set.of("alertRadiusM", "stayCreates", "streetMemory", "areas"));
            require(h, p + ".hunt", "alertRadiusM", "stayCreates", "streetMemory", "areas");
            hunt = new KindFile.Hunt((int) integer(h, "alertRadiusM", p + ".hunt.alertRadiusM", 30, 300),
                    bool(h, "stayCreates", p + ".hunt.stayCreates"), bool(h, "streetMemory", p + ".hunt.streetMemory"),
                    bool(h, "areas", p + ".hunt.areas"));
        }
        KindFile.Contacts contacts = null;
        if (n.has("contacts")) {
            var c = n.get("contacts");
            object(c, p + ".contacts");
            only(c, p + ".contacts", Set.of("role", "autoCreateFromPhone"));
            require(c, p + ".contacts", "role", "autoCreateFromPhone");
            contacts = new KindFile.Contacts(oneOf(c, "role", p + ".contacts.role", Set.of("broker", "office", "agent")),
                    bool(c, "autoCreateFromPhone", p + ".contacts.autoCreateFromPhone"));
        }
        KindFile.Viewings viewings = null;
        if (n.has("viewings")) {
            var v = n.get("viewings");
            object(v, p + ".viewings");
            only(v, p + ".viewings", Set.of("questionsSeed"));
            require(v, p + ".viewings", "questionsSeed");
            viewings = new KindFile.Viewings(oneOf(v, "questionsSeed", p + ".viewings.questionsSeed", Set.of("house", "school")));
        }
        KindFile.Compare compare = null;
        if (n.has("compare")) {
            var c = n.get("compare");
            object(c, p + ".compare");
            only(c, p + ".compare", Set.of("criteria"));
            require(c, p + ".compare", "criteria");
            var list = c.get("criteria");
            array(list, p + ".compare.criteria", 10);
            if (list.isEmpty()) throw fault(p + ".compare.criteria", "at least one");
            var criteria = new ArrayList<String>();
            int i = 0;
            for (var item : list) {
                var ip = p + ".compare.criteria[" + i++ + "]";
                if (!item.isString() || !CRITERION.matcher(item.asString()).matches()) throw fault(ip, "not a criterion key");
                if (criteria.contains(item.asString())) throw fault(ip, "repeated");
                criteria.add(item.asString());
            }
            compare = new KindFile.Compare(List.copyOf(criteria));
        }
        KindFile.Identity identity = null;
        if (n.has("identity")) {
            var d = n.get("identity");
            object(d, p + ".identity");
            only(d, p + ".identity", Set.of("icon", "colour"));
            require(d, p + ".identity", "icon", "colour");
            identity = new KindFile.Identity(oneOf(d, "icon", p + ".identity.icon", ICONS),
                    oneOf(d, "colour", p + ".identity.colour", COLOURS));
        }
        return new KindFile.Capabilities(map, hunt, rooms, moveIn, contacts, viewings, compare, paths, identity, baseline,
                List.copyOf(filters));
    }

    // ---- Small strict readers -----------------------------------------------------------------------------------

    private static void object(JsonNode n, String path) {
        if (n == null || !n.isObject()) throw fault(path, "not an object");
    }

    private static void array(JsonNode n, String path, int max) {
        if (n == null || !n.isArray()) throw fault(path, "not an array");
        if (n.size() > max) throw fault(path, "more than " + max + " items");
    }

    private static void only(JsonNode o, String path, Set<String> keys) {
        for (var e : o.properties()) {
            if (!keys.contains(e.getKey())) throw fault(path + "." + e.getKey(), "unknown key");
        }
    }

    private static void require(JsonNode o, String path, String... keys) {
        for (var k : keys) if (!o.has(k)) throw fault(path + "." + k, "required");
    }

    private static String text(JsonNode o, String key, String path) {
        var v = o.get(key);
        if (v == null || !v.isString()) throw fault(path, "not a string");
        return v.asString();
    }

    private static String matching(JsonNode o, String key, String path, Pattern p) {
        var s = text(o, key, path);
        if (!p.matcher(s).matches()) throw fault(path, "does not match " + p.pattern());
        return s;
    }

    private static String oneOf(JsonNode o, String key, String path, Set<String> allowed) {
        var s = text(o, key, path);
        if (!allowed.contains(s)) throw fault(path, "not one of the allowed values");
        return s;
    }

    private static boolean bool(JsonNode o, String key, String path) {
        var v = o.get(key);
        if (v == null || !v.isBoolean()) throw fault(path, "not a boolean");
        return v.asBoolean();
    }

    private static long integer(JsonNode o, String key, String path, long min, long max) {
        var v = o.get(key);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong()) throw fault(path, "not an integer");
        long l = v.asLong();
        if (l < min || l > max) throw fault(path, "outside " + min + ".." + max);
        return l;
    }

    /** A JSON number with no fraction that fits well inside a long (a bound of a number extra). */
    private static long number(JsonNode o, String key, String path) {
        var v = o.get(key);
        if (v == null || !v.isNumber()) throw fault(path, "not a number");
        double d = v.asDouble();
        if (!Double.isFinite(d) || Math.abs(d) > 1e15 || d != Math.rint(d)) throw fault(path, "not a whole number within 1e15");
        return (long) d;
    }

    private static List<String> idList(JsonNode n, String path, Set<String> allowed) {
        array(n, path, 80);
        var out = new ArrayList<String>();
        int i = 0;
        for (var item : n) {
            var ip = path + "[" + i++ + "]";
            if (!item.isString() || !FIELD_ID.matcher(item.asString()).matches()) throw fault(ip, "not a field id");
            if (out.contains(item.asString())) throw fault(ip, "repeated");
            if (allowed != null && !allowed.contains(item.asString())) throw fault(ip, item.asString() + " is not a field of this kind");
            out.add(item.asString());
        }
        return out;
    }

    /** A part of the file the apps own: only its type and its size are checked. */
    private static void shape(JsonNode root, String key, boolean array, int max) {
        var v = root.get(key);
        var p = "$." + key;
        if (array) array(v, p, max);
        else {
            object(v, p);
            if (v.size() > max) throw fault(p, "more than " + max + " keys");
        }
    }
}
