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

package app.doorprints.deviceauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/schemas/delete-policy-vectors.json` (S4b-BL-127), the same table the website's `delete-policy.spec.ts` runs:
 * every decision row, the confirm button, the 60-second grant and the gate's connect/run rules.
 */
class DeletionPolicyVectorsTest {
    private val root: JsonObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, VECTORS).exists()) dir = dir.parentFile
        Json.parseToJsonElement(File(checkNotNull(dir) { "$VECTORS not found" }, VECTORS).readText()).jsonObject
    }

    private fun rows(key: String) = root.getValue(key).jsonArray.map { it.jsonObject }

    @Test
    fun formatAndSizes() {
        assertEquals("doorprints-delete-policy-vectors/1", root.getValue("format").jsonPrimitive.content)
        assertTrue(rows("decisions").size >= 40)
        // Every action appears, so a new action cannot be added without a row.
        val seen = rows("decisions").map { it.getValue("action").jsonPrimitive.content }.toSet()
        assertEquals(DeletionAction.entries.map { it.name }.toSet(), seen)
    }

    @Test
    fun decisions() {
        for (row in rows("decisions")) {
            val name = row.getValue("name").jsonPrimitive.content
            val c = row.getValue("context").jsonObject
            val ctx = DeletionContext(
                platform = AuthPlatform.valueOf(c.getValue("platform").jsonPrimitive.content),
                deviceLock = c.getValue("deviceLock").jsonPrimitive.boolean,
                webPrf = c.getValue("webPrf").jsonPrimitive.boolean,
                online = c.getValue("online").jsonPrimitive.boolean,
                backupsLeft = c.getValue("backupsLeft").jsonPrimitive.intOrNull,
            )
            val got = DeletionPolicy.decide(DeletionAction.valueOf(row.getValue("action").jsonPrimitive.content), ctx)
            val want = row.getValue("result").jsonObject
            when (want.getValue("outcome").jsonPrimitive.content) {
                "REFUSED" -> assertEquals(DeletionDecision.Refused(RefusalReason.valueOf(want.getValue("reason").jsonPrimitive.content)), got, name)
                else -> assertEquals(
                    DeletionDecision.Allowed(
                        Requirements(
                            level = DeleteLevel.valueOf(want.getValue("level").jsonPrimitive.content),
                            factor = Factor.valueOf(want.getValue("factor").jsonPrimitive.content),
                            tickBox = want.getValue("tickBox").jsonPrimitive.boolean,
                            delaySeconds = want.getValue("delaySeconds").jsonPrimitive.int,
                            pairing = Pairing.valueOf(want.getValue("pairing").jsonPrimitive.content),
                            authValidMs = want.getValue("authValidMs").jsonPrimitive.long,
                        ),
                    ),
                    got, name,
                )
            }
        }
    }

    private fun req(tick: Boolean, delay: Int, factor: Factor, valid: Long) =
        Requirements(DeleteLevel.L2, factor, tick, delay, Pairing.NONE, valid)

    @Test
    fun confirmButton() {
        for (row in rows("confirm")) {
            val r = req(row.getValue("tickBox").jsonPrimitive.boolean, row.getValue("delaySeconds").jsonPrimitive.int, Factor.NONE, 0)
            assertEquals(
                row.getValue("enabled").jsonPrimitive.boolean,
                DeletionPolicy.confirmEnabled(r, row.getValue("ticked").jsonPrimitive.boolean, row.getValue("elapsedMs").jsonPrimitive.long),
                row.getValue("name").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun grants() {
        for (row in rows("grants")) {
            val r = req(false, 0, Factor.valueOf(row.getValue("factor").jsonPrimitive.content), row.getValue("authValidMs").jsonPrimitive.long)
            assertEquals(
                GrantCheck.valueOf(row.getValue("result").jsonPrimitive.content),
                DeletionPolicy.grantCheck(r, row.getValue("grantedAtMs").jsonPrimitive.long, row.getValue("startedAtMs").jsonPrimitive.long),
                row.getValue("name").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun gate() {
        for (row in rows("gate")) {
            val name = row.getValue("name").jsonPrimitive.content
            val platform = AuthPlatform.valueOf(row.getValue("platform").jsonPrimitive.content)
            val want = row.getValue("result").jsonPrimitive.content
            if (row.getValue("op").jsonPrimitive.content == "CONNECT") {
                val got = GateRules.connect(platform, row.getValue("lockEnabled").jsonPrimitive.boolean)
                assertEquals(want, if (got == ConnectDecision.Allowed) "ALLOWED" else "NEEDS_SCREEN_LOCK", name)
            } else {
                val lock = LockState.valueOf(row.getValue("lock").jsonPrimitive.content)
                val got = GateRules.run(platform, lock)
                val text = when (got) {
                    RunDecision.Run -> "RUN"
                    RunDecision.PausedNoLock -> "PAUSED_NO_LOCK"
                    RunDecision.PausedUnknown -> "PAUSED_UNKNOWN"
                }
                assertEquals(want, text, name)
                assertEquals(row.getValue("dropsKeys").jsonPrimitive.boolean, GateRules.dropsKeys(platform, lock), name)
            }
        }
    }

    private companion object {
        const val VECTORS = "docs/schemas/delete-policy-vectors.json"
    }
}
