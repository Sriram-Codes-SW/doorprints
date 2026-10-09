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
import app.doorprints.server.ai.agent.RouteOptimizer;
import app.doorprints.server.house.HouseAnswer;
import app.doorprints.server.house.HouseMoveIn;
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
    static final int ANSWER_LINES_MAX = 20;
    static final int VIEWING_LINES_MAX = 10;
    static final int AREA_NOTE_LINES_MAX = 5;
    static final int DISTANCE_LINES_MAX = 10;
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private HouseDocuments() {
    }

    public static Document toDocument(HouseDto h, List<VisitDto> visits) {
        return toDocument(h, visits, List.of());
    }

    static Document toDocument(HouseDto h, List<VisitDto> visits, List<ViewingLine> viewings) {
        return new Document(h.id().toString(), text(h, visits, viewings), metadata(h, visits));
    }

    /** As above, with the areas, places and area notes of slice 4a. */
    static Document toDocument(HouseDto h, List<VisitDto> visits, List<ViewingLine> viewings, AreaLines.All areas) {
        return new Document(h.id().toString(), text(h, visits, viewings, areas), metadata(h, visits));
    }

    /** Stable, labelled plain text; the labels double as grounding cues for the model. No contact name or phone. */
    public static String text(HouseDto h, List<VisitDto> visits) {
        return text(h, visits, List.of());
    }

    /** As above, with the viewings of this house (slice 3b-1), a line each after the questions. */
    static String text(HouseDto h, List<VisitDto> visits, List<ViewingLine> viewings) {
        return text(h, visits, viewings, AreaLines.All.NONE);
    }

    /** As above, with the area notes that reach this house and its distances to the person's places (slice 4a). */
    static String text(HouseDto h, List<VisitDto> visits, List<ViewingLine> viewings, AreaLines.All areas) {
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
        // S4b-BL-87: the floor in words, the same as the apps': 0 the ground floor, a negative one a basement level.
        if (h.floor() != null) {
            line(sb, "Floor", h.floor() == 0 ? "ground floor" : h.floor() < 0 ? "basement " + -h.floor() : h.floor().toString());
        }
        costLines(sb, h.cost());
        line(sb, "Rooms", rooms(h.rooms(), r));
        answerLines(sb, h.answers(), r);
        viewingLines(sb, viewings, r);
        areaNoteLines(sb, h, areas, r);
        distanceLines(sb, h, areas, r);
        movingInLines(sb, h.moveIn(), r);
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
            notes = notes.length() > NOTES_MAX ? PromptSafety.clipUnits(notes, NOTES_MAX) + " …" : notes;
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
        return metadata(h, List.of());
    }

    /**
     * As above, with {@code visited} (a boolean) and, when there is a live visit, {@code lastVisit} (the latest arrival,
     * epoch seconds): the filter and sort keys for a question about visits (S4b-BL-194). Dates and a flag only.
     */
    public static Map<String, Object> metadata(HouseDto h, List<VisitDto> visits) {
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
        boolean visited = visits != null && !visits.isEmpty();
        m.put("visited", visited);
        if (visited) {
            m.put("lastVisit", visits.stream().map(VisitDto::arrivedAt).max(Comparator.naturalOrder())
                    .orElseThrow().getEpochSecond());
        }
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

    /**
     * The viewing answers of slice 3a, the same words as the on-device {@code AiHouse} and the web {@code houseText}:
     * {@code Asked: <question> | Answer: <answer>} for each answered one, then {@code Still to ask: <question>} for
     * each open one (a skipped question is neither), at most {@value #ANSWER_LINES_MAX} of each kind, in the order
     * shown ({@link HouseAnswer#ordered}: open first, then sort, then id). Question and answer are the person's own
     * words and go through the contact redactor exactly like notes: an answer may well hold the owner's number.
     */
    static void answerLines(StringBuilder sb, List<HouseAnswer> answers, ContactRedactor.Redactor r) {
        var ordered = HouseAnswer.ordered(answers);
        ordered.stream().filter(a -> "ANSWERED".equals(a.reads())).limit(ANSWER_LINES_MAX)
                .forEach(a -> line(sb, "Asked", r.freeText(a.text().strip()) + " | Answer: "
                        + r.freeText(a.answer().strip())));
        ordered.stream().filter(a -> "OPEN".equals(a.reads())).limit(ANSWER_LINES_MAX)
                .forEach(a -> line(sb, "Still to ask", r.freeText(a.text().strip())));
    }

    /**
     * The viewings of slice 3b-1, the same words as the on-device {@code AiHouse} and the web {@code houseText}:
     * {@code Viewing: 2026-10-02 10:30 | SECOND | PLANNED | Notes: Ask for the water bill}, at most
     * {@value #VIEWING_LINES_MAX}, PLANNED first and then the newest first (the id breaks a tie). The time is UTC. Never
     * {@code withWhom}; the notes go through the contact redactor and lose their line breaks, so a note cannot start a
     * line of its own.
     */
    static void viewingLines(StringBuilder sb, List<ViewingLine> viewings, ContactRedactor.Redactor r) {
        if (viewings == null) return;
        viewings.stream().filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing((ViewingLine v) -> "PLANNED".equals(v.status()) ? 0 : 1)
                        .thenComparing(Comparator.comparingLong(ViewingLine::startsAt).reversed())
                        .thenComparing(ViewingLine::id))
                .limit(VIEWING_LINES_MAX)
                .forEach(v -> {
                    var value = new StringBuilder(WHEN.format(java.time.Instant.ofEpochMilli(v.startsAt())))
                            .append(" | ").append(v.kind()).append(" | ").append(v.status());
                    var notes = v.notes() == null ? "" : r.freeText(v.notes().strip()).replaceAll("\\s+", " ").strip();
                    if (!notes.isEmpty()) value.append(" | Notes: ").append(notes);
                    line(sb, "Viewing", value.toString());
                });
    }

    /**
     * The moving-in card of slice 5, the same words as the on-device {@code AiHouse} and the web {@code houseText}:
     * {@code Moving in: <done> of <total> done} (only when there are items) and {@code Moving in notes: <text>} (one
     * line, through the contact redactor). Never the item texts or the date: the ticks say how far it is.
     */
    static void movingInLines(StringBuilder sb, HouseMoveIn m, ContactRedactor.Redactor r) {
        if (m == null) return;
        if (m.items() != null && !m.items().isEmpty()) {
            line(sb, "Moving in", m.doneCount() + " of " + m.items().size() + " done");
        }
        if (m.notes() != null && !m.notes().isBlank()) {
            line(sb, "Moving in notes", r.freeText(m.notes().strip()).replaceAll("\\s+", " ").strip());
        }
    }

    /** A house with the point (0, 0) has none yet (the sync API's default), and an APPROX point is a ring, not a place. */
    private static boolean hasPoint(HouseDto h) {
        return !(h.lat() == 0 && h.lon() == 0);
    }

    /**
     * The notes of {@link #areaNotesReaching} as {@code Area note: <text>}, at most {@value #AREA_NOTE_LINES_MAX},
     * newest first, the same words as the on-device {@code AiHouse} and the web {@code houseText}. The text goes through
     * the contact redactor and loses its line breaks.
     */
    static void areaNoteLines(StringBuilder sb, HouseDto h, AreaLines.All all, ContactRedactor.Redactor r) {
        areaNotesReaching(h, all).stream().limit(AREA_NOTE_LINES_MAX)
                .forEach(n -> line(sb, "Area note", r.freeText(n.text().strip()).replaceAll("\\s+", " ").strip()));
    }

    /**
     * The notes that reach a house: an area note when the area is live, the house has a real point (not APPROX, not
     * unset) and lies within the area's radius (haversine); a street note when the house's street, trimmed, equals the
     * note's street ignoring case. Newest first, the id breaks a tie (vectors N1 to N5 of docs/11 slice 4a).
     */
    static List<AreaLines.Note> areaNotesReaching(HouseDto h, AreaLines.All all) {
        if (all == null || all.notes().isEmpty()) return List.of();
        var placed = hasPoint(h) && !"APPROX".equals(h.locationSource());
        var street = h.street() == null ? "" : h.street().strip();
        var byArea = new HashMap<String, AreaLines.Area>();
        all.areas().forEach(a -> byArea.put(a.id(), a));
        return all.notes().stream().filter(n -> {
            if (n.areaId() != null) {
                var a = byArea.get(n.areaId());
                return placed && a != null
                        && RouteOptimizer.haversineMeters(h.lat(), h.lon(), a.lat(), a.lon()) <= a.radiusM();
            }
            return !street.isEmpty() && street.equalsIgnoreCase(n.street().strip());
        }).sorted(Comparator.comparingLong(AreaLines.Note::updatedAt).reversed().thenComparing(AreaLines.Note::id))
                .toList();
    }

    /**
     * {@code Distance to <place>: <km> km}, at most {@value #DISTANCE_LINES_MAX}, nearest first (the name breaks a
     * tie). The place name goes through the redactor; never the coordinates. A house without a point gets none.
     */
    static void distanceLines(StringBuilder sb, HouseDto h, AreaLines.All all, ContactRedactor.Redactor r) {
        if (all == null || all.places().isEmpty() || !hasPoint(h)) return;
        all.places().stream()
                .map(p -> Map.entry(p, RouteOptimizer.haversineMeters(h.lat(), h.lon(), p.lat(), p.lon())))
                .sorted(Map.Entry.<AreaLines.Place, Double>comparingByValue()
                        .thenComparing(e -> e.getKey().name()))
                .limit(DISTANCE_LINES_MAX)
                .forEach(e -> line(sb, "Distance to " + r.freeText(e.getKey().name().strip()).replaceAll("\\s+", " ").strip(),
                        km(e.getValue()) + " km"));
    }

    /** Metres as kilometres with one decimal, half up (D1 8572.7 m is 8.6, D2 0 is 0.0, D3 3211.7 m is 3.2). */
    static String km(double meters) {
        return String.format(Locale.ROOT, "%.1f", Math.floor(meters / 100 + 0.5) / 10);
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

    /**
     * One-line visit history for a house document: count, date of the latest visit and total minutes where departures
     * are known; "not visited yet" when there are none.
     */
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
