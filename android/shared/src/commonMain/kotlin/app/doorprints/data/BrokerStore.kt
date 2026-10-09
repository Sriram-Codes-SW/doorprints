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

import app.doorprints.shared.export.ExportBroker
import app.doorprints.shared.model.Broker
import app.doorprints.shared.model.BrokerType
import app.doorprints.shared.model.PhoneKey
import app.doorprints.shared.records.RecordLimitException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The brokers on this phone (docs/11 5.25, slice 1b): how a broker is read from its record row, the local edits of it
 * with the copies of the contact that every linked house keeps, the link a saved house gets, and the once-only move of
 * the contacts of older houses into brokers (S4b-BL-168, slice 6). It is separate from [CommonRepository] because the
 * houses' reads and writes (`houses`, `getHouse`, `saveHouse`), the broker screens, the exporters and the import (and
 * its undo) all reach the brokers; a new broker value edits this file, the model and the backup mapper, not the
 * repository. The only other table it writes is the houses (the contact copies and the link).
 *
 * A broker is a row of the generic record table (type `broker`). [records] is the repository's own writer, which checks
 * the id and the size, stamps the row and asks for a sync; [settings] holds the once-only migration mark.
 */
@OptIn(ExperimentalUuidApi::class)
internal class BrokerStore(
    private val db: AppDatabase,
    private val records: RecordWriter,
    private val settings: SettingsStore,
    private val now: () -> Long,
    private val syncSoon: () -> Unit,
) {
    private val gate = Mutex()
    private var migrated = false

    /** The brokers by name (case ignored), then id. The first collection waits for the move of contacts into brokers. */
    fun observeAll(): Flow<List<Pair<String, Broker>>> =
        db.records().byType(BrokerType.name).map { rows ->
            rows.mapNotNull { row -> row.toBroker()?.let { row.id to it } }
                .sortedWith(compareBy({ it.second.name.lowercase() }, { it.first }))
        }.onStart { migrate() }

    /** The live brokers of the table as the backup lists them, rows that cannot be trusted left out. */
    suspend fun exportRows(): List<ExportBroker> =
        liveRows().map { (row, broker) -> ExportBroker.of(row.id, broker, row.updatedAt) }

    suspend fun save(broker: Broker, id: String?): String {
        val clean = requireNotNull(broker.coerced()) { "a broker needs a name of 1..${Broker.MAX_NAME} characters" }
        val brokerId = id ?: Uuid.random().toString()
        db.withImmediateTransaction {
            records.save(BrokerType, brokerId, clean)
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

    suspend fun delete(id: String) {
        db.withImmediateTransaction {
            records.delete(BrokerType, id)
            val stamp = now()
            for (h in db.houses().liveForBroker(id)) db.houses().upsert(h.copy(brokerId = null, updatedAt = stamp, dirty = true))
        }
        syncSoon()
    }

    fun housesOf(id: String): Flow<List<HouseEntity>> = db.houses().observeForBroker(id)

    /**
     * The house with its contact copies made to agree with its broker (`saveHouse`, the one write path every screen
     * uses): a house that names a live broker takes its name and phone; one that names none but has a phone gets the
     * broker with that number (`PhoneKey`) or a new one named from the contact (else the number), and is linked. A
     * blank phone never makes a broker, and a number too short to compare matches none but still makes one. A
     * tombstone is left as it is, and a dangling id stays (it reads as no broker).
     */
    suspend fun linked(house: HouseEntity): HouseEntity {
        if (house.deleted) return house
        val linked = house.brokerId
        if (linked != null) {
            val broker = db.records().get(BrokerType.name, linked)?.takeUnless { it.deleted }?.toBroker() ?: return house
            return house.copy(contactName = broker.name, contactPhone = broker.phone)
        }
        val phone = house.contactPhone?.trim().orEmpty()
        if (phone.isEmpty()) return house
        val key = PhoneKey.of(phone)
        val existing = key?.let { k -> live().firstOrNull { PhoneKey.of(it.second.phone) == k } }
        val (id, broker) = existing ?: run {
            val fresh = madeFrom(house.contactName, phone)
            val id = try {
                save(fresh, null)
            } catch (e: RecordLimitException) {
                return house
            }
            id to fresh
        }
        return house.copy(brokerId = id, contactName = broker.name, contactPhone = broker.phone)
    }

    /**
     * The once-only move of contacts into brokers (slice 1b), on the first read after the update: every live house
     * with a phone number and no broker joins the broker of that number (`PhoneKey`; a number too short to compare
     * stands only for itself; a broker that already has the number is reused; a new one is named from the most
     * recently edited house's contact, else the number) and is linked, the contact it holds left as it is; houses
     * with a blank phone stay unlinked. Guarded by the `brokers.migrated` setting, set at the end even on an empty
     * phone, so a person who unlinks a house on purpose is never re-linked, and it changes nothing a second time.
     * The houses are written dirty with a new `updatedAt`, so the link reaches the server and the other devices.
     */
    suspend fun migrate() {
        if (migrated) return
        gate.withLock {
            if (migrated) return
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
                    for ((id, broker) in live()) if (keyOf(broker.phone) !in known) known[keyOf(broker.phone)] = id
                    for ((key, houses) in groups) {
                        // The newest edit names the broker (the later of two equal ones).
                        val newest = houses.reduce { a, b -> if (b.updatedAt >= a.updatedAt) b else a }
                        val id = known[key] ?: saveNew(madeFrom(newest.contactName, newest.contactPhone!!.trim())) ?: continue
                        for (h in houses) {
                            db.houses().upsert(h.copy(brokerId = id, updatedAt = stamp, dirty = true))
                            linkedHouses++
                        }
                    }
                }
                settings.markBrokersMigrated()
                if (linkedHouses > 0) syncSoon()
            }
            migrated = true
        }
    }

    private suspend fun liveRows(): List<Pair<RecordEntity, Broker>> =
        db.records().listByType(BrokerType.name).mapNotNull { row -> row.toBroker()?.let { row to it } }

    /** The live brokers of the table with their ids, rows that cannot be trusted left out. */
    private suspend fun live(): List<Pair<String, Broker>> = liveRows().map { (row, broker) -> row.id to broker }

    /** A broker made from a house's contact: the name (else the number) and the number, cut to the record's limits. */
    private fun madeFrom(contactName: String?, phone: String) = Broker(
        name = (contactName?.trim().takeUnless { it.isNullOrEmpty() } ?: phone).take(Broker.MAX_NAME),
        phone = phone.take(Broker.MAX_PHONE),
    )

    /** A new broker's id, or null when the type is full. */
    private suspend fun saveNew(broker: Broker): String? = try {
        val id = Uuid.random().toString()
        records.save(BrokerType, id, checkNotNull(broker.coerced()) { "a broker made from a contact has a name" })
        id
    } catch (e: RecordLimitException) {
        null
    }

    companion object {
        /** A backup's broker as the record row an import writes: values coerced, dirty so it is pushed. */
        fun importedRow(b: ExportBroker, updatedAt: Long): RecordEntity = RecordEntity(
            type = BrokerType.name, id = b.id,
            payload = BrokerType.encode(checkNotNull(b.toBroker().coerced()) { "broker ${b.id} was not checked" }),
            updatedAt = updatedAt, deleted = false, dirty = true,
        )
    }
}
