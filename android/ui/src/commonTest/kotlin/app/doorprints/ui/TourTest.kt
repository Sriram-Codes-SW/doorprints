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

import androidx.compose.ui.geometry.Rect
import app.doorprints.data.TourEnd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guided tour's rules (S4b-FR-39), apart from any screen: which steps a phone shows and in what order, the one-time
 * offer, the session that walks them and says how it ended, the registry of target boxes, and the card's placement maths.
 * Named mutation gates for these are in `tools/mutations/app-tour.json`.
 */
class TourTest {
    private val android = TourContext(PlatformFeatures(), assistant = true, offlineMaps = true)
    private val iphone = TourContext(PlatformFeatures.Ios, assistant = true, offlineMaps = true)

    private fun ids(context: TourContext) = TourSteps.forPhone(context).map { it.id }

    // --- the steps ---

    @Test
    fun androidShowsEveryFeatureInTheOrderOfAHouseHunt() {
        assertEquals(
            listOf(
                "welcome", "add", "hunt", "check", "offline", "find", "house", "compare", "assistant", "language", "save", "import",
                "share", "backup", "trace", "drive", "server", "brokers", "criteria", "viewings", "areas", "lock", "help", "done",
            ),
            ids(android),
        )
    }

    @Test
    fun theIphoneLeavesOutOnlyWhatItDoesNotHave() {
        assertEquals(ids(android) - "backup", ids(iphone), "the iPhone has no weekly backup to a folder")
    }

    @Test
    fun aPhoneWithoutAMapHasNoMapStepsAndStartsOnTheHouses() {
        val noMap = TourContext(PlatformFeatures(map = false), assistant = true, offlineMaps = true)
        val steps = TourSteps.forPhone(noMap)
        assertEquals(ids(android).filterNot { it in setOf("add", "hunt", "check", "offline") }, steps.map { it.id })
        assertEquals("houses", steps.first().route, "the tour starts on the tab the app starts on")
        assertEquals("houses", steps.last().route)
    }

    @Test
    fun aStepWhoseFeatureIsOffIsLeftOut() {
        fun without(features: PlatformFeatures, assistant: Boolean = true, offline: Boolean = true) =
            ids(TourContext(features, assistant, offline))
        assertFalse("assistant" in without(PlatformFeatures(), assistant = false))
        assertFalse("offline" in without(PlatformFeatures(), offline = false))
        assertFalse("offline" in without(PlatformFeatures(map = false)))
        assertEquals(setOf("hunt", "trace"), ids(android).toSet() - without(PlatformFeatures(huntMode = false)).toSet())
        assertEquals(setOf("save", "import", "share"), ids(android).toSet() - without(PlatformFeatures(copiesAndImports = false)).toSet())
        assertEquals(setOf("backup"), ids(android).toSet() - without(PlatformFeatures(weeklyBackup = false)).toSet())
        assertEquals(setOf("drive"), ids(android).toSet() - without(PlatformFeatures(googleDrive = false)).toSet())
    }

    @Test
    fun everyStepIsOnATabAndTheStepsStayOnOneTabAtATime() {
        val steps = TourSteps.forPhone(android)
        assertTrue(steps.all { it.route in setOf("map", "houses", "compare", "settings") })
        val tabs = steps.map { it.route }
        // Settings is one run: the screen only scrolls one way.
        assertEquals(tabs.filter { it == "settings" }, tabs.dropWhile { it != "settings" }.takeWhile { it == "settings" })
        assertEquals(steps.map { it.id }.toSet().size, steps.size, "ids are unique")
    }

    @Test
    fun eachStepIsOnTheTabOfTheScreenItPointsAt() {
        assertEquals(
            mapOf(
                "welcome" to "map", "add" to "map", "hunt" to "map", "check" to "map", "offline" to "map", "find" to "houses",
                "house" to "houses", "compare" to "compare", "assistant" to "map", "language" to "settings", "save" to "settings",
                "import" to "settings", "share" to "settings", "backup" to "settings", "trace" to "settings", "drive" to "settings",
                "server" to "settings", "brokers" to "settings", "criteria" to "settings", "viewings" to "settings",
                "areas" to "settings", "lock" to "settings", "help" to "settings", "done" to "map",
            ),
            TourSteps.forPhone(android).associate { it.id to it.route },
        )
    }

