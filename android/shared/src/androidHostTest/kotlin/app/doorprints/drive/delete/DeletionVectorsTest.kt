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

package app.doorprints.drive.delete

import app.doorprints.drive.DriveException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The shared deletion vectors (`docs/schemas/delete-vectors.json`, README section 6.3, S4b-BL-119): the same pure rules
 * as the website's `drive-deletion-rules.spec.ts`.
 */
class DeletionVectorsTest {
    private val root: JsonObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, VECTORS).exists()) dir = dir.parentFile
        Json.parseToJsonElement(File(checkNotNull(dir) { "$VECTORS not found" }, VECTORS).readText()).jsonObject.also {
            assertEquals("doorprints-delete-vectors/1", it.getValue("format").jsonPrimitive.content)
        }
    }

    private fun cases(key: String) = root.getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.str(key: String) = get(key)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.props(key: String) = getValue(key).jsonObject.mapValues { it.value.jsonPrimitive.content }
    private fun kind(code: String) = ItemKind.entries.first { it.code == code }

    @Test
    fun filesAreOursOnlyByKindAndFolder() {
        for (c in cases("classifyFile")) {
            assertEquals(c.str("name"), c.str("item"), DeletionRules.classifyFile(c.props("props"), c.str("container")!!)?.code)
        }
    }

    @Test
    fun foldersAreOursOnlyUnderTheRootWithAKnownRole() {
        for (c in cases("classifyFolder")) {
            assertEquals(c.str("name"), c.str("role"), DeletionRules.classifyFolder(c.props("props"), c.str("container")!!))
        }
    }

    @Test
    fun theOrderIsDataThenKeysThenFolders() {
        for (c in cases("phases")) {
            assertEquals(c.toString(), c.getValue("phase").jsonPrimitive.content.toInt(), DeletionRules.phaseOf(kind(c.str("kind")!!), c.str("folderRole")))
        }
    }

    private fun action(o: JsonObject): DeletionAction = when (o.str("type")) {
        "oneBackup" -> DeletionAction.OneBackup(o.str("fileId")!!)
        "olderBackups" -> DeletionAction.OlderBackups
        "allBackups" -> DeletionAction.AllBackups
        else -> DeletionAction.Everything
    }

    @Test
    fun backupsAreSelectedOldestFirstWithTheirLevel() {
        for (c in cases("selectBackups")) {
            val refs = c.getValue("backups").jsonArray.map { it.jsonObject }.map {
                DeletionRules.BackupRef(it.str("id")!!, it.getValue("complete").jsonPrimitive.boolean, it.getValue("createdAt").jsonPrimitive.long)
            }
            val got = DeletionRules.selectBackups(action(c.getValue("action").jsonObject), refs)
            val ids = c["ids"]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.content }
            assertEquals(c.str("name"), ids, got.ids)
            assertEquals(c.str("name"), DeletionLevel.valueOf(c.str("level")!!), got.level)
        }
    }

    @Test
    fun operationIdsAreStable() {
        for (c in cases("operationIds")) {
            val ids = c.getValue("ids").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(c.toString(), c.str("operationId"), DeletionRules.operationId(DeletionLevel.valueOf(c.str("level")!!), c.str("rootId")!!, ids))
        }
    }

    @Test
    fun authorizationIsCheckedInOrder() {
        for (c in cases("authorization")) {
            val t = c["token"]?.takeIf { it !is JsonNull }?.jsonObject?.let {
                AuthorizationToken(DeletionLevel.valueOf(it.str("level")!!), it.getValue("issuedAtMs").jsonPrimitive.long, it.str("operationId")!!, "proof")
            }
            val got = DeletionRules.authorizationProblem(t, DeletionLevel.valueOf(c.str("required")!!), c.str("operationId")!!, c.getValue("nowMs").jsonPrimitive.long)
            assertEquals(c.str("name"), c.str("problem"), got?.name)
        }
    }

    @Test
    fun onlySomeFailuresStopARun() {
        for (c in cases("stopsRun")) {
            assertEquals(c.toString(), c.getValue("stops").jsonPrimitive.boolean, DeletionRules.stopsRun(DriveException.Kind.valueOf(c.str("kind")!!)))
        }
    }

    private companion object {
        const val VECTORS = "docs/schemas/delete-vectors.json"
    }
}
