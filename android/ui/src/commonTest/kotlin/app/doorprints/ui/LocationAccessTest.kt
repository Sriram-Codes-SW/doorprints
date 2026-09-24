package app.doorprints.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Approximate location told apart from no location (UX review, whole-app audit, round 3): the Map, the house form and
 * the Assistant show one note ([LocationPermissionNote]) whose text and button follow these rules.
 * Round 4: both texts are the screen's own, so the note names what the user just tried. In `:ui` commonTest since CMP-5
 * (was JUnit in `:app`), with the rules it tests.
 */
class LocationAccessTest {

    @Test
    fun approximateOnlyIsNeitherPreciseNorNone() {
        assertEquals(LocationAccess.PRECISE, locationAccess(fine = true, coarse = true))
        // Precise alone (not offered by Android 12+, but possible on older versions or by a policy) is still precise.
        assertEquals(LocationAccess.PRECISE, locationAccess(fine = true, coarse = false))
        // Android 12+'s "Approximate" answer.
        assertEquals(LocationAccess.APPROXIMATE, locationAccess(fine = false, coarse = true))
        assertEquals(LocationAccess.NONE, locationAccess(fine = false, coarse = false))
    }

    @Test
    fun preciseLocationShowsNoNote() {
        assertNull(locationFix(LocationAccess.PRECISE, canAsk = true))
        assertNull(locationFix(LocationAccess.PRECISE, canAsk = false))
    }

    @Test
    fun noLocationOffersAllowThenSettings() {
        assertEquals(LocationFix.ALLOW, locationFix(LocationAccess.NONE, canAsk = true))
        assertEquals(LocationFix.OPEN_SETTINGS, locationFix(LocationAccess.NONE, canAsk = false))
    }

    @Test
    fun approximateLocationOffersPreciseThenSettingsWithTheSwitchNamed() {
        // Asking again makes Android show "Change to precise location?".
        assertEquals(LocationFix.TURN_ON_PRECISE, locationFix(LocationAccess.APPROXIMATE, canAsk = true))
        // The settings page says location is "Allowed"; the note names "Use precise location" there.
        assertEquals(LocationFix.OPEN_SETTINGS_PRECISE, locationFix(LocationAccess.APPROXIMATE, canAsk = false))
    }

    @Test
    fun onlyAllowAndTurnOnPreciseStartAndroidsPrompt() {
        assertTrue(LocationFix.ALLOW.launchesRequest())
        assertTrue(LocationFix.TURN_ON_PRECISE.launchesRequest())
        assertFalse(LocationFix.OPEN_SETTINGS.launchesRequest())
        assertFalse(LocationFix.OPEN_SETTINGS_PRECISE.launchesRequest())
    }

    private val lead = "Doorprints has only your approximate location."
    private val settings = "In settings, open Permissions, then Location, and turn on ‘Use precise location’."

    @Test
    fun eachScreenGivesItsOwnReason() {
        val form = "$lead Placing this house needs precise location, or type the latitude and longitude."
        val plan = "$lead ‘Plan visits’ needs precise location to start from where you are."
        val formDenied = "Location is off for Doorprints. Allow it, or type the latitude and longitude below."
        assertEquals(form, locationNoteText(LocationFix.TURN_ON_PRECISE, formDenied, form, settings))
        val planDenied = "Location is off for Doorprints. ‘Plan visits’ needs it to start from where you are."
        assertEquals(plan, locationNoteText(LocationFix.TURN_ON_PRECISE, planDenied, plan, settings))
        assertEquals(planDenied, locationNoteText(LocationFix.OPEN_SETTINGS, planDenied, plan, settings))
        // No location: the screen's own denied text, never the approximate one.
        assertEquals(formDenied, locationNoteText(LocationFix.ALLOW, formDenied, form, settings))
        assertEquals(formDenied, locationNoteText(LocationFix.OPEN_SETTINGS, formDenied, form, settings))
    }

    @Test
    fun onlyTheSettingsPathNamesThePreciseSwitch() {
        val map = "$lead Hunt mode and ‘Save house here’ need precise location."
        assertEquals("$map $settings", locationNoteText(LocationFix.OPEN_SETTINGS_PRECISE, "off", map, settings))
        assertFalse(locationNoteText(LocationFix.TURN_ON_PRECISE, "off", map, settings).contains(settings))
    }

    @Test
    fun aFeatureRunsOnlyWithPreciseLocation() {
        // Round 5: the Map's Hunt switch, Save house here and My location, and the Assistant's Plan visits.
        assertEquals(LocationStart.RUN, locationStart(precise = true, canAsk = true))
        assertEquals(LocationStart.RUN, locationStart(precise = true, canAsk = false))
        // No location, or approximate only, while Android will still ask: ask now.
        assertEquals(LocationStart.ASK, locationStart(precise = false, canAsk = true))
        // Android will not ask again (no location, or approximate only): the note, never a "Planning…" or "Finding
        // your location…" state that can only end in the same note.
        assertEquals(LocationStart.SHOW_NOTE, locationStart(precise = false, canAsk = false))
    }

    @Test
    fun locationCanBeAskedUntilAndroidStopsShowingItsPrompt() {
        // Never asked (by any screen): Android shows its prompt.
        assertTrue(canAskAgain(asked = false, rationale = false))
        // Refused once: Android asks again and says a rationale may be shown.
        assertTrue(canAskAgain(asked = true, rationale = true))
        // Refused twice, or "Don't ask again": only the app's settings can turn it on.
        assertFalse(canAskAgain(asked = true, rationale = false))
    }
}
