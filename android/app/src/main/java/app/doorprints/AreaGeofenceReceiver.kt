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

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import app.doorprints.data.Repository
import app.doorprints.i18n.AppLocale
import app.doorprints.location.HuntState
import app.doorprints.shared.model.AreaCooldown
import app.doorprints.shared.records.RecordRules
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.flow.first

/**
 * The area wake-up's receiver (docs/11 "Design of slice 4b", 5.17 *On entering*). Not exported: the geofences'
 * mutable PendingIntent ([PlayGeofenceRegistrar.pendingIntent]) and the notification's immutable *Dismiss* are
 * explicit to it, and the system's boot and app-update broadcasts reach a non-exported receiver.
 *  - A geofence event: entering areas offers Hunt mode ([AreaWakeupNotifier.onEnter]); `GEOFENCE_NOT_AVAILABLE`
 *    (location switched off: Play services dropped the geofences) registers them again, which holds once location is
 *    back (and the next resume or boot tries again).
 *  - *Dismiss*: removes the notification and counts as notified.
 *  - Boot and app update: the geofences are set again (they do not survive either).
 * It never starts Hunt mode: only the person's tap on *Start Hunt mode* does.
 */
class AreaGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as DoorprintsApp
        when (intent.action) {
            ACTION_GEOFENCE -> {
                val event = GeofencingEvent.fromIntent(intent) ?: return
                if (event.hasError()) {
                    if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                        runAsync { app.container.areaWakeup.reregisterAll() }
                    }
                    return
                }
                if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
                val ids = event.triggeringGeofences.orEmpty().map { it.requestId }
                runAsync { AreaWakeupNotifier.onEnter(app, app.container.repository, ids, System.currentTimeMillis()) }
            }
            ACTION_DISMISS -> {
                val id = intent.getStringExtra(EXTRA_AREA_ID)?.takeIf(RecordRules::isValidId) ?: return
                runAsync { AreaWakeupNotifier.dismiss(app, app.container.repository, id, System.currentTimeMillis()) }
            }
            in RESCHEDULE_ACTIONS -> runAsync { app.container.areaWakeup.reregisterAll() }
        }
    }

    companion object {
        /** A geofence event, from Play services through [PlayGeofenceRegistrar.pendingIntent]. */
        const val ACTION_GEOFENCE = "app.doorprints.action.AREA_GEOFENCE"
        /** A wake-up notification's *Dismiss*. */
        const val ACTION_DISMISS = "app.doorprints.action.AREA_WAKEUP_DISMISS"
        const val EXTRA_AREA_ID = "areaId"

        /** The system events after which the geofences are registered again (docs/11 5.17 *Re-registration*). */
        val RESCHEDULE_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)

        /** Area [id]'s *Dismiss*: immutable, explicit, its own data URI so each area has its own. */
        fun dismissIntent(context: Context, id: String): PendingIntent {
            val intent = Intent(context, AreaGeofenceReceiver::class.java)
                .setAction(ACTION_DISMISS)
                .setData(Uri.fromParts("doorprints-area-dismiss", id, null))
                .putExtra(EXTRA_AREA_ID, id)
            return PendingIntent.getBroadcast(
                context, ("area-dismiss:$id").hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}

/** What entering an area and *Dismiss* do (5.17 *On entering*, *Cooldown*); the receiver's work, testable on its own. */
object AreaWakeupNotifier {
    /**
     * The person entered the areas [ids] (geofence request ids, untrusted: only valid record ids are read) at [nowMs].
     * For each area that is still live and enabled, with the setting on, Hunt mode not running ([huntRunning]) and its
     * last notification at least 6 hours old ([AreaCooldown]), posts "You're in <area>. Start Hunt mode?" under
     * [Notifications.AREA_WAKEUP_ID] with its tag [Notifications.areaTag] and stamps the area. Returns the ids posted.
     */
    suspend fun onEnter(
        context: Context,
        repository: Repository,
        ids: List<String>,
        nowMs: Long,
        huntRunning: Boolean = HuntState.state.value.active,
        fineLocation: Boolean = ViewingReminderScheduler.hasFineLocation(context),
    ): List<String> {
        val settings = repository.settings
        val on = settings.areaWakeup().first()
        if (!on) return emptyList()
        val wanted = ids.filter(RecordRules::isValidId).distinct()
        if (wanted.isEmpty()) return emptyList()
        val live = repository.areas().associateBy { it.id }
        val posted = ArrayList<String>()
        for (id in wanted) {
            val area = live[id]?.takeIf { it.enabled } ?: continue
            if (!AreaCooldown.shouldNotify(settings.areaLastNotified(id), nowMs, huntRunning, on)) continue
            if (!Notifications.canPost(context)) continue
            val n = Notifications.areaWakeup(AppLocale.wrap(context), area, fineLocation, huntRunning)
            try {
                NotificationManagerCompat.from(context).notify(Notifications.areaTag(id), Notifications.AREA_WAKEUP_ID, n)
            } catch (_: SecurityException) {
                continue
            }
            settings.setAreaLastNotified(id, nowMs)
            posted += id
        }
        return posted
    }

    /** *Dismiss* on area [id]'s notification: removed, and counted as notified at [nowMs]. */
    suspend fun dismiss(context: Context, repository: Repository, id: String, nowMs: Long) {
        if (!RecordRules.isValidId(id)) return
        NotificationManagerCompat.from(context).cancel(Notifications.areaTag(id), Notifications.AREA_WAKEUP_ID)
        repository.settings.setAreaLastNotified(id, nowMs)
    }
}
