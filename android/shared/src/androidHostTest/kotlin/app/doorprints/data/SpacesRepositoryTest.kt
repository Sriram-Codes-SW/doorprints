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
import app.doorprints.shared.model.KindSetting
import app.doorprints.shared.model.SpaceView
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * Spaces in the data layer (kinds PR C, S4b-BL-274 and S4b-BL-289; docs/03 §18.14), on real Room and a real settings file:
 * what this phone tracks is stored in the three values the website uses and read back by the build's kinds; the backup's
 * kinds reach every copy through the rows (`LocalRows.kinds`); a file's kinds are a hint taken only by a phone that never
 * chose; a school's phone makes no broker (S4b-BL-260) while a house's does; Ask and Plan read one space on the person's own
 * AI; and [Repository.aiOwnKey] says when that is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SpacesRepositoryTest {
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
        PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "spaces.preferences_pb") },
        MemorySecrets("serverKey"), geminiSecrets = MemorySecrets("aiKey"), availableKinds = both,
    )

    /** The bodies Gemini was sent; it answers every call with one fixed JSON text. */
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
        devKinds = true,
    )

    private val at = 1_760_000_000_000
    private val houseId = "11111111-1111-4111-8111-111111111111"
    private val schoolId = "22222222-2222-4222-8222-222222222222"

    private fun house(id: String, kind: String = "house", phone: String? = null) = HouseEntity(
        id = id, label = if (kind == "school") "Pine Hill School" else "Green View", lat = 12.97, lon = 77.59,
        createdAt = at, updatedAt = at, contactName = phone?.let { "Office" }, contactPhone = phone, kind = kind,
    )

    @After
    fun close() {
        db.close()
        scope.cancel()
    }

    @Test
    fun whatThePhoneTracksIsStoredInTheThreeValuesAndReadBackByTheBuildsKinds() = runBlocking {
        assertEquals(KindSetting(both), settings.kindSetting().first())
        settings.editKindSetting { it.withExperimental(true).withKindEnabled("school", true).withDefault("school") }
        val stored = settings.kindSetting().first()
        assertEquals("1", stored.experimental)
        assertEquals("[\"house\",\"school\"]", stored.enabledText)
        assertEquals("school", stored.defaultText)
        assertEquals(listOf("house", "school"), stored.enabled)
        assertTrue(stored.spaces)
        // Back to the house alone: the keys are removed, not left at their defaults.
        settings.editKindSetting { it.withExperimental(false) }
        assertEquals(KindSetting(both), settings.kindSetting().first().copy(enabledText = null, defaultText = null))
        assertEquals(listOf("house"), settings.kindSetting().first().enabled)
    }

    @Test
    fun theBackupsKindsComeWithTheRows() = runBlocking {
        val repo = repository()
        assertEquals(listOf("house"), repo.localRows().kinds.enabled)
        settings.editKindSetting { it.withExperimental(true).withKindEnabled("school", true).withDefault("school") }
        val rows = repo.localRows()
        assertEquals(listOf("house", "school"), rows.kinds.enabled)
        assertEquals("school", rows.kinds.default)
    }

    @Test
    fun aFilesKindsAreAHintTakenOnlyByAPhoneThatNeverChose() = runBlocking {
        assertTrue(settings.adoptKinds(listOf("house", "school"), "school"))
        assertEquals("[\"house\",\"school\"]", settings.kindSetting().first().enabledText)
        assertEquals("school", settings.kindSetting().first().defaultText)
        // A second file, or a phone that chose: never over a choice.
        assertFalse(settings.adoptKinds(listOf("house"), "house"))
        assertEquals("school", settings.kindSetting().first().defaultText)
    }

    @Test
    fun aHintIsNotTakenOnceThePersonChoseAnythingAndTheChoosersHintStaysDismissed() = runBlocking {
        settings.editKindSetting { it.withExperimental(true).withKindEnabled("school", true) }
        assertFalse(settings.adoptKinds(listOf("school"), "school"))
        assertTrue(settings.chooserHintShown().first())
        settings.dismissChooserHint()
        assertFalse(settings.chooserHintShown().first())
    }

    @Test
    fun aSchoolsPhoneMakesNoBrokerAndAHousesDoes() = runBlocking {
        val repo = repository()
        repo.saveHouse(house("s1", kind = "school", phone = "98450 12345"))
        assertEquals(0, repo.observeBrokers().first().size)
        assertNull(repo.getHouse("s1")!!.brokerId)
        assertEquals("98450 12345", repo.getHouse("s1")!!.contactPhone) // the contact itself is kept
        repo.saveHouse(house("h1", phone = "98450 12345"))
        assertEquals(1, repo.observeBrokers().first().size)
        assertNotNull(repo.getHouse("h1")!!.brokerId)
    }

    @Test
    fun askAndPlanReadOneSpaceOnThePersonsOwnAi() = runBlocking {
        val repo = repository()
        repo.saveHouse(house(houseId))
        repo.saveHouse(house(schoolId, kind = "school"))
        repo.setAiFeatures(true)
        repo.setAiProvider(AiProviderChoice.DEVICE)
        repo.saveAiProviderConfig(AiProviderConfig(AiKind.GEMINI, "", ""), "AIzaTestKey")
        assertTrue(repo.aiOwnKey.value)
        val school = SpaceView("school", multi = true, default = "house", available = both)
        repo.ask("Which one is near the bus stop?", school)
        assertTrue(schoolId in geminiBodies.last() && houseId !in geminiBodies.last())
        repo.ask("Which one is near the bus stop?")
        assertTrue(schoolId in geminiBodies.last() && houseId in geminiBodies.last())
    }

    @Test
    fun theOwnKeyFlagIsFalseWithTheServersAiAndTrueWithTheDevicesKey() = runBlocking {
        val repo = repository()
        assertFalse(repo.aiOwnKey.value)
        repo.setAiProvider(AiProviderChoice.DEVICE)
        assertFalse(repo.aiOwnKey.value) // chosen, but no key yet
        repo.saveAiProviderConfig(AiProviderConfig(AiKind.GEMINI, "", ""), "AIzaTestKey")
        assertTrue(repo.aiOwnKey.value)
        repo.setAiProvider(AiProviderChoice.SERVER)
        assertFalse(repo.aiOwnKey.value)
    }
}
