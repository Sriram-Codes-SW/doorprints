package com.househunt.app.location

/**
 * Pure rules for Hunt mode's "you've been on this street before" alert, kept free of Android types so they are
 * unit-tested on the JVM. [HuntService] turns the decision into a localised notification.
 */
object StreetAlerts {
    /** At most one alert per street per hour. */
    const val REPEAT_AFTER_MS = 60 * 60_000L

    /** Streets from the geocoder vary in case ("MG Road" / "Mg Road"); one key per street. */
    fun key(street: String): String = street.trim().lowercase()

    /** Same street as before (ignoring case and surrounding spaces), so no new lookup or alert is needed. */
    fun sameStreet(street: String, current: String?): Boolean =
        current != null && key(street) == key(current)

    /** Alert only for a street with saved houses or visits, and not again within [REPEAT_AFTER_MS]. */
    fun shouldAlert(houses: Int, visits: Int, lastAlertAt: Long?, now: Long): Boolean {
        if (houses <= 0 && visits <= 0) return false
        return lastAlertAt == null || now - lastAlertAt >= REPEAT_AFTER_MS
    }
}
