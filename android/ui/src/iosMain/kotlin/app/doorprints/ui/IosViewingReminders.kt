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

import app.doorprints.data.HouseEntity
import app.doorprints.data.Repository
import app.doorprints.shared.model.HuntReminders
import app.doorprints.shared.model.Viewing
import app.doorprints.shared.model.ViewingReminders
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.viewing_hunt_reminder_body
import app.doorprints.ui.res.viewing_reminder_body
import app.doorprints.ui.res.viewings_houseGone
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * Viewing reminders on iPhone (docs/11 5.8, slice 3b-2), what Android's `ViewingReminderScheduler` is there: the
 * pending local notifications of `UNUserNotificationCenter`. Each [reschedule] removes every pending request whose
 * identifier starts with [ID_PREFIX] and adds the upcoming ones ([ViewingReminders.upcoming], at most 60 of the 64 iOS
 * keeps) as non-repeating calendar triggers at their fireAt: "Viewing at Green View at 10:00" in the app language,
 * `userInfo` with Hunt mode's `openHouse` key, so the existing tap handler ([IosNotifications.install]) opens the
 * house. The body is fixed when it is scheduled (iOS shows a pending request as it is), so a rename of the house also
 * reschedules ([IosAppContainer]). Never `withWhom`. The authorization is asked by the viewing form when a reminder is
 * first saved, never here. There is no notification category with a hidden-previews placeholder: building one
 * (`UNNotificationCategory.categoryWithIdentifier(..., hiddenPreviewsBodyPlaceholder, ...)`) crashed the app at launch
 * with a bad pointer inside UserNotifications, on a coroutine thread and only some of the time (PR 84, 2026-09-30);
 * iOS already hides a notification's text on the lock screen unless the person chose otherwise under *Show Previews*.
 * The launch self-check's `reminders` step runs [reschedule] many times so a repeat shows on every CI run.
 *
 * The Hunt mode reminders (docs/11 5.16, slice 3c) are queued here too, as `viewing-hunt-<id>` ([HUNT_PREFIX], so the
 * removal above takes them as well): "Viewing at Green View at 10:00. Start Hunt mode?", with the same merge rule and
 * the cap of 60 over both kinds ([HuntReminders.merged]); a merged one is the Hunt one. A tap opens the Map, which
 * offers Hunt mode in a snackbar ([IosHunt.KEY_OFFER_HUNT_VIEWING], [DeepLink.OfferHunt]; S4b-BL-94c): an iPhone app
 * cannot start location tracking from a notification action, so the person starts it from the Map.
 */
internal object IosViewingReminders {
    const val ID_PREFIX = "viewing-"
    const val HUNT_PREFIX = "viewing-hunt-"

    private val center: UNUserNotificationCenter get() = UNUserNotificationCenter.currentNotificationCenter()

    /**
     * Reads the viewings, the houses, *Remind me about viewings* and the Hunt reminder's switch and lead time from
     * [repository] and reschedules at [nowMs].
     */
    suspend fun rescheduleFrom(repository: Repository, nowMs: Long) {
        val settings = repository.settings
        reschedule(
            repository.viewings(), repository.houses.first(), nowMs,
            viewingsOn = settings.viewingsRemind().first(),
            huntOn = settings.huntRemind().first(),
            huntLead = settings.huntReminderMin().first(),
        )
    }

    /** Replaces the pending reminders with the upcoming ones of [viewings] at [nowMs], both kinds. */
    suspend fun reschedule(
        viewings: List<Viewing>,
        houses: List<HouseEntity>,
        nowMs: Long,
        viewingsOn: Boolean = true,
        huntOn: Boolean = false,
        huntLead: Int = HuntReminders.DEFAULT_LEAD,
    ) {
        val byId = houses.associateBy { it.id }
        val gone = getString(Res.string.viewings_houseGone)
        val requests = HuntReminders.merged(viewings, huntLead, nowMs, viewingsOn, huntOn).map { r ->
            val v = r.viewing
            val house = byId[v.houseId]
            val name = house?.label?.ifBlank { null } ?: gone
            val (h, m) = LocalClock.hourMinuteOf(v.startsAt)
            val time = h.toString().padStart(2, '0') + ":" + m.toString().padStart(2, '0')
            if (r.kind == HuntReminders.Kind.VIEWING) {
                request(ID_PREFIX + v.id, r.at, getString(Res.string.viewing_reminder_body, name, time), house?.id?.let { mapOf(IosHunt.KEY_OPEN_HOUSE to it) })
            } else {
                request(HUNT_PREFIX + v.id, r.at, getString(Res.string.viewing_hunt_reminder_body, name, time), mapOf(IosHunt.KEY_OFFER_HUNT_VIEWING to v.id))
            }
        }
        center.getPendingNotificationRequestsWithCompletionHandler { pending ->
            val old = pending.orEmpty().mapNotNull { (it as? UNNotificationRequest)?.identifier?.takeIf { id -> id.startsWith(ID_PREFIX) } }
            if (old.isNotEmpty()) center.removePendingNotificationRequestsWithIdentifiers(old)
            // The centre takes the calls in order, so the new requests land after the removal.
            requests.forEach { center.addNotificationRequest(it, withCompletionHandler = null) }
        }
    }

    private fun request(identifier: String, fireAt: Long, body: String, userInfo: Map<String, String>?): UNNotificationRequest {
        val content = UNMutableNotificationContent().apply {
            setBody(body)
            setSound(UNNotificationSound.defaultSound)
            setUserInfo(userInfo.orEmpty().entries.associate<Map.Entry<String, String>, Any?, Any?> { it.key to it.value })
        }
        val units = NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or NSCalendarUnitHour or
            NSCalendarUnitMinute or NSCalendarUnitSecond
        val components = NSCalendar.currentCalendar.components(units, fromDate = NSDate.dateWithTimeIntervalSince1970(fireAt / 1000.0))
        val trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(components, repeats = false)
        return UNNotificationRequest.requestWithIdentifier(identifier, content, trigger)
    }
}
