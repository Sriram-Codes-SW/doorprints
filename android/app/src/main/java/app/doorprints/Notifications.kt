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
     * The status-bar icon for every notification: a single-colour silhouette. The launcher icon is a full-bleed
     * square, and small icons are drawn as an alpha mask, so it showed as a solid white block.
     */
    val SMALL_ICON = R.drawable.ic_stat_doorprints

    const val EXTRA_OPEN_HOUSE = "openHouse"

    /**
     * Which screen a notification opens; only the values in [SCREENS] are accepted (threat model F-25). They are the
     * common root's routes ([Routes], `:ui`), which MainActivity hands over as they are.
     */
    const val EXTRA_OPEN_SCREEN = "openScreen"
    const val SCREEN_EXPORT = Routes.EXPORT
    const val SCREEN_IMPORT = Routes.IMPORT
    const val SCREEN_SETTINGS = Routes.SETTINGS
    val SCREENS = setOf(SCREEN_EXPORT, SCREEN_IMPORT, SCREEN_SETTINGS)
    const val EXTRA_NEW_LAT = "newLat"
    const val EXTRA_NEW_LON = "newLon"
    const val EXTRA_VISIT_ID = "visitId"

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
            if (stop != null) addAction(0, context.getString(R.string.export_stop), stop)
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
     * house too: the house screen does not scroll to its questions yet). Private on a locked screen. Every
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
                        openAppIntent(context, ("questions:" + viewing.id).hashCode()) { putExtra(EXTRA_OPEN_HOUSE, house.id) },
                    )
                }
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

    fun alert(context: Context, id: Int, title: String, text: String, tap: PendingIntent?) {
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
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .apply { if (tap != null) setContentIntent(tap) }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}
