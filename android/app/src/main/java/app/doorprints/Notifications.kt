package app.doorprints

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifications {
    const val CHANNEL_HUNT = "hunt"
    const val CHANNEL_ALERTS = "alerts"
    /** Quiet channel for the progress notification of a long export, import or automatic backup (Sprint 4a). */
    const val CHANNEL_EXPORT = "exports"
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
     * The status-bar icon for every notification: a single-colour silhouette. The launcher icon is a full-bleed
     * square, and small icons are drawn as an alpha mask, so it showed as a solid white block.
     */
    val SMALL_ICON = R.drawable.ic_stat_doorprints

    const val EXTRA_OPEN_HOUSE = "openHouse"

    /** Which screen a notification opens; only the values in [SCREENS] are accepted (threat model F-25). */
    const val EXTRA_OPEN_SCREEN = "openScreen"
    const val SCREEN_EXPORT = "export"
    const val SCREEN_IMPORT = "import"
    const val SCREEN_SETTINGS = "settings"
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

    /** True when this app may post notifications at all: below API 33 always, from 33 with `POST_NOTIFICATIONS`. */
    fun canPost(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

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
