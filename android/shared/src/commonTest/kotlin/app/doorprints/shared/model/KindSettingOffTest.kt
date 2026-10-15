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

package app.doorprints.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The school compiled in and off (owner, 2026-10-15; docs/03 §18.12, TC-U-264): a build that lists the school, with
 * *Experimental: more than houses* off, is the house build. What the screens read from [KindSetting] (what is offered, enabled
 * and the default, whether there are spaces, which space holds which candidate, what a backup's `search` object says, whether the
 * assistant is shown) equals a build that lists the house alone, whatever else is stored. The website's twin is
 * `experimental-off.spec.ts`.
 */
class KindSettingOffTest {
    private val shipped = KindSettingRules.availableOf("house,school")
    private val houseBuild = KindSetting()

    /** The shipped list with the switch off, and any stored list or default (a backup's hint, an old build, a hand-edited file). */
    private val offVariants = listOf(
        KindSetting(shipped),
        KindSetting(shipped, null, "[\"house\",\"school\"]", "school"),
        KindSetting(shipped, "0", "[\"school\"]", "school"),
        KindSetting(shipped, "", "[\"house\",\"school\"]", null),
        KindSetting(shipped, "true", "not json", "school"),
    )

    @Test
    fun experimentalOffIsTheHouseAlone() {
        val s = KindSetting(shipped)
        assertEquals(listOf("house", "school"), s.available)
        assertEquals(listOf("house"), KindSettingRules.RELEASED, "the school is available, not released")
        assertFalse(s.experimentalOn)
        assertEquals(listOf("house"), s.offered)
        assertEquals(listOf("house"), s.enabled)
        assertEquals("house", s.default)
        assertFalse(s.spaces)
        assertEquals(true, s.hasUnreleased, "the build lists a kind that needs the switch, so Settings shows the switch")
    }

    @Test
    fun noStoredValueOpensTheSchoolWhileTheSwitchIsOff() {
        for (s in offVariants) {
            assertEquals(houseBuild.offered, s.offered, s.toString())
            assertEquals(houseBuild.enabled, s.enabled, s.toString())
            assertEquals(houseBuild.default, s.default, s.toString())
            assertEquals(houseBuild.spaces, s.spaces, s.toString())
            // No kind row to change: turning the school on is refused until the switch is on.
            assertEquals(s, s.withKindEnabled("school", true))
            assertEquals(s, s.withDefault("school"))
        }
    }

    @Test
    fun everyCandidateIsInTheOneSpaceAndTheBackupWritesTheHousesSearch() {
        for (s in offVariants) {
            for (kind in listOf(null, "house", "school", "clinic")) {
                assertEquals(houseBuild.spaceOf(kind), s.spaceOf(kind), "spaceOf($kind) of $s")
                assertEquals(houseBuild.view("house").holds(kind), s.view("house").holds(kind), "the house view holds $kind in $s")
            }
            assertEquals(KindSettingRules.HOUSE_SEARCH, KindSettingRules.searchOf(s.enabled, s.default))
        }
    }

    @Test
    fun theAssistantIsShownAsInTheHouseBuildForEveryCombination() {
        for (s in offVariants) for (on in listOf(false, true)) for (own in listOf(false, true)) {
            assertEquals(
                SpaceScope.aiShownIn("house", houseBuild.available, on, own, emptyList()),
                SpaceScope.aiShownIn(s.default, s.available, on, own, listOf("school")),
                "$s ai=$on own=$own",
            )
        }
    }

    @Test
    fun turningTheSwitchOffAgainPutsTheHouseBuildBackAndKeepsNothingEnabled() {
        val on = KindSetting(shipped).withExperimental(true).withKindEnabled("school", true).withDefault("school")
        assertEquals(listOf("house", "school"), on.enabled)
        assertEquals("school", on.default)
        assertEquals(true, on.spaces)
        val off = on.withExperimental(false)
        assertNull(off.experimental, "off stores no value")
        assertEquals(houseBuild.enabled, off.enabled)
        assertEquals(houseBuild.default, off.default)
        assertFalse(off.spaces)
    }

    @Test
    fun theHouseAloneBuildIsUntouchedByTheSwitch() {
        // A build that lists no second kind has nothing to switch: even a stored `1` offers the house alone.
        val s = KindSetting(listOf("house"), "1", "[\"house\",\"school\"]", "school")
        assertEquals(listOf("house"), s.offered)
        assertEquals(listOf("house"), s.enabled)
        assertFalse(s.hasUnreleased)
    }
}
