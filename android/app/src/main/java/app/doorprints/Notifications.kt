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

package app.doorprints

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import app.doorprints.data.HouseEntity
import app.doorprints.location.HuntService
import app.doorprints.shared.model.Area
import app.doorprints.shared.model.LocationSource
import app.doorprints.shared.model.Viewing
import app.doorprints.shared.model.ViewingReminders
import app.doorprints.ui.Formats
import app.doorprints.ui.Routes
import app.doorprints.ui.canPostNotifications

object Notifications {
    const val CHANNEL_HUNT = "hunt"
    const val CHANNEL_ALERTS = "alerts"
    /** Quiet channel for the progress notification of a long export, import or automatic backup (Sprint 4a). */
    const val CHANNEL_EXPORT = "exports"
    /** Viewing reminders (docs/11 5.8, slice 3b-2): default importance, private on the lock screen. */
    const val CHANNEL_VIEWINGS = "viewings"
    /** Hunt mode reminders (docs/11 5.16, slice 3c): default importance, private on the lock screen, no DND bypass. */
    const val CHANNEL_HUNT_REMINDERS = "hunt_reminders"
    /** The area wake-up (docs/11 5.17, slice 4b): default importance, private on the lock screen, no DND bypass. */
    const val CHANNEL_AREA_WAKEUP = "area_wakeup"
    /**
     * The repeated-path alert (docs/11 5.27.5): default importance (the phone's own notification sound, no heads-up), no
     * vibration, no badge, private on a lock screen; the person mutes it, or changes its sound, in Android's settings.
     */
    const val CHANNEL_REPEAT_PATH = "repeat_path"
    // Every fixed notification id the app uses lives here, so two features cannot pick the same number. (The
    // per-house, per-street and per-visit alerts use hash codes and cannot be reserved; they are rare and
    // short-lived.)
    const val ONGOING_ID = 1

    /** Hunt mode's "battery is low" alert (HuntService). Was a private constant there, which is how it collided. */
    const val LOW_BATTERY_ID = 2

    /**
     * Foreground notification of the export worker; separate from [ONGOING_ID] (hunt mode). It was 2 until this
     * round, the same number as the low-battery alert: an alert posted during a long export replaced the export's
     * foreground notification. The ids are new this sprint and unreleased, so renumbering costs nothing.
     */
    const val EXPORT_ID = 5

    /**
     * Foreground notification of the import worker. A separate id from [EXPORT_ID] on purpose: an export and an
     * import run under different unique work names, so both can be active at once, and WorkManager's
     * `SystemForegroundDispatcher` keys its bookkeeping on the notification id. Sharing one id means the second
     * promotion replaces the first's notification, and whichever job finishes first tears that notification
     * down — leaving the other running with no foreground notification at all, which on Android 14+ is the state
     * the system is entitled to kill the process in.
     */
    const val IMPORT_ID = 3

    /**
     * Foreground notification of the weekly automatic backup. Its own id for the same reason as [IMPORT_ID]: the
     * backup runs under yet another unique work name and can overlap a manual export or import.
     */
    const val AUTO_BACKUP_ID = 4

    /** "Your copy is saved" / "could not be saved", posted when no Export screen was there to show it. */
    const val EXPORT_DONE_ID = 6

    /** "Backup imported" / "could not be imported", posted when no Import screen was there to show it. */
    const val IMPORT_DONE_ID = 7

    /** "Automatic backup stopped" / "did not work": the weekly backup must never fail only inside Settings. */
    const val AUTO_BACKUP_PROBLEM_ID = 8

    /**
     * Every viewing reminder (slice 3b-2) is posted under this id with the tag [viewingTag] of its viewing: the pair is
     * what Android keys a notification on, so one id serves every viewing without a hash that could hit 1..8 or
     * another alert, and a second reminder for the same viewing replaces the first.
     */
    const val VIEWING_ID = 9

    /** The tag of viewing [viewingId]'s reminder, posted under [VIEWING_ID]. */
    fun viewingTag(viewingId: String) = "viewing:$viewingId"

    /**
     * Every Hunt mode reminder (slice 3c) is posted under this id with the tag [huntTag] of its viewing, as
     * [VIEWING_ID] with [viewingTag]; a merged one (the viewing reminder in it too) is posted here alone.
     */
    const val HUNT_REMINDER_ID = 10

    /** The tag of viewing [viewingId]'s Hunt mode reminder, posted under [HUNT_REMINDER_ID]. */
    fun huntTag(viewingId: String) = "hunt:$viewingId"

