package app.doorprints.server.backup;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import app.doorprints.server.house.HouseStatus;

import java.util.Map;
import java.util.UUID;

/**
 * One house in the shared backup format ({@code doorprints-backup/1}).
 *
 * <p><b>Null semantics (NFR-025).</b> A field that has no value is <em>left out</em> of the JSON; a reader must
 * treat "absent" and "null" as the same thing, which is what the Kotlin writer's {@code explicitNulls = false} and
 * the TypeScript writer's {@code undefined} produce. Only {@code id}, {@code label}, {@code lat}, {@code lon},
 * {@code status}, {@code checklist}, {@code createdAt} and {@code updatedAt} are always present. {@code label} may
 * be the empty string (a house saved before it was named); {@code checklist} may be the empty object. This is the
 * opposite of the sync API, where {@link app.doorprints.server.house.HouseDto} writes every field, including nulls.
 *
 * <p>Timestamps are epoch milliseconds (UTC), not ISO-8601 strings: a backup is a copy of a local store, and both
 * client stores keep them that way. {@code deleted} and {@code syncVersion} are deliberately absent — tombstones
 * are never exported and sync state belongs to the device that holds it.
 *
 * <p>{@code lat} and {@code lon} are {@link Double}, not {@code double}. They are always present, but a primitive
 * would turn a truncated or hand-edited file that leaves them out (or writes {@code null}) into {@code 0.0, 0.0} —
 * {@code spring.jackson.deserialization.fail-on-null-for-primitives} is off — and that is a real place in the Gulf
 * of Guinea that passes every range check. Boxed, the missing value arrives as {@code null} and
 * {@link BackupService} refuses the file (docs/schemas/README.md section 4.4). {@code 0, 0} written explicitly is
 * still accepted: it is how the apps store "no location yet".
 *
 * <p><b>{@code checklist} is the one lenient always-present field</b> (docs/schemas/README.md sections 3.1 and 4.4).
 * Every writer emits it, {@code {}} when there are no scores, but a reader takes an absent or {@code null} checklist
 * as {@code {}} instead of refusing the file: unlike a defaulted {@code 0, 0}, "no scores" is a true statement about
 * a house, and the Android reader in {@code :shared} has always read it that way. The leniency is not silent: it
 * is kept {@code null} here (no compact-constructor default) so that {@link BackupService} can tell a missing
 * checklist from an empty one and name, in the import report, every house whose existing scores the file clears.
 * {@link BackupMapper} never writes {@code null} here.
 *
 * <p>The property order below is part of the contract (two exports of the same data must be the same document, key
 * for key, docs/schemas/README.md section 8.1), so {@code @JsonPropertyOrder} pins it rather than relying on a
 * Jackson default.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "label", "address", "street", "locality", "lat", "lon", "status", "price", "priceType",
        "bedrooms", "rating", "contactName", "contactPhone", "listingUrl", "notes", "checklist", "createdAt",
        "updatedAt"})
public record BackupHouse(
        UUID id,
        String label,
        String address,
        String street,
        String locality,
        /* Boxed on purpose: see the class comment. Never null in an export; a null here is refused on import. */
        Double lat,
        Double lon,
        HouseStatus status,
        Long price,
        String priceType,
        Integer bedrooms,
        Integer rating,
        String contactName,
        String contactPhone,
        String listingUrl,
        String notes,
        /* Always written, possibly empty; keys are sorted so two copies of the same data are the same document.
           Null only on the way in (absent or null in the file), which BackupService reads as {}: see the class
           comment. */
        Map<String, Integer> checklist,
        Long createdAt,
        Long updatedAt
) {
}
