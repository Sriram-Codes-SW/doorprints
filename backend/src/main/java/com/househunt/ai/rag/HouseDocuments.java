package com.househunt.ai.rag;

import com.househunt.ai.ContactRedactor;
import com.househunt.house.HouseDto;
import com.househunt.visit.VisitDto;
import org.springframework.ai.document.Document;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
