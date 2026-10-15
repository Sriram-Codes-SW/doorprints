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

package app.doorprints.ui

import app.doorprints.shared.model.KindCapabilities
import app.doorprints.shared.model.KindSetting
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.hunt_needs_houses
import app.doorprints.ui.res.hunt_stopped_left_space
import app.doorprints.ui.res.map_notifications_off
import app.doorprints.ui.res.map_street_seen
import app.doorprints.ui.res.notif_seen_house
import app.doorprints.ui.res.notif_stay_title
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Hunt mode is a capability of a kind (docs/03 section 18.19, kinds PR 26, TC-U-269; S4b-BL-304 generalised): the walk belongs to
 * the space it was started in, so only the move out of that space, with the walk running, stops it. Nothing here names a kind: the
 * cases run on the house and the school, which both have Hunt, and on a kind that has none.
 */
class HuntSpaceTest {
    @Test
    fun leavingTheWalksSpaceWhileTheWalkRunsStopsIt() {
        assertTrue(HuntSpace.stopsOnLeaving("house", was = "house", now = "school", active = true))
        assertTrue(HuntSpace.stopsOnLeaving("school", was = "school", now = "house", active = true))
        // Into a space whose kind has no Hunt mode at all (a kind this build does not know): the walk ends the same way.
        assertTrue(HuntSpace.stopsOnLeaving("house", was = "house", now = "clinic", active = true))
    }

    @Test
    fun staysInTheWalksSpaceTheWalkGoesOn() = assertFalse(HuntSpace.stopsOnLeaving("school", was = "school", now = "school", active = true))

    @Test
    fun noWalkNothingToStop() = assertFalse(HuntSpace.stopsOnLeaving("school", was = "school", now = "house", active = false))

    @Test
    fun aWalkStartedWhileAnotherSpaceIsOnScreenIsNotStoppedByLeavingThatSpace() {
        // A reminder's Start Hunt mode while the house is on screen starts a school walk: moving on from the house does not stop it.
        assertFalse(HuntSpace.stopsOnLeaving("school", was = "house", now = "school", active = true))
        assertFalse(HuntSpace.stopsOnLeaving("school", was = "house", now = "other", active = true))
        assertFalse(HuntSpace.stopsOnLeaving(null, was = "house", now = "school", active = true), "no kind recorded: nothing to compare")
    }

    @Test
    fun comingBackToTheWalksSpaceStopsNothing() = assertFalse(HuntSpace.stopsOnLeaving("house", was = "school", now = "house", active = true))

    @Test
    fun aKindHasHuntModeWhenItsFileSaysSo() {
        assertTrue(HuntSpace.has("house"))
        assertTrue(HuntSpace.has("school"))
        assertFalse(HuntSpace.has("clinic"), "a kind this build does not know has none")
        assertEquals(KindCapabilities.of("school").hunt, HuntSpace.of("school"))
        assertNull(HuntSpace.of("clinic"))
    }

    // docs/03 sections 18.18 and 18.19: a walk starts only in a kind that is on; an alert's new entry is never made in a space that is off.
    private val both = KindSetting(listOf("house", "school"), experimental = "1", enabledText = "[\"house\",\"school\"]")

    @Test
    fun huntModeStartsOnlyInAKindThatIsOnAndHasIt() {
        assertTrue(HuntSpace.mayStart(KindSetting(), "house")) // the shipped app: the house alone
        assertTrue(HuntSpace.mayStart(both, "house"))
        assertTrue(HuntSpace.mayStart(both, "school"))
        val schoolOnly = both.withDefault("school").withKindEnabled("house", false)
        assertFalse(HuntSpace.mayStart(schoolOnly, "house"))
        assertTrue(HuntSpace.mayStart(schoolOnly, "school"), "the school has Hunt mode of its own")
        assertFalse(HuntSpace.mayStart(both, "clinic"))
    }

