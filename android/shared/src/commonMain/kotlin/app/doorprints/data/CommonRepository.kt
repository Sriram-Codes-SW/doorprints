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

package app.doorprints.data

import app.doorprints.data.Repository.ImportResult
import app.doorprints.data.Repository.LocalRows
import app.doorprints.data.Repository.LocalVersions
import app.doorprints.data.Repository.StreetInfo
import app.doorprints.data.Repository.UndoResult
import app.doorprints.export.CopyUndo
import app.doorprints.shared.ai.AiHouse
import app.doorprints.shared.ai.AiVisit
import app.doorprints.shared.ai.AiProviderConfig
import app.doorprints.shared.ai.AiQuality
import app.doorprints.shared.ai.BaseUrlValidator
import app.doorprints.shared.ai.GeminiClient
import app.doorprints.shared.ai.JsonChatModel
import app.doorprints.shared.api.ApiClient
import app.doorprints.shared.api.AskResponseDto
import app.doorprints.shared.api.HouseDraftDto
import app.doorprints.shared.api.IsoTime
import app.doorprints.shared.api.PairPolledDto
import app.doorprints.shared.api.PairStartedDto
import app.doorprints.shared.api.PlanRequest
import app.doorprints.shared.api.PlanResponseDto
import app.doorprints.shared.api.StatsDto
import app.doorprints.shared.export.BackupValidation
import app.doorprints.shared.export.ExportCriterion
import app.doorprints.shared.export.ExportPreference
import app.doorprints.shared.export.ExportQuestion
import app.doorprints.shared.export.ExportViewing
import app.doorprints.shared.export.ExportArea
import app.doorprints.shared.export.ExportAreaNote
import app.doorprints.shared.export.ExportPlace
import app.doorprints.shared.export.ImportActions
import app.doorprints.shared.export.ImportMode
import app.doorprints.shared.model.Broker
import app.doorprints.shared.model.BrokerType
import app.doorprints.shared.model.Checklist
import app.doorprints.shared.model.Criterion
import app.doorprints.shared.model.CriterionType
import app.doorprints.shared.model.Preference
import app.doorprints.shared.model.PreferenceType
import app.doorprints.shared.model.Scoring
import app.doorprints.shared.model.HouseAnswer
import app.doorprints.shared.model.HouseAnswers
import app.doorprints.shared.model.HouseRooms
import app.doorprints.shared.model.HouseValues
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.HouseStatusRules
import app.doorprints.shared.model.MoveIn
import app.doorprints.shared.model.PhotoMeta
import app.doorprints.shared.model.StatusHouse
import app.doorprints.shared.model.Question
import app.doorprints.shared.model.QuestionCategory
import app.doorprints.shared.model.QuestionScope
import app.doorprints.shared.model.QuestionType
import app.doorprints.shared.model.Viewing
import app.doorprints.shared.model.ViewingType
import app.doorprints.shared.model.Area
import app.doorprints.shared.model.AreaNote
import app.doorprints.shared.model.AreaNoteType
import app.doorprints.shared.model.AreaNotes
import app.doorprints.shared.model.AreaType
import app.doorprints.shared.model.Distances
import app.doorprints.shared.model.HousePoint
import app.doorprints.shared.model.Place
import app.doorprints.shared.model.PlaceType
import app.doorprints.shared.ai.AiAreaNote
import app.doorprints.shared.ai.AiDistance
import app.doorprints.shared.ai.AiViewing
import app.doorprints.shared.model.VisitSource
import app.doorprints.shared.records.RecordLimitException
import app.doorprints.shared.records.RecordRules
import app.doorprints.shared.records.RecordType
import app.doorprints.shared.records.decode
import app.doorprints.shared.sync.SyncOutcome
import app.doorprints.shared.sync.SyncRules
import app.doorprints.shared.trace.TracePoint
import app.doorprints.shared.trace.TraceWalk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The [Repository] both apps' platforms share (S4b-BL-32, before the iOS shell, CMP-8): the Room database, the sync
 * with the optional server, the offline copy's reads and writes, the import's merge and copy and their undo. Moved
 * from `:app`'s `AndroidRepository` unchanged in behaviour; what needs the platform comes in through the constructor.
 *
 * - [photoDir]: the folder the photo files live in (Android: `filesDir/photos`); the files and the photo rows' local
 *   edits are [PhotoStore]'s (S4b-BL-168), and a photo row's file is found from its id ([photoFileOf]; S4b-BL-52).
 * - [syncSoon]: asks for a sync shortly (Android: `SyncWorker.syncSoon`, a WorkManager job).
 * - [apiFor]: the API client for a server address and key (Android: the app-wide HTTP stack; a test's fake engine).
 * - `syncBackendFor`: where [sync] goes for the current settings, or null when nothing is set up (S4b-BL-70); left
 *   out, the configured server ([ServerSyncBackend] over [apiFor]); a test's fake. It suspends so the Drive route can read
 *   the running pass's backend from the coroutine context (`DriveSyncRoute`, common code for both phones).
 *
 * A platform subclass adds what has no common form yet: on Android, adding a photo from a `Uri` (`AndroidRepository`).
 */
@OptIn(ExperimentalUuidApi::class)
open class CommonRepository(
    protected val db: AppDatabase,
    override val settings: SettingsStore,
    private val photoDir: String,
    private val syncSoon: () -> Unit,
    private val apiFor: (serverUrl: String, apiKey: String) -> ApiClient,
    /** Gemini with the person's own key, for on-device AI (docs/03 §13.1); null where a platform has none (tests). */
    geminiFor: ((apiKey: String, quality: AiQuality) -> GeminiClient)? = null,
    syncBackendFor: (suspend (AppSettings) -> SyncBackend?)? = null,
    /** An OpenAI-compatible endpoint with the person's own key, for on-device AI (docs/03 §13.2); null where a platform has none. */
    openAiFor: ((baseUrl: String, model: String, apiKey: String) -> JsonChatModel)? = null,
    /** Anthropic with the person's own key, for on-device AI (docs/03 §13.2); null where a platform has none. */
    anthropicFor: ((baseUrl: String, model: String, apiKey: String) -> JsonChatModel)? = null,
    /** True on Android only: the emulator's name for its computer, `10.0.2.2`, may be an http base URL ([BaseUrlValidator]). */
    emulatorHostAllowed: Boolean = false,
) : Repository {
    private val syncBackendFor: suspend (AppSettings) -> SyncBackend? = syncBackendFor
        ?: { s -> if (s.serverConfigured) ServerSyncBackend(apiFor(s.serverUrl, s.apiKey)) else null }

    /** The clock every local edit is stamped with. */
    protected fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private val photoStore = PhotoStore(db, photoDir, ::now, syncSoon)

    protected fun photoDirPath(): Path = photoStore.dirPath()

    // Every read of the houses and the records waits for the once-only move of contacts into brokers (slice 1b), so
    // no screen ever sees the houses before it: the flag makes every later call a plain read.
    override val houses: Flow<List<HouseEntity>> = flow {
        brokerStore.migrate()
        emitAll(db.houses().observeAll())
    }
    override val visitCounts = db.visits().observeCounts()

    // The trace's window moves with the clock, so the flow is rebuilt each time it is collected (a screen's lifetime).
    override val trackPoints: Flow<List<TrackPointEntity>>
        get() = db.track().observeSince(now() - Repository.TRACK_KEPT_MS)

    override suspend fun saveTrackPoint(point: TrackPointEntity) = db.track().insert(point)

    override suspend fun pruneTrack(before: Long) = db.track().deleteBefore(before)

    override suspend fun clearTrack() = db.track().deleteAll()

    // Saved walks and the walks the Map, the alert and the place check read (docs/11 5.27.6). Local only: the walk
    // tables are in no export, backup or sync, and `localTablesChanged` does not list them.
    private val walkStore = WalkStore(db, ::now)

    override val savedWalkCount: Flow<Int> get() = walkStore.savedWalkCount()
    override fun savedWalksOf(houseId: String): Flow<List<SavedWalkSummary>> = walkStore.savedWalksOf(houseId)
    override suspend fun saveWalk(houseId: String, walkId: Long): SaveWalkResult = walkStore.saveWalk(houseId, walkId)
    override suspend fun deleteTraceWalk(walkId: Long) = walkStore.deleteTraceWalk(walkId)
    override suspend fun deleteSavedWalk(id: String) = walkStore.deleteSavedWalk(id)
    override suspend fun deleteAllSavedWalks() = walkStore.deleteAllSavedWalks()
    override suspend fun savedWalkPoints(id: String): List<TracePoint>? = walkStore.savedWalkPoints(id)
    override suspend fun lastEndedWalk(liveWalkId: Long): Long? =
        walkStore.lastEndedWalk(settings.current().walkAskedUpTo, liveWalkId)
    override suspend fun walks(): List<TraceWalk> = walkStore.walks(now() - Repository.TRACK_KEPT_MS)
    override suspend fun walksOtherThan(liveWalkId: Long): List<List<TracePoint>> =
        walkStore.walksOtherThan(liveWalkId, now() - Repository.TRACK_KEPT_MS)
    override fun placeWalks(): Flow<TraceWalk> = walkStore.placeWalks(now() - Repository.TRACK_KEPT_MS)

    override fun walksChanged(): Flow<Unit> = walkStore.changes()
    override suspend fun sweepWalksOfDeletedHouses() = walkStore.sweep()

    override fun house(id: String): Flow<HouseEntity?> = flow {
        brokerStore.migrate()
        emitAll(db.houses().observe(id))
    }
    override fun visitsFor(houseId: String) = db.visits().observeForHouse(houseId)
    override fun photosFor(houseId: String) = photoStore.observeForHouse(houseId)

    override suspend fun houseSnapshot(): List<HouseEntity> {
        brokerStore.migrate()
        return db.houses().all()
    }

    override suspend fun getHouse(id: String): HouseEntity? {
        brokerStore.migrate()
        return db.houses().get(id)
    }

    override suspend fun saveHouse(house: HouseEntity) {
        // The rooms, answers and move-in as every reader keeps them (slices 1c, 3a, 5): the form's blank names and notes
        // go, a typed answer reads ANSWERED, the order is the one shown; a floor outside -5..200 is unknown (S4b-BL-87).
        val row = brokerStore.linked(house).copy(
            rooms = HouseRooms.coerced(house.rooms), answers = HouseAnswers.coerced(house.answers),
            moveIn = MoveIn.coerced(house.moveIn), floor = HouseValues.floor(house.floor), updatedAt = now(), dirty = true,
        )
        if (row.status == HouseStatus.TAKEN && !row.deleted) {
            // At most one house is TAKEN (slice 5, M1): the one that was returns to SHORTLISTED, in the same transaction.
            db.withImmediateTransaction {
                val others = db.houses().all().filter { it.id != row.id }
                val chosen = HouseStatusRules.choose(
                    others.map { StatusHouse(it.id, it.status) } + StatusHouse(row.id, row.status), row.id, HouseStatus.TAKEN,
                ).associate { it.id to it.status }
                val stamp = row.updatedAt
                for (h in others) {
                    if (chosen[h.id] != h.status) {
                        db.houses().upsert(h.copy(status = chosen.getValue(h.id), updatedAt = stamp, dirty = true))
                    }
                }
                db.houses().upsert(row)
            }
        } else {
            db.houses().upsert(row)
        }
        syncSoon()
    }

    override suspend fun markOthersNotChosen(takenId: String): Int {
        val count = db.withImmediateTransaction {
            val all = db.houses().all()
            val targets = HouseStatusRules.closeTargets(all.map { StatusHouse(it.id, it.status) }, takenId).toHashSet()
            val stamp = now()
            for (h in all) if (h.id in targets) db.houses().upsert(h.copy(status = HouseStatus.NOT_CHOSEN, updatedAt = stamp, dirty = true))
            targets.size
        }
        if (count > 0) syncSoon()
        return count
    }

    override suspend fun closeTargetCount(takenId: String): Int =
        HouseStatusRules.closeTargets(db.houses().all().map { StatusHouse(it.id, it.status) }, takenId).size

    override suspend fun savePhotoMeta(photoId: String, meta: PhotoMeta): Boolean = photoStore.saveMeta(photoId, meta)

    // ---- Brokers (docs/11 5.25, slice 1b): the rules are BrokerStore's (S4b-BL-168) ----

    // The record kinds' shared writer: it checks the id and the size, stamps the row and asks for a sync.
    private val recordWriter = object : RecordWriter {
        override suspend fun <T> save(type: RecordType<T>, id: String, value: T) = saveRecord(type, id, value)

        override suspend fun delete(type: RecordType<*>, id: String) = deleteRecord(type, id)
    }

    private val brokerStore = BrokerStore(db, recordWriter, settings, ::now, syncSoon)

    override fun observeBrokers(): Flow<List<Pair<String, Broker>>> = brokerStore.observeAll()

    override suspend fun saveBroker(broker: Broker, id: String?): String = brokerStore.save(broker, id)

    override suspend fun deleteBroker(id: String) = brokerStore.delete(id)

    override fun brokerHouses(id: String): Flow<List<HouseEntity>> = brokerStore.housesOf(id)

    /** The once-only move of contacts into brokers (slice 1b), on the first read after the update: [BrokerStore.migrate]. */
    suspend fun migrateContactsToBrokers() = brokerStore.migrate()

    // ---- Criteria and ranking (docs/11 5.4, slice 2): the rules are CriterionStore's (S4b-BL-168) ----

    private val criterionStore = CriterionStore(db, recordWriter)

    override fun observeScoring(): Flow<Scoring> = criterionStore.observeScoring()

    override suspend fun scoring(): Scoring = criterionStore.scoring()

    override suspend fun saveCriterion(criterion: Criterion) = criterionStore.save(criterion)

    override suspend fun saveCriteria(criteria: List<Criterion>) = criterionStore.saveAll(criteria)

    override suspend fun addCriterion(label: String, weight: Int): String = criterionStore.add(label, weight)

    override suspend fun deleteCriterion(key: String): Boolean = criterionStore.delete(key)

    override suspend fun saveRatingShare(share: Double) = criterionStore.saveRatingShare(share)

    override suspend fun resetScoring() = criterionStore.reset()

    // ---- The question bank (docs/11 5.5, slice 3a): the rules are QuestionStore's (S4b-BL-168) ----

    private val questionStore = QuestionStore(db, recordWriter, settings)

    override fun observeQuestions(): Flow<List<Question>> = questionStore.observeAll()

    override suspend fun questions(): List<Question> = questionStore.all()

    override suspend fun seedQuestions(language: String): Int = questionStore.seed(language)

    override suspend fun seedQuestionsOnce(language: String) = questionStore.seedOnce(language)

    override suspend fun resetQuestions(language: String) = questionStore.reset(language)

    override suspend fun saveQuestion(question: Question) = questionStore.save(question)

    override suspend fun saveQuestions(questions: List<Question>) = questionStore.saveAll(questions)

    override suspend fun addQuestion(
        text: String,
        category: QuestionCategory,
        appliesTo: QuestionScope,
        defaultOn: Boolean,
    ): String = questionStore.add(text, category, appliesTo, defaultOn)

    override suspend fun deleteQuestion(id: String) = questionStore.delete(id)

    // A house's answers are a field of the house, saved with it.
    override suspend fun saveAnswers(houseId: String, answers: List<HouseAnswer>?) {
        val house = db.houses().get(houseId)?.takeUnless { it.deleted } ?: return
        saveHouse(house.copy(answers = answers))
    }

    // ---- Viewings (docs/11 5.8, slice 3b-1): the rules are ViewingStore's (S4b-BL-168) ----

    private val viewingStore = ViewingStore(
        db,
        saveRecord = { id, viewing -> saveRecord(ViewingType, id, viewing) },
        deleteRecord = { id -> deleteRecord(ViewingType, id) },
    )

    override fun observeViewings(): Flow<List<Viewing>> = viewingStore.observeAll()

    override suspend fun viewings(): List<Viewing> = viewingStore.all()

    override suspend fun viewingIdsForReminders(): List<String> = viewingStore.idsForReminders()

    override suspend fun viewingsOf(houseId: String): List<Viewing> = viewingStore.ofHouse(houseId)

    override suspend fun nextViewing(houseId: String, nowMs: Long): Viewing? = viewingStore.next(houseId, nowMs)

    override suspend fun getViewing(id: String): Viewing? = viewingStore.get(id)

    override suspend fun saveViewing(viewing: Viewing) = viewingStore.save(viewing)

    override suspend fun newViewingId(): String = viewingStore.newId()

    override suspend fun deleteViewing(id: String) = viewingStore.delete(id)

    override suspend fun markViewingDone(id: String, visitId: String?) = viewingStore.markDone(id, visitId)

    // ---- Hunting areas, my places and area notes (docs/11 slice 4a): the rules are AreaStore's (S4b-BL-168) ----

    private val areaStore = AreaStore(db, recordWriter)

    override fun observeAreas(): Flow<List<Area>> = areaStore.observeAreas()
    override suspend fun areas(): List<Area> = areaStore.areas()
    override fun observePlaces(): Flow<List<Place>> = areaStore.observePlaces()
    override suspend fun places(): List<Place> = areaStore.places()
    override fun observeAreaNotes(): Flow<List<AreaNote>> = areaStore.observeNotes()
    override suspend fun areaNotes(): List<AreaNote> = areaStore.notes()

    override suspend fun saveArea(area: Area) = areaStore.saveArea(area)
    override suspend fun savePlace(place: Place) = areaStore.savePlace(place)
    override suspend fun saveAreaNote(note: AreaNote) = areaStore.saveNote(note)

    override suspend fun newAreaId(): String = areaStore.newAreaId()
    override suspend fun newPlaceId(): String = areaStore.newPlaceId()
    override suspend fun newAreaNoteId(): String = areaStore.newNoteId()

    override suspend fun deleteArea(id: String) {
        areaStore.deleteArea(id)
        // The wake-up's per-area stamp (slice 4b) goes with it; a delete by a sync is pruned when the geofences are set.
        settings.removeAreaLastNotified(id)
    }
    override suspend fun deletePlace(id: String) = areaStore.deletePlace(id)
    override suspend fun deleteAreaNote(id: String) = areaStore.deleteNote(id)

    override suspend fun deleteHouse(id: String) {
        val house = db.houses().get(id) ?: return
        saveHouse(house.copy(deleted = true))
    }

    override suspend fun saveVisit(visit: VisitEntity) {
        db.visits().upsert(visit.copy(updatedAt = now(), dirty = true))
        syncSoon()
    }

    override suspend fun getVisit(id: String) = db.visits().get(id)

    override suspend fun deleteVisit(id: String) {
        val visit = db.visits().get(id) ?: return
        saveVisit(visit.copy(deleted = true))
    }

    /** Records a "been here" visit for a house, now. */
    override suspend fun markVisitedNow(house: HouseEntity) {
        val now = now()
        saveVisit(
            VisitEntity(
                id = Uuid.random().toString(), houseId = house.id, lat = house.lat, lon = house.lon,
                street = house.street, arrivedAt = now, source = VisitSource.MANUAL, updatedAt = now,
            )
        )
    }

    override suspend fun streetInfo(street: String) = StreetInfo(
        street,
        db.houses().countOnStreet(street),
        db.visits().countOnStreet(street),
        db.visits().firstOnStreet(street),
    )

    override suspend fun deletePhoto(photo: PhotoEntity): Unit = photoStore.delete(photo)

    override fun <T> observeRecords(type: RecordType<T>): Flow<List<Pair<String, T>>> =
        db.records().byType(type.name).map { rows -> rows.mapNotNull { row -> row.decode(type)?.let { row.id to it } } }
            .onStart { brokerStore.migrate() }

    override suspend fun <T> saveRecord(type: RecordType<T>, id: String, value: T) {
        require(RecordRules.isValidId(id)) { "record id '$id' is not [A-Za-z0-9._-]{1,64}" }
        val payload = type.encode(value)
        require(RecordRules.fitsPayload(payload)) { "record payload over ${RecordRules.MAX_PAYLOAD_BYTES} bytes" }
        // The cap counts live rows: writing over one, or reviving a tombstone, adds none.
        val existing = db.records().get(type.name, id)
        if (existing?.deleted != false && db.records().countLive(type.name) >= RecordRules.MAX_ROWS_PER_TYPE) {
            throw RecordLimitException(type.name, RecordRules.MAX_ROWS_PER_TYPE)
        }
        db.records().upsert(RecordEntity(type = type.name, id = id, payload = payload, updatedAt = now()))
        syncSoon()
    }

    override suspend fun deleteRecord(type: RecordType<*>, id: String) {
        val record = db.records().get(type.name, id) ?: return
        if (record.deleted) return
        db.records().upsert(record.copy(payload = "{}", updatedAt = now(), deleted = true, dirty = true))
        syncSoon()
    }

    /**
     * Calls `/api/stats` with the saved address and key on the IO dispatcher; the outcome, success or failure, is
     * the result.
     */
    override suspend fun testConnection(): Result<StatsDto> = withContext(Dispatchers.IO) {
        val s = settings.current()
        runCatching { apiFor(s.serverUrl, s.apiKey).stats() }
    }

    // ---- AI features (optional; hidden unless the server reports enabled = true): AiStore (S4b-BL-168) ----

    private val ai = AiStore(settings, apiFor, geminiFor, openAiFor, anthropicFor, emulatorHostAllowed, ::aiHouses)

    override val aiEnabled: StateFlow<Boolean> = ai.enabled
    override val aiOff: StateFlow<AiOff?> = ai.off

    override suspend fun refreshAiStatus(): Boolean = ai.refreshStatus()

    override suspend fun setAiFeatures(on: Boolean) = ai.setFeatures(on)

    override suspend fun saveGeminiKey(key: String) = ai.saveKey(key)

    override suspend fun setAiProvider(choice: AiProviderChoice) = ai.setProvider(choice)

    override suspend fun removeGeminiKey() = ai.removeKey()

    override suspend fun setAiQuality(quality: AiQuality) = ai.setQuality(quality)

    override suspend fun testGeminiKey(key: String): Result<Unit> = ai.testProvider(AiProviderConfig.GEMINI, key)

    override suspend fun saveAiProviderConfig(config: AiProviderConfig, key: String) = ai.saveConfig(config, key)

    override suspend fun testAiProvider(config: AiProviderConfig, key: String): Result<Unit> = ai.testProvider(config, key)

    /** The saved houses and their visits as on-device AI reads them, most recently changed first. */
    private suspend fun aiHouses(): List<AiHouse> {
        val visits = db.visits().all().groupBy { it.houseId }
        val viewings = viewings().groupBy { it.houseId }
        val areas = areas()
        val notes = areaNotes()
        val places = places()
        return db.houses().all().sortedByDescending { it.updatedAt }.map { h ->
            val point = HousePoint(h.lat, h.lon, h.street, h.locationSource)
            AiHouse(
                id = h.id, label = h.label, address = h.address, street = h.street, locality = h.locality,
                lat = h.lat, lon = h.lon, status = h.status.name, price = h.price, priceType = h.priceType,
                bedrooms = h.bedrooms, rating = h.rating, contactName = h.contactName, contactPhone = h.contactPhone,
                listingUrl = h.listingUrl, notes = h.notes, areaSqft = h.areaSqft, cost = h.cost, rooms = h.rooms,
                answers = h.answers, moveIn = h.moveIn, floor = h.floor, checklist = h.checklist,
                visits = visits[h.id].orEmpty().map { AiVisit(it.arrivedAt, it.leftAt) },
                viewings = viewings[h.id].orEmpty().map { AiViewing(it.id, it.startsAt, it.kind, it.status, it.notes) },
                areaNotes = AreaNotes.reaching(point, areas, notes).map { AiAreaNote(it.id, it.text, it.updatedAt) },
                distances = Distances.toPlaces(point, places).map { AiDistance(it.place.name, it.meters) },
            )
        }
    }

    override suspend fun extractListing(text: String): HouseDraftDto = ai.extractListing(text)

    override suspend fun ask(question: String): AskResponseDto = ai.ask(question)

    override suspend fun planVisits(request: PlanRequest): PlanResponseDto = ai.planVisits(request)

    // ---- Pairing (docs/03 §12.1): no key yet, so the client is made without one ----

    override suspend fun startPairing(serverUrl: String, deviceName: String): PairStartedDto =
        withContext(Dispatchers.IO) { apiFor(serverUrl, "").pairStart(deviceName) }

    override suspend fun pollPairing(serverUrl: String, pollToken: String): PairPolledDto =
        withContext(Dispatchers.IO) { apiFor(serverUrl, "").pairPoll(pollToken) }

    override suspend fun redeemInvite(link: ConnectLink, deviceName: String): String =
        withContext(Dispatchers.IO) { apiFor(link.server, "").pairRedeem(link.invite, deviceName).deviceKey }


    /**
     * Two-way sync: push local changes, then pull everything the remote has changed since last time, through the
     * [SyncBackend] for the current settings (S4b-BL-70; the server's is [ServerSyncBackend]). A pulled row meets the
     * local one by the backend's [SyncBackend.mergeRule] (the server's: "last edit wins" by updatedAt, a clean local
     * row always takes the server's). Houses and visits always sync; photo transfers only when [photosAllowed] (the
     * caller checks for an unmetered network when the user asked for Wi-Fi only). A remote found behind this phone
     * (S4b-BL-20: [SyncBackend.isBehind], or a push answered with a version at or below a stored cursor) gets
     * everything again and is pulled from 0, and the outcome says so ([SyncOutcome.remoteReset]). Throws on failure;
     * see [SyncOutcome.fromError].
     */
    override suspend fun sync(photosAllowed: Boolean): SyncOutcome =
        try {
            syncPass(photosAllowed)
        } finally {
            sweepWalksOfDeletedHouses() // sync end: a house tombstone that arrived deletes its saved walks (docs/11 5.27.6)
        }

    /**
     * One sync pass for [sync]: push the dirty rows, then pull what the remote changed, behind the settings'
     * [SyncBackend]. When the remote is found behind this phone everything is pushed again and the pull restarts
     * from 0.
     */
    private suspend fun syncPass(photosAllowed: Boolean): SyncOutcome = withContext(Dispatchers.IO) {
        val s = settings.current()
        val backend = syncBackendFor(s) ?: return@withContext SyncOutcome(SyncOutcome.Kind.NOT_CONFIGURED)
        val merge = backend.mergeRule

        // S4b-BL-20: a remote that lost what this phone sent (a server whose database was replaced) is behind the
        // stored cursors and hides changes below them, so everything goes again and the pull starts from 0. A push
        // answer can show the same ([pushAll]).
        val stored = settings.cursors()
        val storedCursors = listOf(stored.house, stored.visit, stored.photo, stored.record)
        var remoteReset = false
        if (storedCursors.any { it > 0 } && backend.isBehind(storedCursors)) {
            resetForServer()
            remoteReset = true
        }
        var pushed: Int
        var photosWaiting: Int
        try {
            val first = pushAll(backend, photosAllowed, if (remoteReset) 0L else storedCursors.max())
            pushed = first.first
            photosWaiting = first.second
        } catch (e: ServerWasReset) {
            resetForServer()
            remoteReset = true
            val again = pushAll(backend, photosAllowed, highestCursor = 0L)
            pushed = e.pushed + again.first
            photosWaiting = again.second
        }

        val cursors = settings.cursors()
        var houseCursor = cursors.house
        var visitCursor = cursors.visit
        var pulled = 0
        // S4b-BL-167: the local rows of a page are read in chunks (`readInChunks`), not one query per pulled row.
        val houseRows = backend.housesSince(houseCursor)
        val localHouses = readInChunks(houseRows.map { it.id }) { db.houses().getMany(it) }.associateByTo(HashMap()) { it.id }
        for (dto in houseRows) {
            houseCursor = maxOf(houseCursor, dto.syncVersion)
            val incoming = dto.toEntity()
            if (merge.keepLocal(localHouses[dto.id], incoming)) continue
            db.houses().upsert(incoming); localHouses[dto.id] = incoming; pulled++
        }
        val visitRows = backend.visitsSince(visitCursor)
        val localVisits = readInChunks(visitRows.map { it.id }) { db.visits().getMany(it) }.associateByTo(HashMap()) { it.id }
        for (dto in visitRows) {
            visitCursor = maxOf(visitCursor, dto.syncVersion)
            val incoming = dto.toEntity()
            if (merge.keepLocal(localVisits[dto.id], incoming)) continue
            db.visits().upsert(incoming); localVisits[dto.id] = incoming; pulled++
        }
        // Records (docs/11 5.30): a row this phone cannot use (toEntity null) is skipped and the cursor still moves
        // past it, as the web does; the next app version that can read it pulls it again from a fresh cursor.
        var recordCursor = cursors.record
        val recordRows = backend.recordsSince(recordCursor)
        val localRecords = HashMap<Pair<String, String>, RecordEntity>()
        recordRows.mapNotNull { it.toEntity() }.groupBy({ it.type }, { it.id }).forEach { (type, ids) ->
            readInChunks(ids) { db.records().getMany(type, it) }.forEach { localRecords[it.type to it.id] = it }
        }
        for (dto in recordRows) {
            recordCursor = maxOf(recordCursor, dto.syncVersion)
            val incoming = dto.toEntity() ?: continue
            if (merge.keepLocal(localRecords[incoming.type to incoming.id], incoming)) continue
            db.records().upsert(incoming); localRecords[incoming.type to incoming.id] = incoming; pulled++
        }
        settings.saveCursors(houseCursor, visitCursor, recordCursor)

        // Photos: apply delete tombstones from other devices, download new photos of live houses.
        var photoCursor = cursors.photo
        var photosComplete = true
        val photoChanges = backend.photoChangesSince(cursors.photo)
        val localPhotos = readInChunks(photoChanges.map { it.id }) { db.photos().getMany(it) }.associateBy { it.id }
        // The houses the page's photos name, read after the houses above were stored, so a house pulled just now counts.
        val photoHouses = readInChunks(photoChanges.map { it.houseId }) { db.houses().getMany(it) }.associateBy { it.id }
        val photoSeen = HashSet<String>()
        for (change in photoChanges) {
            // A page that names one photo twice reads it again the second time: the first change may have changed the row.
            val local = if (photoSeen.add(change.id)) localPhotos[change.id] else db.photos().get(change.id)
            if (change.deleted) {
                if (local != null) {
                    photoStore.deleteFile(photoFileOf(local.id)); db.photos().delete(change.id); pulled++
                }
            } else if (local != null) {
                // A meta change from another device (slice 5): the newer `metaUpdatedAt` wins; an edit here that is
                // newer stays, and is pushed.
                val incoming = change.meta()
                if (!local.deleted && PhotoMeta.incomingWins(local.metaUpdatedAt, incoming.metaUpdatedAt)) {
                    db.photos().upsert(local.withMeta(incoming, dirty = false)); pulled++
                }
            } else if (BackupValidation.isValidId(change.id)) {
                // The id becomes a file name: a server id outside the backup id rule is not downloaded (defence in
                // depth; the server issues UUIDs), and the cursor moves past it like any handled row.
                val house = photoHouses[change.houseId]
                if (house != null && !house.deleted) {
                    if (!photosAllowed) {
                        photosWaiting++; photosComplete = false; continue
                    }
                    val out = photoPath(change.id)
                    // Null: this photo cannot be had (Drive: tampered, planted, a revoked writer); skipped, the backend reports it.
                    val bytes = backend.downloadPhotoIfAvailable(change.id)
                    if (bytes != null) {
                        photoStore.write(out, bytes)
                        db.photos().upsert(
                            PhotoEntity(change.id, change.houseId, out.toString(), true, now()).withMeta(change.meta(), dirty = false),
                        )
                        pulled++
                    }
                }
            }
            // Only move the cursor past rows that are fully handled, so skipped downloads are retried on Wi-Fi.
            if (photosComplete) photoCursor = maxOf(photoCursor, change.syncVersion)
        }
        settings.savePhotoCursor(photoCursor)

        SyncOutcome(
            SyncOutcome.Kind.OK, pushed = pushed, pulled = pulled, photosWaiting = photosWaiting,
            remoteReset = remoteReset,
        )
    }

    /**
     * Reads the stored rows for [keys] with [read], [PULL_READ_CHUNK] distinct keys per query: the number of queries
     * follows the page, not the store and not one per row (S4b-BL-167).
     */
    private suspend fun <T> readInChunks(keys: List<String>, read: suspend (List<String>) -> List<T>): List<T> =
        keys.distinct().chunked(PULL_READ_CHUNK).flatMap { read(it) }

    /** Thrown by [pushAll] when a push answer shows the server behind this phone; [pushed] rows went before it. */
    private class ServerWasReset(val pushed: Int) : Exception()

    /**
     * Marks every house, visit and record for upload and every live photo for upload again, then resets the pull cursors
     * (S4b-BL-20). Rows first: a sync cut off in between finds the server behind again on its next run, instead of
     * leaving rows marked clean that the server does not have.
     *
     * Public for the hand-back from Google Drive (review of PR 142, item 3): rows that were sent only to Drive are marked
     * clean, so when Drive stops being the sync target the app calls this to send everything to the server again.
     */
    suspend fun resetForServer() {
        db.houses().markAllDirty()
        db.visits().markAllDirty()
        db.records().markAllDirty()
        db.photos().markAllForUpload()
        db.photos().markAllMetaDirty()
        settings.resetCursors()
    }

    /**
     * Pushes local changes: visit tombstones without a house, houses, the other visits, records, photo deletes, photo
     * uploads. Returns the rows pushed and the photos left waiting for Wi-Fi. With a [highestCursor] above 0, an
     * accepted house, visit or record write answered with a version at or below it ([SyncRules.pushShowsReset])
     * stops the push with [ServerWasReset].
     */
    /**
     * Pushes local changes ([pushRows]). A backend that keeps whole snapshots (Drive, S4b-BL-118,
     * [SyncBackend.stagesPushes]) sends them together in [SyncBackend.commitPushes]: the local marks that say "sent"
     * wait and run only once that returned (the file complete and read back), also when a later step failed, so what
     * went is not sent again for nothing.
     */
    private suspend fun pushAll(backend: SyncBackend, photosAllowed: Boolean, highestCursor: Long): Pair<Int, Int> {
        val afterCommit = ArrayList<suspend () -> Unit>()
        try {
            return pushRows(backend, photosAllowed, highestCursor) { mark -> if (backend.stagesPushes) afterCommit += mark else mark() }
        } finally {
            if (backend.stagesPushes) {
                withContext(NonCancellable) {
                    backend.commitPushes()
                    afterCommit.forEach { it() }
                }
            }
        }
    }

    private suspend fun pushRows(
        backend: SyncBackend,
        photosAllowed: Boolean,
        highestCursor: Long,
        sent: suspend (suspend () -> Unit) -> Unit,
    ): Pair<Int, Int> {
        var pushed = 0
        fun check(sentUpdatedAt: Long, answerUpdatedAt: String?, answerVersion: Long) {
            val at = answerUpdatedAt?.let { runCatching { IsoTime.parseMillis(it) }.getOrNull() }
            if (SyncRules.pushShowsReset(sentUpdatedAt, at, answerVersion, highestCursor)) throw ServerWasReset(pushed)
        }
        // Visit tombstones without a house go first, before any house tombstone can make the server unlink (and
        // re-stamp) them; see SyncRules.pushesBeforeHouses (Android review, round 17). The houses are read BEFORE the
        // visits: an undo writes its house and visit tombstones in one transaction, so every house tombstone this
        // sync pushes has its visit tombstones in the list read after it, even when the undo lands mid-sync.
        val dirtyHouses = db.houses().dirty()
        val (visitsFirst, visitsAfter) =
            SyncRules.visitsByPushOrder(db.visits().dirty(), { it.deleted }, { it.houseId })
        for (v in visitsFirst) {
            val answer = backend.pushVisit(v.toDto())
            check(v.updatedAt, answer.updatedAt, answer.syncVersion)
            sent { db.visits().markClean(v.id, v.updatedAt) }; pushed++
        }
        for (h in dirtyHouses) {
            val answer = backend.pushHouse(h.toDto())
            check(h.updatedAt, answer.updatedAt, answer.syncVersion)
            sent { db.houses().markClean(h.id, h.updatedAt) }; pushed++
        }
        for (v in visitsAfter) {
            val answer = backend.pushVisit(v.toDto())
            check(v.updatedAt, answer.updatedAt, answer.syncVersion)
            sent { db.visits().markClean(v.id, v.updatedAt) }; pushed++
        }
        // Records after the houses and visits: a record may name a house (a viewing, slice 3), so the house is on
        // the server first.
        for (r in db.records().dirty()) {
            val answer = backend.pushRecord(r.toDto())
            check(r.updatedAt, answer.updatedAt, answer.syncVersion)
            sent { db.records().markClean(r.type, r.id, r.updatedAt) }; pushed++
        }
        // Deletes are tiny, so they go out on any network.
        for (p in db.photos().pendingDelete()) {
            backend.deletePhoto(p.id); sent { db.photos().delete(p.id) }; pushed++
        }
        var photosWaiting = 0
        for (p in db.photos().pendingUpload()) {
            val file = photoFileOf(p.id)
            val size = photoStore.sizeOf(file)
            if (size == null || db.houses().get(p.houseId)?.deleted != false) continue
            if (!photosAllowed) {
                photosWaiting++; continue
            }
            // Streamed from the file, not read into memory; each (re)try opens the file again. A permanent refusal
            // keeps the photo on this phone only ([SyncBackend.uploadPhoto]); anything else aborts the sync.
            backend.uploadPhoto(p.houseId, p.id, file.name, size) { photoStore.open(file) }
            db.photos().upsert(p.copy(uploaded = true)); pushed++
        }
        // Photo meta (slice 5) after the uploads, so a photo taken with a tag reaches the server first. The answer is the
        // server's current meta: a newer one from another device replaces the edit here (last write wins). A photo the
        // server does not have (404) or refuses (400) would fail on every sync ([SyncBackend.pushPhotoMeta] answers
        // null), so its flag is cleared and the meta stays on this phone.
        for (p in db.photos().pendingMeta()) {
            val answer = backend.pushPhotoMeta(p.id, p.toMetaDto())
            if (answer == null) {
                sent { db.photos().markMetaClean(p.id, p.metaUpdatedAt) }
                continue
            }
            val current = answer.meta()
            if (PhotoMeta.incomingWins(p.metaUpdatedAt, current.metaUpdatedAt)) {
                sent {
                    db.photos().get(p.id)?.takeIf { it.metaUpdatedAt == p.metaUpdatedAt }
                        ?.let { db.photos().upsert(it.withMeta(current, dirty = false)) }
                }
            } else {
                sent { db.photos().markMetaClean(p.id, p.metaUpdatedAt) }
            }
            pushed++
        }
        return pushed to photosWaiting
    }

    // ---- Offline copy: export and import (Sprint 4a, S4-02/S4-04) ----

    override suspend fun localRows(): LocalRows = withContext(Dispatchers.IO) {
        brokerStore.migrate()
        LocalRows(
            db.houses().all(), db.visits().all(), db.photos().all(),
            brokerStore.exportRows(),
            lengthUnit = settings.lengthUnit.first(),
            // At most 40 criteria (more can only come from a newer app's sync), so the copy's own check accepts it.
            criteria = db.records().listByType(CriterionType.name)
                .mapNotNull { row -> CriterionStore.of(row)?.let { ExportCriterion.of(it, row.updatedAt) } }
                .take(Criterion.MAX_CRITERIA),
            preferences = db.records().listByType(PreferenceType.name).mapNotNull { row ->
                row.decode(PreferenceType)?.let { ExportPreference(row.id, it.value.take(Preference.MAX_VALUE), row.updatedAt) }
            },
            // At most 100 questions, for the same reason as the criteria.
            questions = db.records().listByType(QuestionType.name)
                .mapNotNull { row -> QuestionStore.of(row)?.let { ExportQuestion.of(it, row.updatedAt) } }
                .take(Question.MAX_QUESTIONS),
            // The viewings (slice 3b-1): untrusted rows are skipped, so the copy's own check accepts what it writes.
            viewings = db.records().listByType(ViewingType.name)
                .mapNotNull { row -> ViewingStore.of(row)?.let { ExportViewing.of(it, row.updatedAt) } }
                .take(Viewing.MAX_VIEWINGS),
            // Areas, places and area notes (slice 4a): untrusted rows are skipped, a stored radius out of range is 500, and
            // nothing is cut to the caps (as the website and the server read them; the caps hold at each save).
            areas = db.records().listByType(AreaType.name)
                .mapNotNull { row -> AreaStore.areaOf(row)?.let { ExportArea.of(it, row.updatedAt) } },
            places = db.records().listByType(PlaceType.name)
                .mapNotNull { row -> AreaStore.placeOf(row)?.let { ExportPlace.of(it, row.updatedAt) } },
            areaNotes = db.records().listByType(AreaNoteType.name)
                .mapNotNull { row -> AreaStore.noteOf(row)?.let { ExportAreaNote.of(it, row.updatedAt) } },
            // The tombstones, for an update file's deletions (S4b-BL-82).
            deletedHouses = db.houses().deletedIds().toSet().let { gone ->
                db.houses().versions().filter { it.id in gone }.associate { it.id to it.updatedAt }
            },
        )
    }

    /**
     * [localRows] now, and again after every change to the houses, visits or photos table, for the Export screen's
     * live count (docs/05 section 14.1: the honest answer to what the file will hold). Keyed on all three tables,
     * not on the house list: a visit Hunt mode records, or a photo added, while the screen is open changes the file
     * but not a single house row (Android review, 2026-09-22). Changes that arrive while a read is still going are
     * conflated into one more read.
     */
    override fun localRowsFlow(): Flow<LocalRows> =
        db.localTablesChanged().conflate().map { localRows() }

    override suspend fun localVersions(): LocalVersions = withContext(Dispatchers.IO) {
        LocalVersions(
            db.houses().versions().associate { it.id to it.updatedAt },
            db.visits().versions().associate { it.id to it.updatedAt },
            db.photos().allIds().toSet(),
            db.houses().deletedIds().toSet(),
            db.houses().all().filter { it.checklist.isNotEmpty() }.mapTo(HashSet()) { it.id },
            db.visits().unlinkedIds().toSet(),
            db.houses().syncedDeletedIds().toSet(),
            db.records().versions(BrokerType.name).associate { it.id to it.updatedAt },
            db.records().versions(CriterionType.name).associate { it.id to it.updatedAt },
            db.records().versions(PreferenceType.name).associate { it.id to it.updatedAt },
            db.records().versions(QuestionType.name).associate { it.id to it.updatedAt },
            db.records().versions(ViewingType.name).associate { it.id to it.updatedAt },
            db.records().versions(AreaType.name).associate { it.id to it.updatedAt },
            db.records().versions(PlaceType.name).associate { it.id to it.updatedAt },
            db.records().versions(AreaNoteType.name).associate { it.id to it.updatedAt },
            photoMeta = db.photos().metaVersions().associate { it.id to it.updatedAt },
            liveQuestions = db.records().listByType(QuestionType.name).mapTo(HashSet()) { it.id },
            liveCriteria = db.records().listByType(CriterionType.name).mapTo(HashSet()) { it.id },
        )
    }

    /** The path a photo row's bytes live in, for the exporter and the importer; the folder is created when missing. */
    fun photoPath(id: String): Path = photoStore.pathFor(id)

    /**
     * The file photo [id]'s bytes live in, built from the photo folder and the id; creates nothing. Every read of a
     * photo row's file goes through this rather than the row's stored `path` (S4b-BL-52): on iOS the app's container
     * folder changes when the app is updated, so a stored full path goes stale, while the id does not. On Android the
     * folder does not move, so this is the same file the row names.
     */
    fun photoFileOf(id: String): Path = photoStore.fileOf(id)

    /**
     * Writes an import's rows (S4-04). Imported rows keep the timestamps the backup gave them, so the server's
     * own last-write-wins rule reaches the same answer, and they are marked `dirty` so they are pushed on the
     * next sync. [photoBytes] returns a photo's bytes from the ZIP, or null if it cannot be read or its hash
     * does not match the manifest; a photo whose bytes are missing is skipped rather than written as a row
     * pointing at nothing, and counted in [ImportResult.photosSkipped] so the screen can say so instead of
     * reporting a clean import of a damaged file.
     *
     * **Merge** writes rows one by one rather than in a transaction on purpose: a 1 GB import that is interrupted
     * should leave the houses it already wrote, not roll everything back, and every row is idempotent (upsert by
     * id), so re-running the same import finishes the job.
     *
     * **Restored houses** (`ImportActions.restoredHouseIds`, a merge with "Also bring back houses deleted on this
     * phone") keep the backup's row but not its timestamp: the tombstone is cleared and `updatedAt` is stamped *now*
     * (and past the tombstone's own, whatever the clock says). Otherwise the next sync would meet the server's
     * tombstone, which is newer than the backup's row, and delete the house again; stamped now, the undelete is the
     * newest edit and wins on the server too (UX review, round 11). Re-running the same import then sees the house
     * as newer here and leaves it alone.
     *
     * **Relinked visits** (`ImportActions.relinkedVisitIds`, Android review round 12) are visits the server unlinked
     * when it purged a house this import writes over a tombstone. The phone's own copy is kept (it is at least as new
     * as the backup's and may have been edited since), its `houseId` is set back from the backup and it is stamped
     * like a restored house, past its own `updatedAt`, so the relink beats the server's unlink on the next sync. A
     * visit that was deleted or put in another house since the preview is left alone and not counted. The photos of
     * such a house already arrive under fresh ids (`ImportPlan.plan`) once the delete has reached the server
     * (`LocalVersions.syncedDeletedHouseIds`), because the server never takes a tombstoned photo id back.
     *
     * **Copy** is all or nothing ([applyCopy]). Its rows get fresh ids on every run, so re-running a half-finished
     * copy would not finish it but add everything it had already written a second time — and a stopped or failed
     * copy left the user no safe way forward (UX review, 2026-09-22). Nothing a copy writes is visible until the
     * very end, and a stop or a failure leaves the phone exactly as it was.
     */
    override suspend fun applyImport(
        actions: ImportActions,
        onProgress: (done: Int, total: Int) -> Unit,
        photoBytes: suspend (entry: String) -> ByteArray?,
    ): ImportResult =
        try {
            applyImportPass(actions, onProgress, photoBytes)
        } finally {
            sweepWalksOfDeletedHouses() // import end: an import that deletes a house takes its saved walks with it
        }

    private suspend fun applyImportPass(
        actions: ImportActions,
        onProgress: (done: Int, total: Int) -> Unit,
        photoBytes: suspend (entry: String) -> ByteArray?,
    ): ImportResult = withContext(Dispatchers.IO) {
        if (actions.mode == ImportMode.COPY) return@withContext applyCopy(actions, onProgress, photoBytes)
        val total = actions.houses.size + actions.visits.size + actions.photos.size + actions.brokers.size +
            actions.criteria.size + actions.preferences.size + actions.questions.size + actions.viewings.size +
            actions.areas.size + actions.places.size + actions.areaNotes.size
        var done = 0
        var houses = 0
        var visits = 0
        var photos = 0
        var skipped = 0
        var updatedHouses = 0
        var updatedVisits = 0
        var restoredHouses = 0
        // Brokers first, by id with the file's `updatedAt` (the plan already chose the newer ones): the houses that
        // name them find them. A broker the file's houses do not name is still kept: it is the person's own record.
        for (broker in actions.brokers) {
            db.records().upsert(BrokerStore.importedRow(broker, broker.updatedAt))
            onProgress(++done, total)
        }
        // Criteria and preferences (slice 2), by key with the file's `updatedAt`: the plan kept the new and newer ones.
        for (c in actions.criteria) {
            db.records().upsert(CriterionStore.importedCriterion(c, c.updatedAt))
            onProgress(++done, total)
        }
        for (p in actions.preferences) {
            db.records().upsert(CriterionStore.importedPreference(p, p.updatedAt))
            onProgress(++done, total)
        }
        // The question bank (slice 3a), by id with the file's `updatedAt`, like the criteria.
        for (q in actions.questions) {
            db.records().upsert(QuestionStore.importedRow(q, q.updatedAt))
            onProgress(++done, total)
        }
        // The viewings (slice 3b-1), by id with the file's `updatedAt`: a newer row brings back one deleted here.
        for (v in actions.viewings) {
            db.records().upsert(ViewingStore.importedRow(v, v.updatedAt))
            onProgress(++done, total)
        }
        // Areas, places and area notes (slice 4a), by id with the file's `updatedAt`, like the questions.
        for (row in AreaStore.importedRows(actions) { it }) {
            db.records().upsert(row)
            onProgress(++done, total)
        }
        for (house in actions.houses) {
            if (house.id in actions.restoredHouseIds) {
                val tombstone = db.houses().get(house.id)
                val stamp = maxOf(now(), (tombstone?.updatedAt ?: 0L) + 1)
                // toEntity already writes deleted = false; the stamp is what makes the undelete stick.
                db.houses().upsert(house.toEntity(dirty = true).copy(updatedAt = stamp))
                restoredHouses++
            } else {
                db.houses().upsert(house.toEntity(dirty = true))
                if (house.id in actions.updatedHouseIds) updatedHouses++
            }
            houses++
            onProgress(++done, total)
        }
        for (visit in actions.visits) {
            if (visit.id in actions.relinkedVisitIds) {
                val local = db.visits().get(visit.id)
                val row = when {
                    // Gone since the preview (visits are not removed, but be safe): the backup's row is all there is.
                    local == null -> visit.toEntity(dirty = true)
                    // Deleted, or put in a house, since the preview: that is a newer decision of the user's.
                    local.deleted || local.houseId != null -> null
                    else -> local.copy(houseId = visit.houseId)
                }
                if (row != null) {
                    val stamp = maxOf(now(), (local?.updatedAt ?: 0L) + 1)
                    db.visits().upsert(row.copy(updatedAt = stamp, dirty = true))
                    visits++
                }
                onProgress(++done, total)
                continue
            }
            db.visits().upsert(visit.toEntity(dirty = true))
            visits++
            if (visit.id in actions.updatedVisitIds) updatedVisits++
            onProgress(++done, total)
        }
        for (photo in actions.photos) {
            val entry = actions.photoSources[photo.id]
            val bytes = entry?.let { photoBytes(it) }
            val out = photoStore.importedPathFor(photo.id)
            if (bytes != null && out != null && db.houses().get(photo.houseId)?.deleted == false) {
                photoStore.write(out, bytes)
                db.photos().upsert(photo.toEntity(out.toString()))
                photos++
            } else {
                // Not written, so counted: bytes we could not read or verify, or — the narrow race — a house
                // deleted on this phone after the preview was made. The preview counted that photo under "new
                // photos" (`ImportPlan` only excludes houses that were already tombstoned when it ran, via
                // `LocalVersions.deletedHouseIds`), so leaving it out here would quietly deliver one fewer photo
                // than the user was promised. Either way the user hears about it.
                skipped++
            }
            onProgress(++done, total)
        }
        // Photos already here whose meta is newer in the file (slice 5): only the meta, stamped as the file has it, so
        // the last write wins on the server too; marked for the next sync.
        for (photo in actions.photoMeta) {
            val local = db.photos().get(photo.id)?.takeUnless { it.deleted } ?: continue
            if (PhotoMeta.incomingWins(local.metaUpdatedAt, photo.meta.metaUpdatedAt)) {
                db.photos().upsert(local.withMeta(photo.meta, dirty = true))
            }
        }
        // An update file's deletions (S4b-BL-82), last: each house is deleted as the person's own delete would be (a
        // tombstone stamped now, pushed on the next sync); one already gone is skipped.
        var removed = 0
        for (id in actions.removedHouseIds) {
            if (db.houses().get(id)?.deleted != false) continue
            deleteHouse(id)
            removed++
        }
        val result = ImportResult(
            houses, visits, photos, skipped, updatedHouses, updatedVisits, restoredHouses,
            brokers = actions.brokers.size,
            criteria = actions.criteria.size,
            preferences = actions.preferences.size,
            questions = actions.questions.size,
            viewings = actions.viewings.size,
            areas = actions.areas.size,
            places = actions.places.size,
            areaNotes = actions.areaNotes.size,
            removedHouses = removed,
        )
        if (result.rows > 0 || actions.photoMeta.isNotEmpty()) syncSoon()
        result
    }


    /**
     * [applyImport] for [ImportMode.COPY]: all or nothing.
     *
     * The slow part — reading, verifying and writing the photo files — comes first, into files that no row points
     * at yet. Then every house, visit and photo row is written in **one transaction**, which is quick (rows only)
     * and either commits all of them or none. A Stop ([onProgress] throws `CancellationException`), a system stop,
     * a full disk or any other failure rolls the transaction back and deletes the photo files already written (each
     * one whose row is not in the database, [discardUncommittedPhotoFiles]: a cancellation that lands just after the
     * commit keeps them), so the phone is left exactly as it was and importing the file again is safe. (If the process is killed outright
     * between the two steps, photo files without a row can be left in the app's private photo folder; no row, and
     * so nothing the user sees, refers to them.)
     *
     * Progress counts the photos as their files are written and then each house and visit row, so the bar moves
     * during the long part; its total is the same as in merge mode.
     *
     * A copy keeps the backup's `updatedAt` unless it lies in the future, when it is stamped now instead
     * ([CopyUndo.copyStamp], Android review round 17): the result's [ImportResult.copiedHouses] / `copiedVisits`
     * carry the stamp each row was really written with, which is what the undo compares against.
     */
    private suspend fun applyCopy(
        actions: ImportActions,
        onProgress: (done: Int, total: Int) -> Unit,
        photoBytes: suspend (entry: String) -> ByteArray?,
    ): ImportResult {
        val total = actions.houses.size + actions.visits.size + actions.photos.size + actions.brokers.size +
            actions.criteria.size + actions.preferences.size + actions.questions.size + actions.viewings.size +
            actions.areas.size + actions.places.size + actions.areaNotes.size
        var done = 0
        var skipped = 0
        // In copy mode every photo belongs to a house of this import (ImportPlan maps it to the house's new id);
        // anything else is not written, and counted, exactly as merge mode counts a photo whose house is gone.
        val newHouseIds = actions.houses.mapTo(HashSet()) { it.id }
        // Each photo file written, with the id of the row that will point at it.
        val written = LinkedHashMap<Path, String>()
        val photoRows = ArrayList<PhotoEntity>()
        // The id and the updatedAt each row was written with, for the undo record (ImportUndo).
        val copiedHouses = LinkedHashMap<String, Long>()
        val copiedVisits = LinkedHashMap<String, Long>()
        // The records this copy creates (S4b-BL-92e, 90c): one that was not here, or only as a tombstone. One written
        // over a live record here is a merge's update, which the undo cannot put back, so it is not recorded.
        val copiedRecords = LinkedHashMap<String, Long>()
        suspend fun writeRecord(row: RecordEntity) {
            val before = db.records().get(row.type, row.id)
            db.records().upsert(row)
            if (before == null || before.deleted) copiedRecords[CopyUndo.recordKey(row.type, row.id)] = row.updatedAt
        }
        try {
            for (photo in actions.photos) {
                val entry = actions.photoSources[photo.id]
                val bytes = entry?.let { photoBytes(it) }
                val out = photoStore.importedPathFor(photo.id)
                if (bytes != null && out != null && photo.houseId in newHouseIds) {
                    written[out] = photo.id
                    photoStore.write(out, bytes)
                    photoRows += photo.toEntity(out.toString())
                } else {
                    skipped++
                }
                onProgress(++done, total)
            }
            // Never stamped in the future (CopyUndo.copyStamp): the server would clamp it, the pull would write the
            // clamped value back, and the undo would take every such copy for one the user edited since.
            val now = now()
            db.withImmediateTransaction {
                for (broker in actions.brokers) {
                    writeRecord(BrokerStore.importedRow(broker, CopyUndo.copyStamp(broker.updatedAt, now)))
                    onProgress(++done, total)
                }
                // A copy keeps the criteria's keys (the copied houses' scores name them) and merges them like a merge.
                for (c in actions.criteria) {
                    writeRecord(CriterionStore.importedCriterion(c, CopyUndo.copyStamp(c.updatedAt, now)))
                    onProgress(++done, total)
                }
                for (p in actions.preferences) {
                    db.records().upsert(CriterionStore.importedPreference(p, CopyUndo.copyStamp(p.updatedAt, now)))
                    onProgress(++done, total)
                }
                for (q in actions.questions) {
                    writeRecord(QuestionStore.importedRow(q, CopyUndo.copyStamp(q.updatedAt, now)))
                    onProgress(++done, total)
                }
                for (house in actions.houses) {
                    val row = house.toEntity(dirty = true).copy(updatedAt = CopyUndo.copyStamp(house.updatedAt, now))
                    db.houses().upsert(row)
                    copiedHouses[row.id] = row.updatedAt
                    onProgress(++done, total)
                }
                for (visit in actions.visits) {
                    val row = visit.toEntity(dirty = true).copy(updatedAt = CopyUndo.copyStamp(visit.updatedAt, now))
                    db.visits().upsert(row)
                    copiedVisits[row.id] = row.updatedAt
                    onProgress(++done, total)
                }
                for (v in actions.viewings) {
                    writeRecord(ViewingStore.importedRow(v, CopyUndo.copyStamp(v.updatedAt, now)))
                    onProgress(++done, total)
                }
                // A copy keeps the ids of areas, places and notes (a note names its area) and merges them like a merge.
                for (row in AreaStore.importedRows(actions) { CopyUndo.copyStamp(it, now) }) {
                    db.records().upsert(row)
                    onProgress(++done, total)
                }
                for (row in photoRows) db.photos().upsert(row)
            }
        } catch (e: Throwable) {
            // Rolled back (or never started): the files are the only thing left to undo. Not always, though: a
            // cancellation can also land after the commit, as the transaction hands back to this coroutine, and then
            // the rows are there and their files must stay (see discardUncommittedPhotoFiles).
            discardUncommittedPhotoFiles(written)
            throw e
        }
        val result = ImportResult(
            actions.houses.size, actions.visits.size, photoRows.size, skipped,
            brokers = actions.brokers.size,
            criteria = actions.criteria.size,
            preferences = actions.preferences.size,
            questions = actions.questions.size,
            viewings = actions.viewings.size,
            areas = actions.areas.size,
            places = actions.places.size,
            areaNotes = actions.areaNotes.size,
            copiedHouses = copiedHouses,
            copiedVisits = copiedVisits,
            copiedPhotos = photoRows.map { it.id },
            copiedRecords = copiedRecords,
        )
        if (result.rows > 0) syncSoon()
        return result
    }

    /** [PhotoStore.discardUncommitted] after a copy import that did not finish ([applyCopy]'s catch). */
    protected suspend fun discardUncommittedPhotoFiles(written: Map<Path, String>) = photoStore.discardUncommitted(written)

    /**
     * Undoes a copy import (UX review, round 16): removes exactly the rows it added, given as the ids of its
     * `ImportUndo` record ([houses] and [visits] with the `updatedAt` each was written with, and [photos]).
     *
     * Every keep-or-remove decision is [CopyUndo]'s (pure, and pinned by `CopyUndoTest`, Android review round 17);
     * this function only reads the rows, asks it and writes the answer. All in **one transaction**, as the copy itself
     * was: either every copy goes or none does. A removed house becomes a tombstone that syncs
     * ([CopyUndo.houseTombstone], as [deleteHouse] writes it), so the next sync removes it from the server and every
     * other device too; a house the user has touched since is kept, with everything in it, and counted in
     * [UndoResult.kept]; one already deleted is skipped. The import's unchanged visits go with their removed house,
     * and so do its unchanged loose street visits, as tombstones **without a house** ([CopyUndo.visitTombstone]) so
     * that [sync] pushes them before the house tombstones and the server's purge cannot unlink and revive them. The
     * photos of a removed house lose their row and, after the commit, their file (the server purges its copies with
     * the house).
     */
    override suspend fun undoCopyImport(
        houses: Map<String, Long>,
        visits: Map<String, Long>,
        photos: Collection<String>,
        records: Map<String, Long>,
    ): UndoResult =
        try {
            undoCopyImportPass(houses, visits, photos, records)
        } finally {
            sweepWalksOfDeletedHouses() // CopyUndo: the copy's houses are tombstoned, so their walks go
        }

    private suspend fun undoCopyImportPass(
        houses: Map<String, Long>,
        visits: Map<String, Long>,
        photos: Collection<String>,
        records: Map<String, Long>,
    ): UndoResult = withContext(Dispatchers.IO) {
        val photoIds = photos.toHashSet()
        val files = ArrayList<Path>()
        var removed = 0
        var changedRecords = false
        val keptHouses = HashSet<String>()
        db.withImmediateTransaction {
            val now = now()
            val removedHouses = HashSet<String>()
            for ((id, writtenAt) in houses) {
                val house = db.houses().get(id)
                val state = house?.let { h ->
                    CopyUndo.HouseNow(
                        updatedAt = h.updatedAt,
                        deleted = h.deleted,
                        liveVisits = db.visits().liveForHouse(id).associate { it.id to it.updatedAt },
                        livePhotoIds = db.photos().liveForHouse(id).map { it.id },
                    )
                }
                when (CopyUndo.decideHouse(state, writtenAt, visits, photoIds)) {
                    CopyUndo.Decision.REMOVE -> {
                        db.houses().upsert(CopyUndo.houseTombstone(checkNotNull(house), now))
                        removedHouses += id
                        removed++
                    }
                    CopyUndo.Decision.KEEP -> keptHouses += id
                    CopyUndo.Decision.SKIP -> Unit
                }
            }
            for ((id, writtenAt) in visits) {
                val visit = db.visits().get(id)
                val state = visit?.let { CopyUndo.VisitNow(it.updatedAt, it.deleted, it.houseId) }
                if (CopyUndo.decideVisit(state, writtenAt, removedHouses) == CopyUndo.Decision.REMOVE) {
                    db.visits().upsert(CopyUndo.visitTombstone(checkNotNull(visit), now))
                }
            }
            for (id in photoIds) {
                val photo = db.photos().get(id) ?: continue
                if (!CopyUndo.removesPhoto(photo.houseId, removedHouses)) continue
                db.photos().delete(id)
                files += photoFileOf(photo.id)
            }
            // The brokers, viewings, questions and criteria it created (S4b-BL-92e, 90c), once the houses are decided:
            // what a house that stays still uses stays with it.
            if (records.isNotEmpty()) {
                val live = db.houses().all().filter { !it.deleted }
                for ((key, writtenAt) in records) {
                    val (type, id) = CopyUndo.recordOf(key) ?: continue
                    val row = db.records().get(type, id)
                    val inUse = row != null && !row.deleted && when (type) {
                        ViewingType.name -> ViewingStore.of(row)?.houseId?.let { h -> live.any { it.id == h } } == true
                        BrokerType.name -> live.any { it.brokerId == id }
                        CriterionType.name -> id !in Checklist.keys && live.any { it.checklist.containsKey(id) }
                        QuestionType.name -> live.any { h -> h.answers.orEmpty().any { it.questionId == id } }
                        else -> true
                    }
                    val state = row?.let { CopyUndo.RecordNow(it.updatedAt, it.deleted) }
                    if (CopyUndo.decideRecord(state, writtenAt, inUse) == CopyUndo.Decision.REMOVE) {
                        db.records().upsert(
                            checkNotNull(row).copy(
                                payload = "{}", deleted = true, dirty = true,
                                updatedAt = CopyUndo.tombstoneStamp(row.updatedAt, now),
                            ),
                        )
                        changedRecords = true
                    }
                }
            }
        }
        // After the commit: a rolled-back undo must not have deleted a single photo file.
        files.forEach { photoStore.deleteFile(it) }
        if (removed > 0 || changedRecords) syncSoon()
        UndoResult(removed, keptHouses.size, keptHouses)
    }

    companion object {
        /** Ids per local read of a pull page, under SQLite's default limit of 999 variables (S4b-BL-167); the web uses the same. */
        internal const val PULL_READ_CHUNK = 500
    }
}
