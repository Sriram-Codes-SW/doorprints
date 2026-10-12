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

package app.doorprints.server.backup;

import app.doorprints.server.common.BadRequestException;
import app.doorprints.server.house.CandidateExtras;
import app.doorprints.server.house.House;
import app.doorprints.server.house.Money;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The backup family after the format reset (docs/03 ADR-36 §18.4 doors 1 and 2; S4b-BL-206, kinds step 3;
 * docs/schemas/README.md §1.1): {@code doorprints-backup/1} with {@code search} and {@code candidates}, each with
 * {@code kind}, {@code money} and {@code extras}, and {@code candidateId} on visits, photos and viewings. The twin of the
 * website's {@code backup-family.ts} and Kotlin's {@code BackupWire}, rule for rule.
 *
 * <p>{@link BackupData} and {@link BackupHouse} are still the rows of the family before, so this is the one place the two
 * meet, at the two endpoints: {@code GET /api/export} turns what {@link BackupService#export()} builds into the new
 * family ({@link #currentOf}); {@code POST /api/import} reads a file of the new family (it has {@code candidates}) back
 * as the family before after the new family's own checks ({@link #legacyOf}), and <b>reads a file of the family before
 * as it always did</b>, so an older Doorprints' backup still imports. The two {@code /1} are told apart by shape.
 */
public final class BackupWire {

    /** The format id of the new family: one so far, for a full copy and an update file alike. */
    public static final String CURRENT_FORMAT = "doorprints-backup/1";
    /** What a newer number of the new family reads as: newer than any this reader knows ({@link BackupFormat#isNewer}). */
    public static final String NEWER_FORMAT = "doorprints-backup/999";

    private static final String FAMILY = "doorprints-backup/";
    private static final Pattern KIND = Pattern.compile(House.KIND_PATTERN);
    private static final Pattern EXTRA_ID = Pattern.compile(CandidateExtras.ID_PATTERN);
    private static final List<String> VALUES = List.of("bedrooms", "areaSqft", "floor");
    private static final List<String> COST = List.of("deposit", "depositMonths", "maintenance", "maintenanceIncluded",
            "brokerage", "brokerageMonths", "lockInMonths", "noticeMonths", "availableFrom", "myOffer", "agreedPrice");
    /** The house kind's money extras with the cadence each must have; absent: as often as the candidate's money. */
    private static final Map<String, String> FIXED = Map.of("deposit", "once", "maintenance", "month", "brokerage", "once");
    private static final Set<String> MONEY = Set.of("deposit", "maintenance", "brokerage", "myOffer", "agreedPrice");
    private static final Set<String> MOVED = Set.of("price", "priceType", "bedrooms", "areaSqft", "floor", "cost", "extrasOther", "kind");
    private static final Set<String> ROWS_WITH_HOUSE = Set.of("visits", "photos", "viewings");
    /** A candidate's keys in the contract's order ({@code backup-sample.json}); others follow as they came. */
    private static final List<String> CANDIDATE_ORDER = List.of("id", "kind", "label", "address", "street", "locality",
            "lat", "lon", "status", "money", "rating", "contactName", "contactPhone", "listingUrl", "notes",
            "locationSource", "extras", "rooms", "answers", "moveIn", "brokerId", "checklist", "createdAt", "updatedAt");
    /** A house's keys in the family before, in its order ({@code backup-sample-houses.json}). */
    private static final List<String> HOUSE_ORDER = List.of("id", "kind", "label", "address", "street", "locality", "lat",
            "lon", "status", "price", "priceType", "bedrooms", "rating", "contactName", "contactPhone", "listingUrl",
            "notes", "areaSqft", "locationSource", "cost", "rooms", "answers", "moveIn", "floor", "brokerId", "checklist",
            "createdAt", "updatedAt");

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private BackupWire() {
    }

    // ---- Writing ----

    /** A {@code data.json} of the family before as the new family writes it; anything but an object is kept as it is. */
    public static JsonNode currentOf(JsonNode legacy) {
        if (!(legacy instanceof ObjectNode root)) return legacy;
        var out = NODES.objectNode();
        for (var e : root.properties()) {
            var key = e.getKey();
            var value = e.getValue();
            switch (key) {
                case "format" -> out.put("format", CURRENT_FORMAT);
                case "exportedAt" -> {
                    out.set("exportedAt", value);
                    out.set("search", NODES.objectNode().put("kind", House.HOUSE_KIND));
                }
                case "houses" -> out.set("candidates", map(value, BackupWire::candidateOf));
                case "deleted" -> out.set(key, map(value, d -> "house".equals(text(d, "kind")) ? copy(d).put("kind", "candidate") : d));
                default -> out.set(key, ROWS_WITH_HOUSE.contains(key) ? map(value, r -> rename(r, "houseId", "candidateId")) : value);
            }
        }
        return out;
    }

    private static ObjectNode candidateOf(ObjectNode h) {
        var money = moneyOf(h.get("price"), text(h, "priceType"));
        var row = NODES.objectNode();
        row.set("kind", h.has("kind") && h.get("kind").isString() ? h.get("kind") : NODES.stringNode(House.HOUSE_KIND));
        if (money != null) row.set("money", money);
        var extras = extrasOf(h, money == null || !money.has("cadence") ? "month" : money.get("cadence").asString());
        if (extras != null) row.set("extras", extras);
        for (var e : h.properties()) if (!MOVED.contains(e.getKey())) row.set(e.getKey(), e.getValue());
        return ordered(row, CANDIDATE_ORDER);
    }

    /** {@link Money#of}: absent when neither is known; a price without a type keeps no cadence (owner, 2026-10-12). */
    private static ObjectNode moneyOf(JsonNode price, String priceType) {
        var cadence = Money.cadenceOf(priceType);
        var amount = price != null && price.isNumber() ? price : null;
        if (amount == null && cadence == null) return null;
        var out = NODES.objectNode();
        if (amount != null) out.set("amount", amount);
        if (cadence != null) out.put("cadence", cadence);
        return out;
    }

    private static ObjectNode extrasOf(ObjectNode h, String ownCadence) {
        var cost = h.get("cost") instanceof ObjectNode c ? c : NODES.objectNode();
        var out = NODES.objectNode();
        var ids = new ArrayList<>(VALUES);
        ids.addAll(COST);
        for (var id : ids) {
            var value = VALUES.contains(id) ? h.get(id) : cost.get(id);
            if (value == null || value.isNull()) continue;
            if (MONEY.contains(id)) {
                out.set(id, NODES.objectNode().<ObjectNode>set("amount", value).put("cadence", FIXED.getOrDefault(id, ownCadence)));
            } else {
                out.set(id, value);
            }
        }
        return out.isEmpty() ? null : out;
    }

    // ---- Reading ----

    /** True for a {@code data.json} of the new family: it lists {@code candidates} (the family before listed {@code houses}). */
    public static boolean isCurrentFamily(JsonNode root) {
        return root instanceof ObjectNode o && o.has("candidates");
    }

    /**
     * A {@code data.json} of either family as the rows of the family before ({@link BackupData}): a file of the new
     * family after its own checks (a kind id; money of whole rupees with a cadence of the vocabulary, a house's
     * {@code month} or {@code once}; a money extra's amount and cadence; an unknown extra as a field id with a value of an
     * extras type, at most 30), a file of the family before as it came.
     *
     * A well-formed file with a cadence this reader does not support (one it does not know, or one a house's money or
     * a fixed money extra may not have) reads as a newer file ({@link #NEWER_FORMAT}): "update the app", not a broken file.
     *
     * @throws BadRequestException (a 400) for a file of the new family that breaks those rules
     */
    public static JsonNode legacyOf(JsonNode body) {
        if (!isCurrentFamily(body)) return body;
        try {
            return legacyRows((ObjectNode) body);
        } catch (Newer e) {
            var out = NODES.objectNode().put("format", NEWER_FORMAT);
            out.set("exportedAt", body.get("exportedAt"));
            out.set("houses", NODES.arrayNode());
            out.set("visits", NODES.arrayNode());
            out.set("photos", NODES.arrayNode());
            return out;
        }
    }

    /** A well-formed value this reader does not support: the file is a newer app's. */
    private static final class Newer extends RuntimeException {
        Newer() {
            super(null, null, false, false);
        }
    }

    private static JsonNode legacyRows(ObjectNode root) {
        if (root.has("houses")) throw broken("candidates and houses");
        var format = text(root, "format");
        var out = NODES.objectNode();
        for (var e : root.properties()) {
            var key = e.getKey();
            var value = e.getValue();
            if ("format".equals(key)) {
                if (CURRENT_FORMAT.equals(format)) {
                    var deletions = root.get("deleted") != null && !root.get("deleted").isNull();
                    out.put(key, deletions ? BackupFormat.ID_WITH_DELETIONS : BackupFormat.ID_WITH_BROKERS);
                } else if (format != null && format.startsWith(FAMILY)) {
                    out.put(key, NEWER_FORMAT);
                } else {
                    out.set(key, value);
                }
            } else if ("search".equals(key)) {
                continue;
            } else if ("candidates".equals(key)) {
                if (value.isNull()) out.set("houses", value);
                else if (value instanceof ArrayNode list) out.set("houses", houses(list));
                else throw broken("candidates");
            } else if (ROWS_WITH_HOUSE.contains(key) && value instanceof ArrayNode list) {
                out.set(key, map(list, r -> rename(r, "candidateId", "houseId")));
            } else if ("deleted".equals(key) && value instanceof ArrayNode list) {
                out.set(key, map(list, d -> "candidate".equals(text(d, "kind")) ? copy(d).put("kind", "house") : d));
            } else {
                out.set(key, value);
            }
        }
        return out;
    }

    private static ArrayNode houses(ArrayNode list) {
        var out = NODES.arrayNode();
        for (var c : list) {
            if (!(c instanceof ObjectNode candidate)) throw broken("candidate");
            out.add(houseOf(candidate));
        }
        return out;
    }

    private static ObjectNode houseOf(ObjectNode c) {
        var kind = text(c, "kind");
        if (kind == null || !KIND.matcher(kind).matches()) throw broken("kind");
        var row = NODES.objectNode();
        for (var e : c.properties()) {
            switch (e.getKey()) {
                case "kind" -> {
                    if (!House.HOUSE_KIND.equals(kind)) row.set("kind", e.getValue());
                }
                case "money" -> legacyMoney(row, e.getValue(), kind);
                case "extras" -> legacyExtras(row, e.getValue());
                default -> row.set(e.getKey(), e.getValue());
            }
        }
        return ordered(row, HOUSE_ORDER);
    }

    private static void legacyMoney(ObjectNode row, JsonNode value, String kind) {
        if (value.isNull()) return;
        var amount = amount(value, false, false);
        var cadence = text(value, "cadence");
        if (House.HOUSE_KIND.equals(kind) && cadence != null && !Money.HOUSE_CADENCES.contains(cadence)) throw new Newer();
        if (amount != null) row.put("price", amount);
        if ("month".equals(cadence)) row.put("priceType", "RENT");
        else if ("once".equals(cadence)) row.put("priceType", "SALE");
    }

    private static void legacyExtras(ObjectNode row, JsonNode value) {
        if (value.isNull()) return;
        if (!(value instanceof ObjectNode extras)) throw broken("extras");
        var cost = NODES.objectNode();
        int other = 0;
        for (var e : extras.properties()) {
            var id = e.getKey();
            var v = e.getValue();
            if (VALUES.contains(id)) {
                row.set(id, v);
            } else if (MONEY.contains(id)) {
                if (v.isNull()) continue;
                var amount = amount(v, true, true);
                var fixed = FIXED.get(id);
                if (fixed != null && !fixed.equals(text(v, "cadence"))) throw new Newer();
                cost.put(id, amount);
            } else if (COST.contains(id)) {
                cost.set(id, v);
            } else {
                if (!EXTRA_ID.matcher(id).matches() || !isExtraValue(v)) throw broken("extras." + id);
                other++;
            }
        }
        if (other > CandidateExtras.MAX_OTHER) throw broken("extras");
        if (!cost.isEmpty()) row.set("cost", cost);
    }

    /**
     * A money object's amount (null when it has none and none is required), after checking its cadence: a text, one this
     * reader does not know being a newer file's; each required for a money extra, at least one of the two otherwise.
     */
    private static Long amount(JsonNode value, boolean required, boolean cadenceRequired) {
        if (!(value instanceof ObjectNode m)) throw broken("money");
        var c = m.get("cadence");
        var hasCadence = c != null && !c.isNull();
        if (hasCadence && !c.isString()) throw broken("money.cadence");
        if (!hasCadence && cadenceRequired) throw broken("money.cadence");
        var a = m.get("amount");
        Long amount = null;
        if (a != null && !a.isNull()) {
            if (!(a.isInt() || a.isLong()) || a.asLong() < 0 || a.asLong() > Money.MAX_RUPEES) throw broken("money.amount");
            amount = a.asLong();
        }
        if (amount == null && (required || !hasCadence)) throw broken("money.amount");
        if (hasCadence && !Money.CADENCES.contains(c.asString())) throw new Newer();
        return amount;
    }

    /** A value of one of the six extras types as the wire spells it (door 5; {@code CandidateExtras.isExtraValue}). */
    private static boolean isExtraValue(JsonNode v) {
        if (v == null || v.isNull()) return false;
        if (v.isBoolean()) return true;
        if (v.isNumber()) return Double.isFinite(v.asDouble());
        if (v.isString()) return v.asString().length() <= CandidateExtras.MAX_TEXT;
        if (v instanceof ObjectNode m) {
            var a = m.get("amount");
            return m.size() == 2 && a != null && (a.isInt() || a.isLong()) && a.asLong() >= 0 && a.asLong() <= Money.MAX_RUPEES
                    && Money.CADENCES.contains(text(m, "cadence"));
        }
        return false;
    }

    // ---- Helpers ----

    private static ObjectNode ordered(ObjectNode row, List<String> order) {
        var out = NODES.objectNode();
        for (var key : order) if (row.has(key)) out.set(key, row.get(key));
        for (var e : row.properties()) if (!out.has(e.getKey())) out.set(e.getKey(), e.getValue());
        return out;
    }

    private static JsonNode map(JsonNode list, java.util.function.Function<ObjectNode, JsonNode> f) {
        if (!(list instanceof ArrayNode array)) return list;
        var out = NODES.arrayNode();
        for (var row : array) out.add(row instanceof ObjectNode o ? f.apply(o) : row);
        return out;
    }

    private static ObjectNode rename(ObjectNode row, String from, String to) {
        var out = NODES.objectNode();
        for (var e : row.properties()) out.set(e.getKey().equals(from) ? to : e.getKey(), e.getValue());
        return out;
    }

    private static ObjectNode copy(ObjectNode row) {
        return row.deepCopy();
    }

    private static String text(JsonNode node, String key) {
        var v = node.get(key);
        return v != null && v.isString() ? v.asString() : null;
    }

    private static BadRequestException broken(String why) {
        return new BadRequestException("Not a backup of the format: " + why);
    }
}
