package com.househunt.app

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
    const val ONGOING_ID = 1

    const val EXTRA_OPEN_HOUSE = "openHouse"
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

    fun alert(context: Context, id: Int, title: String, text: String, tap: PendingIntent?) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33
        ) return
        // What a locked screen shows instead of the house details (threat model F-14, SEC-022).
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_public))
            .build()
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher)
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