    @Test
    fun aRemindersWalkIsTheKindWithViewingsAndAnAreasWalkIsTheDefaultKindFirst() {
        // A viewing is the house's (viewings are a capability only it has), whatever the default.
        assertEquals("house", HuntSpace.startKind(KindSetting(), HuntSpace.Starter.REMINDER))
        assertEquals("house", HuntSpace.startKind(both.withDefault("school"), HuntSpace.Starter.REMINDER))
        assertNull(HuntSpace.startKind(both.withDefault("school").withKindEnabled("house", false), HuntSpace.Starter.REMINDER), "no kind on has viewings")
        // An area wake-up starts the default kind's walk when that kind takes areas, else the next enabled one.
        assertEquals("house", HuntSpace.startKind(KindSetting(), HuntSpace.Starter.AREA))
        assertEquals("house", HuntSpace.startKind(both, HuntSpace.Starter.AREA))
        assertEquals("school", HuntSpace.startKind(both.withDefault("school"), HuntSpace.Starter.AREA))
        assertEquals("school", HuntSpace.startKind(both.withDefault("school").withKindEnabled("house", false), HuntSpace.Starter.AREA))
        assertEquals("house", HuntSpace.wanted(HuntSpace.Starter.REMINDER), "the words of the kind that would have started it")
        assertEquals("house", HuntSpace.wanted(HuntSpace.Starter.AREA))
    }

    @Test
    fun aHuntAlertMakesAnEntryOfTheWalksKindWhileItIsOnAndTheDefaultKindsWhenItIsOff() {
        assertEquals("house", HuntSpace.alertKind(KindSetting(), "house"))
        assertEquals("house", HuntSpace.alertKind(both, "house"))
        assertEquals("school", HuntSpace.alertKind(both, "school"), "a school walk makes a school, not the default kind")
        assertEquals("school", HuntSpace.alertKind(both.withDefault("school"), "school"))
        assertEquals("house", HuntSpace.alertKind(both.withDefault("school"), "house"))
        val schoolOnly = both.withDefault("school").withKindEnabled("house", false)
        assertEquals("school", HuntSpace.alertKind(schoolOnly, "house"), "the walk's kind was turned off: the default kind's entry")
        assertEquals("school", HuntSpace.alertKind(schoolOnly, null), "an alert of an older build: the default kind's")
    }

    @Test
    fun aKindIdOffTheWireIsBelievedOnlyWhenItIsOneOfTheAppsOwn() {
        assertEquals("school", HuntSpace.knownKind("school"))
        assertEquals("house", HuntSpace.knownKind("house"))
        assertNull(HuntSpace.knownKind("../etc"))
        assertNull(HuntSpace.knownKind("clinic"))
        assertNull(HuntSpace.knownKind(null))
    }

    @Test
    fun theHuntWordsAreTheKindFilesAndTheHousesAreTheStringsHuntModeAlwaysHad() {
        assertEquals(Res.string.notif_seen_house.key, HuntWords.of("house").seen.key)
        assertEquals(Res.string.map_street_seen.key, HuntWords.HOUSE.streetSeen.key)
        assertEquals(Res.string.hunt_stopped_left_space.key, HuntWords.HOUSE.leftSpace.key)
        assertEquals(Res.string.hunt_needs_houses.key, HuntWords.HOUSE.needsKind.key)
        assertEquals(Res.string.notif_stay_title.key, HuntWords.HOUSE.stayTitle.key)
        assertEquals(Res.string.map_notifications_off.key, HuntWords.HOUSE.notificationsOff.key)
        assertEquals("school_map_notifications_off", HuntWords.SCHOOL.notificationsOff.key)
        assertEquals("school_notif_seen", HuntWords.of("school").seen.key)
        assertEquals("school_map_street_seen", HuntWords.SCHOOL.streetSeen.key)
        assertEquals("school_notif_stay_title", HuntWords.SCHOOL.stayTitle.key)
        assertEquals("school_notif_channel_alerts", HuntWords.SCHOOL.channelAlerts.key)
        assertSame(HuntWords.HOUSE, HuntWords.of("clinic")) // a kind with no table of its own reads the house's
    }
}
