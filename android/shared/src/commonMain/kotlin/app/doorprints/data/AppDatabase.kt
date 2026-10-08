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

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow
import app.doorprints.shared.model.HouseAnswer
import app.doorprints.shared.model.HouseAnswers
import app.doorprints.shared.model.HouseRoom
import app.doorprints.shared.model.HouseRooms
import app.doorprints.shared.model.MoveIn
import app.doorprints.shared.model.PhotoTags
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Room's converters from the model's structured values (checklist, rooms, answers, move-in, photo tags) to JSON
 * text and back. The rooms, answers, move-in and tags read leniently: text that does not decode reads as empty,
 * never as a crash.
 */
class Converters {
    private val mapSerializer = MapSerializer(String.serializer(), Int.serializer())

    @TypeConverter
    fun checklistToJson(map: Map<String, Int>): String = Json.encodeToString(mapSerializer, map)

    @TypeConverter
    fun jsonToChecklist(json: String): Map<String, Int> =
        if (json.isBlank()) emptyMap() else Json.decodeFromString(mapSerializer, json)

    /** The rooms (slice 1c) as compact JSON in the format's key order; null for none, never `[]`. */
    @TypeConverter
    fun roomsToJson(rooms: List<HouseRoom>?): String? = HouseRooms.encode(rooms)

    /** Coerced on the way out as well: text that does not decode (never written here) reads as no rooms, not a crash. */
    @TypeConverter
    fun jsonToRooms(json: String?): List<HouseRoom>? = HouseRooms.coerced(HouseRooms.decode(json))

    /** The questions asked (slice 3a) as compact JSON in the format's key order; null for none, never `[]`. */
    @TypeConverter
    fun answersToJson(answers: List<HouseAnswer>?): String? = HouseAnswers.encode(answers)

    /** Coerced on the way out, like the rooms: text that does not decode reads as no answers, not a crash. */
    @TypeConverter
    fun jsonToAnswers(json: String?): List<HouseAnswer>? = HouseAnswers.coerced(HouseAnswers.decode(json))

    /** Moving in (slice 5) as compact JSON in the format's key order; null for none. */
    @TypeConverter
    fun moveInToJson(moveIn: MoveIn?): String? = MoveIn.encode(MoveIn.coerced(moveIn))

    /** Coerced on the way out, like the rooms: text that does not decode reads as no move-in, not a crash. */
    @TypeConverter
    fun jsonToMoveIn(json: String?): MoveIn? = MoveIn.coerced(MoveIn.decode(json))

    private val tagsSerializer = ListSerializer(String.serializer())

    /** A photo's tags (slice 5) as a JSON array; null for none, never `[]`. */
    @TypeConverter
    fun tagsToJson(tags: List<String>?): String? = tags?.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(tagsSerializer, it) }

    /** Coerced on the way out: text that does not decode reads as no tags. */
    @TypeConverter
    fun jsonToTags(json: String?): List<String>? =
        json?.takeIf { it.isNotBlank() }?.let { runCatching { Json.decodeFromString(tagsSerializer, it) }.getOrNull() }
            ?.let { PhotoTags.coerced(it) }?.takeIf { it.isNotEmpty() }
}

/**
 * The `houses` table. Reads hide tombstones unless they say otherwise; `dirty`, `markClean` and `versions` serve
 * the sync loop and the import preview. A row is marked clean only while its `updatedAt` is still the one that was
 * pushed, so an edit made during a sync stays dirty.
 */
