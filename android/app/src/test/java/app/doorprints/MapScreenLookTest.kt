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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S4b-FR-34, docs/11 5.27.4: a change of *How repeated paths look* re-renders the Map with the same `track` instance, and
 * the walks are not read or detected again. `PlatformMap` is an `expect` composable that MapLibre draws (no fake of it
 * can stand in for the real Map screen), so the plumbing is pinned where it lives: the drawing is built in one
 * `produceState` that sees the walks only (its keys and body never name the look or the settings), the look is read
 * on its own from the settings, and the two reach `PlatformMap` as two parameters; `TraceDrawing.of` takes no look. The
 * behaviour half (the same walks give the same instance) is `TraceRedrawTest`.
 */
class MapScreenLookTest {
    private val root: File = run {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        dir
    }

    private val map = root.resolve("ui/src/commonMain/kotlin/app/doorprints/ui/MapScreen.kt").readText()

    /** The text of the balanced `{ ... }` block that opens at or after [from]. */
    private fun block(text: String, from: Int): String {
        val open = text.indexOf('{', from)
        var depth = 0
        for (i in open until text.length) {
            if (text[i] == '{') depth++
            if (text[i] == '}' && --depth == 0) return text.substring(open, i + 1)
        }
        error("unbalanced")
    }

    @Test
    fun theTrackIsBuiltFromTheWalksOnlyNotFromTheLook() {
        val at = map.indexOf("val track by produceState(")
        assertTrue("the Map builds its track in a produceState", at >= 0)
        val head = map.substring(at, map.indexOf('{', at))
        val body = block(map, at)
        assertEquals("keyed by the repository only", "val track by produceState(TraceDrawing.EMPTY, repo) ", head)
        for (word in listOf("repeatLook", "RepeatLook", "settings", "look")) {
            assertFalse("the drawing's block names $word: a look change would rebuild it", word in body)
        }
        assertTrue("it is built by the cache over the stored walks", "cache.of(repo.walks())" in body)
    }

    @Test
    fun theLookIsItsOwnParameterOfThePlatformMapNextToTheSameTrack() {
        assertTrue(Regex("""repeatLook\s*=\s*repo\.settings\.settings\.collectAsStateWithLifecycle\(AppSettings\(\)\)\.value\.repeatLook""").containsMatchIn(map))
        val at = map.indexOf("PlatformMap(\n")
        val call = map.substring(at, at + 400) // the first parameters, before the events object
        assertTrue("track = track," in call)
        assertTrue("repeatLook = repeatLook," in call)
    }

    @Test
    fun theDrawingTakesNoLook() {
        val drawing = root.resolve("ui/src/commonMain/kotlin/app/doorprints/ui/TraceDrawing.kt").readText()
        assertTrue(
            "of() takes the walks and a point budget, no look",
            "fun of(walks: List<TraceWalk>, drawBudget: Int = DRAW_POINT_BUDGET): TraceDrawing {" in drawing,
        )
        assertFalse("RepeatLook" in drawing.substringBefore("/** The place check's source and layers"))
    }
}
