package app.doorprints.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LocationAsk] over the platform seam (CMP-5): it reads the grants, the "asked" flag and whether the system will
 * still ask from [PlatformServices], once when created and again on [LocationAsk.refresh]; the note's button records
 * the ask and starts the prompt while the system will still show it, and opens the app's settings once it will not.
 */
class LocationAskTest {
    private class FakePlatform(
        var access: LocationAccess = LocationAccess.NONE,
        var asked: Boolean = false,
        var rationale: Boolean = false,
    ) : PlatformServices {
        var settingsOpened = 0
        override fun isScreenReaderOn() = false
        override fun locationAccess() = access
        override fun locationAsked() = asked
        override fun markLocationAsked() {
            asked = true
        }
        override fun canAskLocation() = canAskAgain(asked, rationale)
        override fun openAppSettings() {
            settingsOpened++
        }
        override fun canPostNotifications() = true
        override fun dial(number: String) = Unit
        override fun openUrl(url: String) = false
    }

    @Test
    fun theFirstTapAsksAndRecordsIt() {
        val platform = FakePlatform()
        val ask = LocationAsk(platform)
        assertEquals(LocationFix.ALLOW, ask.fix)
        var launched = 0
        ask.requestOrOpenSettings { launched++ }
        assertEquals(1, launched)
        assertTrue(platform.asked)
        assertTrue(ask.asked)
        assertEquals(0, platform.settingsOpened)
    }

    @Test
    fun afterASecondRefusalTheButtonOpensSettings() {
        val platform = FakePlatform(asked = true, rationale = false)
        val ask = LocationAsk(platform)
        assertEquals(LocationFix.OPEN_SETTINGS, ask.fix)
        var launched = 0
        ask.requestOrOpenSettings { launched++ }
        assertEquals(0, launched)
        assertEquals(1, platform.settingsOpened)
    }

    @Test
    fun refreshFollowsAGrantInSettings() {
        val platform = FakePlatform(access = LocationAccess.APPROXIMATE, asked = true, rationale = true)
        val ask = LocationAsk(platform)
        assertTrue(ask.approximateOnly)
        assertEquals(LocationFix.TURN_ON_PRECISE, ask.fix)
        platform.access = LocationAccess.PRECISE
        // Not read again until a resume or an answer.
        assertFalse(ask.granted)
        ask.refresh()
        assertTrue(ask.granted)
        assertNull(ask.fix)
        // Nothing to turn on: the button does nothing.
        var launched = 0
        ask.requestOrOpenSettings { launched++ }
        assertEquals(0, launched)
        assertEquals(0, platform.settingsOpened)
    }
}
