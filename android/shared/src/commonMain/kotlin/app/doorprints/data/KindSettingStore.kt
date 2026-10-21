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

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.doorprints.shared.model.KindSetting
import app.doorprints.shared.model.KindSettingRules
import app.doorprints.shared.model.UserKindFile
import app.doorprints.shared.model.UserKinds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement

/**
 * What this device tracks (*Settings > What you track*), the part of [SettingsStore] that holds the kinds (kinds PR C,
 * S4b-BL-274; docs/03 §18.12) and, since docs/03 ADR-39, the kinds a person added from a kind file: the website's
 * `doorprints.kinds.*` keys with the same values, device-local, never synced. The kind files themselves (`kinds.user`, a JSON
 * list of checked files, at most [app.doorprints.shared.model.UserKindFiles.MAX_KINDS]) are read into [UserKinds] every time the
 * setting is read, and checked again there. Reads no secret; an unreadable file counts as the house alone.
 */
internal class KindSettingStore(
    private val dataStore: DataStore<Preferences>,
    /** The kinds this build ships ([SettingsStore.availableKinds]). */
    private val available: List<String>,
    private val released: List<String>,
) {
    private object Keys {
        val experimental = stringPreferencesKey("kinds.experimental")
        val enabled = stringPreferencesKey("kinds.enabled")
        val default = stringPreferencesKey("kinds.default")
        /** *Kinds you add (experimental)*: `1` on (docs/03 ADR-39). */
        val custom = stringPreferencesKey("kinds.custom")
        /** The kind files a person added: a JSON list of checked files (docs/03 ADR-39). */
        val user = stringPreferencesKey("kinds.user")
    }

    /** The setting of [p], the kinds a person added loaded into [UserKinds] first (they are available after the built-in ones). */
    private fun settingOf(p: Preferences): KindSetting {
        val stored = p[Keys.user]
        UserKinds.load(stored)
        return KindSetting(
            available + UserKinds.ids(), p[Keys.experimental], p[Keys.enabled], p[Keys.default], released, p[Keys.custom],
            userRevision = stored?.hashCode() ?: 0,
        )
    }

    fun setting(): Flow<KindSetting> = dataStore.data
        .map(::settingOf)
        .catch { emit(KindSetting(available, released = released)) }
        .distinctUntilChanged()

    private fun MutablePreferences.put(key: Preferences.Key<String>, value: String?) {
        if (value == null) remove(key) else this[key] = value
    }

    private fun MutablePreferences.store(next: KindSetting) {
        put(Keys.experimental, next.experimental)
        put(Keys.enabled, next.enabledText)
        put(Keys.default, next.defaultText)
        put(Keys.custom, next.custom)
    }

    /** Applies [change] to what is stored and stores the result (a null value removing its key): one atomic edit, so two taps never undo each other. */
    suspend fun edit(change: (KindSetting) -> KindSetting) {
        dataStore.edit { it.store(change(settingOf(it))) }
    }

    /**
     * Takes the kinds and default a backup names, only on a device that never chose (neither list nor default stored): a
     * hint, never applied over a choice (S4b-BL-212), and only the built-in kinds are kept (`KindSettingRules.knownSpaces`,
     * S4b-BL-307). Returns whether it was taken.
     */
    suspend fun adopt(kinds: List<String>, default: String): Boolean {
        val (known, chosen) = KindSettingRules.knownSpaces(kinds, default) ?: return false
        var taken = false
        dataStore.edit {
            if (it[Keys.enabled] == null && it[Keys.default] == null) {
                it[Keys.enabled] = KindSettingRules.listText(known)
                it[Keys.default] = chosen
                taken = true
            }
        }
        return taken
    }

    /**
     * Stores a checked kind [file] (docs/03 ADR-39): a new kind is added at the end, refused when the phone keeps the most it
     * can; one with the id of an added kind replaces it in place (the person confirmed it). Its entries are never touched, and
     * it is not turned on: the person does that in its row.
     */
    suspend fun addUserKind(file: UserKindFile): UserKinds.Added {
        var result = UserKinds.Added.FULL
        dataStore.edit {
            val (next, added) = UserKinds.withAdded(it[Keys.user], file)
            result = added
            if (added != UserKinds.Added.FULL) it.put(Keys.user, next)
        }
        return result
    }

    /**
     * Takes the added kind [id] away: its file only, after it is turned off (a default that was it gives way to the next kind on).
     * Its entries stay, in the default space, until the kind is added again. Returns whether there was such a kind.
     */
    suspend fun removeUserKind(id: String): Boolean {
        var removed = false
        dataStore.edit {
            val stored = it[Keys.user]
            if (UserKinds.storedList(stored).none { raw -> UserKinds.idOf(raw) == id }) return@edit
            it.store(settingOf(it).withKindEnabled(id, false))
            it.put(Keys.user, UserKinds.withRemoved(stored, id))
            removed = true
        }
        return removed
    }

    /**
     * The kinds a backup carries ([raws], `search.userKinds`) this phone does not have yet, added after each is checked as an
     * imported file is; one already here is kept as it is, one that does not pass is left out. Returns the ids added.
     */
    suspend fun adoptUserKinds(raws: List<JsonElement>): List<String> {
        if (raws.isEmpty()) return emptyList()
        var added: List<String> = emptyList()
        dataStore.edit {
            val (next, ids) = UserKinds.withAdopted(it[Keys.user], raws)
            added = ids
            if (ids.isNotEmpty()) it.put(Keys.user, next)
        }
        return added
    }
}
