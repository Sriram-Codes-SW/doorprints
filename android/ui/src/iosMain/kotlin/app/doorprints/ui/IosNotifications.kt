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

import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatus
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * Local notifications on iOS (S4b-BL-69, Hunt mode on iPhone): the alerts [IosHunt] posts through
 * `UNUserNotificationCenter`, the one prompt ("Doorprints would like to send you notifications", asked in context when
 * Hunt mode is turned on, as on Android 13+), and the taps, which become the app's deep links ([install]).
 *
 * iOS reads the authorization asynchronously, so [status] keeps the last answer: every read asks again and answers
 * from the last one, which is right by the time it matters (the first read is at start-up, [install]). Alerts are
 * shown as banners while the app is in the foreground too, as Android's heads-up alerts are. What the lock screen
 * shows is iOS's own *Show Previews* setting (docs/02 F-14). Main thread, except where noted.
 */
internal object IosNotifications {
    private val center: UNUserNotificationCenter get() = UNUserNotificationCenter.currentNotificationCenter()

    /** The authorization as last read; null before the first answer. Main thread. */
    private var status: UNAuthorizationStatus? = null
    private var delegate: Delegate? = null
    private var onTap: ((userInfo: Map<Any?, *>) -> Unit)? = null

    /**
     * Sets the centre's delegate (before the app finishes launching, from the Swift app's `init`, so a tap that starts
     * the app is delivered too) and reads the authorization once. [onTap] gets the tapped notification's `userInfo`.
     */
    fun install(onTap: (userInfo: Map<Any?, *>) -> Unit) {
        this.onTap = onTap
        if (delegate == null) {
            val d = Delegate()
            delegate = d
            center.delegate = d
        }
        refresh()
    }

    /** Reads the authorization again; [then] runs on the main thread with the answer. */
    private fun refresh(then: ((UNAuthorizationStatus) -> Unit)? = null) {
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val read = settings?.authorizationStatus ?: UNAuthorizationStatusNotDetermined
            // The handler runs on a background queue; the field and the callers are main-thread.
            dispatch_async(dispatch_get_main_queue()) {
                status = read
                then?.invoke(read)
            }
        }
    }

    /** True when an alert can reach the user (allowed, or provisionally); refreshed on every read. */
    fun canPost(): Boolean {
        refresh()
        return when (status) {
            UNAuthorizationStatusAuthorized,
            UNAuthorizationStatusProvisional,
            UNAuthorizationStatusEphemeral,
            -> true
            else -> false
        }
    }

    /**
     * The authorization read now rather than the last answer (the area wake-up, S4b-BL-96, may run in a background
     * relaunch before anything has read it): true when an alert can reach the user. Any thread.
     */
    suspend fun authorized(): Boolean = suspendCancellableCoroutine { continuation ->
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val read = settings?.authorizationStatus ?: UNAuthorizationStatusNotDetermined
            dispatch_async(dispatch_get_main_queue()) { status = read }
            if (continuation.isActive) continuation.resume(read in POSTABLE)
        }
    }

    private val POSTABLE = setOf(UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional, UNAuthorizationStatusEphemeral)

    /** True while iOS will still show its prompt: before the first answer only (it never asks twice). */
    fun canAsk(): Boolean = status == null || status == UNAuthorizationStatusNotDetermined

    /** Shows the prompt (alerts and sound, no badge) and calls [onAnswered] on the main thread after the answer. */
    fun request(onAnswered: () -> Unit) {
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { _, _ ->
            refresh { onAnswered() }
        }
    }

    /**
     * Posts one alert at once under [id] (a second with the same id replaces the first, as Android's ids do), with
     * [userInfo] for the tap ([install]). Dropped by iOS when not allowed.
     */
    fun post(id: String, title: String, body: String, userInfo: Map<Any?, *> = emptyMap<Any?, Any?>()) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body)
            setSound(UNNotificationSound.defaultSound)
            setUserInfo(userInfo)
        }
        val request = UNNotificationRequest.requestWithIdentifier(id, content, trigger = null)
        center.addNotificationRequest(request, withCompletionHandler = null)
    }

    /** Takes the alert [id] down (a "stay here?" alert once its visit is saved as a house). */
    fun remove(id: String) {
        center.removeDeliveredNotificationsWithIdentifiers(listOf(id))
        center.removePendingNotificationRequestsWithIdentifiers(listOf(id))
    }

    private class Delegate : NSObject(), UNUserNotificationCenterDelegateProtocol {
        /** In the foreground too: a banner with sound, kept in the list, as Android's heads-up alerts. */
        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            willPresentNotification: UNNotification,
            withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
        ) {
            withCompletionHandler(
                UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionSound or
                    UNNotificationPresentationOptionList,
            )
        }

        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            didReceiveNotificationResponse: UNNotificationResponse,
            withCompletionHandler: () -> Unit,
        ) {
            onTap?.invoke(didReceiveNotificationResponse.notification.request.content.userInfo)
            withCompletionHandler()
        }
    }
}