    /**
     * Every area wake-up notification (slice 4b) is posted under this id with the tag [areaTag] of its area, as
     * [VIEWING_ID] with [viewingTag]: a second one for the same area (after the 6-hour cooldown) replaces the first.
     */
    const val AREA_WAKEUP_ID = 11

    /** "Google Drive backup is paused because this phone no longer has a screen lock" (docs/15 §10.3): one, replaced by a later one. */
    const val DRIVE_LOCK_ID = 12

    /** "You have walked this way before" (docs/11 5.27.5): one at a time, a second replaces the first. */
    const val REPEAT_PATH_ID = 13

    /** The tag of area [areaId]'s wake-up notification, posted under [AREA_WAKEUP_ID]. */
    fun areaTag(areaId: String) = "area:$areaId"

    /**
     * The status-bar icon for every notification: a single-colour silhouette. The launcher icon is a full-bleed
     * square, and small icons are drawn as an alpha mask, so it showed as a solid white block.
     */
    val SMALL_ICON = R.drawable.ic_stat_doorprints

    const val EXTRA_OPEN_HOUSE = "openHouse"

    /** With [EXTRA_OPEN_HOUSE], true: the house opens scrolled to its questions (a reminder's *Questions*, S4b-BL-93b). */
    const val EXTRA_OPEN_QUESTIONS = "openQuestions"

    /**
     * Which screen a notification opens; only the values in [SCREENS] are accepted (threat model F-25). They are the
     * common root's routes ([Routes], `:ui`), which MainActivity hands over as they are.
     */
    const val EXTRA_OPEN_SCREEN = "openScreen"
    const val SCREEN_EXPORT = Routes.EXPORT
    const val SCREEN_IMPORT = Routes.IMPORT
    const val SCREEN_SETTINGS = Routes.SETTINGS
    const val SCREEN_MAP = Routes.MAP
    val SCREENS = setOf(SCREEN_EXPORT, SCREEN_IMPORT, SCREEN_SETTINGS, SCREEN_MAP)
    const val EXTRA_NEW_LAT = "newLat"
    const val EXTRA_NEW_LON = "newLon"
    const val EXTRA_VISIT_ID = "visitId"

    /** A viewing to open (a Hunt mode reminder's body, slice 3c); MainActivity accepts only a valid record id. */
    const val EXTRA_OPEN_VIEWING = "openViewing"

    /**
     * *Start Hunt mode* from viewing (the value) [huntTag]'s reminder: to `HuntService`, which then removes that
     * reminder; to MainActivity while location is not granted, which removes it and opens the Map, asking first (5.18).
     */
    const val EXTRA_START_HUNT = "startHunt"

    /**
     * *Start Hunt mode* from area (the value) [areaTag]'s wake-up notification (slice 4b): to `HuntService`, which then
     * removes it; to MainActivity while location is not granted, which removes it and opens the Map, asking first.
     */
    const val EXTRA_START_HUNT_AREA = "startHuntArea"

