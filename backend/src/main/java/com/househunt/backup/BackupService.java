package com.househunt.backup;

import com.househunt.backup.ImportReport.Outcome;
import com.househunt.backup.ImportReport.Tally;
import com.househunt.house.House;
import com.househunt.house.HouseChangedEvent;
import com.househunt.house.HouseRepository;
import com.househunt.house.HouseStatus;
import com.househunt.photo.PhotoRepository;
import com.househunt.sync.ClientClock;
import com.househunt.sync.SyncVersions;
import com.househunt.visit.Visit;
import com.househunt.visit.VisitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads and writes the shared JSON backup format ({@link BackupFormat}).
 *
 * <p>{@link #export()} is what {@code GET /api/export} returns; {@link #importBackup} is what
 * {@code POST /api/import} applies. Both speak the {@code data.json} half of the format, so a backup written by the
 * Android or web app restores to a server and back without a converter (docs/schemas/README.md).
 *
 * <p>Import rules:
 * <ul>
 *   <li><b>Validate first.</b> Every row is checked before anything is written, so a file with one bad row changes
 *       nothing (400 with the first problems listed). Timestamps go through {@link ClientClock} exactly like a sync
 *       write, so a backup from a device with a broken clock cannot win every later conflict (F-08).</li>
 *   <li><b>Merge by id, last write wins</b> on {@code updatedAt} ({@link ImportReport#decide}). An equal
 *       {@code updatedAt} writes nothing, so re-importing the same file twice burns no sync versions and syncs
 *       nothing to the other devices.</li>
 *   <li><b>Never deletes.</b> A backup holds live rows only, so an import can create or update rows but never
 *       removes one; a row this server has and the file does not is simply left alone.</li>
 *   <li><b>Photos are metadata only.</b> The JSON carries no image bytes, so photo rows are reported and skipped;
 *       the bytes are uploaded with {@code POST /api/houses/{id}/photos}.</li>
 *   <li><b>A missing checklist is read as no scores</b>, not refused — the one lenient always-present field
 *       (docs/schemas/README.md section 4.4). When that clears scores this server has, the report names the
 *       house.</li>
 *   <li><b>Restoring a deleted house cannot bring its photos back.</b> A delete purges the content (F-16): the
 *       house's photos are tombstoned with their bytes dropped and its visits are unlinked. An import makes the
 *       row live again; the photo bytes are gone for good, and a visit re-links only if the file's copy of it is
 *       newer than the unlink. The report says so per house.</li>
 *   <li><b>The report's per-row problems are capped</b> at {@value #MAX_REPORTED_PROBLEMS} plus an "and N more"
 *       line, like the 400 message; the whole-file notes (photo bytes, AI index) come first and are never
 *       dropped.</li>
 *   <li><b>The AI index is not fanned out per row.</b> See {@link #publishChanges}: a restore of more than
 *       {@value #MAX_INDEX_EVENTS} houses publishes no {@link HouseChangedEvent} at all and says so in the report,
 *       because each event is one un-batched embedding call.</li>
 *   <li><b>Dry run</b> ({@code ?dryRun=true}) runs the same validation and the same decisions, reports the same
 *       {@link ImportReport#problems()}, and writes nothing (and publishes nothing). It is the preview of docs/11
 *       section 5.2.</li>
 * </ul>
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    /**
     * How many per-row problems are named — in a 400 message and in an {@link ImportReport} alike — before the list
     * is cut short with an "and N more" tail. Without it a 20 000-row restore over deleted houses would answer an
     * 16 MiB request with megabytes of near-identical lines (docs/schemas/README.md section 6.10).
     */
    static final int MAX_REPORTED_PROBLEMS = 20;

    /**
     * How many houses one import may announce to the AI indexer.
     *
     * <p>Each {@link HouseChangedEvent} becomes one un-batched embedding call in {@code HouseIndexer}, on the
     * common task executor and with no quota short-circuit — unlike {@code reindexAll()}, which batches and stops
     * on a provider 429. {@code app.limits.max-import-rows} is 20 000 by default, so fanning out per row would let
     * a single restore queue tens of thousands of provider calls (and spend a whole free-tier quota) while the
     * caller is told the import succeeded. Above this many houses the events are dropped and the report says so.
     */
    static final int MAX_INDEX_EVENTS = 50;

    private final HouseRepository houses;
    private final VisitRepository visits;
    private final PhotoRepository photos;
    private final SyncVersions versions;
    private final ClientClock clock;
    private final ApplicationEventPublisher events;

    public BackupService(HouseRepository houses, VisitRepository visits, PhotoRepository photos,
                         SyncVersions versions, ClientClock clock, ApplicationEventPublisher events) {
        this.houses = houses;
        this.visits = visits;
        this.photos = photos;
        this.versions = versions;
        this.clock = clock;
        this.events = events;
    }

    /** Everything live on the server, in the format's fixed order. Photo bytes are fetched separately. */
    @Transactional(readOnly = true)
    public BackupData export() {
        return BackupMapper.toBackup(houses.findByDeletedFalseOrderByUpdatedAtDesc(),
                visits.findByDeletedFalseOrderByArrivedAtDesc(), photos.findAllLiveMetadata(), clock.now());
    }

    /**
     * Validates a backup and merges it into the server's data.
     *
     * @param dryRun when true nothing is written and the report is a preview
     * @throws IllegalArgumentException (answered with 400) when the file is not a valid {@code doorprints-backup/1}
     */
    @Transactional
    public ImportReport importBackup(BackupData data, boolean dryRun) {
        validate(data);
        // Per-row notes (a skipped visit, a restored house) can number in the thousands, so they are capped in the
        // report; the whole-file notes (photo bytes, AI index) are at most two lines and are always reported.
        var rowProblems = new ArrayList<String>();
        var fileNotes = new ArrayList<String>();
        var houseTally = new Tally();
        var visitTally = new Tally();
        var photoTally = new Tally();
        // Houses whose AI document this import invalidated, collected instead of announced row by row.
        var changedHouses = new LinkedHashSet<UUID>();
        if (!dryRun) versions.lock(); // one writer at a time, and versions become visible in order (F-09)

        for (var row : data.houses()) houseTally.count(mergeHouse(row, dryRun, rowProblems, changedHouses));

        // A visit's house must exist (foreign key): either it is in this file or the server already has the row.
        var housesInFile = new HashSet<UUID>();
        for (var row : data.houses()) housesInFile.add(row.id());
        for (var row : data.visits()) {
            var houseId = row.houseId();
            if (houseId != null && !housesInFile.contains(houseId) && !houses.existsById(houseId)) {
                rowProblems.add("visit " + row.id() + ": house " + houseId + " is not in the file or on this server");
                visitTally.count(Outcome.SKIPPED);
                continue;
            }
            visitTally.count(mergeVisit(row, dryRun, changedHouses));
        }

        for (var ignored : data.photos()) photoTally.count(Outcome.SKIPPED);
        if (!data.photos().isEmpty()) {
            fileNotes.add(data.photos().size() + " photo row(s) carry no image bytes in a JSON backup; upload them "
                    + "with POST /api/houses/{id}/photos");
        }

        publishChanges(changedHouses, fileNotes, dryRun);

        var reported = new ArrayList<String>(fileNotes);
        reported.addAll(capped(rowProblems, "and %d more row problem(s) not listed"));
        var report = new ImportReport(BackupFormat.ID, dryRun, houseTally.toEntity(), visitTally.toEntity(),
                photoTally.toEntity(), List.copyOf(reported));
        if (!dryRun) {
            log.info("import: houses={} visits={} (created/updated/keptNewer/unchanged/skipped)",
                    report.houses(), report.visits());
        }
        return report;
    }

    /**
     * The first {@value #MAX_REPORTED_PROBLEMS} of {@code problems}, plus one tail line (built from
     * {@code tailFormat} and the number left out) when there were more.
     */
    private static List<String> capped(List<String> problems, String tailFormat) {
        if (problems.size() <= MAX_REPORTED_PROBLEMS) return problems;
        var shown = new ArrayList<String>(problems.subList(0, MAX_REPORTED_PROBLEMS));
        shown.add(tailFormat.formatted(problems.size() - MAX_REPORTED_PROBLEMS));
        return shown;
    }

    /**
     * Tells the AI indexer which houses changed — once per house, or not at all.
     *
     * <p>{@code HouseIndexer.onHouseChanged} embeds one document per event, one provider call each, with no
     * batching and no quota short-circuit. A big restore would therefore turn one HTTP request into thousands of
     * provider calls. Above {@value #MAX_INDEX_EVENTS} houses nothing is published and the report carries the line
     * that says the index is stale, so the caller is not told a clean success it did not get. Re-indexing is then
     * {@code POST /api/ai/reindex}, which batches by 20 and stops on a provider 429. With AI disabled nobody
     * listens either way, and the note simply says what would have to be re-indexed if it were on.
     *
     * <p>A dry run publishes nothing — it writes nothing, so there is nothing to re-index — but it does get the
     * same note, because the preview has to show the problems the real import would report.
     */
    private void publishChanges(Set<UUID> changedHouses, List<String> fileNotes, boolean dryRun) {
        if (changedHouses.isEmpty()) return;
        if (changedHouses.size() > MAX_INDEX_EVENTS) {
            fileNotes.add("AI index not updated for " + changedHouses.size() + " house(s): an import this large is "
                    + "not indexed row by row. If AI search is enabled, run POST /api/ai/reindex");
            if (!dryRun) {
                log.info("import: {} houses changed (> {}), no index events published", changedHouses.size(),
                        MAX_INDEX_EVENTS);
            }
            return;
        }
        if (dryRun) return;
        for (var houseId : changedHouses) events.publishEvent(new HouseChangedEvent(houseId));
    }

    private Outcome mergeHouse(BackupHouse row, boolean dryRun, List<String> problems, Set<UUID> changedHouses) {
        var inFile = clock.accept(Instant.ofEpochMilli(row.updatedAt()), "houses.updatedAt");
        var existing = houses.findById(row.id()).orElse(null);
        var outcome = ImportReport.decide(existing == null ? null : existing.getUpdatedAt(), inFile);
        if (outcome == Outcome.KEPT_NEWER || outcome == Outcome.UNCHANGED) return outcome;

        // A backup never holds tombstones, so an imported row is always live again. The delete purged its content
        // (F-16): the photos were tombstoned with their bytes dropped, which no JSON backup can undo, and the visits
        // were unlinked. The unlink is an ordinary write, though, so a visit row in this same file that is newer
        // than the delete wins in mergeVisit and re-links — the normal case for a full backup restored over a
        // delete. The line says exactly that much, and holds for the preview and the applied import alike.
        if (existing != null && existing.isDeleted()) {
            problems.add("house " + row.id() + " was deleted here: the row is restored, and its photos and their "
                    + "bytes are gone for good; a visit row in this file re-links to it only if its updatedAt is "
                    + "newer than that delete");
        }
        // checklist is the one always-present field a reader is lenient about: absent or null reads as {} (no
        // scores) rather than refusing the file (docs/schemas/README.md sections 3.1 and 4.4). The whole row wins
        // (section 4.2), so that clears whatever scores this server has — which the report says, instead of
        // wiping them in silence. A create, or a house with no scores here, loses nothing and gets no line.
        if (row.checklist() == null && existing != null && !existing.getChecklist().isEmpty()) {
            problems.add("house " + row.id() + ": the file has no checklist, which is read as no scores, so the "
                    + existing.getChecklist().size() + " checklist score(s) this server had are cleared");
        }
        // A dry run decides everything and writes nothing, the report included: the preview must carry the same
        // problems as the real import, so the would-be index work is counted here too.
        changedHouses.add(row.id());
        if (dryRun) return outcome;

        var house = existing == null ? new House(row.id()) : existing;
        // The whole row wins or loses together (docs/schemas/README.md section 4.2), so an UPDATE takes the file's
        // createdAt as well: the export is ordered by it, and keeping the server's would make a restore re-order
        // the very file it came from.
        house.setCreatedAt(clock.accept(Instant.ofEpochMilli(row.createdAt()), "houses.createdAt"));
        house.setLabel(row.label());
        house.setAddress(row.address());
        house.setStreet(row.street());
        house.setLocality(row.locality());
        house.setLat(row.lat());
        house.setLon(row.lon());
        house.setStatus(row.status() == null ? HouseStatus.NEW : row.status());
        house.setPrice(row.price());
        house.setPriceType(row.priceType());
        house.setBedrooms(row.bedrooms());
        house.setRating(row.rating());
        house.setContactName(row.contactName());
        house.setContactPhone(row.contactPhone());
        house.setListingUrl(row.listingUrl());
        house.setNotes(row.notes());
        house.setChecklist(row.checklist() == null ? Map.of() : row.checklist());
        house.setDeleted(false);
        house.setUpdatedAt(inFile);
        house.setSyncVersion(versions.next());
        houses.save(house);
        return outcome;
    }

    private Outcome mergeVisit(BackupVisit row, boolean dryRun, Set<UUID> changedHouses) {
        var inFile = clock.accept(Instant.ofEpochMilli(row.updatedAt()), "visits.updatedAt");
        var existing = visits.findById(row.id()).orElse(null);
        var outcome = ImportReport.decide(existing == null ? null : existing.getUpdatedAt(), inFile);
        if (outcome == Outcome.KEPT_NEWER || outcome == Outcome.UNCHANGED) return outcome;

        // The house's AI index includes a visit summary, so remember which houses changed — the one the visit
        // lands on and, when it moves, the one it left. Collected, not published: see publishChanges. A dry run
        // counts them too, so the preview's problems match the real import's.
        var previousHouseId = existing == null ? null : existing.getHouseId();
        if (row.houseId() != null) changedHouses.add(row.houseId());
        if (previousHouseId != null) changedHouses.add(previousHouseId);
        if (dryRun) return outcome;

        var visit = existing == null ? new Visit(row.id()) : existing;
        visit.setHouseId(row.houseId());
        visit.setLat(row.lat());
        visit.setLon(row.lon());
        visit.setStreet(row.street());
        visit.setArrivedAt(Instant.ofEpochMilli(row.arrivedAt()));
        visit.setLeftAt(row.leftAt() == null ? null : Instant.ofEpochMilli(row.leftAt()));
        visit.setSource(row.source());
        visit.setDeleted(false);
        visit.setUpdatedAt(inFile);
        visit.setSyncVersion(versions.next());
        visits.save(visit);
        return outcome;
    }

    // ---- validation -------------------------------------------------------------------------------------------

    /**
     * Rejects the whole file (400) unless every row is usable. A backup is one document: half-importing a file with
     * a broken row would leave data nobody asked for and no way to tell what landed.
     */
    private void validate(BackupData data) {
        if (!BackupFormat.ID.equals(data.format())) {
            // The format id is echoed (shortened) because it is what the user has to fix; no other part of the
            // body ever appears in an error message (SEC-015).
            var seen = data.format() == null ? "missing"
                    : "\"" + data.format().substring(0, Math.min(40, data.format().length())) + "\"";
            throw new IllegalArgumentException("Not a " + BackupFormat.ID + " backup (format was " + seen + ")");
        }
        var problems = new ArrayList<String>();
        validateHouses(data.houses(), problems);
        validateVisits(data.visits(), problems);
        validatePhotos(data.photos(), problems);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Invalid backup: " + String.join("; ", capped(problems, "and %d more")));
        }
    }

    private void validateHouses(List<BackupHouse> rows, List<String> problems) {
        var seen = new HashSet<UUID>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var at = "houses[" + i + "]";
            if (row == null) {
                problems.add(at + ": missing");
                continue;
            }
            requireId(at, row.id(), seen, problems);
            require(row.label() != null, at + ".label is required (it may be empty)", problems);
            maxLength(at + ".label", row.label(), 200, problems);
            maxLength(at + ".address", row.address(), 500, problems);
            maxLength(at + ".street", row.street(), 200, problems);
            maxLength(at + ".locality", row.locality(), 200, problems);
            requireCoordinates(at, row.lat(), row.lon(), problems);
            require(row.status() != null, at + ".status is required", problems);
            require(row.price() == null || row.price() >= 0, at + ".price must not be negative", problems);
            require(row.priceType() == null || "RENT".equals(row.priceType()) || "SALE".equals(row.priceType()),
                    at + ".priceType must be RENT or SALE", problems);
            require(row.bedrooms() == null || row.bedrooms() >= 0, at + ".bedrooms must not be negative", problems);
            require(row.rating() == null || (row.rating() >= 1 && row.rating() <= 5),
                    at + ".rating must be 1..5", problems);
            maxLength(at + ".contactName", row.contactName(), 200, problems);
            maxLength(at + ".contactPhone", row.contactPhone(), 50, problems);
            maxLength(at + ".listingUrl", row.listingUrl(), 1000, problems);
            maxLength(at + ".notes", row.notes(), 20_000, problems);
            validateChecklist(at, row.checklist(), problems);
            requireTime(at + ".createdAt", row.createdAt(), problems);
            requireTime(at + ".updatedAt", row.updatedAt(), problems);
        }
    }

    /**
     * Checklist keys are user text, so problems name the house, never the key itself (SEC-015). A {@code null}
     * checklist (absent or null in the file) is legal and means "no scores": it is the one always-present field a
     * reader does not refuse (docs/schemas/README.md section 4.4).
     */
    private void validateChecklist(String at, Map<String, Integer> checklist, List<String> problems) {
        if (checklist == null) return;
        for (var entry : checklist.entrySet()) {
            var key = entry.getKey();
            if (key == null || key.isEmpty() || key.length() > 100) {
                problems.add(at + ".checklist has a key that is empty or longer than 100 characters");
                continue;
            }
            var score = entry.getValue();
            require(score != null && score >= 0 && score <= 5, at + ".checklist has a score outside 0..5", problems);
        }
    }

    private void validateVisits(List<BackupVisit> rows, List<String> problems) {
        var seen = new HashSet<UUID>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var at = "visits[" + i + "]";
            if (row == null) {
                problems.add(at + ": missing");
                continue;
            }
            requireId(at, row.id(), seen, problems);
            requireCoordinates(at, row.lat(), row.lon(), problems);
            maxLength(at + ".street", row.street(), 200, problems);
            require(row.source() != null, at + ".source is required (AUTO or MANUAL)", problems);
            requireTime(at + ".arrivedAt", row.arrivedAt(), problems);
            requireTime(at + ".updatedAt", row.updatedAt(), problems);
            if (row.leftAt() != null && row.arrivedAt() != null && row.leftAt() < row.arrivedAt()) {
                problems.add(at + ".leftAt must not be before arrivedAt");
            }
        }
    }

    private void validatePhotos(List<BackupPhoto> rows, List<String> problems) {
        var seen = new HashSet<UUID>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var at = "photos[" + i + "]";
            if (row == null) {
                problems.add(at + ": missing");
                continue;
            }
            requireId(at, row.id(), seen, problems);
            require(row.houseId() != null, at + ".houseId is required", problems);
            requireTime(at + ".createdAt", row.createdAt(), problems);
            var name = row.fileName();
            require(name != null && !name.isEmpty() && name.length() <= 200
                            && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && !name.contains(".."),
                    at + ".fileName must be a plain file name", problems);
        }
    }

    private void requireId(String at, UUID id, Set<UUID> seen, List<String> problems) {
        if (id == null) {
            problems.add(at + ".id is required");
        } else if (!seen.add(id)) {
            problems.add(at + ".id " + id + " appears twice");
        }
    }

    /**
     * {@code lat} and {@code lon} must both be present and in range. Absent or {@code null} is refused rather than
     * read as {@code 0}: a defaulted {@code 0, 0} is a real place that passes the range check, so a truncated file
     * would otherwise import its houses into the Gulf of Guinea in silence (docs/schemas/README.md section 4.4).
     * The range check runs only on a present value, so a missing one is never unboxed.
     */
    private void requireCoordinates(String at, Double lat, Double lon, List<String> problems) {
        if (lat == null) {
            problems.add(at + ".lat is required");
        } else {
            require(lat >= -90 && lat <= 90, at + ".lat is out of range", problems);
        }
        if (lon == null) {
            problems.add(at + ".lon is required");
        } else {
            require(lon >= -180 && lon <= 180, at + ".lon is out of range", problems);
        }
    }

    /**
     * A timestamp must be present and sane. The same {@link ClientClock} rules as a sync write apply (F-08), and
     * they are checked here, before anything is written, so an absurd date in row 900 cannot roll back row 1.
     */
    private void requireTime(String at, Long millis, List<String> problems) {
        if (millis == null) {
            problems.add(at + " is required");
            return;
        }
        Instant when;
        try {
            when = Instant.ofEpochMilli(millis);
        } catch (DateTimeException | ArithmeticException e) {
            problems.add(at + " is not a valid timestamp");
            return;
        }
        try {
            clock.validate(when, at);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        }
    }

    private void maxLength(String at, String value, int max, List<String> problems) {
        require(value == null || value.length() <= max, at + " is longer than " + max + " characters", problems);
    }

    private void require(boolean ok, String problem, List<String> problems) {
        if (!ok) problems.add(problem);
    }
}
