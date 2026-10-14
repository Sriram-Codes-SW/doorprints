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
final class KindDocuments {

    private KindDocuments() {
    }

    static String text(HouseDto h, List<VisitDto> visits, AreaLines.All areas, AiKind k) {
        var r = ContactRedactor.forHouse(h);
        var sb = new StringBuilder();
        for (var id : k.ask()) {
            if (k.never().contains(id)) continue;
            switch (id) {
                case "label" -> HouseDocuments.line(sb, k.noun(), r.freeText(h.label()));
                case "address" -> HouseDocuments.line(sb, "Address", r.place(h.address()));
                case "street" -> HouseDocuments.line(sb, "Street", r.place(h.street()));
                case "locality" -> HouseDocuments.line(sb, "Locality", r.place(h.locality()));
                case "status" -> HouseDocuments.line(sb, "Status", h.status() == null ? null : h.status().name());
                case "rating" -> HouseDocuments.line(sb, "My rating", h.rating() == null ? null : h.rating() + "/5");
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
                    if (extra == null) throw new IllegalStateException("kind " + k.id() + " cannot write " + id);
                    var other = h.extras() == null ? null : h.extras().other().get(id);
                    HouseDocuments.line(sb, extra.words(), value(extra, other, r));
                }
            }
        }
        return sb.toString().strip();
    }

    /**
     * An extra's value in a document line, or null when it does not keep to its type and range (no line): an option the
     * kind offers (its words); a text of at most {@code maxLength} characters, redacted and on one line; a whole number
     * within {@code min..max} (a JSON number with no fraction); {@code yes} or {@code no}; money with a whole amount
     * within range and the extra's one cadence, as {@code Rs <amount>}.
     */
    static String value(AiKind.Extra e, JsonNode v, ContactRedactor.Redactor r) {
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
            case "money" -> {
                if (!v.isObject() || !v.path("cadence").isString() || !e.cadence().equals(v.path("cadence").asString())) {
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
     * The metadata of such a document: the id, the kind, the label and locality (redacted) and the status and rating when the
     * slot names them, and the visit keys; the price, its type and the BHK only when the slot names the money or the
     * BHK (a school names neither, so an Ask filter on them never keeps a school).
     */
    static Map<String, Object> metadata(HouseDto h, List<VisitDto> visits, AiKind k) {
        var r = ContactRedactor.forHouse(h);
        var m = new HashMap<String, Object>();
        m.put("houseId", h.id().toString());
        // The kind the text was written under: Ask drops the document once the kind leaves KINDS_AVAILABLE (S4b-BL-308).
        m.put("kind", k.id());
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