    @Test
    fun eachStepPointsAtItsOwnControl() {
        assertEquals(
            mapOf(
                "add" to listOf("map.save"), "hunt" to listOf("map.hunt"), "check" to listOf("map.check"),
                "offline" to listOf("map.offline"), "find" to listOf("houses.search"),
                "house" to listOf("houses.first", "houses.search"), "compare" to listOf("compare.picker", "compare.title"),
                "assistant" to listOf("nav.assistant"), "language" to listOf("settings.language"),
                "save" to listOf("settings.export"), "import" to listOf("settings.import"), "share" to listOf("settings.share"),
                "backup" to listOf("settings.backup"), "trace" to listOf("settings.trace"), "drive" to listOf("settings.drive"),
                "server" to listOf("settings.server"), "brokers" to listOf("settings.brokers"),
                "criteria" to listOf("settings.criteria"), "viewings" to listOf("settings.viewings"),
                "areas" to listOf("settings.areas"), "lock" to listOf("settings.lock"), "help" to listOf("settings.help"),
            ),
            TourSteps.forPhone(android).filter { it.targets.isNotEmpty() }.associate { it.id to it.targets },
        )
    }

    @Test
    fun theTourOpensAndClosesOnAPlainCard() {
        val steps = TourSteps.forPhone(android)
        assertTrue(steps.first().targets.isEmpty() && steps.last().targets.isEmpty())
        assertTrue(steps.drop(1).dropLast(1).all { it.targets.isNotEmpty() }, "every other step points at something")
    }

    // --- the offer ---

    @Test
    fun theOfferComesOnceOnTheHomeTabToSomeoneWhoHasNotSeenTheTour() {
        assertTrue(TourOffer.show(TourMemory.UNSEEN, active = false, route = "map", home = "map"))
        assertFalse(TourOffer.show(TourMemory.UNSEEN, active = false, route = "houses", home = "map"), "not on another tab")
        assertFalse(TourOffer.show(TourMemory.UNSEEN, active = true, route = "map", home = "map"), "not while the tour runs")
        assertFalse(TourOffer.show(TourMemory.SEEN, active = false, route = "map", home = "map"), "not again once it ended")
        assertFalse(TourOffer.show(TourMemory.LOADING, active = false, route = "map", home = "map"), "not before the choice is read")
    }

    @Test
    fun theRememberedChoiceIsReadAsSeenAsSoonAsTheTourEndsHere() {
        assertEquals(TourMemory.LOADING, TourMemory.of(loaded = false, stored = null, endedHere = false))
        assertEquals(TourMemory.UNSEEN, TourMemory.of(loaded = true, stored = null, endedHere = false))
        assertEquals(TourMemory.SEEN, TourMemory.of(loaded = true, stored = TourEnd.DONE, endedHere = false))
        assertEquals(TourMemory.SEEN, TourMemory.of(loaded = true, stored = TourEnd.SKIPPED, endedHere = false))
        assertEquals(TourMemory.SEEN, TourMemory.of(loaded = true, stored = null, endedHere = true), "before the write lands")
    }

    // --- the session ---

    private fun session(count: Int = 3): TourSession {
        val steps = TourSteps.forPhone(android).take(count)
        return TourSession().apply { start(steps) }
    }

    @Test
    fun walksForwardAndBackNeverBeforeTheFirstStepAndFinishesAfterTheLast() {
        val s = session(3)
        assertTrue(s.active && s.isFirst && !s.isLast)
        s.back()
        assertEquals(0, s.position, "back on the first step stays on it")
        assertNull(s.next())
        assertNull(s.next())
        assertEquals(2, s.position)
        assertTrue(s.isLast)
        s.back()
        assertEquals(1, s.position)
        assertNull(s.next())
        assertEquals(TourEnd.DONE, s.next(), "next on the last step finishes")
        assertFalse(s.active)
        assertNull(s.step)
    }

