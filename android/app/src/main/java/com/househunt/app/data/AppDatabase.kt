package com.househunt.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {
    private val mapSerializer = MapSerializer(String.serializer(), Int.serializer())

    @TypeConverter
    fun checklistToJson(map: Map<String, Int>): String = Json.encodeToString(mapSerializer, map)

    @TypeConverter
    fun jsonToChecklist(json: String): Map<String, Int> =
        if (json.isBlank()) emptyMap() else Json.decodeFromString(mapSerializer, json)
}

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

    @Upsert
    suspend fun upsert(house: HouseEntity)

    @Query("SELECT * FROM houses WHERE dirty = 1")
    suspend fun dirty(): List<HouseEntity>

    @Query("UPDATE houses SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

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

    /**
     * The tombstones of [deletedIds] that have reached the server (pushed from here, or pulled from another device):
     * only those were purged there, so only their photos need fresh ids on a restore (Android review, round 13).
     * A query only, so the Room schema and identity hash do not change.
     */
    @Query("SELECT id FROM houses WHERE deleted = 1 AND dirty = 0")
    suspend fun syncedDeletedIds(): List<String>
}

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

    @Upsert
    suspend fun upsert(visit: VisitEntity)

    @Query("SELECT * FROM visits WHERE dirty = 1")
    suspend fun dirty(): List<VisitEntity>

    @Query("UPDATE visits SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

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

    @Upsert
    suspend fun upsert(photo: PhotoEntity)

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun get(id: String): PhotoEntity?

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
}

// exportSchema (Sprint 3.5): Room writes app/schemas/com.househunt.app.data.AppDatabase/<version>.json on every build
// (room.schemaLocation in app/build.gradle.kts). The committed 2.json and RoomSchemaTest pin the identity hash, so a
// change to the table layout (for example through the HouseStatus/VisitSource types now defined in :shared) fails
// the unit tests instead of crashing upgraded installs with "Room cannot verify the data integrity".
@Database(entities = [HouseEntity::class, VisitEntity::class, PhotoEntity::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun houses(): HouseDao
    abstract fun visits(): VisitDao
    abstract fun photos(): PhotoDao

    companion object {
        /** v2: photos.deleted, the local queue of photo deletes to send to the server (threat model F-15). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "househunt.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
