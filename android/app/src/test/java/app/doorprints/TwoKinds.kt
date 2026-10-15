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

package app.doorprints

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.doorprints.data.KeystoreSecretStore
import app.doorprints.data.Repository
import app.doorprints.data.SettingsStore
import app.doorprints.location.HuntState
import app.doorprints.ui.AppServices
import app.doorprints.ui.MapServices
import app.doorprints.ui.LocalAppServices
import app.doorprints.ui.LocalDevKinds
import app.doorprints.ui.ProvidePlatformServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The real app (Room, the repository) with a settings store of its own that ships the school too, as a debug build made
 * with `-Pdoorprints.devKinds=true` does (kinds PR C, S4b-BL-274): the spaces tests start a [DoorprintsRoot][app.doorprints.ui.DoorprintsRoot]
 * on it. Robolectric has no Android Keystore, so the store has a stand-in cipher (as `AiSettingsSectionTest` does). Close it
 * after the test.
 */
class TwoKinds(app: DoorprintsApp, available: List<String> = listOf("house", "school")) : AutoCloseable {
    private val job = SupervisorJob()
    private val file = File.createTempFile("two-kinds", ".preferences_pb").also { it.delete() }
    private val seal: (String) -> String = { "v1:" + it.reversed() }
    private val open: (String?) -> String? = { s -> s?.takeIf { it.startsWith("v1:") }?.removePrefix("v1:")?.reversed() }

    val store = SettingsStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file },
        KeystoreSecretStore(seal, open = open),
        geminiSecrets = KeystoreSecretStore(seal, KeystoreSecretStore.GEMINI_ENTRY, open),
        availableKinds = available,
    )
    private val real = app.container.repository
    val repository: Repository = object : Repository by real {
        override val settings: SettingsStore get() = store
    }
    /** How many times the Map's services were asked to stop Hunt mode (S4b-BL-304); the real service is asked too, which a JVM test does not run. */
    var huntStops = 0
        private set
    private val realMap = app.container.services.mapScreen
    private val map: MapServices = object : MapServices by realMap {
        override fun stopHunt() {
            huntStops++
            realMap.stopHunt()
            HuntState.update { HuntState.State() } // the service's onDestroy does this in the app
        }
    }
    val services: AppServices = object : AppServices by app.container.services {
        override val repository: Repository get() = this@TwoKinds.repository
        override val mapScreen: MapServices get() = map
    }

    /** Turns *Experimental: more than houses* on and the school on, optionally with the school the default. */
    fun enableSchool(schoolDefault: Boolean = false) = runBlocking {
        store.editKindSetting { s -> s.withExperimental(true).withKindEnabled("school", true).let { if (schoolDefault) it.withDefault("school") else it } }
    }

    /** The school alone and the default, the house turned off in *What you track* (docs/03 §18.18): the house's entries are kept. */
    fun schoolOnly() = runBlocking {
        store.editKindSetting { s -> s.withExperimental(true).withKindEnabled("school", true).withDefault("school").withKindEnabled("house", false) }
    }

    /** The content with this store's services (and the developer flag, as a build that ships the school has it). */
    @Composable
    fun Provide(content: @Composable () -> Unit) {
        ProvidePlatformServices {
            CompositionLocalProvider(LocalAppServices provides services, LocalDevKinds provides true, content = content)
        }
    }

    override fun close() {
        runBlocking { job.cancelAndJoin() }
        file.delete()
    }
}
