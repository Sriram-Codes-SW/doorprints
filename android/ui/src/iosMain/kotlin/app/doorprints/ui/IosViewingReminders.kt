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
import app.doorprints.shared.model.Viewing
import app.doorprints.shared.model.ViewingReminders
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.viewing_reminder_body
import app.doorprints.ui.res.viewing_reminder_public
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
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionNone
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
 * reschedules ([IosAppContainer]). A locked screen with *Show Previews* off shows "Doorprints reminder" (the category's
 * `hiddenPreviewsBodyPlaceholder`). Never `withWhom`. The authorization is asked by the viewing form when a reminder is
 * first saved, never here.
 */
internal object IosViewingReminders {
    const val ID_PREFIX = "viewing-"
    private const val CATEGORY = "viewing"

    private val center: UNUserNotificationCenter get() = UNUserNotificationCenter.currentNotificationCenter()

    /** Reads the viewings, the houses and *Remind me about viewings* from [repository] and reschedules at [nowMs]. */
    suspend fun rescheduleFrom(repository: Repository, nowMs: Long) {
        val on = repository.settings.viewingsRemind().first()
        reschedule(if (on) repository.viewings() else emptyList(), repository.houses.first(), nowMs)
    }

    /** Replaces the pending viewing reminders with the upcoming ones of [viewings] at [nowMs]. */
    suspend fun reschedule(viewings: List<Viewing>, houses: List<HouseEntity>, nowMs: Long) {
        val byId = houses.associateBy { it.id }
        val gone = getString(Res.string.viewings_houseGone)
        val requests = ViewingReminders.upcoming(viewings, nowMs).map { (v, fireAt) ->
            val house = byId[v.houseId]
            val (h, m) = LocalClock.hourMinuteOf(v.startsAt)
            val time = h.toString().padStart(2, '0') + ":" + m.toString().padStart(2, '0')
            request(v.id, fireAt, getString(Res.string.viewing_reminder_body, house?.label?.ifBlank { null } ?: gone, time), house?.id)
        }
        center.setNotificationCategories(
            setOf(
                UNNotificationCategory.categoryWithIdentifier(
                    CATEGORY, actions = emptyList<Any>(), intentIdentifiers = emptyList<Any>(),
                    hiddenPreviewsBodyPlaceholder = getString(Res.string.viewing_reminder_public),
                    options = UNNotificationCategoryOptionNone,
                ),
            ),
        )
        center.getPendingNotificationRequestsWithCompletionHandler { pending ->
            val old = pending.orEmpty().mapNotNull { (it as? UNNotificationRequest)?.identifier?.takeIf { id -> id.startsWith(ID_PREFIX) } }
            if (old.isNotEmpty()) center.removePendingNotificationRequestsWithIdentifiers(old)
            // The centre takes the calls in order, so the new requests land after the removal.
            requests.forEach { center.addNotificationRequest(it, withCompletionHandler = null) }
        }
    }

    private fun request(viewingId: String, fireAt: Long, body: String, houseId: String?): UNNotificationRequest {
        val content = UNMutableNotificationContent().apply {
            setBody(body)
            setSound(UNNotificationSound.defaultSound)
            setCategoryIdentifier(CATEGORY)
            setUserInfo(if (houseId != null) mapOf<Any?, Any?>(IosHunt.KEY_OPEN_HOUSE to houseId) else emptyMap<Any?, Any?>())
        }
        val units = NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or NSCalendarUnitHour or
            NSCalendarUnitMinute or NSCalendarUnitSecond
        val components = NSCalendar.currentCalendar.components(units, fromDate = NSDate.dateWithTimeIntervalSince1970(fireAt / 1000.0))
        val trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(components, repeats = false)
        return UNNotificationRequest.requestWithIdentifier(ID_PREFIX + viewingId, content, trigger)
    }
}
