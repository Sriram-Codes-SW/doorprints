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

import android.app.Application
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import app.doorprints.shared.ai.AiKind
import app.doorprints.shared.ai.AiProviderConfig
import app.doorprints.shared.ai.GeminiClient
import app.doorprints.shared.api.ApiHttp
import app.doorprints.shared.export.BackupWire
import app.doorprints.shared.export.ExportOptions
import app.doorprints.shared.export.BackupData
import app.doorprints.shared.model.SpaceView
import app.doorprints.shared.model.UserKindFiles
import app.doorprints.shared.model.UserKindRead
import app.doorprints.shared.model.UserKinds
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The kinds a person adds in the data layer (docs/03 ADR-39, docs/06 TC-U-296), on real Room and a real settings file: a
 * checked file is stored under `kinds.user` and read back into the setting (available after the built-in kinds, offered only
 * while *Kinds you add* is on); a new file of the same id replaces it in place and the ninth is refused; removing one turns it
 * off and takes its file only, never an entry; a backup carries the files as `search.userKinds` and an import adds the ones
 * this phone does not have; and the on-device AI never reads an entry of such a kind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UserKindsRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Application = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()

    private class MemorySecrets(name: String) : SecretStore {
        private val key = stringPreferencesKey(name)
        override fun get(settings: Preferences): String? = settings[key]
        override fun put(settings: MutablePreferences, apiKey: String) {
            settings[key] = apiKey
        }
        override fun clear(settings: MutablePreferences) {
            settings.remove(key)
        }
    }

    private val both = listOf("house", "school")

    private val settings = SettingsStore(
        PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "kinds.preferences_pb") },
        MemorySecrets("serverKey"), geminiSecrets = MemorySecrets("aiKey"), availableKinds = both,
    )

    private val geminiBodies = mutableListOf<String>()
    private val geminiEngine = MockEngine { request ->
        geminiBodies += request.body.toByteArray().decodeToString()
        respond(
            """{"candidates":[{"content":{"parts":[{"text":"{\"answer\":\"On device.\",\"summary\":\"Device plan\"}"}]}}]}""",
            HttpStatusCode.OK, Headers.build { append("Content-Type", "application/json") },
        )
    }

    private fun repository() = CommonRepository(
        db, settings,
        photoDir = File(tmp.root, "photos").path,
        syncSoon = {},
        apiFor = { _, _ -> error("no server") },
        geminiFor = { key, quality -> GeminiClient(ApiHttp.client(geminiEngine), key, timeoutMs = null, quality = quality) },
    )

    private val vectors = run {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs/schemas/kinds/user-kind-vectors.json").isFile) dir = dir.parentFile
        Json.parseToJsonElement(File(checkNotNull(dir), "docs/schemas/kinds/user-kind-vectors.json").readText()).jsonObject
    }

    private fun sample(id: String): JsonObject =
        vectors.getValue("valid").jsonArray.map { it.jsonObject.getValue("file").jsonObject }.first { it.getValue("id").jsonPrimitive.content == id }

    private fun checked(json: JsonObject) = (UserKindFiles.read(json.toString()) as UserKindRead.Ok).file

    private val at = 1_760_000_000_000

    private fun entry(id: String, kind: String) = HouseEntity(id = id, label = "Iron Temple $id", lat = 12.97, lon = 77.59, createdAt = at, updatedAt = at, kind = kind)

    @After
    fun close() {
        db.close()
        scope.cancel()
        UserKinds.load(null)
    }

    @Test
    fun aStoredKindIsAvailableAfterTheBuiltInOnesAndOfferedOnlyWhileKindsYouAddIsOn() = runBlocking {
        assertEquals(UserKinds.Added.ADDED, settings.addUserKind(checked(sample("u_gym"))))
        val off = settings.kindSetting().first()
        assertEquals(listOf("house", "school", "u_gym"), off.available)
        assertEquals(both, off.offered)
        assertEquals("Gyms", UserKinds.file("u_gym")?.many)
        settings.editKindSetting { it.withCustom(true) }
        settings.editKindSetting { it.withKindEnabled("u_gym", true) }
        val on = settings.kindSetting().first()
        assertEquals(listOf("house", "u_gym"), on.enabled)
        // A new file of the same id replaces it in place, and the setting is read again (its words changed, its id did not).
        val renamed = checked(JsonObject(sample("u_gym_two") + ("id" to JsonPrimitive("u_gym"))))
        assertEquals(UserKinds.Added.REPLACED, settings.addUserKind(renamed))
        val replaced = settings.kindSetting().first()
        assertTrue(replaced.userRevision != on.userRevision)
        assertEquals("javascript:alert(1)", UserKinds.file("u_gym")?.many)
        assertEquals(listOf("house", "u_gym"), replaced.enabled)
        // At most eight: the ninth is refused and nothing changes.
        val cafe = sample("u_cafe")
        for (i in 2..UserKindFiles.MAX_KINDS) settings.addUserKind(checked(JsonObject(cafe + ("id" to JsonPrimitive("u_cafe$i")))))
        settings.kindSetting().first()
        assertEquals(UserKindFiles.MAX_KINDS, UserKinds.ids().size)
        assertEquals(UserKinds.Added.FULL, settings.addUserKind(checked(cafe)))
        assertEquals(UserKindFiles.MAX_KINDS, settings.kindSetting().first().available.size - both.size)
    }

    @Test
    fun removingAKindTurnsItOffAndTakesItsFileOnlyNeverAnEntry() = runBlocking {
        val repo = repository()
        settings.addUserKind(checked(sample("u_gym")))
        settings.editKindSetting { it.withCustom(true).withKindEnabled("u_gym", true).withDefault("u_gym") }
        repo.saveHouse(entry("g1", "u_gym"))
        repo.saveHouse(entry("h1", "house"))
        assertEquals("u_gym", settings.kindSetting().first().default)
        assertTrue(settings.removeUserKind("u_gym"))
        assertFalse(settings.removeUserKind("u_gym"))
        val after = settings.kindSetting().first()
        assertEquals(both, after.available)
        assertEquals("house", after.default)
        // Its entry is kept, as it was, and the default space holds it now.
        assertEquals("u_gym", repo.getHouse("g1")?.kind)
        val view = SpaceView("house", multi = true, default = "house", available = after.available)
        assertTrue(view.holds("u_gym"))
        assertEquals(2, repo.houses.first().size)
    }

    @Test
    fun aBackupCarriesTheFilesAndAnImportAddsTheOnesThisPhoneHasNot() = runBlocking {
        val repo = repository()
        settings.addUserKind(checked(sample("u_gym")))
        repo.saveHouse(entry("g1", "u_gym"))
        val rows = repo.localRows()
        val bundle = rows.toBundle(ExportOptions())
        assertEquals(listOf(sample("u_gym")), bundle.options.userKinds)
        val text = BackupWire.encodeData(BackupData.of(bundle), bundle.options.enabledKinds, bundle.options.defaultKind, bundle.options.userKinds)
        val search = Json.parseToJsonElement(text).jsonObject.getValue("search")
        val carried = UserKinds.ofSearch(search)
        assertEquals(listOf(sample("u_gym")), carried)
        // Its entry travels with its kind id, as every entry does.
        assertTrue(text.contains("\"kind\":\"u_gym\""))
        // On another phone: the kind is added, checked, and not turned on; a second import adds nothing.
        settings.removeUserKind("u_gym")
        assertEquals(listOf("u_gym", "u_cafe"), settings.adoptUserKinds(carried + sample("u_cafe")))
        assertTrue("u_cafe" in settings.kindSetting().first().available)
        assertEquals(emptyList<String>(), settings.adoptUserKinds(carried))
        assertEquals(listOf("house"), settings.kindSetting().first().enabled)
    }

    @Test
    fun theOnDeviceAiNeverReadsAnEntryOfAKindAPersonAdded() = runBlocking {
        val repo = repository()
        settings.addUserKind(checked(sample("u_gym")))
        repo.saveHouse(entry("11111111-1111-4111-8111-111111111111", "house"))
        repo.saveHouse(entry("33333333-3333-4333-8333-333333333333", "u_gym"))
        repo.setAiFeatures(true)
        repo.setAiProvider(AiProviderChoice.DEVICE)
        repo.saveAiProviderConfig(AiProviderConfig(AiKind.GEMINI, "", ""), "AIzaTestKey")
        repo.ask("Which one is nearest?")
        assertNotNull(geminiBodies.lastOrNull())
        assertTrue(geminiBodies.last().contains("11111111-1111-4111-8111-111111111111"))
        assertFalse(geminiBodies.last().contains("33333333-3333-4333-8333-333333333333"))
        assertFalse(geminiBodies.last().contains("Gyms"))
    }
}
