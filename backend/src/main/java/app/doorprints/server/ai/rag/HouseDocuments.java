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
import app.doorprints.server.house.HouseCost;
import app.doorprints.server.house.HouseDto;
import app.doorprints.server.house.HouseRoom;
import app.doorprints.server.visit.VisitDto;
import org.springframework.ai.document.Document;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Turns a house (+ its visits) into ONE retrieval document. Houses are small (a few hundred tokens even with long
 * notes), so there is no chunking: one document per house, id = house id, which makes re-indexing an idempotent
 * upsert and makes every citation point at a whole house. Notes are capped so one essay-length note can't dominate
 * the embedding.
 *
 * <p>The text is sent to the embedding provider on every (re)index and, read back from the vector store, as Ask
 * context, so it never contains the contact name or phone: the contact fields are left out and every free-text field
 * goes through {@link ContactRedactor} (threat model F-30): label, checklist keys and notes lose the whole name and
 * every name part, address, street and locality the whole name. Label and locality in the metadata are redacted too.
 */
public final class HouseDocuments {

    static final int NOTES_MAX = 3000;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private HouseDocuments() {
    }

    public static Document toDocument(HouseDto h, List<VisitDto> visits) {
        return new Document(h.id().toString(), text(h, visits), metadata(h));
    }

    /** Stable, labelled plain text; the labels double as grounding cues for the model. No contact name or phone. */
    public static String text(HouseDto h, List<VisitDto> visits) {
        var r = ContactRedactor.forHouse(h);
        var sb = new StringBuilder();
        line(sb, "House", r.freeText(h.label()));
        line(sb, "Address", r.place(h.address()));
        line(sb, "Street", r.place(h.street()));
        line(sb, "Locality", r.place(h.locality()));
        if (h.price() != null) {
            line(sb, "Price", "Rs " + h.price() + (h.priceType() == null ? "" : "RENT".equals(h.priceType())
                    ? " per month (rent)" : " (sale)"));
        }
        if (h.bedrooms() != null) line(sb, "Size", h.bedrooms() == 0 ? "studio / 1RK" : h.bedrooms() + " BHK");
        if (h.areaSqft() != null) line(sb, "Carpet area", h.areaSqft() + " sq ft");
        costLines(sb, h.cost());
        line(sb, "Rooms", rooms(h.rooms(), r));
        line(sb, "Status", h.status() == null ? null : h.status().name());
        if (h.rating() != null) line(sb, "My rating", h.rating() + "/5");
        if (h.checklist() != null && !h.checklist().isEmpty()) {
            var items = new StringBuilder();
            new TreeMap<>(h.checklist()).forEach((k, v) -> items.append(items.isEmpty() ? "" : ", ")
                    .append(r.freeText(k)).append(' ').append(v).append("/5"));
            line(sb, "Checklist", items.toString());
        }
        // Deliberately no "Contact" line: the contact name and phone never go to the provider (F-30).
        line(sb, "Visits", visitSummary(visits));
        if (h.notes() != null && !h.notes().isBlank()) {
            var notes = h.notes().strip();
            notes = notes.length() > NOTES_MAX ? notes.substring(0, NOTES_MAX) + " …" : notes;
            line(sb, "Notes", r.freeText(notes));
        }
        return sb.toString().strip();
    }

    /**
     * Only non-null values (Document metadata rejects nulls); used for structured filtering in PgVector and for
     * citation labels. Label and locality are redacted too: an OpenAI-compatible embedding model may embed metadata
     * with the text ({@code MetadataMode.EMBED}), and citation labels reach MCP clients.
     */
    public static Map<String, Object> metadata(HouseDto h) {
        var r = ContactRedactor.forHouse(h);
        var m = new HashMap<String, Object>();
        m.put("houseId", h.id().toString());
        m.put("label", h.label() == null ? "" : r.freeText(h.label()));
        if (h.status() != null) m.put("status", h.status().name());
        if (h.priceType() != null) m.put("priceType", h.priceType());
        if (h.price() != null) m.put("price", h.price());
        if (h.bedrooms() != null) m.put("bedrooms", h.bedrooms());
        if (h.rating() != null) m.put("rating", h.rating());
        if (h.locality() != null) m.put("locality", r.place(h.locality()));
        return m;
    }

