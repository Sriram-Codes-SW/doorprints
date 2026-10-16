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
package app.doorprints.server.ai.rag;

import app.doorprints.server.ai.ContactRedactor;
import app.doorprints.server.ai.PromptSafety;
import app.doorprints.server.ai.kind.AiKind;
import app.doorprints.server.ai.kind.AiKinds;
import app.doorprints.server.house.HouseDto;
import app.doorprints.server.visit.VisitDto;
import tools.jackson.databind.JsonNode;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Ask document of a candidate of a built-in kind other than the house (kinds PR 7, S4b-BL-211; docs/03 ADR-36
 * §18.11), read through its kind's {@code ai.ask} slot, in the slot's order: the core fields and sections the slot
 * names in the house document's words ({@link HouseDocuments}), and each typed extra it names as {@code <words>:
 * <value>} (the kind's English words). Nothing else reaches the provider: not a core field or extra the slot leaves out,
 * not an id {@code ai.never} names, and never an extra the kind does not know (a newer app's, kept by
 * {@code CandidateExtras.other} and written back unread). A value is read only when it keeps to its extra's type and
 * range ({@link #value}); a text goes through the contact redactor and loses its line breaks, as notes and area notes do.
 * The twin of the website's {@code core/ai/kind-ai.ts} and the phone's {@code KindDocuments};
 * {@code docs/schemas/kinds/kind-ai-vectors.json} holds the three to the same lines.
 */
public final class KindDocuments {

    private KindDocuments() {
    }

    static String text(HouseDto h, List<VisitDto> visits, AreaLines.All areas, AiKind k) {
        return text(h, visits, List.of(), areas, k);
    }

    /**
     * The document of {@code h} as kind {@code k}'s {@code ai.ask} slot says (kinds PR 28, §18.22), line by line in the
     * slot's order. Every kind is written here, the house too: an id the server's AI knows (the house's price, size, area,
     * floor, cost, rooms, questions, viewings, moving in and checklist) is written in its own words and rules, any other
     * extra by its type and the words of its kind file. A line that needs two ids (a deposit in rupees or months) is written
     * where the first of them stands. {@code never} ids are skipped whatever the slot says.
     */
    static String text(HouseDto h, List<VisitDto> visits, List<ViewingLine> viewings, AreaLines.All areas, AiKind k) {
        var r = ContactRedactor.forHouse(h);
        var sb = new StringBuilder();
        var cost = h.cost();
        var written = new java.util.HashSet<String>();
        for (var id : k.ask()) {
            if (k.never().contains(id) || written.contains(id)) continue;
            switch (id) {
                case "label" -> HouseDocuments.line(sb, k.noun(), r.freeText(h.label()));
                case "address" -> HouseDocuments.line(sb, "Address", r.place(h.address()));
                case "street" -> HouseDocuments.line(sb, "Street", r.place(h.street()));
                case "locality" -> HouseDocuments.line(sb, "Locality", r.place(h.locality()));
                case "money" -> {
                    if (h.price() != null) {
                        HouseDocuments.line(sb, "Price", "Rs " + h.price() + (h.priceType() == null ? ""
                                : "RENT".equals(h.priceType()) ? " per month (rent)" : " (sale)"));
                    }
                }
                case "bedrooms" -> {
                    if (h.bedrooms() != null) {
                        HouseDocuments.line(sb, "Size", h.bedrooms() == 0 ? "studio / 1RK" : h.bedrooms() + " BHK");
                    }
                }
                case "areaSqft" -> {
                    if (h.areaSqft() != null) HouseDocuments.line(sb, "Carpet area", h.areaSqft() + " sq ft");
                }
                // S4b-BL-87: the floor in words, the same as the apps': 0 the ground floor, a negative one a basement level.
                case "floor" -> {
                    if (h.floor() != null) {
                        HouseDocuments.line(sb, "Floor", h.floor() == 0 ? "ground floor"
                                : h.floor() < 0 ? "basement " + -h.floor() : h.floor().toString());
                    }
                }
                case "deposit", "depositMonths" -> {
                    written.addAll(java.util.List.of("deposit", "depositMonths"));
                    if (cost != null) {
                        HouseDocuments.line(sb, "Deposit", HouseDocuments.rupeesOrMonths(
                                slot(k, "deposit") ? cost.deposit() : null,
                                slot(k, "depositMonths") ? cost.depositMonths() : null));
                    }
                }
                case "maintenance", "maintenanceIncluded" -> {
                    written.addAll(java.util.List.of("maintenance", "maintenanceIncluded"));
                    if (cost != null && slot(k, "maintenance") && cost.maintenance() != null) {
                        HouseDocuments.line(sb, "Maintenance", "Rs " + cost.maintenance() + " per month"
                                + (!slot(k, "maintenanceIncluded") ? ""
                                : Boolean.TRUE.equals(cost.maintenanceIncluded()) ? " (included)" : " (not included)"));
                    }
                }
                case "brokerage", "brokerageMonths" -> {
                    written.addAll(java.util.List.of("brokerage", "brokerageMonths"));
                    if (cost != null) {
                        HouseDocuments.line(sb, "Brokerage", HouseDocuments.rupeesOrMonths(
                                slot(k, "brokerage") ? cost.brokerage() : null,
                                slot(k, "brokerageMonths") ? cost.brokerageMonths() : null));
                    }
                }
                case "lockInMonths" -> {
                    if (cost != null && cost.lockInMonths() != null) {
                        HouseDocuments.line(sb, "Lock-in", HouseDocuments.months(cost.lockInMonths()));
                    }
                }
                case "noticeMonths" -> {
                    if (cost != null && cost.noticeMonths() != null) {
                        HouseDocuments.line(sb, "Notice", HouseDocuments.months(cost.noticeMonths()));
                    }
                }
                case "availableFrom" -> {
                    if (cost != null) HouseDocuments.line(sb, "Available from", cost.availableFrom());
                }
                case "agreedPrice" -> {
                    if (cost != null && cost.agreedPrice() != null) {
                        HouseDocuments.line(sb, "Agreed price", "Rs " + cost.agreedPrice());
                    }
                }
                case "rooms" -> HouseDocuments.line(sb, "Rooms", HouseDocuments.rooms(h.rooms(), r));
                case "questions" -> HouseDocuments.answerLines(sb, h.answers(), r);
                case "viewings" -> HouseDocuments.viewingLines(sb, viewings, r);
                case "moveIn" -> HouseDocuments.movingInLines(sb, h.moveIn(), r);
                case "checklist" -> {
                    if (h.checklist() != null && !h.checklist().isEmpty()) {
                        var items = new StringBuilder();
                        new java.util.TreeMap<>(h.checklist()).forEach((key, v) -> items.append(items.isEmpty() ? "" : ", ")
                                .append(r.freeText(key)).append(' ').append(v).append("/5"));
                        HouseDocuments.line(sb, "Checklist", items.toString());
                    }
                }
                case "status" -> HouseDocuments.line(sb, "Status", h.status() == null ? null : h.status().name());
                case "rating" -> {
                    if (h.rating() != null) HouseDocuments.line(sb, "My rating", h.rating() + "/5");
                }
                case "areaNotes" -> HouseDocuments.areaNoteLines(sb, h, areas, r);
                case "distances" -> HouseDocuments.distanceLines(sb, h, areas, r);
                case "visits" -> HouseDocuments.line(sb, "Visits", HouseDocuments.visitSummary(visits));
                case "notes" -> {
                    if (h.notes() != null && !h.notes().isBlank()) {
                        var notes = h.notes().strip();
                        notes = notes.length() > HouseDocuments.NOTES_MAX
                                ? PromptSafety.clipUnits(notes, HouseDocuments.NOTES_MAX) + " …" : notes;
                        HouseDocuments.line(sb, "Notes", r.freeText(notes));
                    }
                }
                default -> {
                    var extra = k.extra(id);
                    if (extra == null || extra.words() == null) {
                        throw new IllegalStateException("kind " + k.id() + " cannot write " + id);
                    }
                    var other = h.extras() == null ? null : h.extras().other().get(id);
                    HouseDocuments.line(sb, extra.words(), value(extra, other, r));
                }
            }
        }
        // Deliberately no "Contact" line: the contact name and phone never go to the provider (F-30).
        return sb.toString().strip();
    }

    /** Whether the kind's {@code ask} slot names {@code id} and {@code never} does not. */
    private static boolean slot(AiKind k, String id) {
        return k.allows(k.ask(), id);
    }

    /**
     * An extra's value in a document line, or null when it does not keep to its type and range (no line): an option the
     * kind offers (its words); a text of at most {@code maxLength} characters, redacted and on one line; a whole number
     * within {@code min..max} (a JSON number with no fraction); {@code yes} or {@code no}; money with a whole amount
     * within range and the extra's one cadence, as {@code Rs <amount>}.
     */
    public static String value(AiKind.Extra e, JsonNode v, ContactRedactor.Redactor r) {
        if (v == null || v.isNull()) return null;
        return switch (e.type()) {
            case "enum" -> v.isString() ? e.options().get(v.asString()) : null;
            case "text" -> {
                if (!v.isString() || v.asString().length() > e.maxLength()) yield null;
                var t = r.freeText(v.asString().strip());
                yield t == null ? null : t.replaceAll("\\s+", " ").strip();
            }
            case "number" -> {
                var n = whole(v);
                yield n == null || n < e.min() || n > e.max() ? null : n.toString();
            }
            case "bool" -> v.isBoolean() ? (v.asBoolean() ? "yes" : "no") : null;
            case "date" -> v.isString() && v.asString().matches("\\d{4}-\\d{2}-\\d{2}") ? v.asString() : null;
            case "money" -> {
                if (e.cadence() == null || !v.isObject() || !v.path("cadence").isString()
                        || !e.cadence().equals(v.path("cadence").asString())) {
                    yield null;
                }
                var amount = whole(v.path("amount"));
                yield amount == null || amount < e.min() || amount > e.max() ? null : "Rs " + amount;
            }
            default -> null;
        };
    }

    /** A JSON number with no fractional part as a long ({@code 25} and {@code 25.0}), else null. */
    private static Long whole(JsonNode v) {
        if (v == null || !v.isNumber()) return null;
        if (v.isIntegralNumber()) return v.canConvertToLong() ? v.asLong() : null;
        double d = v.asDouble();
        return Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) < 1e15 ? (long) d : null;
    }

    /**
     * The kind's extras that {@code slot} names, for a Plan tool's record (S4b-BL-270): the extra's English words to its value
     * as {@link #value} reads it (typed, in range, a text redacted and on one line), in the slot's order, an extra with no
     * readable value left out. Never an extra the slot does not name, one {@code ai.never} names, or one the kind does not
     * know. Non-null for a candidate of the kind (empty when it has no readable value), so a record carries the key; a
     * house's record has none (the house is not read through this). {@code h} is the candidate as {@link AiKind#view}
     * leaves it, so the contact it holds is only for the redactor.
     */
    public static Map<String, String> extras(HouseDto h, AiKind k, List<String> slot) {
        var r = ContactRedactor.forHouse(h);
        var out = new java.util.LinkedHashMap<String, String>();
        for (var id : slot) {
            var extra = k.extra(id);
            if (extra == null || !k.allows(slot, id)) continue;
            var v = value(extra, h.extras() == null ? null : h.extras().other().get(id), r);
            if (v != null) out.put(extra.words(), v);
        }
        return out;
    }

    /**
     * The metadata of such a document: the id, the kind, the label and locality (redacted) and the status and rating when the
     * slot names them, and the visit keys; the price, its type and the BHK only when the slot names the money or the
     * BHK (a school names neither, so an Ask filter on them never keeps a school).
     */
    static Map<String, Object> metadata(HouseDto h, List<VisitDto> visits, AiKind k) {
        var r = ContactRedactor.forHouse(h);
        var m = new HashMap<String, Object>();
        m.put("houseId", h.id().toString());
        // The kind the text was written under: Ask drops the document once the kind leaves KINDS_AVAILABLE (S4b-BL-308).
        if (!AiKinds.alwaysAvailable(k.id())) m.put("kind", k.id());
        m.put("label", h.label() == null || !k.allows(k.ask(), "label") ? "" : r.freeText(h.label()));
        if (h.status() != null && k.allows(k.ask(), "status")) m.put("status", h.status().name());
        if (k.allows(k.ask(), "money")) {
            if (h.priceType() != null) m.put("priceType", h.priceType());
            if (h.price() != null) m.put("price", h.price());
        }
        if (h.bedrooms() != null && k.allows(k.ask(), "bedrooms")) m.put("bedrooms", h.bedrooms());
        if (h.rating() != null && k.allows(k.ask(), "rating")) m.put("rating", h.rating());
        if (h.locality() != null && k.allows(k.ask(), "locality")) m.put("locality", r.place(h.locality()));
        boolean visited = visits != null && !visits.isEmpty();
        m.put("visited", visited);
        if (visited) {
            m.put("lastVisit", visits.stream().map(VisitDto::arrivedAt).max(Comparator.naturalOrder())
                    .orElseThrow().getEpochSecond());
        }
        return m;
    }
}