    @Test
    fun skippingEndsItAtAnyStepAsSkippedAndSaysSo() {
        val s = session(3)
        s.next()
        assertEquals(TourEnd.SKIPPED, s.skip())
        assertFalse(s.active)
        assertEquals(TourEnd.SKIPPED, TourSession().skip(), "Not now on the offer, with no tour running, is a skip too")
    }

    @Test
    fun aTourThatIsNotRunningIgnoresNextAndBackAndAnEmptyTourDoesNotStart() {
        val s = TourSession()
        assertNull(s.next())
        s.back()
        assertFalse(s.active)
        s.start(emptyList())
        assertFalse(s.active)
    }

    @Test
    fun startingAgainBeginsAtTheFirstStep() {
        val s = session(3)
        s.next()
        s.skip()
        s.start(TourSteps.forPhone(android).take(3))
        assertEquals(0, s.position)
        assertEquals("welcome", s.step?.id)
    }

    // --- the registry ---

    @Test
    fun theRegistryHoldsWhereTargetsAreAndDropsThemWhenTheyLeave() {
        val r = TourRegistry()
        val box = Rect(10f, 20f, 110f, 70f)
        r.report("a", box)
        assertEquals(box, r.box("a"))
        assertNull(r.box("b"))
        r.report("a", Rect(0f, 0f, 5f, 5f))
        assertEquals(Rect(0f, 0f, 5f, 5f), r.box("a"), "a target that moved is read where it is now")
        r.forget("a")
        assertNull(r.box("a"))
    }

    @Test
    fun aStepHighlightsTheFirstOfItsTargetsThatIsOnScreen() {
        val r = TourRegistry()
        val targets = listOf("first", "second")
        assertNull(r.firstOnScreen(targets))
        r.report("second", Rect(0f, 0f, 1f, 1f))
        assertEquals("second", r.firstOnScreen(targets))
        r.report("first", Rect(0f, 0f, 1f, 1f))
        assertEquals("first", r.firstOnScreen(targets))
    }

    // --- the card's placement ---

    private val screen = Rect(0f, 0f, 1000f, 2000f)
    private val area = Rect(0f, 100f, 1000f, 1900f)
    private val gap = 16f
    private val clear = 8f
    private val pad = 8f
    private val minCard = 300f

    private fun spotOf(target: Rect?) = TourLayout.spot(target, screen, pad, gap, minCard)

    @Test
    fun theHighlightIsTheControlWithPaddingAndNoneForAControlThatIsNotThere() {
        assertEquals(Rect(92f, 292f, 308f, 408f), spotOf(Rect(100f, 300f, 300f, 400f)))
        assertNull(spotOf(null))
        assertNull(spotOf(Rect(100f, 300f, 100f, 400f)), "no width")
        assertNull(spotOf(Rect(100f, 2500f, 300f, 2600f)), "off the screen")
    }

    @Test
    fun aControlMostlyScrolledAwayIsNotHighlighted() {
        // 100 tall at the top edge: only a quarter inside.
        assertNull(spotOf(Rect(100f, -80f, 300f, 20f)))
        assertNotNull(spotOf(Rect(100f, -20f, 300f, 80f)), "more than half on the screen")
    }

    @Test
    fun aLongSectionIsHighlightedByItsTopPartSoTheCardKeepsRoom() {
        val spot = spotOf(Rect(0f, 200f, 1000f, 1900f))!!
        assertEquals(200f - pad, spot.top)
        assertEquals(screen.height * TourLayout.SPOT_FRACTION, spot.height, 0.01f)
    }