    /**
     * The cost fields of slice 1a, the same words as the on-device {@code AiHouse}: rupees win over months when both
     * are set (as in the clients' arithmetic). Never {@code myOffer}: a negotiation is the person's own and does not
     * go to the provider (docs/11 section 5.30 item 5).
     */
    static void costLines(StringBuilder sb, HouseCost c) {
        if (c == null) return;
        line(sb, "Deposit", rupeesOrMonths(c.deposit(), c.depositMonths()));
        if (c.maintenance() != null) {
            line(sb, "Maintenance", "Rs " + c.maintenance() + " per month"
                    + (Boolean.TRUE.equals(c.maintenanceIncluded()) ? " (included)" : " (not included)"));
        }
        line(sb, "Brokerage", rupeesOrMonths(c.brokerage(), c.brokerageMonths()));
        if (c.lockInMonths() != null) line(sb, "Lock-in", months(c.lockInMonths()));
        if (c.noticeMonths() != null) line(sb, "Notice", months(c.noticeMonths()));
        line(sb, "Available from", c.availableFrom());
        if (c.agreedPrice() != null) line(sb, "Agreed price", "Rs " + c.agreedPrice());
    }

    /**
     * The rooms of slice 1c, the same words as the on-device {@code AiHouse} and the web {@code houseText}:
     * {@code Master bedroom 13 ft 0 in x 12 ft 0 in (condition 4/5); Kitchen 9 ft 10 in x 8 ft 0 in}, in the order
     * shown (sort, then id), always feet and inches. Names (redacted like the label), sizes and condition only: the
     * room notes may hold a contact name or number and are never sent (docs/11 section 5.30 item 5).
     */
    static String rooms(List<HouseRoom> rooms, ContactRedactor.Redactor r) {
        if (rooms == null || rooms.isEmpty()) return null;
        var parts = new java.util.ArrayList<String>();
        rooms.stream().filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing((HouseRoom x) -> x.sort() == null ? 0 : x.sort())
                        .thenComparing(x -> x.id() == null ? "" : x.id()))
                .forEach(x -> {
                    var name = x.name() == null || x.name().isBlank() ? typeName(x.type()) : r.freeText(x.name().strip());
                    var sb = new StringBuilder(name);
                    if (x.lengthCm() != null && x.widthCm() != null) {
                        sb.append(' ').append(feetInches(x.lengthCm())).append(" x ").append(feetInches(x.widthCm()));
                    }
                    if (x.condition() != null) sb.append(" (condition ").append(x.condition()).append("/5)");
                    parts.add(sb.toString());
                });
        return String.join("; ", parts);
    }

    /** {@code BEDROOM} as {@code Bedroom}. */
    private static String typeName(String type) {
        if (type == null || type.isEmpty()) return "Room";
        return type.charAt(0) + type.substring(1).toLowerCase(Locale.ROOT);
    }

    /** Whole centimetres as {@code 13 ft 0 in}: the nearest inch, then feet and the inches left. */
    static String feetInches(int cm) {
        int inches = (int) Math.round(cm / 2.54);
        return inches / 12 + " ft " + inches % 12 + " in";
    }

    private static String rupeesOrMonths(Long rupees, Integer months) {
        return rupees != null ? "Rs " + rupees : months != null ? months(months) : null;
    }

    private static String months(int n) {
        return n + (n == 1 ? " month" : " months");
    }

    static String visitSummary(List<VisitDto> visits) {
        if (visits == null || visits.isEmpty()) return "not visited yet";
        var last = visits.stream().map(VisitDto::arrivedAt).max(java.util.Comparator.naturalOrder()).orElseThrow();
        long totalMinutes = visits.stream()
                .filter(v -> v.leftAt() != null && v.leftAt().isAfter(v.arrivedAt()))
                .mapToLong(v -> Duration.between(v.arrivedAt(), v.leftAt()).toMinutes())
                .sum();
        return visits.size() + (visits.size() == 1 ? " visit" : " visits") + ", last on " + DAY.format(last)
                + (totalMinutes > 0 ? ", " + totalMinutes + " min in total" : "");
    }

    private static void line(StringBuilder sb, String key, String value) {
        if (value != null && !value.isBlank()) sb.append(key).append(": ").append(value.strip()).append('\n');
    }
}