@Dao
interface HouseDao {
    @Query("SELECT * FROM houses WHERE deleted = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<HouseEntity>>

    @Query("SELECT * FROM houses WHERE deleted = 0")
    suspend fun all(): List<HouseEntity>

    @Query("SELECT * FROM houses WHERE id = :id")
    fun observe(id: String): Flow<HouseEntity?>

    @Query("SELECT * FROM houses WHERE id = :id")
    suspend fun get(id: String): HouseEntity?

    /** The stored houses with these ids, tombstones included, in one query; at most `CommonRepository.PULL_READ_CHUNK` ids (SQLite's variable limit). */
    @Query("SELECT * FROM houses WHERE id IN (:ids)")
    suspend fun getMany(ids: List<String>): List<HouseEntity>

    @Upsert
    suspend fun upsert(house: HouseEntity)

    @Query("SELECT * FROM houses WHERE dirty = 1")
    suspend fun dirty(): List<HouseEntity>

    @Query("UPDATE houses SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    /** Every house, tombstones too, to be sent again: the server was found behind this phone (S4b-BL-20). */
    @Query("UPDATE houses SET dirty = 1")
    suspend fun markAllDirty()

    @Query("SELECT COUNT(*) FROM houses WHERE deleted = 0 AND street IS NOT NULL AND LOWER(street) = LOWER(:street)")
    suspend fun countOnStreet(street: String): Int

    /**
     * Tombstones included (Sprint 4a, import preview): a deleted house still has an `updatedAt`, and an older row
     * in a backup must not bring it back to life. Only the two columns the merge rule needs are read.
     */
    @Query("SELECT id, updatedAt FROM houses")
    suspend fun versions(): List<RowVersion>

    /**
     * The tombstones out of [versions], for the import preview. `ImportPlan` needs both: an id it must still
     * compare timestamps against, *and* the fact that the house is not somewhere a photo can be attached.
     */
    @Query("SELECT id FROM houses WHERE deleted = 1")
    suspend fun deletedIds(): List<String>

    /** The tombstones themselves, in one query: the Drive sync writes them to the other devices (no `get` per id). */
    @Query("SELECT * FROM houses WHERE deleted = 1")
    suspend fun deleted(): List<HouseEntity>

    /**
     * The tombstones of [deletedIds] that have reached the server (pushed from here, or pulled from another device):
     * only those were purged there, so only their photos need fresh ids on a restore (Android review, round 13).
     * A query only, so the Room schema and identity hash do not change.
     */
    @Query("SELECT id FROM houses WHERE deleted = 1 AND dirty = 0")
    suspend fun syncedDeletedIds(): List<String>

    /** A broker's live houses, for its page and for the rewrite of the contact copies (slice 1b). */
    @Query("SELECT * FROM houses WHERE deleted = 0 AND brokerId = :brokerId ORDER BY updatedAt DESC")
    suspend fun liveForBroker(brokerId: String): List<HouseEntity>

    @Query("SELECT * FROM houses WHERE deleted = 0 AND brokerId = :brokerId ORDER BY updatedAt DESC")
    fun observeForBroker(brokerId: String): Flow<List<HouseEntity>>
}

/** The `visits` table, read and marked clean like [HouseDao]. */
@Dao
interface VisitDao {
    @Query("SELECT * FROM visits WHERE deleted = 0 AND houseId = :houseId ORDER BY arrivedAt DESC")
    fun observeForHouse(houseId: String): Flow<List<VisitEntity>>

    @Query(
        "SELECT houseId, COUNT(*) AS visits, MAX(arrivedAt) AS lastVisit FROM visits " +
            "WHERE deleted = 0 AND houseId IS NOT NULL GROUP BY houseId"
    )
    fun observeCounts(): Flow<List<HouseVisitCount>>

    @Query("SELECT * FROM visits WHERE id = :id")
    suspend fun get(id: String): VisitEntity?

    /** The stored visits with these ids, tombstones included, in one query (see [HouseDao.getMany]). */
    @Query("SELECT * FROM visits WHERE id IN (:ids)")
    suspend fun getMany(ids: List<String>): List<VisitEntity>

    @Upsert
    suspend fun upsert(visit: VisitEntity)

    @Query("SELECT * FROM visits WHERE dirty = 1")
    suspend fun dirty(): List<VisitEntity>

    @Query("UPDATE visits SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    /** Every visit, tombstones too, to be sent again (S4b-BL-20). */
    @Query("UPDATE visits SET dirty = 1")
    suspend fun markAllDirty()

    @Query("SELECT COUNT(*) FROM visits WHERE deleted = 0 AND street IS NOT NULL AND LOWER(street) = LOWER(:street)")
    suspend fun countOnStreet(street: String): Int

    @Query("SELECT MIN(arrivedAt) FROM visits WHERE deleted = 0 AND street IS NOT NULL AND LOWER(street) = LOWER(:street)")
    suspend fun firstOnStreet(street: String): Long?

    /** Every live visit, oldest first: the export reads the whole table once. */
    @Query("SELECT * FROM visits WHERE deleted = 0 ORDER BY arrivedAt, id")
    suspend fun all(): List<VisitEntity>

    /** Tombstones included, see [HouseDao.versions]. */
    @Query("SELECT id, updatedAt FROM visits")
    suspend fun versions(): List<RowVersion>

    /** The deleted visits themselves, in one query (the Drive sync writes the tombstones to the other devices). */
    @Query("SELECT * FROM visits WHERE deleted = 1")
    suspend fun deleted(): List<VisitEntity>

    /**
     * Live visits with no house, for the import (Android review, round 12): after a synced house delete the server
     * sends that house's visits back unlinked, and a restore of the house puts them back in it.
     */
    @Query("SELECT id FROM visits WHERE houseId IS NULL AND deleted = 0")
    suspend fun unlinkedIds(): List<String>

    /** A house's live visits, for the undo of a copy import (a query only; the schema does not change). */
    @Query("SELECT * FROM visits WHERE houseId = :houseId AND deleted = 0")
    suspend fun liveForHouse(houseId: String): List<VisitEntity>
}

/**
 * The `photos` table: the rows, the upload and delete queues (`uploaded`, `deleted`) and the metadata flags
 * (`metaDirty`) that the photo sync works through.
 */
@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos WHERE houseId = :houseId AND deleted = 0 ORDER BY createdAt")
    fun observeForHouse(houseId: String): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE uploaded = 0 AND deleted = 0")
    suspend fun pendingUpload(): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE deleted = 1")
    suspend fun pendingDelete(): List<PhotoEntity>

    @Query("SELECT COUNT(*) FROM photos WHERE houseId = :houseId AND deleted = 0")
    suspend fun countLive(houseId: String): Int

    @Query("UPDATE photos SET deleted = 1 WHERE id = :id")
    suspend fun markDeleted(id: String)

    /** Every live photo to be uploaded again (S4b-BL-20); the deletes still queued stay queued. */
    @Query("UPDATE photos SET uploaded = 0 WHERE deleted = 0")
    suspend fun markAllForUpload()

    @Upsert
    suspend fun upsert(photo: PhotoEntity)

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun get(id: String): PhotoEntity?

    /** The stored photo rows with these ids, deleted ones included, in one query (see [HouseDao.getMany]). */
    @Query("SELECT * FROM photos WHERE id IN (:ids)")
    suspend fun getMany(ids: List<String>): List<PhotoEntity>

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun delete(id: String)

    /** Every live photo, oldest first: the export reads the whole table once. */
    @Query("SELECT * FROM photos WHERE deleted = 0 ORDER BY createdAt, id")
    suspend fun all(): List<PhotoEntity>

    /** Including ones queued for deletion, so an import does not re-create a photo the user just deleted. */
    @Query("SELECT id FROM photos")
    suspend fun allIds(): List<String>

    /** A house's live photos, for the undo of a copy import (a query only; the schema does not change). */
    @Query("SELECT * FROM photos WHERE houseId = :houseId AND deleted = 0")
    suspend fun liveForHouse(houseId: String): List<PhotoEntity>

    /** Live photos the server has whose metadata changed here since the last sync (slice 5). */
    @Query("SELECT * FROM photos WHERE metaDirty = 1 AND uploaded = 1 AND deleted = 0")
    suspend fun pendingMeta(): List<PhotoEntity>

    /** Clears the flag only when the meta sent is still the stored one: an edit made during the sync stays dirty. */
    @Query("UPDATE photos SET metaDirty = 0 WHERE id = :id AND metaUpdatedAt = :metaUpdatedAt")
    suspend fun markMetaClean(id: String, metaUpdatedAt: Long)

    /** Every photo's metadata edit time, for an import's last-write-wins (slice 5). */
    @Query("SELECT id, metaUpdatedAt AS updatedAt FROM photos")
    suspend fun metaVersions(): List<RowVersion>

    /** Every live photo with metadata to be sent again (S4b-BL-20, slice 5). */
    @Query("UPDATE photos SET metaDirty = 1 WHERE deleted = 0 AND metaUpdatedAt > 0")
    suspend fun markAllMetaDirty()
}

/** The `track_points` table, the path trace of the last days; local to this phone and never synced. */
@Dao
interface TrackDao {
    /** The trace of the last days, oldest first, for the map's line (docs/11 5.27). */
    @Query("SELECT * FROM track_points WHERE at >= :since ORDER BY at")
    fun observeSince(since: Long): Flow<List<TrackPointEntity>>

    @Insert
    suspend fun insert(point: TrackPointEntity)

    /** The retention limit: points older than [before] go. */
    @Query("DELETE FROM track_points WHERE at < :before")
    suspend fun deleteBefore(before: Long)

    @Query("DELETE FROM track_points")
    suspend fun deleteAll()

    /** The trace of the last days, read once, oldest first. */
    @Query("SELECT * FROM track_points WHERE at >= :since ORDER BY at")
    suspend fun since(since: Long): List<TrackPointEntity>

    /** The points of one walk, oldest first. */
    @Query("SELECT * FROM track_points WHERE walkId = :walkId ORDER BY at")
    suspend fun ofWalk(walkId: Long): List<TrackPointEntity>

    /** *Delete this walk* and the move into `saved_walks`: the walk's points go. */
    @Query("DELETE FROM track_points WHERE walkId = :walkId")
    suspend fun deleteWalk(walkId: Long)

    /** The walk ids newer than the watermark, newest first, other than the live walk's (docs/11 5.27.6). */
    @Query("SELECT DISTINCT walkId FROM track_points WHERE walkId > :askedUpTo AND walkId != :liveWalkId ORDER BY walkId DESC")
    suspend fun walkIdsAbove(askedUpTo: Long, liveWalkId: Long): List<Long>
}

/**
 * The `saved_walks` table (Room version 11): every read joins `houses` and skips a tombstone, so a deleted house's walks
 * are hidden at once and come back with *Undo*; [sweepOfDeletedHouses] removes them when the delete is final.
 */
@Dao
interface SavedWalkDao {
    @Insert
    suspend fun insert(walk: SavedWalkEntity)

    /** Every saved walk of a live house with its bytes, newest first (the Map and the detection). */
    @Query("SELECT w.* FROM saved_walks w JOIN houses h ON h.id = w.houseId WHERE h.deleted = 0 ORDER BY w.startedAt DESC")
    suspend fun live(): List<SavedWalkEntity>

    /** [live] without the bytes (same order): the walks are then read one at a time with [get]. */
    @Query("SELECT w.id, w.startedAt FROM saved_walks w JOIN houses h ON h.id = w.houseId WHERE h.deleted = 0 ORDER BY w.startedAt DESC")
    suspend fun liveRefs(): List<SavedWalkRef>

    /** One house's saved walks without the bytes, newest first. */
    @Query(
        "SELECT w.id, w.houseId, w.startedAt, w.endedAt, w.pointCount, w.lengthM FROM saved_walks w " +
            "JOIN houses h ON h.id = w.houseId WHERE h.deleted = 0 AND w.houseId = :houseId ORDER BY w.startedAt DESC",
    )
    fun observeForHouse(houseId: String): Flow<List<SavedWalkSummary>>

    @Query("SELECT * FROM saved_walks WHERE id = :id")
    suspend fun get(id: String): SavedWalkEntity?

    /** The count shown in Settings: the walks of live houses. */
    @Query("SELECT COUNT(*) FROM saved_walks w JOIN houses h ON h.id = w.houseId WHERE h.deleted = 0")
    fun observeCount(): Flow<Int>

    /** For the 20-a-house limit. */
    @Query("SELECT COUNT(*) FROM saved_walks WHERE houseId = :houseId")
    suspend fun countForHouse(houseId: String): Int

    /** For the 200-a-device limit. */
    @Query("SELECT COUNT(*) FROM saved_walks")
    suspend fun count(): Int

    @Query("DELETE FROM saved_walks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM saved_walks")
    suspend fun deleteAll()

    /** A saved walk never outlives its house: every walk whose house is a tombstone or missing goes. */
    @Query("DELETE FROM saved_walks WHERE houseId NOT IN (SELECT id FROM houses WHERE deleted = 0)")
    suspend fun sweepOfDeletedHouses(): Int
}

/**
 * The `records` table (docs/11 5.30, Room version 4): one DAO for every record type, the type's name a column, so
 * slice 1 onwards adds no DAO. The sync functions mirror [HouseDao]'s.
 */
@Dao
interface RecordDao {
    /** The live rows of one type, for a screen. */
    @Query("SELECT * FROM records WHERE type = :type AND deleted = 0 ORDER BY id")
    fun byType(type: String): Flow<List<RecordEntity>>

    /** The live rows of one type, read once (an export). */
    @Query("SELECT * FROM records WHERE type = :type AND deleted = 0 ORDER BY id")
    suspend fun listByType(type: String): List<RecordEntity>

    /** Tombstones included: the sync's last-write-wins needs a deleted row's `updatedAt` too. */
    @Query("SELECT * FROM records WHERE type = :type AND id = :id")
    suspend fun get(type: String, id: String): RecordEntity?

    /** The stored records of one [type] with these ids, tombstones included, in one query (see [HouseDao.getMany]). */
    @Query("SELECT * FROM records WHERE type = :type AND id IN (:ids)")
    suspend fun getMany(type: String, ids: List<String>): List<RecordEntity>

    @Upsert
    suspend fun upsert(record: RecordEntity)

    @Query("SELECT * FROM records WHERE dirty = 1")
    suspend fun dirty(): List<RecordEntity>

    /** Clean only while the row is still the one that was pushed, as [HouseDao.markClean] does. */
    @Query("UPDATE records SET dirty = 0 WHERE type = :type AND id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(type: String, id: String, updatedAt: Long)

    /** Every record, tombstones too, to be sent again: the server was found behind this phone (S4b-BL-20). */
    @Query("UPDATE records SET dirty = 1")
    suspend fun markAllDirty()

    /** The live and deleted rows of one type with their versions, for the import's last-write-wins (slice 1b). */
    @Query("SELECT id, updatedAt FROM records WHERE type = :type")
    suspend fun versions(type: String): List<RowVersion>

    /** Every row of every type, tombstones included (tests, and the backup's writer from slice 1). */
    @Query("SELECT * FROM records ORDER BY type, id")
    suspend fun all(): List<RecordEntity>

    /** The "remove all local data" path: records go with the houses. */
    @Query("DELETE FROM records")
    suspend fun deleteAll()

    /** For the per-type cap (`RecordRules.MAX_ROWS_PER_TYPE`) before a new row is written. */
    @Query("SELECT COUNT(*) FROM records WHERE type = :type AND deleted = 0")
    suspend fun countLive(type: String): Int
}

// Room KMP since CMP-4 P4a (ADR-23): this file moved from :app to :shared commonMain with its package, tables,
// columns, version and migration unchanged. Every DAO function is suspend or returns a Flow, as common code requires.
// exportSchema (Sprint 3.5): Room writes shared/schemas/app.doorprints.data.AppDatabase/<version>.json on every build
// (room { schemaDirectory } in shared/build.gradle.kts). The committed <version>.json files and RoomSchemaTest pin the
// identity hash, so a change to the table layout fails the unit tests instead of crashing upgraded installs with "Room
// cannot verify the data integrity". The builders are per platform: :app's data/AppDatabaseFactory.kt (the Context, the
// file name through DatabaseFile, the framework SQLite) and iosMain's AppDatabaseIos.kt (the bundled driver).
@Database(
    entities = [
        HouseEntity::class, VisitEntity::class, PhotoEntity::class, TrackPointEntity::class, RecordEntity::class,
        SavedWalkEntity::class,
    ],
    version = 11,
    exportSchema = true,
)
/**
 * The phone's Room database (houses, visits, photos, path trace, records, saved walks), one for Android and iOS.
 * The version, [MIGRATIONS] and the exported schema files are pinned by `RoomSchemaTest`, so a table change cannot
 * reach an installed app unmigrated.
 */
@TypeConverters(Converters::class)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun houses(): HouseDao
    abstract fun visits(): VisitDao
    abstract fun photos(): PhotoDao
    abstract fun track(): TrackDao
    abstract fun records(): RecordDao
    abstract fun savedWalks(): SavedWalkDao

    companion object {
        /**
         * v2: photos.deleted, the local queue of photo deletes to send to the server (threat model F-15). Written
         * against Room's common [SQLiteConnection]: Room calls this overload with the framework open helper on Android
         * too (wrapped in a connection), so one migration serves both platforms (`AppDatabaseMigrationTest`).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE photos ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3 (S4b-FR-2, 2026-09-29): the path trace's `track_points`, a local-only table (never synced). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `track_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`at` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `accuracyM` REAL NOT NULL)",
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_track_points_at` ON `track_points` (`at`)")
            }
        }

        /**
         * v4 (docs/11 5.30 slice 0, ADR-28, 2026-09-30): the `records` table, one envelope for every new kind of data
         * of Sprint 4b; the SQL is what Room generates for [RecordEntity] (`4.json`).
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `records` (`type` TEXT NOT NULL, `id` TEXT NOT NULL, " +
                        "`payload` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, " +
                        "`dirty` INTEGER NOT NULL, PRIMARY KEY(`type`, `id`))",
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_records_type` ON `records` (`type`)")
            }
        }

        /**
         * v5 (docs/11 5.30 slice 1a, 2026-09-30): the house's own values, `areaSqft`, `locationSource` and the cost as
         * eleven `cost_*` columns ([HouseEntity.cost], `@Embedded(prefix = "cost_")`), all nullable with no default,
         * as Room lists them in `5.json`. A house from before keeps every column null: unknown, shown as today.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                for (column in HOUSE_VALUE_COLUMNS) connection.execSQL("ALTER TABLE houses ADD COLUMN $column")
            }
        }

        /** The columns v5 adds, name and affinity, in `5.json`'s order. */
        val HOUSE_VALUE_COLUMNS: List<String> = listOf(
            "`areaSqft` INTEGER", "`locationSource` TEXT",
            "`cost_deposit` INTEGER", "`cost_depositMonths` INTEGER", "`cost_maintenance` INTEGER",
            "`cost_maintenanceIncluded` INTEGER", "`cost_brokerage` INTEGER", "`cost_brokerageMonths` INTEGER",
            "`cost_lockInMonths` INTEGER", "`cost_noticeMonths` INTEGER", "`cost_availableFrom` TEXT",
            "`cost_myOffer` INTEGER", "`cost_agreedPrice` INTEGER",
        )

        /**
         * v6 (docs/11 5.30 slice 1b, 2026-09-30): `houses.brokerId`, the broker's record id, nullable with no default
         * as Room lists it in `6.json`. A house from before has none; the once-only migration of contacts into
         * brokers (`CommonRepository.migrateContactsToBrokers`) links the ones with a phone number.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE houses ADD COLUMN `brokerId` TEXT")
            }
        }

        /**
         * v7 (docs/11 5.6, slice 1c, 2026-09-30): `houses.rooms`, the house's rooms as JSON text ([Converters]),
         * nullable with no default as Room lists it in `7.json`. A house from before has none.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE houses ADD COLUMN `rooms` TEXT")
            }
        }

        /**
         * v8 (docs/11 5.5, slice 3a, 2026-09-30): `houses.answers`, the questions asked at the house as JSON text
         * ([Converters]), nullable with no default as Room lists it in `8.json`. A house from before has none.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE houses ADD COLUMN `answers` TEXT")
            }
        }

        /**
         * v9 (docs/11 5.7 and 5.24, slice 5, 2026-09-30): the photo's metadata (`roomId`, `tags` as JSON text, `caption`,
         * `metaUpdatedAt` and `metaDirty`, the last two 0 for a photo from before) and `houses.moveIn`, the move-in as
         * JSON text, as Room lists them in `9.json`. A row from before has no meta and no move-in.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE photos ADD COLUMN `roomId` TEXT")
                connection.execSQL("ALTER TABLE photos ADD COLUMN `tags` TEXT")
                connection.execSQL("ALTER TABLE photos ADD COLUMN `caption` TEXT")
                connection.execSQL("ALTER TABLE photos ADD COLUMN `metaUpdatedAt` INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE photos ADD COLUMN `metaDirty` INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE houses ADD COLUMN `moveIn` TEXT")
            }
        }

        /**
         * v10 (S4b-BL-87, 2026-10-01): `houses.floor`, the floor the flat is on, nullable with no default as Room lists
         * it in `10.json`. A house from before has no floor.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE houses ADD COLUMN `floor` INTEGER")
            }
        }

        /**
         * v11 (S4b-FR-14, 2026-10-06): saved walks (docs/11 5.27.6, docs/03 §6.2). `track_points.walkId` (0 = no id, so
         * the rows from before are split by the gap rule alone) with its index, and the local-only `saved_walks` table.
         * The SQL is Room's own, from `11.json`.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE track_points ADD COLUMN `walkId` INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_track_points_walkId` ON `track_points` (`walkId`)")
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `saved_walks` (`id` TEXT NOT NULL, `houseId` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, `savedAt` INTEGER NOT NULL, " +
                        "`pointCount` INTEGER NOT NULL, `lengthM` INTEGER NOT NULL, `points` BLOB NOT NULL, PRIMARY KEY(`id`))",
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_walks_houseId` ON `saved_walks` (`houseId`)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_walks_startedAt` ON `saved_walks` (`startedAt`)")
            }
        }

        /**
         * Every migration, in order, for the three builders (Android's `AppDatabaseFactory`, iOS's `AppDatabaseIos`,
         * the tests): a new version adds its migration here once (readiness review 2026-09-29, docs/14 §8 finding 6)
         * and pins its `<version>.json` in `RoomSchemaTest`.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
            MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
        )
    }
}

/** Room's generated constructor for [AppDatabase] on each platform (Room KMP: no reflection on iOS). */
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
