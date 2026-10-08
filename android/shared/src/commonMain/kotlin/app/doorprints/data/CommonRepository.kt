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
import app.doorprints.shared.ai.AiKind
import app.doorprints.shared.ai.AiProviderConfig
import app.doorprints.shared.ai.AnthropicClient
import app.doorprints.shared.ai.BaseUrlCheck
import app.doorprints.shared.ai.BaseUrlValidator
import app.doorprints.shared.ai.GeminiClient
import app.doorprints.shared.ai.JsonChatModel
import app.doorprints.shared.ai.OnDeviceAi
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
import app.doorprints.shared.export.ExportBroker
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
import app.doorprints.shared.model.DefaultQuestions
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
import app.doorprints.shared.model.ViewingStatus
import app.doorprints.shared.model.ViewingType
import app.doorprints.shared.model.Viewings
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
import app.doorprints.shared.model.PhoneKey
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The [Repository] both apps' platforms share (S4b-BL-32, before the iOS shell, CMP-8): the Room database, the sync
 * with the optional server, the offline copy's reads and writes, the import's merge and copy and their undo. Moved
 * from `:app`'s `AndroidRepository` unchanged in behaviour; what needs the platform comes in through the constructor.
 *
 * - [photoDir]: the folder the photo files live in (Android: `filesDir/photos`). A photo row's `path` is still written
 *   as the file's full path in it, but never read to reach the file: the file is found from the row's id
 *   ([photoFileOf]), because an iOS app's container folder moves when the app is updated (S4b-BL-52). The files are
 *   read and written with kotlinx-io's [SystemFileSystem].
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
    private val geminiFor: ((apiKey: String) -> GeminiClient)? = null,
    syncBackendFor: (suspend (AppSettings) -> SyncBackend?)? = null,
    /** An OpenAI-compatible endpoint with the person's own key, for on-device AI (docs/03 §13.2); null where a platform has none. */
    private val openAiFor: ((baseUrl: String, model: String, apiKey: String) -> JsonChatModel)? = null,
    /** Anthropic with the person's own key, for on-device AI (docs/03 §13.2); null where a platform has none. */
    private val anthropicFor: ((baseUrl: String, model: String, apiKey: String) -> JsonChatModel)? = null,
    /** True on Android only: the emulator's name for its computer, `10.0.2.2`, may be an http base URL ([BaseUrlValidator]). */
    private val emulatorHostAllowed: Boolean = false,
) : Repository {
    private val syncBackendFor: suspend (AppSettings) -> SyncBackend? = syncBackendFor
        ?: { s -> if (s.serverConfigured) ServerSyncBackend(apiFor(s.serverUrl, s.apiKey)) else null }

    protected val fs: FileSystem = SystemFileSystem

    /** The clock every local edit is stamped with. */
    protected fun now(): Long = Clock.System.now().toEpochMilliseconds()

    /**
     * The photo folder, created when missing. Two calls on the IO pool can race to create it on a first run, and
     * kotlinx-io's native `createDirectories` then fails with "File exists" where `java.io.File.mkdirs` did not; a
     * folder that is there afterwards is all that counts.
     */
    protected fun photoDirPath(): Path = Path(photoDir).also { dir ->
        try {
            fs.createDirectories(dir)
        } catch (e: IOException) {
            if (fs.metadataOrNull(dir)?.isDirectory != true) throw e
        }
    }

    private fun writeFile(path: Path, bytes: ByteArray) = fs.sink(path).buffered().use { it.write(bytes) }

    /** Deletes a photo file if it is there; like `java.io.File.delete`, never throws (a failure leaves the file). */
    private fun deleteFile(path: Path) {
        runCatching { fs.delete(path, mustExist = false) }
    }

    // Every read of the houses and the records waits for the once-only move of contacts into brokers (slice 1b), so
    // no screen ever sees the houses before it: the flag makes every later call a plain read.
    override val houses: Flow<List<HouseEntity>> = flow {
        migrateContactsToBrokers()
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
        migrateContactsToBrokers()
        emitAll(db.houses().observe(id))
    }
    override fun visitsFor(houseId: String) = db.visits().observeForHouse(houseId)
    override fun photosFor(houseId: String) = db.photos().observeForHouse(houseId)

    override suspend fun houseSnapshot(): List<HouseEntity> {
        migrateContactsToBrokers()
        return db.houses().all()
    }

    override suspend fun getHouse(id: String): HouseEntity? {
        migrateContactsToBrokers()
        return db.houses().get(id)
    }

    override suspend fun saveHouse(house: HouseEntity) {
        // The rooms, answers and move-in as every reader keeps them (slices 1c, 3a, 5): the form's blank names and notes
        // go, a typed answer reads ANSWERED, the order is the one shown; a floor outside -5..200 is unknown (S4b-BL-87).
        val row = withBroker(house).copy(
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

    override suspend fun savePhotoMeta(photoId: String, meta: PhotoMeta): Boolean {
        val photo = db.photos().get(photoId)?.takeUnless { it.deleted } ?: return false
        val clean = PhotoMeta.coerced(meta.roomId, meta.tags, meta.caption, null)
        if (clean.sameValues(photo.meta)) return false
        // Past the stored time, whatever the clock says, so the edit wins against the one it replaces.
        val stamp = maxOf(now(), photo.metaUpdatedAt + 1)
        db.photos().upsert(photo.withMeta(clean.copy(metaUpdatedAt = stamp), dirty = true))
        syncSoon()
        return true
    }

    // ---- Brokers (docs/11 5.25, slice 1b) ----

    private val brokerGate = Mutex()
    private var brokersMigrated = false

    /** The live brokers of the table, invalid rows left out. */
    private suspend fun liveBrokers(): List<Pair<String, Broker>> =
        db.records().listByType(BrokerType.name).mapNotNull { row -> row.toBroker()?.let { row.id to it } }

    /**
     * The house with its contact copies made to agree with its broker (`saveHouse`, the one write path every screen
     * uses): a house that names a live broker takes its name and phone; one that names none but has a phone gets the
     * broker with that number (`PhoneKey`) or a new one named from the contact (else the number), and is linked. A
     * blank phone never makes a broker, and a number too short to compare matches none but still makes one. A
     * tombstone is left as it is, and a dangling id stays (it reads as no broker).
     */
    private suspend fun withBroker(house: HouseEntity): HouseEntity {
        if (house.deleted) return house
        val linked = house.brokerId
        if (linked != null) {
            val broker = db.records().get(BrokerType.name, linked)?.takeUnless { it.deleted }?.toBroker() ?: return house
            return house.copy(contactName = broker.name, contactPhone = broker.phone)
        }
        val phone = house.contactPhone?.trim().orEmpty()
        if (phone.isEmpty()) return house
        val key = PhoneKey.of(phone)
        val existing = key?.let { k -> liveBrokers().firstOrNull { PhoneKey.of(it.second.phone) == k } }
        val (id, broker) = existing ?: run {
            val fresh = newBroker(house.contactName, phone)
            val id = try {
                saveBroker(fresh)
            } catch (e: RecordLimitException) {
                return house
            }
            id to fresh
        }
        return house.copy(brokerId = id, contactName = broker.name, contactPhone = broker.phone)
    }

    /** A broker made from a house's contact: the name (else the number) and the number, cut to the record's limits. */
    private fun newBroker(contactName: String?, phone: String) = Broker(
        name = (contactName?.trim().takeUnless { it.isNullOrEmpty() } ?: phone).take(Broker.MAX_NAME),
        phone = phone.take(Broker.MAX_PHONE),
    )

    override fun observeBrokers(): Flow<List<Pair<String, Broker>>> =
        db.records().byType(BrokerType.name).map { rows ->
            rows.mapNotNull { row -> row.toBroker()?.let { row.id to it } }
                .sortedWith(compareBy({ it.second.name.lowercase() }, { it.first }))
        }.onStart { migrateContactsToBrokers() }

    override suspend fun saveBroker(broker: Broker, id: String?): String {
        val clean = requireNotNull(broker.coerced()) { "a broker needs a name of 1..${Broker.MAX_NAME} characters" }
        val brokerId = id ?: Uuid.random().toString()
        db.withImmediateTransaction {
            saveRecord(BrokerType, brokerId, clean)
            // The linked houses keep copies of the name and phone; they follow the broker.
            val stamp = now()
            for (h in db.houses().liveForBroker(brokerId)) {
                if (h.contactName != clean.name || h.contactPhone != clean.phone) {
                    db.houses().upsert(h.copy(contactName = clean.name, contactPhone = clean.phone, updatedAt = stamp, dirty = true))
                }
            }
        }
        syncSoon()
        return brokerId
    }

    override suspend fun deleteBroker(id: String) {
        db.withImmediateTransaction {
            deleteRecord(BrokerType, id)
            val stamp = now()
            for (h in db.houses().liveForBroker(id)) db.houses().upsert(h.copy(brokerId = null, updatedAt = stamp, dirty = true))
        }
        syncSoon()
    }

    override fun brokerHouses(id: String): Flow<List<HouseEntity>> = db.houses().observeForBroker(id)

    // ---- Criteria and ranking (docs/11 5.4, slice 2) ----

    /** A criterion row with its key; null when the payload does not decode or the key is not a usable id. */
    private fun RecordEntity.toCriterion(): Criterion? = decode(CriterionType)?.copy(key = id)?.coerced()

    private fun scoringOf(criteria: List<RecordEntity>, preferences: List<RecordEntity>): Scoring = Scoring.of(
        criteria.mapNotNull { it.toCriterion() },
        preferences.mapNotNull { row -> row.decode(PreferenceType)?.let { row.id to it.value } }.toMap(),
    )

    override fun observeScoring(): Flow<Scoring> =
        combine(db.records().byType(CriterionType.name), db.records().byType(PreferenceType.name), ::scoringOf)

    override suspend fun scoring(): Scoring =
        scoringOf(db.records().listByType(CriterionType.name), db.records().listByType(PreferenceType.name))

    override suspend fun saveCriterion(criterion: Criterion) {
        db.withImmediateTransaction { writeCriterion(criterion, scoring()) }
    }

    override suspend fun saveCriteria(criteria: List<Criterion>) {
        db.withImmediateTransaction {
            for (c in criteria) writeCriterion(c, scoring())
        }
    }

    /**
     * One criterion as a record, or no record for a built-in at its default (a reset of that one). Nothing is written
     * when the record already says the same, so a renumbering does not stamp criteria that did not move.
     */
    private suspend fun writeCriterion(criterion: Criterion, current: Scoring) {
        val clean = requireNotNull(criterion.coerced()) { "criterion key '${criterion.key}' is not [A-Za-z0-9._-]{1,64}" }
        if (clean.isDefault) {
            deleteRecord(CriterionType, clean.key)
            return
        }
        val stored = db.records().get(CriterionType.name, clean.key)?.takeUnless { it.deleted }?.toCriterion()
        if (stored == clean) return
        if (current[clean.key] == null && current.criteria.size >= Criterion.MAX_CRITERIA) {
            throw RecordLimitException(CriterionType.name, Criterion.MAX_CRITERIA)
        }
        saveRecord(CriterionType, clean.key, clean)
    }

    override suspend fun addCriterion(label: String, weight: Int): String {
        val name = label.trim()
        require(name.isNotEmpty() && name.length <= Criterion.MAX_LABEL) { "a criterion needs a name of 1..${Criterion.MAX_LABEL} characters" }
        var key = ""
        db.withImmediateTransaction {
            val current = scoring()
            if (current.criteria.size >= Criterion.MAX_CRITERIA) throw RecordLimitException(CriterionType.name, Criterion.MAX_CRITERIA)
            // A key a tombstone holds is taken too: reusing it would bring back the old criterion's scores on the houses.
            val used = db.records().versions(CriterionType.name).mapTo(HashSet()) { it.id }
            key = Criterion.newCustomKey({ it in used })
            val sort = (current.criteria.maxOfOrNull { it.sort } ?: -1) + 1
            saveRecord(CriterionType, key, Criterion(key = key, label = name, weight = weight, sort = sort).coerced()!!)
        }
        return key
    }

    override suspend fun deleteCriterion(key: String): Boolean {
        if (key in Checklist.keys) return false
        var deleted = false
        db.withImmediateTransaction {
            if (db.houses().all().none { !it.deleted && it.checklist.containsKey(key) }) {
                deleteRecord(CriterionType, key)
                deleted = true
            }
        }
        return deleted
    }

    override suspend fun saveRatingShare(share: Double) {
        val value = share.coerceIn(0.0, 1.0)
        if (value == Scoring.DEFAULT_RATING_SHARE) {
            deleteRecord(PreferenceType, Preference.RATING_SHARE)
        } else {
            saveRecord(PreferenceType, Preference.RATING_SHARE, Preference(Scoring.shareText(value)))
        }
    }

    override suspend fun resetScoring() {
        db.withImmediateTransaction {
            for (row in db.records().listByType(CriterionType.name)) deleteRecord(CriterionType, row.id)
            for (row in db.records().listByType(PreferenceType.name)) deleteRecord(PreferenceType, row.id)
        }
    }

    // ---- The question bank and a house's answers (docs/11 5.5, slice 3a) ----

    /** A question row with its id, coerced; null when the payload does not decode or cannot be trusted (skipped). */
    private fun RecordEntity.toQuestion(): Question? = decode(QuestionType)?.copy(id = id)?.coerced()

    private fun questionsOf(rows: List<RecordEntity>): List<Question> =
        rows.mapNotNull { it.toQuestion() }.sortedWith(Question.ORDER)

    override fun observeQuestions(): Flow<List<Question>> = db.records().byType(QuestionType.name).map(::questionsOf)

    override suspend fun questions(): List<Question> = questionsOf(db.records().listByType(QuestionType.name))

    override suspend fun seedQuestions(language: String): Int {
        var written = 0
        db.withImmediateTransaction {
            for (d in DefaultQuestions.ALL) {
                // A tombstone is a record too: a default the person deleted stays deleted (Reset brings it back).
                if (db.records().get(QuestionType.name, d.id) != null) continue
                // Clean and stamped SEEDED_AT (S4b-BL-90a): an untouched default is never pushed, and whatever another
                // device did to it wins when it is pulled. An edit here makes it dirty with a real time.
                val payload = QuestionType.encode(d.question(language))
                db.records().upsert(
                    RecordEntity(QuestionType.name, d.id, payload, updatedAt = DefaultQuestions.SEEDED_AT, dirty = false),
                )
                written++
            }
        }
        return written
    }

    private val seedGate = Mutex()

    override suspend fun seedQuestionsOnce(language: String) {
        seedGate.withLock {
            if (settings.questionsSeeded()) return
            seedQuestions(language)
            settings.markQuestionsSeeded()
        }
    }

    override suspend fun resetQuestions(language: String) {
        db.withImmediateTransaction {
            for (d in DefaultQuestions.ALL) {
                // A deleted default that would be the 101st question stays deleted (the web skips it too).
                val live = db.records().get(QuestionType.name, d.id)?.deleted == false
                if (!live && db.records().countLive(QuestionType.name) >= Question.MAX_QUESTIONS) continue
                saveRecord(QuestionType, d.id, d.question(language))
            }
        }
    }

    override suspend fun saveQuestion(question: Question) {
        db.withImmediateTransaction { writeQuestion(question) }
    }

    override suspend fun saveQuestions(questions: List<Question>) {
        db.withImmediateTransaction { for (q in questions) writeQuestion(q) }
    }

    /** One question as a record; nothing is written when the record already says the same (a renumbering stamps only the moved). */
    private suspend fun writeQuestion(question: Question) {
        val clean = requireNotNull(question.copy(text = question.text.trim()).coerced()) {
            "a question needs a record id and a text of 1..${Question.MAX_TEXT} characters"
        }
        val stored = db.records().get(QuestionType.name, clean.id)?.takeUnless { it.deleted }
        if (stored?.toQuestion() == clean) return
        if (stored == null && db.records().countLive(QuestionType.name) >= Question.MAX_QUESTIONS) {
            throw RecordLimitException(QuestionType.name, Question.MAX_QUESTIONS)
        }
        saveRecord(QuestionType, clean.id, clean)
    }

    override suspend fun addQuestion(
        text: String,
        category: QuestionCategory,
        appliesTo: QuestionScope,
        defaultOn: Boolean,
    ): String {
        val words = text.trim()
        require(words.isNotEmpty() && words.length <= Question.MAX_TEXT) { "a question needs 1..${Question.MAX_TEXT} characters" }
        var id = ""
        db.withImmediateTransaction {
            val live = db.records().listByType(QuestionType.name)
            if (live.size >= Question.MAX_QUESTIONS) throw RecordLimitException(QuestionType.name, Question.MAX_QUESTIONS)
            // An id a tombstone holds is taken too: reusing it would bring the old question back on another device.
            val used = db.records().versions(QuestionType.name).mapTo(HashSet()) { it.id }
            id = Question.newCustomId({ it in used })
            val sort = (live.mapNotNull { it.toQuestion() }.maxOfOrNull { it.sort } ?: -1) + 1
            saveRecord(
                QuestionType, id,
                Question(id, words, category.name, appliesTo.name, defaultOn, sort),
            )
        }
        return id
    }

    override suspend fun deleteQuestion(id: String) = deleteRecord(QuestionType, id)

    override suspend fun saveAnswers(houseId: String, answers: List<HouseAnswer>?) {
        val house = db.houses().get(houseId)?.takeUnless { it.deleted } ?: return
        saveHouse(house.copy(answers = answers))
    }

    // ---- Viewings (docs/11 5.8, slice 3b-1) ----

    /** A viewing row with its id, coerced; null when the payload does not decode or cannot be trusted (skipped). */
    private fun RecordEntity.toViewing(): Viewing? = decode(ViewingType)?.copy(id = id)?.coerced()

    private fun viewingsOf(rows: List<RecordEntity>): List<Viewing> =
        rows.mapNotNull { it.toViewing() }.sortedWith(Viewing.ORDER)

    override fun observeViewings(): Flow<List<Viewing>> = db.records().byType(ViewingType.name).map(::viewingsOf)

    override suspend fun viewings(): List<Viewing> = viewingsOf(db.records().listByType(ViewingType.name))

    // The versions query lists the tombstones too, without decoding a payload.
    override suspend fun viewingIdsForReminders(): List<String> = db.records().versions(ViewingType.name).map { it.id }

    override suspend fun viewingsOf(houseId: String): List<Viewing> = viewings().filter { it.houseId == houseId }

    override suspend fun nextViewing(houseId: String, nowMs: Long): Viewing? = Viewings.nextOf(viewings(), houseId, nowMs)

    override suspend fun getViewing(id: String): Viewing? =
        db.records().get(ViewingType.name, id)?.takeUnless { it.deleted }?.toViewing()

    override suspend fun saveViewing(viewing: Viewing) {
        val clean = requireNotNull(viewing.coerced()) { "a viewing needs a record id, a house and a start time" }
        db.withImmediateTransaction {
            val stored = db.records().get(ViewingType.name, clean.id)?.takeUnless { it.deleted }?.toViewing()
            // Nothing new: no write, so the record keeps its stamp and is not pushed again.
            if (stored == clean) return@withImmediateTransaction
            saveRecord(ViewingType, clean.id, clean)
        }
    }

    override suspend fun newViewingId(): String {
        // A tombstone's id is taken too: reusing it would bring the old viewing back on another device.
        val used = db.records().versions(ViewingType.name).mapTo(HashSet()) { it.id }
        return Viewing.newId({ it in used })
    }

    override suspend fun deleteViewing(id: String) = deleteRecord(ViewingType, id)

    override suspend fun markViewingDone(id: String, visitId: String?) {
        val viewing = getViewing(id) ?: return
        saveViewing(viewing.copy(status = ViewingStatus.DONE.name, visitId = visitId ?: viewing.visitId))
    }

    // ---- Hunting areas, my places and area notes (docs/11 slice 4a) ----

    private fun RecordEntity.toArea(): Area? = decode(AreaType)?.copy(id = id)?.coerced()
    private fun RecordEntity.toPlace(): Place? = decode(PlaceType)?.copy(id = id)?.coerced()
    private fun RecordEntity.toAreaNote(): AreaNote? = decode(AreaNoteType)?.copy(id = id, updatedAt = updatedAt)?.coerced()

    private fun areasOf(rows: List<RecordEntity>) = rows.mapNotNull { it.toArea() }.sortedWith(Area.BY_NAME)
    private fun placesOf(rows: List<RecordEntity>) = rows.mapNotNull { it.toPlace() }.sortedWith(Place.BY_NAME)
    private fun notesOf(rows: List<RecordEntity>) = rows.mapNotNull { it.toAreaNote() }.sortedWith(AreaNote.NEWEST_FIRST)

    override fun observeAreas(): Flow<List<Area>> = db.records().byType(AreaType.name).map(::areasOf)
    override suspend fun areas(): List<Area> = areasOf(db.records().listByType(AreaType.name))
    override fun observePlaces(): Flow<List<Place>> = db.records().byType(PlaceType.name).map(::placesOf)
    override suspend fun places(): List<Place> = placesOf(db.records().listByType(PlaceType.name))
    override fun observeAreaNotes(): Flow<List<AreaNote>> = db.records().byType(AreaNoteType.name).map(::notesOf)
    override suspend fun areaNotes(): List<AreaNote> = notesOf(db.records().listByType(AreaNoteType.name))

    override suspend fun saveArea(area: Area) {
        val clean = area.copy(name = area.name.trim())
        require(clean.isValid) { "an area needs a record id, a name of 1..${Area.MAX_NAME}, a point and a radius of 200..2000 m" }
        writeCapped(AreaType, clean.id, clean, Area.MAX_AREAS) { it.toArea() }
    }

    override suspend fun savePlace(place: Place) {
        val clean = place.copy(name = place.name.trim())
        require(clean.isValid) { "a place needs a record id, a name of 1..${Place.MAX_NAME} and a point" }
        writeCapped(PlaceType, clean.id, clean, Place.MAX_PLACES) { it.toPlace() }
    }

    override suspend fun saveAreaNote(note: AreaNote) {
        val clean = note.copy(
            areaId = note.areaId?.trim()?.ifEmpty { null }, street = note.street?.trim()?.ifEmpty { null },
            text = note.text.trim(), updatedAt = 0L,
        )
        require(clean.isValid) { "an area note needs a record id, exactly one of an area or a street, and 1..${AreaNote.MAX_TEXT} characters" }
        writeCapped(AreaNoteType, clean.id, clean, AreaNote.MAX_NOTES) { it.toAreaNote()?.copy(updatedAt = 0L) }
    }

    /**
     * Writes [value] unless the live record already says the same (no write: it keeps its stamp and is not pushed
     * again); `RecordLimitException` when it would be live record number [max] + 1 of [type].
     */
    private suspend fun <T> writeCapped(type: RecordType<T>, id: String, value: T, max: Int, read: (RecordEntity) -> T?) {
        db.withImmediateTransaction {
            val stored = db.records().get(type.name, id)?.takeUnless { it.deleted }
            if (stored != null && read(stored) == value) return@withImmediateTransaction
            if (stored == null && db.records().countLive(type.name) >= max) throw RecordLimitException(type.name, max)
            saveRecord(type, id, value)
        }
    }

    // A tombstone's id is taken too: reusing it would bring the old record back on another device.
    private suspend fun usedIds(type: RecordType<*>): Set<String> = db.records().versions(type.name).mapTo(HashSet()) { it.id }

    override suspend fun newAreaId(): String = usedIds(AreaType).let { used -> Area.newId({ it in used }) }
    override suspend fun newPlaceId(): String = usedIds(PlaceType).let { used -> Place.newId({ it in used }) }
    override suspend fun newAreaNoteId(): String = usedIds(AreaNoteType).let { used -> AreaNote.newId({ it in used }) }

    override suspend fun deleteArea(id: String) {
        deleteRecord(AreaType, id)
        // The wake-up's per-area stamp (slice 4b) goes with it; a delete by a sync is pruned when the geofences are set.
        settings.removeAreaLastNotified(id)
    }
    override suspend fun deletePlace(id: String) = deleteRecord(PlaceType, id)
    override suspend fun deleteAreaNote(id: String) = deleteRecord(AreaNoteType, id)

    /**
     * The once-only move of contacts into brokers (slice 1b), on the first read after the update: every live house
     * with a phone number and no broker joins the broker of that number (`PhoneKey`; a number too short to compare
     * stands only for itself; a broker that already has the number is reused; a new one is named from the most
     * recently edited house's contact, else the number) and is linked, the contact it holds left as it is; houses
     * with a blank phone stay unlinked. Guarded by the `brokers.migrated` setting, set at the end even on an empty
     * phone, so a person who unlinks a house on purpose is never re-linked, and it changes nothing a second time.
     * The houses are written dirty with a new `updatedAt`, so the link reaches the server and the other devices.
     */
    suspend fun migrateContactsToBrokers() {
        if (brokersMigrated) return
        brokerGate.withLock {
            if (brokersMigrated) return
            if (!settings.brokersMigrated()) {
                var linkedHouses = 0
                db.withImmediateTransaction {
                    val stamp = now()
                    fun keyOf(phone: String?) = phone?.trim().orEmpty().let { p -> PhoneKey.of(p) ?: "raw:$p" }
                    val groups = db.houses().all()
                        .filter { it.brokerId == null && !it.contactPhone.isNullOrBlank() }
                        .sortedWith(compareBy({ it.createdAt }, { it.id }))
                        .groupBy { keyOf(it.contactPhone) }
                    val known = HashMap<String, String>()
                    for ((id, broker) in liveBrokers()) if (keyOf(broker.phone) !in known) known[keyOf(broker.phone)] = id
                    for ((key, houses) in groups) {
                        // The newest edit names the broker (the later of two equal ones).
                        val newest = houses.reduce { a, b -> if (b.updatedAt >= a.updatedAt) b else a }
                        val id = known[key] ?: saveNewBroker(newBroker(newest.contactName, newest.contactPhone!!.trim())) ?: continue
                        for (h in houses) {
                            db.houses().upsert(h.copy(brokerId = id, updatedAt = stamp, dirty = true))
                            linkedHouses++
                        }
                    }
                }
                settings.markBrokersMigrated()
                if (linkedHouses > 0) syncSoon()
            }
            brokersMigrated = true
        }
    }

    /** A new broker's id, or null when the type is full. */
    private suspend fun saveNewBroker(broker: Broker): String? = try {
        val id = Uuid.random().toString()
        saveRecord(BrokerType, id, checkNotNull(broker.coerced()) { "a broker made from a contact has a name" })
        id
    } catch (e: RecordLimitException) {
        null
    }

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

    /**
     * Deletes the local file now. A photo the server already has is queued (deleted = 1) and the delete is sent on
     * the next sync, even if the phone is offline now (threat model F-15).
     */
    override suspend fun deletePhoto(photo: PhotoEntity): Unit = withContext(Dispatchers.IO) {
        deleteFile(photoFileOf(photo.id))
        if (photo.uploaded) {
            db.photos().markDeleted(photo.id)
            syncSoon()
        } else {
            db.photos().delete(photo.id)
        }
    }

    override fun <T> observeRecords(type: RecordType<T>): Flow<List<Pair<String, T>>> =
        db.records().byType(type.name).map { rows -> rows.mapNotNull { row -> row.decode(type)?.let { row.id to it } } }
            .onStart { migrateContactsToBrokers() }

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

    // ---- AI features (optional; hidden unless the server reports enabled = true) ----

    private val _aiEnabled = MutableStateFlow(false)
    override val aiEnabled: StateFlow<Boolean> = _aiEnabled.asStateFlow()
    private val _aiOff = MutableStateFlow<AiOff?>(AiOff.NO_SERVER)
    override val aiOff: StateFlow<AiOff?> = _aiOff.asStateFlow()

    /** What the server said last: AI on for this device, or why not (NO_SERVER, SERVER, DEVICE). */
    private var serverAi: AiOff? = AiOff.NO_SERVER

    /**
     * Asks the server whether AI features are on for this device. No server set up means off, and so does a real
     * `enabled: false` answer. A failed request (offline, a timeout, a server error) keeps what was known (UX review,
     * whole-app audit): turning the Assistant tab off on every network error removed it while the user was on it.
     * AI is then offered only when this phone's *AI features* switch is on too ([AppSettings.aiFeatures]).
     */
    override suspend fun refreshAiStatus(): Boolean = withContext(Dispatchers.IO) {
        val s = settings.current()
        serverAi = if (!s.serverConfigured) {
            AiOff.NO_SERVER
        } else {
            runCatching { apiFor(s.serverUrl, s.apiKey).aiStatus() }.fold(
                { status ->
                    when {
                        status.offForDevice -> AiOff.DEVICE
                        !status.enabled -> AiOff.SERVER
                        else -> null
                    }
                },
                { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    serverAi
                },
            )
        }
        publishAi(s)
    }

    override suspend fun setAiFeatures(on: Boolean) {
        settings.saveAiFeatures(on)
        publishAi(settings.current())
    }

    /** On-device AI with the person's own key (docs/03 §13.1): chosen, and a key saved. */
    private fun usesOwnKey(s: AppSettings) = s.aiProvider == AiProviderChoice.DEVICE && when (s.aiProviderConfig.kind) {
        AiKind.GEMINI -> s.geminiKey.isNotBlank() && geminiFor != null
        // The key is optional here (a model on the person's own computer needs none).
        AiKind.OPENAI_COMPATIBLE -> openAiFor != null && s.aiProviderConfig.model.isNotBlank() &&
            BaseUrlValidator.check(s.aiProviderConfig.baseUrl, emulatorHostAllowed) is BaseUrlCheck.Valid
        // Anthropic has no keyless form: the key is required.
        AiKind.ANTHROPIC -> anthropicFor != null && s.geminiKey.isNotBlank() && s.aiProviderConfig.model.isNotBlank() &&
            BaseUrlValidator.check(s.aiProviderConfig.baseUrl, emulatorHostAllowed) is BaseUrlCheck.Valid
    }

    /** The model for the saved choice, or null when nothing here can build it. */
    private fun chatModel(config: AiProviderConfig, key: String): JsonChatModel? = when (config.kind) {
        AiKind.GEMINI -> geminiFor?.invoke(key)
        AiKind.OPENAI_COMPATIBLE -> openAiFor?.invoke(config.baseUrl, config.model, key)
        AiKind.ANTHROPIC -> anthropicFor?.invoke(config.baseUrl, config.model, key)
    }

    /**
     * Works out whether AI is offered and why not, from the settings and the server's report, and publishes it to
     * [aiOff] and [aiEnabled]. With the person's own key only this phone's switch counts.
     */
    private fun publishAi(s: AppSettings): Boolean {
        // With the person's own key nothing depends on a server: only this phone's switch counts.
        val off = if (usesOwnKey(s)) (if (s.aiFeatures) null else AiOff.OPT_IN) else serverAi ?: if (s.aiFeatures) null else AiOff.OPT_IN
        _aiOff.value = off
        _aiEnabled.value = off == null
        return off == null
    }

    override suspend fun saveGeminiKey(key: String) {
        settings.saveGeminiKey(key)
        publishAi(settings.current())
    }

    override suspend fun setAiProvider(choice: AiProviderChoice) {
        settings.saveAiProvider(choice)
        publishAi(settings.current())
    }

    override suspend fun removeGeminiKey() {
        settings.removeGeminiKey()
        publishAi(settings.current())
    }

    override suspend fun testGeminiKey(key: String): Result<Unit> = testAiProvider(AiProviderConfig.GEMINI, key)

    override suspend fun saveAiProviderConfig(config: AiProviderConfig, key: String) {
        val checked = checkedConfig(config)
        require(checked.kind != AiKind.ANTHROPIC || key.isNotBlank()) { "key" }
        settings.saveAiProviderConfig(checked, key)
        publishAi(settings.current())
    }

    override suspend fun testAiProvider(config: AiProviderConfig, key: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val client = chatModel(checkedConfig(config), key.trim()) ?: throw IllegalStateException("no provider")
            client.ping()
        }
    }

    /** [config] with its base URL normalised, or an [IllegalArgumentException] saying what is wrong with it. */
    private fun checkedConfig(config: AiProviderConfig): AiProviderConfig = when (config.kind) {
        AiKind.GEMINI -> AiProviderConfig.GEMINI
        AiKind.OPENAI_COMPATIBLE, AiKind.ANTHROPIC -> {
            // Anthropic has one address; a config saved without one means that.
            val address = if (config.kind == AiKind.ANTHROPIC && config.baseUrl.isBlank()) AnthropicClient.BASE_URL else config.baseUrl
            val url = when (val check = BaseUrlValidator.check(address, emulatorHostAllowed)) {
                is BaseUrlCheck.Valid -> check.normalised
                is BaseUrlCheck.Invalid -> throw IllegalArgumentException(check.reason.wire)
            }
            require(config.model.isNotBlank()) { "model" }
            config.copy(baseUrl = url, model = config.model.trim())
        }
    }

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

    /** On-device AI for the saved key, or null when AI goes through the server. One per key, so its rate limit holds. */
    private var onDevice: Pair<Pair<AiProviderConfig, String>, OnDeviceAi>? = null

    /**
     * The on-device AI for the saved key, or null when AI goes through the server; the instance is reused while
     * the key and provider stay the same.
     */
    private suspend fun ownKeyAi(): OnDeviceAi? {
        val s = settings.current()
        if (!usesOwnKey(s)) return null
        val key = s.aiProviderConfig to s.geminiKey
        onDevice?.let { (saved, ai) -> if (saved == key) return ai }
        return OnDeviceAi(chatModel(s.aiProviderConfig, s.geminiKey)!!, ::aiHouses).also { onDevice = key to it }
    }

    /**
     * Runs [block] against the configured server on the IO dispatcher; throws `IllegalStateException` when no
     * server is set.
     */
    private suspend fun <T> withApi(block: suspend (ApiClient) -> T): T = withContext(Dispatchers.IO) {
        val s = settings.current()
        check(s.serverConfigured) { "Server not configured" }
        block(apiFor(s.serverUrl, s.apiKey))
    }

    // The same three calls, answered by the server or on this device (ADR-26): the screens do not know which.
    override suspend fun extractListing(text: String): HouseDraftDto =
        ownKeyAi()?.let { withContext(Dispatchers.IO) { it.extractListing(text) } } ?: withApi { it.extractListing(text) }

    override suspend fun ask(question: String): AskResponseDto =
        ownKeyAi()?.let { withContext(Dispatchers.IO) { it.ask(question) } } ?: withApi { it.ask(question) }

    override suspend fun planVisits(request: PlanRequest): PlanResponseDto =
        ownKeyAi()?.let { withContext(Dispatchers.IO) { it.planVisits(request) } } ?: withApi { it.planVisits(request) }

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
                    deleteFile(photoFileOf(local.id)); db.photos().delete(change.id); pulled++
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
                        writeFile(out, bytes)
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
            val size = fs.metadataOrNull(file)?.size
            if (size == null || db.houses().get(p.houseId)?.deleted != false) continue
            if (!photosAllowed) {
                photosWaiting++; continue
            }
            // Streamed from the file, not read into memory; each (re)try opens the file again. A permanent refusal
            // keeps the photo on this phone only ([SyncBackend.uploadPhoto]); anything else aborts the sync.
            backend.uploadPhoto(p.houseId, p.id, file.name, size) { fs.source(file).buffered() }
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
        migrateContactsToBrokers()
        LocalRows(
            db.houses().all(), db.visits().all(), db.photos().all(),
            liveBrokerRows().map { (row, broker) -> ExportBroker.of(row.id, broker, row.updatedAt) },
            lengthUnit = settings.lengthUnit.first(),
            // At most 40 criteria (more can only come from a newer app's sync), so the copy's own check accepts it.
            criteria = db.records().listByType(CriterionType.name)
                .mapNotNull { row -> row.toCriterion()?.let { ExportCriterion.of(it, row.updatedAt) } }
                .take(Criterion.MAX_CRITERIA),
            preferences = db.records().listByType(PreferenceType.name).mapNotNull { row ->
                row.decode(PreferenceType)?.let { ExportPreference(row.id, it.value.take(Preference.MAX_VALUE), row.updatedAt) }
            },
            // At most 100 questions, for the same reason as the criteria.
            questions = db.records().listByType(QuestionType.name)
                .mapNotNull { row -> row.toQuestion()?.let { ExportQuestion.of(it, row.updatedAt) } }
                .take(Question.MAX_QUESTIONS),
            // The viewings (slice 3b-1): untrusted rows are skipped, so the copy's own check accepts what it writes.
            viewings = db.records().listByType(ViewingType.name)
                .mapNotNull { row -> row.toViewing()?.let { ExportViewing.of(it, row.updatedAt) } }
                .take(Viewing.MAX_VIEWINGS),
            // Areas, places and area notes (slice 4a): untrusted rows are skipped, a stored radius out of range is 500, and
            // nothing is cut to the caps (as the website and the server read them; the caps hold at each save).
            areas = db.records().listByType(AreaType.name)
                .mapNotNull { row -> row.toArea()?.let { ExportArea.of(it, row.updatedAt) } },
            places = db.records().listByType(PlaceType.name)
                .mapNotNull { row -> row.toPlace()?.let { ExportPlace.of(it, row.updatedAt) } },
            areaNotes = db.records().listByType(AreaNoteType.name)
                .mapNotNull { row -> row.toAreaNote()?.let { ExportAreaNote.of(it, row.updatedAt) } },
            // The tombstones, for an update file's deletions (S4b-BL-82).
            deletedHouses = db.houses().deletedIds().toSet().let { gone ->
                db.houses().versions().filter { it.id in gone }.associate { it.id to it.updatedAt }
            },
        )
    }

    private suspend fun liveBrokerRows(): List<Pair<RecordEntity, Broker>> =
        db.records().listByType(BrokerType.name).mapNotNull { row -> row.toBroker()?.let { row to it } }

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
    fun photoPath(id: String): Path {
        photoDirPath()
        return photoFileOf(id)
    }

    /**
     * The file photo [id]'s bytes live in, built from the photo folder and the id; creates nothing. Every read of a
     * photo row's file goes through this rather than the row's stored `path` (S4b-BL-52): on iOS the app's container
     * folder changes when the app is updated, so a stored full path goes stale, while the id does not. On Android the
     * folder does not move, so this is the same file the row names.
     */
    fun photoFileOf(id: String): Path = photoFileIn(photoDir, id)

    /**
     * The photo path for an id that came out of a backup, or null when it would not land in the photo
     * directory.
     *
     * `BackupValidation.checkData` already refuses a backup whose ids are not `[A-Za-z0-9_-]{1,64}`, so this
     * never fires on a file that got this far; it is here because [photoPath] interpolates its argument straight
     * into a path, and a second, independent check costs one path resolution per photo. The id check alone keeps
     * the name inside the folder (no separator, no `..`); a file already at that name is resolved as well, so a link
     * to an existing file elsewhere is refused. A dangling link is not caught (`exists` follows links), as the old
     * `canonicalPath` check did not catch it either; the folder is the app's private one. A file that vanishes between
     * the two calls makes the photo count as skipped rather than stopping the import. Defence in depth for the same
     * bug class as the zip-slip guard on entry names.
     */
    private fun importedPhotoPath(id: String): Path? {
        if (!BackupValidation.isValidId(id)) return null
        val path = photoPath(id)
        if (!fs.exists(path)) return path
        return runCatching {
            if (fs.resolve(path).parent == fs.resolve(photoDirPath())) path else null
        }.getOrNull()
    }

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
            db.records().upsert(importedBroker(broker, broker.updatedAt))
            onProgress(++done, total)
        }
        // Criteria and preferences (slice 2), by key with the file's `updatedAt`: the plan kept the new and newer ones.
        for (c in actions.criteria) {
            db.records().upsert(importedCriterion(c, c.updatedAt))
            onProgress(++done, total)
        }
        for (p in actions.preferences) {
            db.records().upsert(importedPreference(p, p.updatedAt))
            onProgress(++done, total)
        }
        // The question bank (slice 3a), by id with the file's `updatedAt`, like the criteria.
        for (q in actions.questions) {
            db.records().upsert(importedQuestion(q, q.updatedAt))
            onProgress(++done, total)
        }
        // The viewings (slice 3b-1), by id with the file's `updatedAt`: a newer row brings back one deleted here.
        for (v in actions.viewings) {
            db.records().upsert(importedViewing(v, v.updatedAt))
            onProgress(++done, total)
        }
        // Areas, places and area notes (slice 4a), by id with the file's `updatedAt`, like the questions.
        for (row in importedSlice4a(actions) { it }) {
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
            val out = importedPhotoPath(photo.id)
            if (bytes != null && out != null && db.houses().get(photo.houseId)?.deleted == false) {
                writeFile(out, bytes)
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

    /** A backup's broker as the record row an import writes: values coerced, dirty so it is pushed. */
    private fun importedBroker(b: ExportBroker, updatedAt: Long): RecordEntity = RecordEntity(
        type = BrokerType.name, id = b.id, payload = BrokerType.encode(checkNotNull(b.toBroker().coerced()) { "broker ${b.id} was not checked" }),
        updatedAt = updatedAt, deleted = false, dirty = true,
    )

    /** A backup's criterion as its record row: coerced (the plan checked it), dirty so it is pushed. */
    private fun importedCriterion(c: ExportCriterion, updatedAt: Long): RecordEntity = RecordEntity(
        type = CriterionType.name, id = c.key,
        payload = CriterionType.encode(checkNotNull(c.toCriterion().coerced()) { "criterion ${c.key} was not checked" }),
        updatedAt = updatedAt, deleted = false, dirty = true,
    )

    /** A backup's question as its record row: coerced (the plan checked it), dirty so it is pushed. */
    private fun importedQuestion(q: ExportQuestion, updatedAt: Long): RecordEntity = RecordEntity(
        type = QuestionType.name, id = q.id,
        payload = QuestionType.encode(checkNotNull(q.toQuestion().coerced()) { "question ${q.id} was not checked" }),
        updatedAt = updatedAt, deleted = false, dirty = true,
    )

    /** A backup's viewing as its record row: coerced (the plan checked it), dirty so it is pushed. */
    private fun importedViewing(v: ExportViewing, updatedAt: Long): RecordEntity = RecordEntity(
        type = ViewingType.name, id = v.id,
        payload = ViewingType.encode(checkNotNull(v.toViewing().coerced()) { "viewing ${v.id} was not checked" }),
        updatedAt = updatedAt, deleted = false, dirty = true,
    )

    /**
     * A backup's areas, places and area notes as record rows (slice 4a), stamped by [stamp] from the file's `updatedAt`:
     * coerced (the plan checked them), dirty so they are pushed.
     */
    private fun importedSlice4a(actions: ImportActions, stamp: (Long) -> Long): List<RecordEntity> =
        actions.areas.map { a ->
            imported(AreaType, a.id, checkNotNull(a.toArea()?.coerced()) { "area ${a.id} was not checked" }, stamp(a.updatedAt))
        } + actions.places.map { p ->
            imported(PlaceType, p.id, checkNotNull(p.toPlace()?.coerced()) { "place ${p.id} was not checked" }, stamp(p.updatedAt))
        } + actions.areaNotes.map { n ->
            imported(AreaNoteType, n.id, checkNotNull(n.toAreaNote().coerced()) { "note ${n.id} was not checked" }, stamp(n.updatedAt))
        }

    private fun <T> imported(type: RecordType<T>, id: String, value: T, updatedAt: Long) = RecordEntity(
        type = type.name, id = id, payload = type.encode(value), updatedAt = updatedAt, deleted = false, dirty = true,
    )

    private fun importedPreference(p: ExportPreference, updatedAt: Long): RecordEntity = RecordEntity(
        type = PreferenceType.name, id = p.key, payload = PreferenceType.encode(Preference(p.value)),
        updatedAt = updatedAt, deleted = false, dirty = true,
    )

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
                val out = importedPhotoPath(photo.id)
                if (bytes != null && out != null && photo.houseId in newHouseIds) {
                    written[out] = photo.id
                    writeFile(out, bytes)
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
                    writeRecord(importedBroker(broker, CopyUndo.copyStamp(broker.updatedAt, now)))
                    onProgress(++done, total)
                }
                // A copy keeps the criteria's keys (the copied houses' scores name them) and merges them like a merge.
                for (c in actions.criteria) {
                    writeRecord(importedCriterion(c, CopyUndo.copyStamp(c.updatedAt, now)))
                    onProgress(++done, total)
                }
                for (p in actions.preferences) {
                    db.records().upsert(importedPreference(p, CopyUndo.copyStamp(p.updatedAt, now)))
                    onProgress(++done, total)
                }
                for (q in actions.questions) {
                    writeRecord(importedQuestion(q, CopyUndo.copyStamp(q.updatedAt, now)))
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
                    writeRecord(importedViewing(v, CopyUndo.copyStamp(v.updatedAt, now)))
                    onProgress(++done, total)
                }
                // A copy keeps the ids of areas, places and notes (a note names its area) and merges them like a merge.
                for (row in importedSlice4a(actions) { CopyUndo.copyStamp(it, now) }) {
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

    /**
     * After a copy import that did not finish ([applyCopy]'s catch): deletes each photo file it wrote ([written], file
     * to photo id) whose row is not in the database, and keeps the others. The transaction is all or nothing, so
     * after a rollback no row is there and every file goes, as before; but a cancellation of the caller can also land
     * after the commit (the rows are written, then the resumption throws), and deleting then would leave committed
     * rows pointing at missing files (code review of PR #23). A row that cannot be read counts as missing, the old
     * rule. `NonCancellable`, because the caller is usually being cancelled right now (Room 2.8's reads do not check
     * that today, but nothing promises it); only these reads and deletes are, never the transaction itself.
     */
    protected suspend fun discardUncommittedPhotoFiles(written: Map<Path, String>) = withContext(NonCancellable) {
        for ((file, id) in written) {
            val committed = runCatching { db.photos().get(id) != null }.getOrDefault(false)
            if (!committed) deleteFile(file)
        }
    }

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
                        ViewingType.name -> row.toViewing()?.houseId?.let { h -> live.any { it.id == h } } == true
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
        files.forEach { deleteFile(it) }
        if (removed > 0 || changedRecords) syncSoon()
        UndoResult(removed, keptHouses.size, keptHouses)
    }

    companion object {
        /** Ids per local read of a pull page, under SQLite's default limit of 999 variables (S4b-BL-167); the web uses the same. */
        internal const val PULL_READ_CHUNK = 500

        /** Photo [id]'s file in the photo folder [photoDir]: `<photoDir>/<id>.jpg` ([photoFileOf]; `PhotoFileTest`). */
        internal fun photoFileIn(photoDir: String, id: String): Path = Path(photoDir, "$id.jpg")
    }
}
