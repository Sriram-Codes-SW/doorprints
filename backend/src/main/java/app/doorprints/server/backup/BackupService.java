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

import app.doorprints.server.backup.ImportReport.Outcome;
import app.doorprints.server.backup.ImportReport.Tally;
import app.doorprints.server.house.House;
import app.doorprints.server.house.HouseChangedEvent;
import app.doorprints.server.house.HouseAnswer;
import app.doorprints.server.house.HouseMoveIn;
import app.doorprints.server.house.HouseCost;
import app.doorprints.server.house.HouseRoom;
import app.doorprints.server.house.HouseDto;
import app.doorprints.server.house.HouseRepository;
import app.doorprints.server.house.HouseStatus;
import app.doorprints.server.photo.PhotoMeta;
import app.doorprints.server.photo.PhotoRepository;
import app.doorprints.server.photo.PhotoService;
import app.doorprints.server.record.Record;
import app.doorprints.server.record.RecordController;
import app.doorprints.server.record.RecordDto;
import app.doorprints.server.record.RecordKey;
import app.doorprints.server.record.RecordRepository;
import app.doorprints.server.sync.ClientClock;
import app.doorprints.server.sync.SyncVersions;
import app.doorprints.server.visit.Visit;
import app.doorprints.server.visit.VisitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

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
 *   <li><b>Brokers are records.</b> The {@code brokers} list of a {@code /2} file is merged into the {@code record}
 *       table as type {@code broker} (payload: name, phone, agency, feeTerms, notes, rating), by id, last write wins
 *       on {@code updatedAt}, like a house; a house's {@code brokerId} is kept as given, even when no such broker
 *       exists yet. The export writes the list, and the format id {@code /2}, only when the copy holds something {@code /1} has no place for (see {@link BackupFormat#ID_WITH_BROKERS}).</li>
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
    private final RecordRepository records;
    private final ObjectMapper json;
    private final PhotoService photoService;

    public BackupService(HouseRepository houses, VisitRepository visits, PhotoRepository photos,
                         SyncVersions versions, ClientClock clock, ApplicationEventPublisher events,
                         RecordRepository records, ObjectMapper json, PhotoService photoService) {
        this.houses = houses;
        this.visits = visits;
        this.photos = photos;
        this.versions = versions;
        this.clock = clock;
        this.events = events;
        this.records = records;
        this.json = json;
        this.photoService = photoService;
    }

    /** Everything live on the server, in the format's fixed order. Photo bytes are fetched separately. */
    @Transactional(readOnly = true)
    public BackupData export() {
        return BackupMapper.toBackup(houses.findByDeletedFalseOrderByUpdatedAtDesc(),
                visits.findByDeletedFalseOrderByArrivedAtDesc(), photos.findAllLiveMetadata(),
                records.findByKeyTypeAndDeletedFalse(BackupBroker.TYPE),
                records.findByKeyTypeAndDeletedFalse(BackupCriterion.TYPE),
                records.findByKeyTypeAndDeletedFalse(BackupPreference.TYPE),
                records.findByKeyTypeAndDeletedFalse(BackupQuestion.TYPE),
                records.findByKeyTypeAndDeletedFalse(BackupViewing.TYPE),
                records.findByKeyTypeAndDeletedFalse(BackupArea.TYPE),
                records.findByKeyTypeAndDeletedFalse(BackupPlace.TYPE),
                records.findByKeyTypeAndDeletedFalse(BackupAreaNote.TYPE),
                json, clock.now());
    }

    /**
     * Validates a backup and merges it into the server's data.
     *
     * @param dryRun when true nothing is written and the report is a preview
     * @throws IllegalArgumentException (answered with 400) when the file is not a valid {@code doorprints-backup/1}
     *                                  or {@code /2} document (a newer format is refused with "update the app")
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
        var brokerTally = new Tally();
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

        for (var row : data.photos()) photoTally.count(mergePhotoMeta(row, dryRun));
        if (!data.photos().isEmpty()) {
            fileNotes.add(data.photos().size() + " photo row(s) carry no image bytes in a JSON backup; upload them "
                    + "with POST /api/houses/{id}/photos");
        }

        // Brokers are independent of the houses (a house's brokerId is not checked against them), so they merge last.
        var liveBrokers = new long[]{records.countByKeyTypeAndDeletedFalse(BackupBroker.TYPE)};
        for (var row : data.brokers()) brokerTally.count(mergeBroker(row, dryRun, liveBrokers, rowProblems));

        // Criteria and preferences (slice 2) merge after brokers.
        // Counted as the apps count them (S4b-BL-90b): the ten built-ins always, plus the live custom ones.
        var liveCriteria = new long[]{BackupCriterion.BUILT_IN_KEYS.size() + records.findByKeyTypeAndDeletedFalse(
                BackupCriterion.TYPE).stream().filter(r -> !BackupCriterion.BUILT_IN_KEYS.contains(r.getKey().id())).count()};
        var criterionTally = new Tally();
        for (var row : data.criteria()) criterionTally.count(mergeCriterion(row, dryRun, liveCriteria, rowProblems));

        var livePreferences = new long[]{records.countByKeyTypeAndDeletedFalse(BackupPreference.TYPE)};
        var preferenceTally = new Tally();
        for (var row : data.preferences()) preferenceTally.count(mergePreference(row, dryRun, livePreferences, rowProblems));

        // Viewing questions (slice 3a) merge last.
        var liveQuestions = new long[]{records.countByKeyTypeAndDeletedFalse(BackupQuestion.TYPE)};
        var questionTally = new Tally();
        for (var row : data.questions()) questionTally.count(mergeQuestion(row, dryRun, liveQuestions, rowProblems));

        // Viewings (slice 3b-1) merge after the questions; a viewing's house is not checked (it may be gone).
        var liveViewings = new long[]{records.countByKeyTypeAndDeletedFalse(BackupViewing.TYPE)};
        var viewingTally = new Tally();
        for (var row : data.viewings()) {
            viewingTally.count(mergeViewing(row, dryRun, liveViewings, rowProblems, changedHouses));
        }

        // Areas, places and area notes (slice 4a) merge after the viewings; the apps hold the small caps, the server the record cap.
        var areaTally = new Tally();
        var areaLive = new long[]{records.countByKeyTypeAndDeletedFalse(BackupArea.TYPE)};
        for (var row : data.areas()) areaTally.count(mergeArea(row, dryRun, areaLive, rowProblems));
        var placeTally = new Tally();
        var placeLive = new long[]{records.countByKeyTypeAndDeletedFalse(BackupPlace.TYPE)};
        for (var row : data.places()) placeTally.count(mergePlace(row, dryRun, placeLive, rowProblems));
        var noteTally = new Tally();
        var noteLive = new long[]{records.countByKeyTypeAndDeletedFalse(BackupAreaNote.TYPE)};
        for (var row : data.areaNotes()) noteTally.count(mergeAreaNote(row, dryRun, noteLive, rowProblems));

        publishChanges(changedHouses, fileNotes, dryRun);

        var reported = new ArrayList<String>(fileNotes);
        reported.addAll(capped(rowProblems, "and %d more row problem(s) not listed"));
        var report = new ImportReport(data.format(), dryRun, houseTally.toEntity(), visitTally.toEntity(),
                photoTally.toEntity(), brokerTally.toEntity(), criterionTally.toEntity(), preferenceTally.toEntity(),
                questionTally.toEntity(), viewingTally.toEntity(), areaTally.toEntity(), placeTally.toEntity(),
                noteTally.toEntity(), List.copyOf(reported));
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

    /**
     * A JSON backup carries no image bytes, so a photo row never creates a photo (SKIPPED). What the person said about
     * one (slice 5: room, tags, caption) does merge into a photo this server already holds, last write wins on
     * {@code metaUpdatedAt} like the sync: newer in the file is UPDATED, the same stamp UNCHANGED, older KEPT_NEWER.
     * A row with no edit time, or for a photo that is not here (or is another house's), changes nothing.
     */
    private Outcome mergePhotoMeta(BackupPhoto row, boolean dryRun) {
        if (row.metaUpdatedAt() == null || row.metaUpdatedAt() <= 0) return Outcome.SKIPPED;
        var current = photoService.metadata(row.id());
        if (current == null || current.deleted() || !current.houseId().equals(row.houseId())) return Outcome.SKIPPED;
        if (current.metaUpdatedAt() > row.metaUpdatedAt()) return Outcome.KEPT_NEWER;
        if (current.metaUpdatedAt() == row.metaUpdatedAt()) return Outcome.UNCHANGED;
        if (!dryRun) photoService.applyMeta(current, row.roomId(), row.tags(), row.caption(), row.metaUpdatedAt());
        return Outcome.UPDATED;
    }

    /**
     * Merges one house row by id, last write wins on {@code updatedAt}: a row that is not newer changes nothing. A
     * newer row replaces every field of the house, including {@code createdAt}, and makes a deleted house live again;
     * the report then notes what a delete cannot give back (photo bytes) and any checklist scores the file clears.
     * The house is queued for re-indexing, in a dry run too.
     */
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
        house.setAreaSqft(row.areaSqft());
        house.setLocationSource(row.locationSource());
        house.setCost(HouseCost.write(row.cost())); // an empty object reads as no cost
        house.setRooms(HouseRoom.write(row.rooms())); // an empty list reads as no rooms
        house.setAnswers(HouseAnswer.write(row.answers())); // an empty list reads as no answers
        house.setMoveIn(HouseMoveIn.write(row.moveIn())); // an empty object reads as no move-in
        house.setFloor(row.floor());
        house.setBrokerId(row.brokerId()); // as given: the broker may arrive later, or be read as none
        house.setChecklist(row.checklist() == null ? Map.of() : row.checklist());
        house.setDeleted(false);
        house.setUpdatedAt(inFile);
        house.setSyncVersion(versions.next());
        houses.save(house);
        return outcome;
    }

    /**
     * Writes a broker as a {@code broker} record, the payload as {@code RecordController} would store it. A tombstone
     * under the same id is made live again when the file's copy is newer. {@code liveCount} (one element, so it can
     * be updated here) holds the number of live brokers, so the per-type cap of the records API applies to an import
     * too, dry run or not.
     */
    private Outcome mergeBroker(BackupBroker row, boolean dryRun, long[] liveCount, List<String> problems) {
        var inFile = clock.accept(Instant.ofEpochMilli(row.updatedAt()), "brokers.updatedAt");
        var key = new RecordKey(BackupBroker.TYPE, row.id());
        var existing = records.findById(key).orElse(null);
        var outcome = ImportReport.decide(existing == null ? null : existing.getUpdatedAt(), inFile);
        if (outcome == Outcome.KEPT_NEWER || outcome == Outcome.UNCHANGED) return outcome;
        var becomesLive = existing == null || existing.isDeleted();
        if (becomesLive) {
            if (liveCount[0] >= RecordController.MAX_LIVE_ROWS_PER_TYPE) {
                problems.add("broker " + row.id() + ": skipped, this server holds the most brokers it keeps ("
                        + RecordController.MAX_LIVE_ROWS_PER_TYPE + ")");
                return Outcome.SKIPPED;
            }
            liveCount[0]++;
        }
        if (dryRun) return outcome;

        var payload = json.createObjectNode();
        payload.put("name", row.name());
        if (row.phone() != null) payload.put("phone", row.phone());
        if (row.agency() != null) payload.put("agency", row.agency());
        if (row.feeTerms() != null) payload.put("feeTerms", row.feeTerms());
        if (row.notes() != null) payload.put("notes", row.notes());
        if (row.rating() != null) payload.put("rating", row.rating());
        var record = existing == null ? new Record(key) : existing;
        record.setPayload(payload.toString());
        record.setDeleted(false);
        record.setUpdatedAt(inFile.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        record.setSyncVersion(versions.next());
        records.save(record);
        return outcome;
    }

    /**
     * Merges one checklist criterion as a {@code criterion} record by key. A custom key that becomes live counts
     * against the apps' limit of {@link BackupCriterion#MAX}; the built-in keys never do.
     */
    private Outcome mergeCriterion(BackupCriterion row, boolean dryRun, long[] liveCount, List<String> problems) {
        var inFile = clock.accept(Instant.ofEpochMilli(row.updatedAt()), "criteria.updatedAt");
        var key = new RecordKey(BackupCriterion.TYPE, row.key());
        var existing = records.findById(key).orElse(null);
        var outcome = ImportReport.decide(existing == null ? null : existing.getUpdatedAt(), inFile);
        if (outcome == Outcome.KEPT_NEWER || outcome == Outcome.UNCHANGED) return outcome;
        // A built-in key never adds one; a custom key that becomes live stays within the apps' 40 (S4b-BL-90b).
        var becomesLive = (existing == null || existing.isDeleted()) && !BackupCriterion.BUILT_IN_KEYS.contains(row.key());
        if (becomesLive) {
            if (liveCount[0] >= BackupCriterion.MAX) {
                problems.add("criterion " + row.key() + ": skipped, there are already " + BackupCriterion.MAX
                        + " criteria, the most the apps keep");
                return Outcome.SKIPPED;
            }
            liveCount[0]++;
        }
        if (dryRun) return outcome;

        var payload = json.createObjectNode();
        payload.put("weight", row.weight());
        payload.put("mustHave", row.mustHave());
        payload.put("minScore", row.minScore());
        if (row.sort() != null) payload.put("sort", row.sort());
        if (row.label() != null) payload.put("label", row.label());
        if (row.archived() != null && row.archived()) payload.put("archived", true);
        var record = existing == null ? new Record(key) : existing;
        record.setPayload(payload.toString());
        record.setDeleted(false);
        record.setUpdatedAt(inFile.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        record.setSyncVersion(versions.next());
        records.save(record);
        return outcome;
    }

    /**
     * Merges one preference as a {@code preference} record by key, within the per-type record limit.
     */
    private Outcome mergePreference(BackupPreference row, boolean dryRun, long[] liveCount, List<String> problems) {
        var inFile = clock.accept(Instant.ofEpochMilli(row.updatedAt()), "preferences.updatedAt");
        var key = new RecordKey(BackupPreference.TYPE, row.key());
        var existing = records.findById(key).orElse(null);
        var outcome = ImportReport.decide(existing == null ? null : existing.getUpdatedAt(), inFile);
        if (outcome == Outcome.KEPT_NEWER || outcome == Outcome.UNCHANGED) return outcome;
        var becomesLive = existing == null || existing.isDeleted();
        if (becomesLive) {
            if (liveCount[0] >= RecordController.MAX_LIVE_ROWS_PER_TYPE) {
                problems.add("preference " + row.key() + ": skipped, this server holds the most preferences it keeps ("
                        + RecordController.MAX_LIVE_ROWS_PER_TYPE + ")");
                return Outcome.SKIPPED;
            }
            liveCount[0]++;
        }
        if (dryRun) return outcome;

        var payload = json.createObjectNode();
        payload.put("value", row.value());
        var record = existing == null ? new Record(key) : existing;
        record.setPayload(payload.toString());
        record.setDeleted(false);
        record.setUpdatedAt(inFile.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        record.setSyncVersion(versions.next());
        records.save(record);
        return outcome;
    }

    /** A question as a {@code question} record: payload keys in the format's order, {@code archived} only when true. */
    private Outcome mergeQuestion(BackupQuestion row, boolean dryRun, long[] liveCount, List<String> problems) {
        var inFile = clock.accept(Instant.ofEpochMilli(row.updatedAt()), "questions.updatedAt");
        var key = new RecordKey(BackupQuestion.TYPE, row.id());
        var existing = records.findById(key).orElse(null);
        var outcome = ImportReport.decide(existing == null ? null : existing.getUpdatedAt(), inFile);
        if (outcome == Outcome.KEPT_NEWER || outcome == Outcome.UNCHANGED) return outcome;
        var becomesLive = existing == null || existing.isDeleted();
        if (becomesLive) {
            // The bank's 100 (the apps' cap, S4b-BL-90b), counting what is here and what this file added before.
            if (liveCount[0] >= BackupQuestion.MAX) {
                problems.add("question " + row.id() + ": skipped, the question bank already holds " + BackupQuestion.MAX
                        + " questions, the most the apps keep");
                return Outcome.SKIPPED;
            }
            liveCount[0]++;
        }
        if (dryRun) return outcome;

        var payload = json.createObjectNode();
        payload.put("text", row.text());
        payload.put("category", row.category() == null ? "OTHER" : row.category());
        payload.put("appliesTo", row.appliesTo() == null ? "BOTH" : row.appliesTo());
        payload.put("defaultOn", Boolean.TRUE.equals(row.defaultOn()));
        payload.put("sort", row.sort() == null ? 0 : row.sort());
        if (Boolean.TRUE.equals(row.archived())) payload.put("archived", true);
        var record = existing == null ? new Record(key) : existing;
        record.setPayload(payload.toString());
        record.setDeleted(false);
        record.setUpdatedAt(inFile.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        record.setSyncVersion(versions.next());
        records.save(record);
        return outcome;
    }

    /**
     * A viewing as a {@code viewing} record: payload keys in the format's order, {@code huntReminder} only when true,
     * {@code withWhom}, {@code notes} and {@code visitId} only when set. A problem line never carries the row's text.
     */
    private Outcome mergeViewing(BackupViewing row, boolean dryRun, long[] liveCount, List<String> problems,
                                 Set<UUID> changedHouses) {
        var inFile = clock.accept(Instant.ofEpochMilli(row.updatedAt()), "viewings.updatedAt");
        var key = new RecordKey(BackupViewing.TYPE, row.id());
        var existing = records.findById(key).orElse(null);
        var outcome = ImportReport.decide(existing == null ? null : existing.getUpdatedAt(), inFile);
        if (outcome == Outcome.KEPT_NEWER || outcome == Outcome.UNCHANGED) return outcome;
        var becomesLive = existing == null || existing.isDeleted();
        if (becomesLive) {
            if (liveCount[0] >= RecordController.MAX_LIVE_ROWS_PER_TYPE) {
                problems.add("a viewing was skipped, this server holds the most viewings it keeps ("
                        + RecordController.MAX_LIVE_ROWS_PER_TYPE + ")");
                return Outcome.SKIPPED;
            }
            liveCount[0]++;
        }
        // A viewing is part of its house's AI document (S4b-BL-92d): that house, and the one it named before, change.
        houseOf(row.houseId(), changedHouses);
        if (existing != null && !existing.isDeleted()) {
            try {
                var before = json.readTree(existing.getPayload()).path("houseId");
                if (before.isString()) houseOf(before.asString(), changedHouses);
            } catch (RuntimeException e) {
                // A stored payload that does not read names no house.
            }
        }
        if (dryRun) return outcome;

        var payload = json.createObjectNode();
        payload.put("houseId", row.houseId());
        payload.put("startsAt", row.startsAt());
        payload.put("durationMin", row.durationMin() == null ? BackupViewing.DEFAULT_DURATION : row.durationMin());
        payload.put("kind", row.kind() == null ? "FIRST" : row.kind());
        payload.put("status", row.status() == null ? "PLANNED" : row.status());
        payload.put("remindMin", row.remindMin() == null ? 60 : row.remindMin());
        if (Boolean.TRUE.equals(row.huntReminder())) payload.put("huntReminder", true);
        if (row.withWhom() != null && !row.withWhom().isEmpty()) payload.put("withWhom", row.withWhom());
        if (row.notes() != null && !row.notes().isEmpty()) payload.put("notes", row.notes());
        if (row.visitId() != null && !row.visitId().isEmpty()) payload.put("visitId", row.visitId());
        var record = existing == null ? new Record(key) : existing;
        record.setPayload(payload.toString());
        record.setDeleted(false);
        record.setUpdatedAt(inFile.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        record.setSyncVersion(versions.next());
        records.save(record);
        return outcome;
    }

    /** Adds the house [id] names to [changed] when it is a UUID; a viewing may name anything. */
    private static void houseOf(String id, Set<UUID> changed) {
        if (id == null) return;
        try {
            changed.add(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            // Not a house id: nothing to re-index.
        }
    }

    /**
     * Merges one hunting area as an {@code area} record.
     */
    private Outcome mergeArea(BackupArea row, boolean dryRun, long[] liveCount, List<String> problems) {
        return mergeRecord(BackupArea.TYPE, "area", "areas", row.id(), row.updatedAt(), dryRun, liveCount, problems, () -> {
            var payload = json.createObjectNode();
            payload.put("name", row.name());
            payload.put("lat", row.lat());
            payload.put("lon", row.lon());
            payload.put("radiusM", row.radiusM() == null ? BackupArea.DEFAULT_RADIUS : row.radiusM());
            if (Boolean.FALSE.equals(row.enabled())) payload.put("enabled", false);
            return payload;
        });
    }

    /**
     * Merges one saved place as a {@code place} record.
     */
    private Outcome mergePlace(BackupPlace row, boolean dryRun, long[] liveCount, List<String> problems) {
        return mergeRecord(BackupPlace.TYPE, "place", "places", row.id(), row.updatedAt(), dryRun, liveCount, problems, () -> {
            var payload = json.createObjectNode();
            payload.put("name", row.name());
            payload.put("lat", row.lat());
            payload.put("lon", row.lon());
            return payload;
        });
    }

    /**
     * Merges one area note as an {@code areanote} record, keyed to an area or to a street.
     */
    private Outcome mergeAreaNote(BackupAreaNote row, boolean dryRun, long[] liveCount, List<String> problems) {
        return mergeRecord(BackupAreaNote.TYPE, "area note", "area notes", row.id(), row.updatedAt(), dryRun, liveCount,
                problems, () -> {
            var payload = json.createObjectNode();
            if (row.areaId() != null) payload.put("areaId", row.areaId());
            else payload.put("street", row.street());
            payload.put("text", row.text());
            return payload;
        });
    }

    /**
     * One record of a small type (slice 4a) by id, last write wins, never deleting; a newer row revives a tombstone.
     * A problem line never carries the row's text, nor its id.
     */
    private Outcome mergeRecord(String type, String one, String many, String id, Long updatedAt, boolean dryRun,
                                long[] liveCount, List<String> problems,
                                java.util.function.Supplier<tools.jackson.databind.node.ObjectNode> payloadOf) {
        var inFile = clock.accept(Instant.ofEpochMilli(updatedAt), many + ".updatedAt");
        var key = new RecordKey(type, id);
        var existing = records.findById(key).orElse(null);
        var outcome = ImportReport.decide(existing == null ? null : existing.getUpdatedAt(), inFile);
        if (outcome == Outcome.KEPT_NEWER || outcome == Outcome.UNCHANGED) return outcome;
        if (existing == null || existing.isDeleted()) {
            if (liveCount[0] >= RecordController.MAX_LIVE_ROWS_PER_TYPE) {
                problems.add("an " + one + " was skipped, this server holds the most " + many + " it keeps ("
                        + RecordController.MAX_LIVE_ROWS_PER_TYPE + ")");
                return Outcome.SKIPPED;
            }
            liveCount[0]++;
        }
        if (dryRun) return outcome;
        var record = existing == null ? new Record(key) : existing;
        record.setPayload(payloadOf.get().toString());
        record.setDeleted(false);
        record.setUpdatedAt(inFile.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        record.setSyncVersion(versions.next());
        records.save(record);
        return outcome;
    }

    /**
     * Merges one visit by id, last write wins. The house it lands on, and the one it left if it moved, are queued for
     * re-indexing. The caller has already checked the house exists.
     */
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
        if (!BackupFormat.accepts(data.format())) {
            // Nothing of the body is echoed, the format id included (SEC-015): an answer that changes with the
            // caller's text reads to a scanner as an injection (the ZAP API scan raised "SQL Injection" on this
            // very message, S4b-BL-89), and the app knows the format it wrote.
            if (BackupFormat.isNewer(data.format())) {
                throw new IllegalArgumentException("This backup is newer than this server reads"
                        + " (up to doorprints-backup/" + BackupFormat.MAX_VERSION + "): update the app");
            }
            throw new IllegalArgumentException("Not a " + BackupFormat.ID + " backup");
        }
        var problems = new ArrayList<String>();
        validateHouses(data.houses(), problems);
        validateVisits(data.visits(), problems);
        validatePhotos(data.photos(), problems);
        validateBrokers(data.brokers(), problems);
        validateCriteria(data.criteria(), problems);
        validatePreferences(data.preferences(), problems);
        validateQuestions(data.questions(), problems);
        validateViewings(data.viewings(), problems);
        validateAreas(data.areas(), problems);
        validatePlaces(data.places(), problems);
        validateAreaNotes(data.areaNotes(), problems);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Invalid backup: " + String.join("; ", capped(problems, "and %d more")));
        }
    }

    /**
     * Adds a problem for each house row that is missing, repeats an id, or has a value out of range: required fields,
     * lengths, coordinates, price, rating, floor, cost, rooms, answers, move-in, checklist and times.
     */
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
            require(row.brokerId() == null || row.brokerId().matches(RecordDto.ID_PATTERN),
                    at + ".brokerId is not a valid record id", problems);
            require(row.areaSqft() == null || (row.areaSqft() >= 1 && row.areaSqft() <= 100_000),
                    at + ".areaSqft must be 1..100000", problems);
            require(row.locationSource() == null || row.locationSource().matches(HouseDto.LOCATION_SOURCES),
                    at + ".locationSource must be GPS, MAP or APPROX", problems);
            if (row.cost() != null) {
                for (var field : row.cost().problems()) problems.add(at + ".cost." + field + " is out of range");
            }
            for (var problem : HouseRoom.problems(row.rooms())) problems.add(at + "." + problem);
            for (var problem : HouseAnswer.problems(row.answers())) problems.add(at + "." + problem);
            for (var problem : HouseMoveIn.problems(row.moveIn())) problems.add(at + "." + problem);
            require(row.floor() == null || (row.floor() >= House.MIN_FLOOR && row.floor() <= House.MAX_FLOOR),
                    at + ".floor must be -5..200", problems);
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

    /**
     * Adds a problem for each visit row with a missing or repeated id, bad coordinates, a missing source or time, or
     * a departure before the arrival.
     */
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

    /**
     * Adds a problem for each photo row with a missing or repeated id, no house, a bad time or edit, or a file name
     * that is not a plain name (no path separators or {@code ..}, so it can never point outside a backup folder).
     */
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
            problems.addAll(PhotoMeta.problems(at, row.roomId(), row.tags(), row.caption(), row.metaUpdatedAt()));
            if (row.metaUpdatedAt() != null && row.metaUpdatedAt() > 0) {
                try {
                    clock.validate(Instant.ofEpochMilli(row.metaUpdatedAt()), at + ".metaUpdatedAt");
                } catch (DateTimeException | ArithmeticException | IllegalArgumentException e) {
                    problems.add(at + ".metaUpdatedAt is out of range (check the device clock)"); // never the value
                }
            }
            var name = row.fileName();
            require(name != null && !name.isEmpty() && name.length() <= 200
                            && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && !name.contains(".."),
                    at + ".fileName must be a plain file name", problems);
        }
    }

    /**
     * Adds a problem for each broker row with a missing, invalid or repeated record id, no name, too-long text or a
     * rating outside 1..5.
     */
    private void validateBrokers(List<BackupBroker> rows, List<String> problems) {
        var seen = new HashSet<String>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var at = "brokers[" + i + "]";
            if (row == null) {
                problems.add(at + ": missing");
                continue;
            }
            if (row.id() == null) {
                problems.add(at + ".id is required");
            } else if (!row.id().matches(RecordDto.ID_PATTERN)) {
                problems.add(at + ".id is not a valid record id");
            } else if (!seen.add(row.id())) {
                problems.add(at + ".id " + row.id() + " appears twice");
            }
            require(row.name() != null && !row.name().isBlank(), at + ".name is required", problems);
            maxLength(at + ".name", row.name(), BackupBroker.MAX_NAME, problems);
            maxLength(at + ".phone", row.phone(), BackupBroker.MAX_PHONE, problems);
            maxLength(at + ".agency", row.agency(), BackupBroker.MAX_AGENCY, problems);
            maxLength(at + ".feeTerms", row.feeTerms(), BackupBroker.MAX_FEE_TERMS, problems);
            maxLength(at + ".notes", row.notes(), BackupBroker.MAX_NOTES, problems);
            require(row.rating() == null || (row.rating() >= 1 && row.rating() <= 5), at + ".rating must be 1..5",
                    problems);
            requireTime(at + ".updatedAt", row.updatedAt(), problems);
        }
    }

    /**
     * Adds a problem for each criterion row: key shape and uniqueness, weight and minimum score ranges, a label on a
     * built-in key, and the cap of 40 criteria.
     */
    private void validateCriteria(List<BackupCriterion> rows, List<String> problems) {
        var seen = new HashSet<String>();
        var builtInKeys = BackupCriterion.BUILT_IN_KEYS;
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var at = "criteria[" + i + "]";
            if (row == null) {
                problems.add(at + ": missing");
                continue;
            }
            // Key validation
            if (row.key() == null) {
                problems.add(at + ".key is required");
            } else if (!row.key().matches("[A-Za-z0-9._-]{1,64}")) {
                problems.add(at + ".key does not match pattern [A-Za-z0-9._-]{1,64}");
            } else if (!seen.add(row.key())) {
                problems.add(at + ".key " + row.key() + " appears twice");
            }
            // Weight validation
            require(row.weight() != null && row.weight() >= BackupCriterion.MIN_WEIGHT
                    && row.weight() <= BackupCriterion.MAX_WEIGHT,
                    at + ".weight must be " + BackupCriterion.MIN_WEIGHT + ".." + BackupCriterion.MAX_WEIGHT, problems);
            // MinScore validation
            require(row.minScore() != null && row.minScore() >= BackupCriterion.MIN_SCORE
                    && row.minScore() <= BackupCriterion.MAX_SCORE,
                    at + ".minScore must be " + BackupCriterion.MIN_SCORE + ".." + BackupCriterion.MAX_SCORE, problems);
            // Sort validation
            require(row.sort() == null || row.sort() >= 0, at + ".sort must not be negative", problems);
            // Label validation
            var isBuiltIn = row.key() != null && builtInKeys.contains(row.key());
            if (isBuiltIn && row.label() != null) {
                problems.add(at + ".label is not allowed for built-in key " + row.key());
            }
            maxLength(at + ".label", row.label(), BackupCriterion.MAX_LABEL, problems);
            // MustHave validation
            require(row.mustHave() != null, at + ".mustHave is required", problems);
            // RequireTime for updatedAt
            requireTime(at + ".updatedAt", row.updatedAt(), problems);
        }
        // Cap at 40 criteria
        require(rows.size() <= 40, "criteria: at most 40 criteria allowed, found " + rows.size(), problems);
    }

    /**
     * Adds a problem for each preference row with a bad or repeated key, a missing or too-long value, or a bad time.
     */
    private void validatePreferences(List<BackupPreference> rows, List<String> problems) {
        var seen = new HashSet<String>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var at = "preferences[" + i + "]";
            if (row == null) {
                problems.add(at + ": missing");
                continue;
            }
            // Key validation
            if (row.key() == null) {
                problems.add(at + ".key is required");
            } else if (!row.key().matches("[A-Za-z0-9._-]{1,64}")) {
                problems.add(at + ".key does not match pattern [A-Za-z0-9._-]{1,64}");
            } else if (!seen.add(row.key())) {
                problems.add(at + ".key " + row.key() + " appears twice");
            }
            // Value validation
            require(row.value() != null, at + ".value is required", problems);
            maxLength(at + ".value", row.value(), BackupPreference.MAX_VALUE, problems);
            // RequireTime for updatedAt
            requireTime(at + ".updatedAt", row.updatedAt(), problems);
        }
    }

    /** Question text is the person's own, so a message names the row by its index, never by its content. */
    private void validateQuestions(List<BackupQuestion> rows, List<String> problems) {
        var seen = new HashSet<String>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var at = "questions[" + i + "]";
            if (row == null) {
                problems.add(at + ": missing");
                continue;
            }
            if (row.id() == null) {
                problems.add(at + ".id is required");
            } else if (!row.id().matches(RecordDto.ID_PATTERN)) {
                problems.add(at + ".id is not a valid record id");
            } else if (!seen.add(row.id())) {
                problems.add(at + ".id appears twice");
            }
            require(row.text() != null && !row.text().isBlank(), at + ".text is required", problems);
            maxLength(at + ".text", row.text(), BackupQuestion.MAX_TEXT, problems);
            require(row.category() == null || BackupQuestion.CATEGORIES.contains(row.category()),
                    at + ".category is out of range", problems);
            require(row.appliesTo() == null || BackupQuestion.SCOPES.contains(row.appliesTo()),
                    at + ".appliesTo is out of range", problems);
            require(row.sort() == null || row.sort() >= 0, at + ".sort must not be negative", problems);
            requireTime(at + ".updatedAt", row.updatedAt(), problems);
        }
        require(rows.size() <= BackupQuestion.MAX,
                "questions: at most " + BackupQuestion.MAX + " questions allowed, found " + rows.size(), problems);
    }

    /** A viewing's notes and "with whom" are the person's own, so a message names the row by its index, never by its content. */
    private void validateViewings(List<BackupViewing> rows, List<String> problems) {
        var seen = new HashSet<String>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var at = "viewings[" + i + "]";
            if (row == null) {
                problems.add(at + ": missing");
                continue;
            }
            if (row.id() == null) {
                problems.add(at + ".id is required");
            } else if (!row.id().matches(RecordDto.ID_PATTERN)) {
                problems.add(at + ".id is not a valid record id");
            } else if (!seen.add(row.id())) {
                problems.add(at + ".id appears twice");
            }
            require(row.houseId() != null && !row.houseId().isBlank(), at + ".houseId is required", problems);
            maxLength(at + ".houseId", row.houseId(), BackupViewing.MAX_REF, problems);
            require(row.startsAt() != null && row.startsAt() > 0, at + ".startsAt must be a positive time", problems);
            require(row.durationMin() == null || (row.durationMin() >= BackupViewing.MIN_DURATION
                    && row.durationMin() <= BackupViewing.MAX_DURATION),
                    at + ".durationMin must be " + BackupViewing.MIN_DURATION + ".." + BackupViewing.MAX_DURATION, problems);
            require(row.kind() == null || BackupViewing.KINDS.contains(row.kind()), at + ".kind is out of range", problems);
            require(row.status() == null || BackupViewing.STATUSES.contains(row.status()),
                    at + ".status is out of range", problems);
            require(row.remindMin() == null || BackupViewing.REMINDERS.contains(row.remindMin()),
                    at + ".remindMin is out of range", problems);
            maxLength(at + ".withWhom", row.withWhom(), BackupViewing.MAX_WITH_WHOM, problems);
            maxLength(at + ".notes", row.notes(), BackupViewing.MAX_NOTES, problems);
            maxLength(at + ".visitId", row.visitId(), BackupViewing.MAX_REF, problems);
            requireTime(at + ".updatedAt", row.updatedAt(), problems);
        }
        require(rows.size() <= BackupViewing.MAX,
                "viewings: at most " + BackupViewing.MAX + " viewings allowed, found " + rows.size(), problems);
    }

    /** A record id like the viewings': a bad or repeated one refuses the file. Messages name the row by its index only. */
    private void requireRecordId(String at, String id, Set<String> seen, List<String> problems) {
        if (id == null) {
            problems.add(at + ".id is required");
        } else if (!id.matches(RecordDto.ID_PATTERN)) {
            problems.add(at + ".id is not a valid record id");
        } else if (!seen.add(id)) {
            problems.add(at + ".id appears twice");
        }
    }

    /**
     * Requires a non-blank name within the length limit.
     */
    private void requireName(String at, String name, int max, List<String> problems) {
        require(name != null && !name.isBlank(), at + ".name is required", problems);
        maxLength(at + ".name", name, max, problems);
    }

    /**
     * Adds a problem for each area row: id, name, coordinates, radius and time, and the cap on the number of areas.
     */
    private void validateAreas(List<BackupArea> rows, List<String> problems) {
        var seen = new HashSet<String>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var at = "areas[" + i + "]";
            if (row == null) {
                problems.add(at + ": missing");
                continue;
            }
            requireRecordId(at, row.id(), seen, problems);
            requireName(at, row.name(), BackupArea.MAX_NAME, problems);
            requireCoordinates(at, row.lat(), row.lon(), problems);
            require(row.radiusM() == null || (row.radiusM() >= BackupArea.MIN_RADIUS
                    && row.radiusM() <= BackupArea.MAX_RADIUS),
                    at + ".radiusM must be " + BackupArea.MIN_RADIUS + ".." + BackupArea.MAX_RADIUS, problems);
            requireTime(at + ".updatedAt", row.updatedAt(), problems);
        }
        require(rows.size() <= BackupArea.MAX,
                "areas: at most " + BackupArea.MAX + " areas allowed, found " + rows.size(), problems);
    }

    /**
     * Adds a problem for each place row: id, name, coordinates and time, and the cap on the number of places.
     */
    private void validatePlaces(List<BackupPlace> rows, List<String> problems) {
        var seen = new HashSet<String>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var at = "places[" + i + "]";
            if (row == null) {
                problems.add(at + ": missing");
                continue;
            }
            requireRecordId(at, row.id(), seen, problems);
            requireName(at, row.name(), BackupPlace.MAX_NAME, problems);
            requireCoordinates(at, row.lat(), row.lon(), problems);
            requireTime(at + ".updatedAt", row.updatedAt(), problems);
        }
        require(rows.size() <= BackupPlace.MAX,
                "places: at most " + BackupPlace.MAX + " places allowed, found " + rows.size(), problems);
    }

    /** A note's text and street are the person's own, so a message names the row by its index, never by its content. */
    private void validateAreaNotes(List<BackupAreaNote> rows, List<String> problems) {
        var seen = new HashSet<String>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var at = "areaNotes[" + i + "]";
            if (row == null) {
                problems.add(at + ": missing");
                continue;
            }
            requireRecordId(at, row.id(), seen, problems);
            require((row.areaId() == null) != (row.street() == null),
                    at + " needs exactly one of areaId and street", problems);
            require(row.areaId() == null || !row.areaId().isBlank(), at + ".areaId must not be blank", problems);
            maxLength(at + ".areaId", row.areaId(), BackupAreaNote.MAX_REF, problems);
            require(row.street() == null || !row.street().isBlank(), at + ".street must not be blank", problems);
            maxLength(at + ".street", row.street(), BackupAreaNote.MAX_STREET, problems);
            require(row.text() != null && !row.text().isBlank(), at + ".text is required", problems);
            maxLength(at + ".text", row.text(), BackupAreaNote.MAX_TEXT, problems);
            requireTime(at + ".updatedAt", row.updatedAt(), problems);
        }
        require(rows.size() <= BackupAreaNote.MAX,
                "areaNotes: at most " + BackupAreaNote.MAX + " notes allowed, found " + rows.size(), problems);
    }

    /**
     * Requires a present house, visit or photo id that does not repeat within the file.
     */
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

    /**
     * Adds a problem when a present text is longer than the limit.
     */
    private void maxLength(String at, String value, int max, List<String> problems) {
        require(value == null || value.length() <= max, at + " is longer than " + max + " characters", problems);
    }

    /**
     * Adds the problem line when the condition is false.
     */
    private void require(boolean ok, String problem, List<String> problems) {
        if (!ok) problems.add(problem);
    }
}
