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
package app.doorprints.drive.photo

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The rules of the 30-day clean-up: which deleted photos' Drive files leave the person's Drive (S4b-BL-293; docs/15 §5.1). */
class PhotoSweepRulesTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private val vectors = Json.parseToJsonElement(repoFile("docs/schemas/photo-sweep-vectors.json").readText()).jsonObject
    private fun JsonObject.text(key: String) = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    /** The shared vectors `docs/schemas/photo-sweep-vectors.json`, the file the website's `photo-sweep-rules.spec.ts` reads too. */
    @Test
    fun theSharedVectors() {
        assertEquals("doorprints-photo-sweep-vectors/1", vectors.text("format"))
        assertEquals(vectors.text("windowDays")!!.toInt(), PhotoSweepRules.SWEEP_WINDOW_DAYS)
        val now = vectors.text("now")!!.toLong()
        val cases = vectors.getValue("cases").jsonArray.map { it.jsonObject }
        check(cases.size >= 10)
        for (c in cases) {
            val photos = c.getValue("photos").jsonArray.map { it.jsonObject }.map { p ->
                GonePhoto(p.text("id")!!, now - p.text("deletedAgoMs")!!.toLong(), ref = p.text("driveFileId")?.let { PhotoRef(it, "a".repeat(64)) })
            }
            val kept = c.text("oldestStayingBackupAgoMs")?.let { now - it.toLong() }
            assertEquals(c.text("name"), c.getValue("expect").jsonArray.map { it.jsonPrimitive.content }, PhotoSweepRules.photoFilesToSweep(photos, kept, now))
        }
    }

    @Test
    fun theTombstoneSourceIsEmptyUntilBoundAndBindsOnce() = runTest {
        val source = PhotoTombstoneSource()
        assertTrue(source.read().isEmpty())
        val one = GonePhoto("p1", 1L)
        source.bind { listOf(one) }
        assertEquals(listOf(one), source.read())
        val again = runCatching { source.bind { emptyList() } }
        assertTrue(again.exceptionOrNull() is IllegalStateException)
    }

    private val day = 24L * 60 * 60 * 1000
    private val now = 2_000L * day

    /** A deleted photo whose bytes are gone here, with the Drive file of its bytes (null when none is known). */
    private fun gone(id: String, deletedAt: Long, file: String? = "file-$id") =
        GonePhoto(id = id, deletedAt = deletedAt, ref = file?.let { PhotoRef(it, "a".repeat(64)) })

    private fun sweep(photos: List<GonePhoto>, oldestKeptBackupAt: Long?) =
        PhotoSweepRules.photoFilesToSweep(photos, oldestKeptBackupAt, now)

    @Test
    fun `is the restore window itself, so the clean-up cannot take a file Restore still promises`() {
        assertEquals(PhotoRestoreRules.RESTORE_WINDOW_DAYS, PhotoSweepRules.SWEEP_WINDOW_DAYS)
        assertEquals(30, PhotoSweepRules.SWEEP_WINDOW_DAYS)
    }

    @Test
    fun `takes a file older than the window when no backup is kept`() {
        assertEquals(listOf("file-p1"), sweep(listOf(gone("p1", now - 31 * day)), null))
    }

    @Test
    fun `keeps a file at exactly the window and takes it one millisecond older`() {
        assertEquals(emptyList<String>(), sweep(listOf(gone("p1", now - 30 * day)), null))
        assertEquals(listOf("file-p1"), sweep(listOf(gone("p1", now - 30 * day - 1)), null))
    }

    @Test
    fun `keeps a file a staying backup could still hold - not older than the oldest staying backup`() {
        // The oldest staying backup was made after the deletion, so no backup holds the file.
        assertEquals(listOf("file-p1"), sweep(listOf(gone("p1", now - 40 * day)), now - 39 * day))
        // The oldest staying backup predates the deletion, so it may hold the file.
        assertEquals(emptyList<String>(), sweep(listOf(gone("p1", now - 40 * day)), now - 41 * day))
        // Exactly at the oldest staying backup's time: still not strictly older, so keep it.
        assertEquals(emptyList<String>(), sweep(listOf(gone("p1", now - 40 * day)), now - 40 * day))
    }

    @Test
    fun `needs both conditions - old enough and older than every staying backup`() {
        assertEquals(emptyList<String>(), sweep(listOf(gone("p1", now - 5 * day)), now - 4 * day))
        assertEquals(emptyList<String>(), sweep(listOf(gone("p1", now - 5 * day)), null))
        assertEquals(emptyList<String>(), sweep(listOf(gone("p1", now - 5 * day)), now - 6 * day))
    }

    @Test
    fun `skips a photo with no Drive file named - there is nothing to take`() {
        assertEquals(emptyList<String>(), sweep(listOf(gone("p1", now - 40 * day, null)), null))
    }

    @Test
    fun `returns each file once, even when two photos name it`() {
        val both = listOf(gone("p1", now - 40 * day, "shared"), gone("p2", now - 50 * day, "shared"))
        assertEquals(listOf("shared"), sweep(both, null))
    }

    @Test
    fun `sweeps only the photos that qualify, in order, and leaves the rest`() {
        val photos = listOf(gone("old", now - 90 * day), gone("young", now - 2 * day), gone("mid", now - 31 * day, null))
        assertEquals(listOf("file-old"), sweep(photos, now - 80 * day))
    }

    @Test
    fun `a null oldest staying backup means nothing can refer, so age alone decides`() {
        assertNull(PhotoSweepRules.photoFilesToSweep(emptyList(), null, now).firstOrNull())
    }
}
