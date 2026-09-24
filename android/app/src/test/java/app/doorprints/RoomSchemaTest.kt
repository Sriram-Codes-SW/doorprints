package app.doorprints

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the on-device database (Sprint 3.5). HouseStatus and VisitSource moved to :shared and the entities now
 * implement shared interfaces; none of that may change the table layout of AppDatabase version 2, or every upgraded
 * install would crash with "Room cannot verify the data integrity".
 *
 * Room's identity hash is a digest of the tables, columns (name, affinity, NOT NULL, default), primary keys and
 * indices. [IDENTITY_HASH_V2] is the hash of the v2 layout the app shipped with before Sprint 3.5 (commit 16cb3ef);
 * the same value is in the committed schemas/app.doorprints.data.AppDatabase/2.json.
 *
 * If this test fails after an intentional schema change: bump the database version, add a Migration (and a
 * migration test), commit the new <version>.json that the build writes, and add a pin for the new version.
 * Never edit 2.json or [IDENTITY_HASH_V2] to make the test pass.
 */
class RoomSchemaTest {

    private companion object {
        const val IDENTITY_HASH_V2 = "539964c2013f14439605fab0d18a142a"
        const val SCHEMA_PATH = "schemas/app.doorprints.data.AppDatabase/2.json"
    }

    /** Gradle runs unit tests with the module directory (android/app) as working directory; allow android/ too. */
    private fun moduleFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull { it.exists() } ?: File(path)

    @Test
    fun exportedSchemaV2KeepsTheShippedIdentityHash() {
        // Room rewrites this file during the build whenever the generated schema differs from it.
        val schema = moduleFile(SCHEMA_PATH)
        assertTrue("Room schema not exported to ${schema.absolutePath} (exportSchema / room.schemaLocation)", schema.exists())
        val database = Json.parseToJsonElement(schema.readText()).jsonObject.getValue("database").jsonObject
        assertEquals(2, database.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(IDENTITY_HASH_V2, database.getValue("identityHash").jsonPrimitive.content)
    }

    @Test
    fun generatedDatabaseOpensWithTheShippedIdentityHash() {
        // The hash Room checks at runtime is compiled into AppDatabase_Impl: RoomOpenDelegate(2, "<hash>", "<legacy>").
        val generated = moduleFile("build/generated").walkTopDown()
            .firstOrNull { it.isFile && (it.name == "AppDatabase_Impl.kt" || it.name == "AppDatabase_Impl.java") }
        assertTrue("AppDatabase_Impl not found under build/generated (Room KSP output)", generated != null)
        val match = Regex("RoomOpenDelegate\\(\\s*(\\d+)\\s*,\\s*\"([0-9a-f]{32})\"").find(generated!!.readText())
        assertTrue("RoomOpenDelegate(version, identityHash, ...) not found in ${generated.path}", match != null)
        assertEquals("database version", "2", match!!.groupValues[1])
        assertEquals("identity hash compiled into ${generated.name}", IDENTITY_HASH_V2, match.groupValues[2])
    }
}
