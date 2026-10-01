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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the on-device database (Sprint 3.5). HouseStatus and VisitSource moved to :shared and the entities now
 * implement shared interfaces, and since CMP-4 P4a the whole database is Room KMP code in :shared commonMain (this test
 * moved from :app with it); none of that may change the table layout of AppDatabase version 2, or every upgraded
 * install would crash with "Room cannot verify the data integrity".
 *
 * Room's identity hash is a digest of the tables, columns (name, affinity, NOT NULL, default), primary keys and
 * indices. [IDENTITY_HASHES] pins one per shipped version (v2 is the layout the app shipped with before Sprint 3.5,
 * commit 16cb3ef); the same values are in the committed schemas/app.doorprints.data.AppDatabase/<version>.json.
 *
 * If this test fails after an intentional schema change: bump the database version, add a Migration (and a
 * migration test), commit the new <version>.json that the build writes, and add a pin for the new version.
 * Never edit a committed json or [IDENTITY_HASHES] to make the test pass.
 */
class RoomSchemaTest {

    private companion object {
        /**
         * One pin per shipped schema version, the hash of the committed `schemas/…/<version>.json` (readiness review
         * 2026-09-29, docs/14 §8 finding 6: a new version adds its line here and its migration to
         * `AppDatabase.MIGRATIONS`). Never edit a committed json or a pin to make the test pass.
         */
        val IDENTITY_HASHES = mapOf(
            2 to "539964c2013f14439605fab0d18a142a",
            // v3 (S4b-FR-2, 2026-09-29): track_points, the path trace.
            3 to "49e636464035d2b630ae32d44d609d99",
            // v4 (docs/11 5.30 slice 0, 2026-09-30): records, the envelope for every new kind of data of Sprint 4b.
            4 to "e13c40884a09c87c71df21c9864d2d1d",
            // v5 (docs/11 5.30 slice 1a, 2026-09-30): houses.areaSqft, houses.locationSource and the cost_* columns.
            5 to "647cd06d0f59c4c9cafc50b70adadc5f",
            // v6 (docs/11 5.30 slice 1b, 2026-09-30): houses.brokerId.
            6 to "2d8989be61a253b05a425724fdac611c",
            // v7 (docs/11 5.6 slice 1c, 2026-09-30): houses.rooms, the rooms as JSON text.
            7 to "0f1bb1e926eca4448414d4c3f66907ba",
            // v8 (docs/11 5.5 slice 3a, 2026-09-30): houses.answers, the questions asked as JSON text.
            8 to "1ef29df83d30a6f86a9b88986f715705",
            // v9 (docs/11 5.7 and 5.24, slice 5, 2026-09-30): the photos' roomId, tags, caption, metaUpdatedAt and
            // metaDirty, and houses.moveIn.
            9 to "680c57fd1f6c668e1169c9014a9b2a66",
            // v10 (S4b-BL-87, 2026-10-01): houses.floor.
            10 to "0365b6bdbbacce16395d044cf2c0100c",
        )
        const val SCHEMA_DIR = "schemas/app.doorprints.data.AppDatabase"
    }

    /** Gradle runs host tests with the module directory (android/shared) as working directory; allow android/ too. */
    private fun moduleFile(path: String): File =
        listOf(File(path), File("shared", path)).firstOrNull { it.exists() } ?: File(path)

    @Test
    fun everyExportedSchemaKeepsItsShippedIdentityHash() {
        // Room rewrites these files during the build whenever the generated schema differs from them.
        val dir = moduleFile(SCHEMA_DIR)
        assertTrue("Room schemas not exported to ${dir.absolutePath} (exportSchema / room { schemaDirectory })", dir.isDirectory)
        val exported = dir.listFiles { f -> f.name.endsWith(".json") }!!.associate { f ->
            val database = Json.parseToJsonElement(f.readText()).jsonObject.getValue("database").jsonObject
            database.getValue("version").jsonPrimitive.content.toInt() to database.getValue("identityHash").jsonPrimitive.content
        }
        assertEquals("every committed schema version has a pin, and every pin a committed schema", IDENTITY_HASHES.keys, exported.keys)
        IDENTITY_HASHES.forEach { (version, hash) -> assertEquals("identity hash of version $version", hash, exported[version]) }
    }

    @Test
    fun theCurrentVersionHasAMigrationFromEveryEarlierOne() {
        val current = IDENTITY_HASHES.keys.max()
        // MIGRATIONS is the one list the three builders read; it must reach the current version from version 1.
        val steps = AppDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals("one migration per version step", (1 until current).map { it to it + 1 }, steps)
    }

    @Test
    fun generatedDatabaseOpensWithTheShippedIdentityHash() {
        val current = IDENTITY_HASHES.keys.max()
        // The hash Room checks at runtime is compiled into AppDatabase_Impl: RoomOpenDelegate(2, "<hash>", "<legacy>").
        // KSP writes the Android one under build/generated/ksp/android (the iOS ones, on macOS, under ksp/ios*).
        val generated = moduleFile("build/generated/ksp/android").walkTopDown()
            .firstOrNull { it.isFile && (it.name == "AppDatabase_Impl.kt" || it.name == "AppDatabase_Impl.java") }
        assertTrue("AppDatabase_Impl not found under build/generated (Room KSP output)", generated != null)
        val match = Regex("RoomOpenDelegate\\(\\s*(\\d+)\\s*,\\s*\"([0-9a-f]{32})\"").find(generated!!.readText())
        assertTrue("RoomOpenDelegate(version, identityHash, ...) not found in ${generated.path}", match != null)
        assertEquals("database version", current.toString(), match!!.groupValues[1])
        assertEquals("identity hash compiled into ${generated.name}", IDENTITY_HASHES.getValue(current), match.groupValues[2])
    }
}
