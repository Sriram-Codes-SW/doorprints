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

package app.doorprints.drive.backup

import app.doorprints.crypto.Vectors
import app.doorprints.crypto.s
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `docs/schemas/backup-vectors.json`, written by an independent implementation (Python) and read by the website's
 * `backup-vectors.spec.ts` too: retention with the shrink guard, and the automatic schedule (S4b-BL-116).
 */
class BackupVectorsTest {
    private val root = Vectors.load("backup-vectors.json")

    @Test
    fun constantsAreTheOnesInTheVectors() {
        val c = root.getValue("constants").jsonObject
        assertEquals(c.getValue("daily").jsonPrimitive.int, BackupRetention.DAILY)
        assertEquals(c.getValue("weekly").jsonPrimitive.int, BackupRetention.WEEKLY)
        assertEquals(c.getValue("monthly").jsonPrimitive.int, BackupRetention.MONTHLY)
        assertEquals(c.getValue("dailyMs").jsonPrimitive.long, BackupSchedule.DAILY_MS)
        assertEquals(c.getValue("verifyMs").jsonPrimitive.long, BackupSchedule.VERIFY_MS)
        assertEquals(c.getValue("retryMs").jsonPrimitive.long, BackupSchedule.RETRY_MS)
        assertEquals(c.getValue("quotaRetryMs").jsonPrimitive.long, BackupSchedule.QUOTA_RETRY_MS)
        assertEquals(c.getValue("clockSkewMs").jsonPrimitive.long, BackupSchedule.CLOCK_SKEW_MS)
    }

    @Test
    fun retention() {
        val cases = root.getValue("retention").jsonArray
        assertEquals(19, cases.size)
        for (c in cases) {
            val v = c.jsonObject
            val name = v.s("name")
            val entries = v.getValue("backups").jsonArray.map {
                val o = it.jsonObject
                RetentionEntry(o.s("id"), o.getValue("createdAt").jsonPrimitive.long, o.getValue("houses").jsonPrimitive.int)
            }
            val confirmed = v.getValue("confirmedDrops").jsonArray.map { it.jsonPrimitive.content }.toSet()
            val r = BackupRetention.select(entries, v.getValue("utcOffsetMinutes").jsonPrimitive.int, confirmed)
            assertEquals(name, strings(v, "keep"), r.keep)
            assertEquals(name, strings(v, "prune"), r.prune)
            val hold = v.getValue("hold")
            if (hold is JsonNull) {
                assertNull(name, r.hold)
            } else {
                val h = hold.jsonObject
                assertEquals(name, ShrinkHold(h.s("backupId"), h.getValue("houses").jsonPrimitive.int, h.s("previousId"), h.getValue("previousHouses").jsonPrimitive.int), r.hold)
            }
        }
    }

    @Test
    fun schedule() {
        val cases = root.getValue("schedule").jsonArray
        assertEquals(18, cases.size)
        for (c in cases) {
            val v = c.jsonObject
            val i = v.getValue("input").jsonObject
            fun long(k: String) = i[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.long
            val input = BackupSchedule.Input(
                now = long("now")!!,
                enabled = i.getValue("enabled").jsonPrimitive.boolean,
                ready = i.getValue("ready").jsonPrimitive.boolean,
                manual = i["manual"]?.jsonPrimitive?.boolean ?: false,
                lastSuccessAt = long("lastSuccessAt"),
                lastAttemptAt = long("lastAttemptAt"),
                lastFailure = i["lastFailure"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let { BackupSchedule.Failure.valueOf(it) },
                lastVerifyAt = long("lastVerifyAt"),
            )
            val e = v.getValue("expect").jsonObject
            val d = BackupSchedule.decide(input)
            val name = v.s("name")
            assertEquals(name, e.getValue("backup").jsonPrimitive.boolean, d.backup)
            assertEquals(name, e.s("reason"), d.reason.name)
            assertEquals(name, e["nextAt"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.long, d.nextAt)
            assertEquals(name, e.getValue("verify").jsonPrimitive.boolean, d.verify)
        }
    }

    private fun strings(o: JsonObject, key: String) = (o.getValue(key) as JsonArray).map { it.jsonPrimitive.content }
}