    @Test
    fun theHighlightLeavesTheCardAtLeastItsMinimumOnTheRoomierSide() {
        val short = Rect(0f, 0f, 1000f, 700f)
        val spot = TourLayout.spot(Rect(0f, 100f, 1000f, 600f), short, pad, gap, minCard = 250f)!!
        assertTrue(spot.height <= short.height - 2 * (250f + gap), "${spot.height}")
    }

    @Test
    fun theCardGoesBelowTheHighlightWhenItFitsThere() {
        val spot = spotOf(Rect(100f, 300f, 300f, 400f))
        val p = TourLayout.place(area, spot, need = 400f, gap, clear)
        assertEquals(TourSide.BELOW, p.side)
        assertEquals(area.bottom - gap - spot!!.bottom - clear, p.maxHeight)
        assertEquals(area.bottom - gap - 400f, p.top(area, 400f, gap), "hugging the bottom edge, by the thumb")
    }

    @Test
    fun theCardGoesAboveWhenItDoesNotFitBelowAndAboveHasMoreRoom() {
        val spot = spotOf(Rect(100f, 1400f, 300f, 1500f))
        val p = TourLayout.place(area, spot, need = 600f, gap, clear)
        assertEquals(TourSide.ABOVE, p.side)
        assertEquals(spot!!.top - area.top - gap - clear, p.maxHeight)
        assertEquals(area.top + gap, p.top(area, 600f, gap))
    }

    @Test
    fun theCardStaysBelowWhenAboveHasLessRoomEvenIfItDoesNotFit() {
        val spot = spotOf(Rect(100f, 400f, 300f, 500f))
        val p = TourLayout.place(area, spot, need = 1700f, gap, clear)
        assertEquals(TourSide.BELOW, p.side, "the larger side wins, and the text scrolls")
        assertTrue(p.maxHeight < 1700f)
    }

    @Test
    fun withNoHighlightTheCardIsCentredOrForTheOfferAtTheBottom() {
        assertEquals(TourSide.CENTRE, TourLayout.place(area, null, need = 500f, gap, clear).side)
        assertEquals(area.top + (area.height - 500f) / 2f, TourLayout.place(area, null, 500f, gap, clear).top(area, 500f, gap))
        assertEquals(TourSide.BELOW, TourLayout.place(area, null, need = 500f, gap, clear, bottom = true).side)
    }

    @Test
    fun aHighlightInTheBottomBarSendsTheCardAboveItAndNotOverTheBar() {
        // The Assistant tab: the card's area ends at the bar's top, the highlight is inside the bar.
        val bar = Rect(0f, 1800f, 1000f, 2000f)
        val cardArea = Rect(0f, 100f, 1000f, 1800f)
        val spot = TourLayout.spot(Rect(500f, 1840f, 700f, 1960f), screen, pad, gap, minCard)
        // A tall card: it takes all the room above, which ends at the bar, not at the highlight inside the bar.
        val p = TourLayout.place(cardArea, spot, need = 1800f, gap, clear)
        assertEquals(TourSide.ABOVE, p.side)
        val height = minOf(1800f, p.maxHeight)
        assertTrue(p.top(cardArea, height, gap) + height <= bar.top, "the card ends above the bar")
    }

    @Test
    fun theCardNeverCoversTheHighlightWhereverItIs() {
        var checked = 0
        for (top in 150..1800 step 50) for (h in listOf(40, 120, 400, 900)) for (need in listOf(200, 500, 900, 1600)) {
            val spot = spotOf(Rect(60f, top.toFloat(), 400f, (top + h).toFloat())) ?: continue
            val p = TourLayout.place(area, spot, need.toFloat(), gap, clear)
            val height = minOf(need.toFloat(), p.maxHeight)
            val cardTop = p.top(area, height, gap)
            val overlaps = cardTop < spot.bottom && cardTop + height > spot.top
            assertFalse(overlaps, "card $cardTop..${cardTop + height} covers highlight ${spot.top}..${spot.bottom}")
            assertTrue(cardTop >= area.top && cardTop + height <= area.bottom, "card inside the area")
            checked++
        }
        assertTrue(checked > 100)
    }
}
