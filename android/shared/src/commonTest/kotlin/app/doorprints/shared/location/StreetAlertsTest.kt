package app.doorprints.shared.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Hunt mode's "been on this street before" alert rules. */
class StreetAlertsTest {

    private val hour = StreetAlerts.REPEAT_AFTER_MS

    @Test
    fun keyIgnoresCaseAndSpaces() {
        assertEquals("mg road", StreetAlerts.key("  MG Road "))
        assertTrue(StreetAlerts.sameStreet("Mg Road", "MG ROAD"))
        assertFalse(StreetAlerts.sameStreet("MG Road", "Brigade Road"))
        assertFalse(StreetAlerts.sameStreet("MG Road", null))
    }

    @Test
    fun unknownStreetNeverAlerts() {
        assertFalse(StreetAlerts.shouldAlert(houses = 0, visits = 0, lastAlertAt = null, now = 10 * hour))
    }

    @Test
    fun knownStreetAlertsOnceAnHour() {
        assertTrue(StreetAlerts.shouldAlert(houses = 1, visits = 0, lastAlertAt = null, now = 10 * hour))
        assertTrue(StreetAlerts.shouldAlert(houses = 0, visits = 2, lastAlertAt = null, now = 10 * hour))
        assertFalse(StreetAlerts.shouldAlert(houses = 1, visits = 2, lastAlertAt = 10 * hour, now = 10 * hour + hour - 1))
        assertTrue(StreetAlerts.shouldAlert(houses = 1, visits = 2, lastAlertAt = 10 * hour, now = 11 * hour))
    }
}