    /** Pass a localised context (an Activity, or AppLocale.wrap(app)) so channel names follow the app language. */
    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_HUNT, context.getString(R.string.notif_channel_hunt), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_channel_hunt_desc)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EXPORT, context.getString(R.string.notif_channel_export), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_channel_export_desc)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_VIEWINGS, context.getString(R.string.notif_channel_viewings), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_viewings_desc)
                // The house's name is private: a locked screen shows the public version, "Doorprints reminder".
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_HUNT_REMINDERS, context.getString(R.string.notif_channel_hunt_reminders), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_hunt_reminders_desc)
                // As the viewing reminders: "Doorprints reminder" on a locked screen; Do Not Disturb applies as set.
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setBypassDnd(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_AREA_WAKEUP, context.getString(R.string.notif_channel_area_wakeup), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_area_wakeup_desc)
                // The area's name says where the person is: "Doorprints reminder" on a locked screen; DND as set.
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setBypassDnd(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REPEAT_PATH, context.getString(R.string.notif_channel_repeat), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_repeat_desc)
                // The phone's default notification sound (the importance gives it); no vibration, no badge (5.27.5).
                enableVibration(false)
                setShowBadge(false)
                // Where she walked is private: a locked screen shows the public version (F-14).
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.notif_channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notif_channel_alerts_desc)
                // House names, prices and streets are private: hide them on a locked screen (threat model F-14).
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
        )
    }

    fun openAppIntent(context: Context, requestCode: Int, extras: Intent.() -> Unit = {}): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply(extras)
        return PendingIntent.getActivity(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Opens one of [SCREENS]; each screen has its own request code so the PendingIntents do not replace each other. */
    fun openScreenIntent(context: Context, screen: String): PendingIntent =
        openAppIntent(context, SCREEN_REQUEST_BASE + SCREENS.indexOf(screen).coerceAtLeast(0)) {
            putExtra(EXTRA_OPEN_SCREEN, screen)
        }

    private const val SCREEN_REQUEST_BASE = 100

    /**
     * The ongoing notification a long export or import shows while it runs. It carries no house names or prices,
     * only "Saving your copy" and a progress bar, so nothing private appears on a locked screen (F-14). Tapping it
     * opens the screen that shows the same progress ([tap]); [stop], when given, is a Stop action built with
     * `WorkManager.createCancelPendingIntent`, so the job can be stopped without opening the app.
     */
    fun progress(
        context: Context,
        title: String,
        done: Int,
        total: Int,
        tap: PendingIntent? = null,
        stop: PendingIntent? = null,
    ) = NotificationCompat.Builder(context, CHANNEL_EXPORT)
        .setSmallIcon(SMALL_ICON)
        .setContentTitle(title)
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .setProgress(total.coerceAtLeast(1), done.coerceIn(0, total.coerceAtLeast(1)), total <= 0)
        .apply {
            if (tap != null) setContentIntent(tap)
            if (stop != null) addAction(0, context.getString(R.string.notif_task_stop), stop)
        }
        .build()

    /**
     * A one-off result on the quiet export channel: an export or import that finished while no screen was showing
     * it, or an automatic backup that stopped. Private on a locked screen (only the app name shows), dismissed when
     * tapped.
     *
     * Returns whether the notification was really posted: false when notifications are not allowed (API 33+ without
     * `POST_NOTIFICATIONS`, or turned off for the app) or the post failed. The workers record that in their output
     * (`ExportRequest.KEY_NOTIFIED`), so a screen opened hours later still shows a result nobody was told about
     * instead of assuming the notification did the job (UX review, round 11).
     */
    fun result(
        context: Context,
        id: Int,
        title: String,
        text: String?,
        tap: PendingIntent?,
        actions: List<NotificationCompat.Action> = emptyList(),
    ): Boolean {
        if (!canPost(context) || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        // The user can also silence just this channel ("Copies and backups"); a post there is never seen.
        val channel = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL_EXPORT)
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_EXPORT)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(context.getString(R.string.app_name))
            .build()
        val n = NotificationCompat.Builder(context, CHANNEL_EXPORT)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(title)
            .apply {
                if (!text.isNullOrBlank()) {
                    setContentText(text)
                    setStyle(NotificationCompat.BigTextStyle().bigText(text))
                }
                if (tap != null) setContentIntent(tap)
                actions.forEach { addAction(it) }
            }
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(id, n)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * The reminder of [viewing] at [nowMs] (docs/11 5.8, slice 3b-2), worked out when it is shown from the stored
     * viewing and [house] (null: a house that is gone): "Viewing at Green View in 25 min" (or, more than two hours
     * ahead, "Viewing at Green View" with the start below). Never `withWhom`, which is contact data. Actions: *Open
     * house*, *Directions* (a `geo:` link, only for a house whose position is not approximate) and *Questions* (the
     * house, scrolled to its questions; [EXTRA_OPEN_QUESTIONS]). Private on a locked screen. Every
     * PendingIntent is immutable (T-E8); the request codes come from the viewing id, one per action.
     */
    fun viewingReminder(context: Context, viewing: Viewing, house: HouseEntity?, nowMs: Long): Notification {
        val name = house?.label?.ifBlank { null } ?: context.getString(R.string.notif_viewing_house_gone)
        val minutes = ViewingReminders.minutesUntil(viewing.startsAt, nowMs)
        val title = if (minutes <= SOON_MINUTES) {
            context.getString(R.string.notif_viewing_soon, name, minutes)
        } else {
            context.getString(R.string.notif_viewing_title, name)
        }
        val text = context.getString(R.string.notif_viewing_text, Formats.dateTime(viewing.startsAt))
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_VIEWINGS)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(context.getString(R.string.notif_viewing_public))
            .build()
        val openHouse = house?.let { h ->
            openAppIntent(context, ("open:" + viewing.id).hashCode()) { putExtra(EXTRA_OPEN_HOUSE, h.id) }
        }
        return NotificationCompat.Builder(context, CHANNEL_VIEWINGS)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(openHouse ?: openAppIntent(context, ("open:" + viewing.id).hashCode()))
            .apply {
                if (house != null && openHouse != null) {
                    addAction(0, context.getString(R.string.notif_viewing_open), openHouse)
                    if (house.locationSource != LocationSource.APPROX) {
                        addAction(0, context.getString(R.string.notif_viewing_directions), directionsIntent(context, viewing.id, house))
                    }
                    addAction(
                        0, context.getString(R.string.notif_viewing_questions),
                        openAppIntent(context, ("questions:" + viewing.id).hashCode()) {
                            putExtra(EXTRA_OPEN_HOUSE, house.id)
                            putExtra(EXTRA_OPEN_QUESTIONS, true)
                        },
                    )
                }
            }
            .build()
    }

    /**
     * The Hunt mode reminder of [viewing] at [nowMs] (docs/11 5.16, slice 3c), worked out when it is shown: "Viewing at
     * Green View in 15 min. Start Hunt mode?" (the minutes from the start, as an inexact alarm may come early). Never
     * `withWhom`. Actions: **Start Hunt mode** (left out while [huntRunning]): when [fineLocation] is granted an
     * immutable PendingIntent that starts `HuntService` (a start from the person's tap on a notification may run a
     * location foreground service), otherwise MainActivity with [EXTRA_START_HUNT], whose Map asks for location first
     * (5.18); *Open house* when [merged] (the viewing reminder is in this one) and the house is there; and **Dismiss**.
     * Tapping the body opens the viewing. Private on a locked screen with the public version "Doorprints reminder".
     */
    fun huntReminder(
        context: Context,
        viewing: Viewing,
        house: HouseEntity?,
        nowMs: Long,
        merged: Boolean,
        fineLocation: Boolean,
        huntRunning: Boolean,
    ): Notification {
        val name = house?.label?.ifBlank { null } ?: context.getString(R.string.notif_viewing_house_gone)
        val minutes = ViewingReminders.minutesUntil(viewing.startsAt, nowMs)
        val title = context.getString(R.string.notif_hunt_reminder, name, minutes)
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_HUNT_REMINDERS)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(context.getString(R.string.notif_viewing_public))
            .build()
        val openViewing = openAppIntent(context, ("hunt-open:" + viewing.id).hashCode()) { putExtra(EXTRA_OPEN_VIEWING, viewing.id) }
        return NotificationCompat.Builder(context, CHANNEL_HUNT_REMINDERS)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notif_viewing_text, Formats.dateTime(viewing.startsAt)))
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(openViewing)
            .apply {
                if (!huntRunning) {
                    addAction(0, context.getString(R.string.notif_hunt_reminder_start), startHuntIntent(context, viewing.id, fineLocation))
                }
                if (merged && house != null) {
                    addAction(
                        0, context.getString(R.string.notif_viewing_open),
                        openAppIntent(context, ("hunt-house:" + viewing.id).hashCode()) { putExtra(EXTRA_OPEN_HOUSE, house.id) },
                    )
                }
                addAction(0, context.getString(R.string.notif_hunt_reminder_dismiss), ViewingReminderScheduler.dismissIntent(context, viewing.id))
            }
            .build()
    }

    /**
     * *Start Hunt mode*'s PendingIntent, immutable: `HuntService` itself with [fineLocation] (the service stops quietly
     * if the permission went away since), else MainActivity at the Map's location question ([EXTRA_START_HUNT]).
     */
    fun startHuntIntent(context: Context, viewingId: String, fineLocation: Boolean): PendingIntent =
        startHunt(context, EXTRA_START_HUNT, viewingId, "hunt", fineLocation)

    /** *Start Hunt mode* of area [areaId]'s wake-up notification (slice 4b): as [startHuntIntent], the same 3c path. */
    fun startHuntFromAreaIntent(context: Context, areaId: String, fineLocation: Boolean): PendingIntent =
        startHunt(context, EXTRA_START_HUNT_AREA, areaId, "area", fineLocation)

    private fun startHunt(context: Context, extra: String, id: String, kind: String, fineLocation: Boolean): PendingIntent =
        if (fineLocation) {
            PendingIntent.getForegroundService(
                context, ("$kind-start:$id").hashCode(),
                Intent(context, HuntService::class.java).putExtra(extra, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            openAppIntent(context, ("$kind-ask:$id").hashCode()) { putExtra(extra, id) }
        }

    /**
     * The area wake-up's notification for [area] (docs/11 5.17, slice 4b): "You're in Adyar. Start Hunt mode?" on
     * [CHANNEL_AREA_WAKEUP], private on a locked screen with the public version "Doorprints reminder", no full-screen
     * intent. Actions: **Start Hunt mode** ([startHuntFromAreaIntent]; left out while [huntRunning]) and **Dismiss**
     * ([AreaGeofenceReceiver.dismissIntent], which counts as notified); tapping the body opens the app. Every
     * PendingIntent is immutable; nothing starts without a tap.
     */
    fun areaWakeup(context: Context, area: Area, fineLocation: Boolean, huntRunning: Boolean): Notification {
        val title = context.getString(R.string.notif_area_wakeup, area.name)
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_AREA_WAKEUP)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(context.getString(R.string.notif_viewing_public))
            .build()
        return NotificationCompat.Builder(context, CHANNEL_AREA_WAKEUP)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(openAppIntent(context, ("area-open:" + area.id).hashCode()))
            .apply {
                if (!huntRunning) {
                    addAction(0, context.getString(R.string.notif_hunt_reminder_start), startHuntFromAreaIntent(context, area.id, fineLocation))
                }
                addAction(0, context.getString(R.string.notif_hunt_reminder_dismiss), AreaGeofenceReceiver.dismissIntent(context, area.id))
            }
            .build()
    }

    /** Up to this many minutes ahead the reminder says "in 25 min"; further ahead (2 hours, 1 day) it gives the start. */
    private const val SOON_MINUTES = 90

    /**
     * The phone's maps app at [house]: `geo:lat,lon?q=lat,lon`, the position only (no name goes to the other app).
     * Dot decimals whatever the locale, as a `geo:` URI needs.
     */
    fun directionsIntent(context: Context, viewingId: String, house: HouseEntity): PendingIntent {
        val at = String.format(java.util.Locale.ROOT, "%.6f,%.6f", house.lat, house.lon)
        val intent = Intent(Intent.ACTION_VIEW, "geo:$at?q=$at".toUri())
        return PendingIntent.getActivity(
            context, ("directions:$viewingId").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** True when this app may post notifications at all: below API 33 always, from 33 with `POST_NOTIFICATIONS`. */
    fun canPost(context: Context): Boolean = canPostNotifications(context)

    /**
     * A Hunt mode alert: private on a locked screen, which shows the public version "Doorprints alert" (F-14, SEC-022).
     * [hideOnLockScreen] (the app lock is on, S4b-BL-68, T-I29) makes it secret: a locked screen shows nothing of it
     * even where the phone is set to show all notification content, which would show a private one whole and name the
     * house; it still sounds and shows once the phone is unlocked.
     */
    fun alert(context: Context, id: Int, title: String, text: String, tap: PendingIntent?, hideOnLockScreen: Boolean = false) {
        if (!canPost(context)) return
        // What a locked screen shows instead of the house details (threat model F-14, SEC-022).
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(context.getString(R.string.notif_public))
            .build()
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(if (hideOnLockScreen) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .apply { if (tap != null) setContentIntent(tap) }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    /**
     * The repeated-path alert (docs/11 5.27.5): *You have walked this way before* on [CHANNEL_REPEAT_PATH], a tap opens the
     * Map, auto-cancelled and gone after two minutes. Private on a locked screen with the public version of the Hunt
     * alerts ("Doorprints alert", so no new string reaches the lock screen); [hideOnLockScreen] (the app lock is on,
     * S4b-BL-68) makes it secret. It holds no place, distance or count. Dropped quietly when notifications are not allowed.
     */
    fun repeatPath(context: Context, hideOnLockScreen: Boolean): Notification {
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(context.getString(R.string.notif_public))
            .build()
        return NotificationCompat.Builder(context, CHANNEL_REPEAT_PATH)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(context.getString(R.string.notif_repeat_title))
            .setContentText(context.getString(R.string.notif_repeat_text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setTimeoutAfter(REPEAT_PATH_TIMEOUT_MS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(if (hideOnLockScreen) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(openScreenIntent(context, SCREEN_MAP))
            .build()
    }

    /** Posts [repeatPath]; false when notifications are not allowed or the post failed. */
    fun alertRepeatPath(context: Context, hideOnLockScreen: Boolean): Boolean {
        if (!canPost(context)) return false
        return try {
            NotificationManagerCompat.from(context).notify(REPEAT_PATH_ID, repeatPath(context, hideOnLockScreen))
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** The alert goes by itself after two minutes (docs/11 5.27.5). */
    const val REPEAT_PATH_TIMEOUT_MS = 120_000L
}
