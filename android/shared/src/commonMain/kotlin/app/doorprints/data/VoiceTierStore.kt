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
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * What voice input learned about a key's plan (owner decision 2026-10-19, docs/03 ADR-37, docs/02 RR-33; the TC-U-170 pattern), named
 * by the first 16 hex characters of the key's SHA-256 (`voice.tier.<fp>` and `voice.tier-ack.<fp>`), in this phone's settings beside the
 * key's own slot: never in [AppSettings], so in no backup, copy, sync or share file. The value is the verdict, a time and the name of
 * the evidence; never the key, a reply or speech. Read and written only by `VoiceTierGate`.
 */
class VoiceTierStore(private val dataStore: DataStore<Preferences>) {
    private fun entryKey(fp: String) = stringPreferencesKey("voice.tier.$fp")
    private fun ackKey(fp: String) = stringPreferencesKey("voice.tier-ack.$fp")

    suspend fun entry(fp: String): String? = dataStore.data.first()[entryKey(fp)]

    suspend fun saveEntry(fp: String, entry: String) {
        dataStore.edit { it[entryKey(fp)] = entry }
    }

    /** The provider the person confirmed "my plan is paid" for, for this key, or null. */
    suspend fun ack(fp: String): String? = dataStore.data.first()[ackKey(fp)]

    /** Records the confirmation for [provider] (null withdraws it). */
    suspend fun saveAck(fp: String, provider: String?) {
        dataStore.edit { if (provider == null) it.remove(ackKey(fp)) else it[ackKey(fp)] = provider }
    }

    /** Drops everything kept for a key that was replaced or removed. */
    suspend fun forget(fp: String) {
        dataStore.edit {
            it.remove(entryKey(fp))
            it.remove(ackKey(fp))
        }
    }

    /** Changes whenever a kept entry is written or dropped, so a screen showing the verdict reads it again. */
    val version: Flow<Int> = dataStore.data
        .map { p -> p.asMap().entries.filter { it.key.name.startsWith("voice.tier") }.sumOf { it.key.name.hashCode() * 31 + it.value.hashCode() } }
        .distinctUntilChanged()
        .catch { emit(0) }
}
