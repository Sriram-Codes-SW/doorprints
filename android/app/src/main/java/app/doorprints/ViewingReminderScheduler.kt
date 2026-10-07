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

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.doorprints.data.Repository
import app.doorprints.i18n.AppLocale
import app.doorprints.location.HuntState
import app.doorprints.shared.model.HuntReminders
import app.doorprints.shared.model.ViewingReminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Schedules the viewing reminders (docs/11 5.8, slice 3b-2; the alarm rules of 5.16 *Scheduling*) and the Hunt mode
 * reminders (5.16, slice 3c). Everything comes from the stored viewings each time ([rescheduleAll]): cancel both
 * alarms of every viewing id here, tombstones included, then set the upcoming 60 of both kinds together
 * ([HuntReminders.merged]: a viewing reminder while *Remind me about viewings* is on, a Hunt reminder while *Offer
 * Hunt mode before viewings* is on, one alarm when the two are within 10 minutes). One immutable [PendingIntent] per
 * (viewing, kind) to the non-exported [ViewingReminderReceiver]: the viewing kind's data is `doorprints-viewing:<id>`,
 * the Hunt kind's (a merged one too) `doorprints-hunt:<id>`, so two ids with the same hash, or the two kinds of one id,
 * still get two alarms.
 *
 * How each is set: `setExactAndAllowWhileIdle` when `canScheduleExactAlarms()` (checked before every exact call; a
 * `SecurityException` falls back); otherwise `setWindow(fireAt - 10 min, 10 min)`, early and never late; when an alarm
 * cannot be set at all (the system's alarm cap), WorkManager one-time work with the delay. The text is worked out when
 * the reminder is shown, so nothing private waits in the alarm.
 *
 * [exact] and [window] are the two AlarmManager calls, replaceable in tests to make them throw.
 */
class ViewingReminderScheduler(
    private val context: Context,
    private val repository: Repository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val alarms: AlarmManager = context.getSystemService(AlarmManager::class.java)
    private val lock = Mutex()

    internal var exact: (at: Long, operation: PendingIntent) -> Unit =
        { at, op -> alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op) }
    internal var window: (start: Long, length: Long, operation: PendingIntent) -> Unit =
        { start, length, op -> alarms.setWindow(AlarmManager.RTC_WAKEUP, start, length, op) }

    /** How one reminder was set. */
    enum class How { EXACT, WINDOW, WORK }

    /** One alarm set by [rescheduleAll]: viewing [id]'s, of [kind], and how. */
    data class Scheduled(val id: String, val kind: HuntReminders.Kind, val how: How)

    /** True when an exact alarm may be set: below Android 12 always, from 12 with *Alarms & reminders* allowed. */
    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    /**
     * Cancels every reminder of both kinds and sets the upcoming ones; returns what was set, earliest first. One run at
     * a time (a boot broadcast and the start-up collector can overlap).
     */
    suspend fun rescheduleAll(): List<Scheduled> = lock.withLock {
        for (id in repository.viewingIdsForReminders()) {
            cancel(operation(context, id, PendingIntent.FLAG_NO_CREATE))
            cancel(huntOperation(context, id, PendingIntent.FLAG_NO_CREATE))
        }
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
        val settings = repository.settings
        val viewingsOn = settings.viewingsRemind().first()
        val huntOn = settings.huntRemind().first()
        if (!viewingsOn && !huntOn) return@withLock emptyList()
        val t = now()
        HuntReminders.merged(repository.viewings(), settings.huntReminderMin().first(), t, viewingsOn, huntOn).map { r ->
            val operation = if (r.kind == HuntReminders.Kind.VIEWING) {
                operation(context, r.viewing.id, PendingIntent.FLAG_UPDATE_CURRENT)!!
            } else {
                huntOperation(context, r.viewing.id, PendingIntent.FLAG_UPDATE_CURRENT)!!
            }
            Scheduled(r.viewing.id, r.kind, schedule(operation, r.viewing.id, r.kind, r.at, t))
        }
    }

    private fun schedule(operation: PendingIntent, id: String, kind: HuntReminders.Kind, at: Long, nowMs: Long): How {
        if (canScheduleExact()) {
            try {
                exact(at, operation)
                return How.EXACT
            } catch (_: SecurityException) {
                // Revoked between the check and the call: the window alarm below.
            }
        }
        try {
            window(ViewingReminders.windowStart(at, nowMs), ViewingReminders.WINDOW_MS, operation)
            return How.WINDOW
        } catch (_: RuntimeException) {
            // The system's cap on an app's alarms (IllegalStateException) or a refused call: WorkManager instead.
        }
        val work = OneTimeWorkRequestBuilder<ViewingReminderWorker>()
            .setInitialDelay((at - nowMs).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(EXTRA_VIEWING_ID to id, EXTRA_HUNT to (kind != HuntReminders.Kind.VIEWING)))
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueue(work)
        return How.WORK
    }

    private fun cancel(operation: PendingIntent?) {
        operation ?: return
        alarms.cancel(operation)
        operation.cancel()
    }

    companion object {
        const val ACTION_REMIND = "app.doorprints.action.VIEWING_REMINDER"
        /** A Hunt mode reminder's alarm (slice 3c), a merged one too. */
        const val ACTION_HUNT_REMIND = "app.doorprints.action.HUNT_REMINDER"
        /** A Hunt mode reminder's *Dismiss*. */
        const val ACTION_HUNT_DISMISS = "app.doorprints.action.HUNT_REMINDER_DISMISS"
        const val EXTRA_VIEWING_ID = "viewingId"
        /** The WorkManager fallback's input: true for a Hunt (or merged) reminder. */
        const val EXTRA_HUNT = "hunt"
        const val WORK_TAG = "viewing-reminder"

        /** Viewing [id]'s alarm operation; with `FLAG_NO_CREATE` null when none is set. Always immutable (T-E8). */
        internal fun operation(context: Context, id: String, flags: Int): PendingIntent? {
            val intent = Intent(context, ViewingReminderReceiver::class.java)
                .setAction(ACTION_REMIND)
                .setData(Uri.fromParts("doorprints-viewing", id, null))
                .putExtra(EXTRA_VIEWING_ID, id)
            return PendingIntent.getBroadcast(context, id.hashCode(), intent, flags or PendingIntent.FLAG_IMMUTABLE)
        }

        /** Viewing [id]'s Hunt mode reminder alarm operation (a merged one too); as [operation], its own data URI. */
        internal fun huntOperation(context: Context, id: String, flags: Int): PendingIntent? {
            val intent = Intent(context, ViewingReminderReceiver::class.java)
                .setAction(ACTION_HUNT_REMIND)
                .setData(Uri.fromParts("doorprints-hunt", id, null))
                .putExtra(EXTRA_VIEWING_ID, id)
            return PendingIntent.getBroadcast(context, ("hunt:$id").hashCode(), intent, flags or PendingIntent.FLAG_IMMUTABLE)
        }

        /** A Hunt mode reminder's *Dismiss*: removes it (immutable, to the same non-exported receiver). */
        fun dismissIntent(context: Context, id: String): PendingIntent {
            val intent = Intent(context, ViewingReminderReceiver::class.java)
                .setAction(ACTION_HUNT_DISMISS)
                .setData(Uri.fromParts("doorprints-hunt-dismiss", id, null))
                .putExtra(EXTRA_VIEWING_ID, id)
            return PendingIntent.getBroadcast(
                context, ("hunt-dismiss:$id").hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Shows viewing [id]'s Hunt mode reminder at [nowMs] when it is still due as stored now
         * ([HuntReminders.showNow]: PLANNED, `huntReminder` still on, not started) and *Offer Hunt mode before viewings*
         * is on. It is merged (carries *Open house*) when the viewing reminder is on and within 10 minutes. [fineLocation]
         * and [huntRunning] are read when shown: *Start Hunt mode* starts the service or asks for location, and is left
         * out while Hunt mode runs. Returns whether it was posted.
         */
        suspend fun showHunt(
            context: Context,
            repository: Repository,
            id: String,
            nowMs: Long,
            fineLocation: Boolean = hasFineLocation(context),
            huntRunning: Boolean = HuntState.state.value.active,
        ): Boolean {
            val settings = repository.settings
            if (!settings.huntRemind().first()) return false
            val viewing = repository.getViewing(id) ?: return false
            val lead = settings.huntReminderMin().first()
            val merged = settings.viewingsRemind().first() && HuntReminders.isMerged(viewing, lead)
            if (!HuntReminders.showNow(viewing, lead, nowMs, withViewing = merged)) return false
            if (!Notifications.canPost(context)) return false
            val house = repository.getHouse(viewing.houseId)
            val n = Notifications.huntReminder(AppLocale.wrap(context), viewing, house, nowMs, merged, fineLocation, huntRunning)
            return try {
                NotificationManagerCompat.from(context).notify(Notifications.huntTag(id), Notifications.HUNT_REMINDER_ID, n)
                true
            } catch (_: SecurityException) {
                false
            }
        }

        /** Precise location, as Hunt mode's house alerts need (5.18); approximate only asks again from the Map. */
        fun hasFineLocation(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /**
         * Shows viewing [id]'s reminder at [nowMs] when it is still due as stored now ([ViewingReminders.showNow]) and
         * the setting is on; an alarm left behind by an edit, a cancel, a delete or "remove all data" shows nothing.
         * Returns whether it was posted.
         */
        suspend fun show(context: Context, repository: Repository, id: String, nowMs: Long): Boolean {
            if (!repository.settings.viewingsRemind().first()) return false
            val viewing = repository.getViewing(id) ?: return false
            if (!ViewingReminders.showNow(viewing, nowMs)) return false
            // Merged into the Hunt mode reminder since this alarm was set (slice 3c): that one carries it.
            if (repository.settings.huntRemind().first() && HuntReminders.isMerged(viewing, repository.settings.huntReminderMin().first())) {
                return false
            }
            if (!Notifications.canPost(context)) return false
            val house = repository.getHouse(viewing.houseId)
            val n = Notifications.viewingReminder(AppLocale.wrap(context), viewing, house, nowMs)
            return try {
                NotificationManagerCompat.from(context).notify(Notifications.viewingTag(id), Notifications.VIEWING_ID, n)
                true
            } catch (_: SecurityException) {
                false
            }
        }
    }
}

/** The reminders' (and the area wake-up's) short-lived scope for a receiver's `goAsync` work (the app's scope is the process's). */
internal val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    // The system always hands a real PendingResult to onReceive; a unit test that calls onReceive itself gets null. A
    // null one used to throw from the `finally` below, on a background thread, and a later test saw that as an
    // uncaught exception before it started.
    val pending: BroadcastReceiver.PendingResult? = goAsync()
    receiverScope.launch {
        try {
            block()
        } catch (_: Exception) {
            // Nothing to tell anyone from a broadcast; the next start reschedules.
        } finally {
            pending?.finish()
        }
    }
}

/**
 * A viewing reminder's alarm went off: shows it ([ViewingReminderScheduler.show]). Not exported; only this app's own
 * immutable PendingIntent reaches it.
 */
class ViewingReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ViewingReminderScheduler.EXTRA_VIEWING_ID) ?: return
        when (intent.action) {
            ViewingReminderScheduler.ACTION_REMIND -> {
                val app = context.applicationContext as DoorprintsApp
                runAsync { ViewingReminderScheduler.show(app, app.container.repository, id, System.currentTimeMillis()) }
            }
            ViewingReminderScheduler.ACTION_HUNT_REMIND -> {
                val app = context.applicationContext as DoorprintsApp
                runAsync { ViewingReminderScheduler.showHunt(app, app.container.repository, id, System.currentTimeMillis()) }
            }
            ViewingReminderScheduler.ACTION_HUNT_DISMISS ->
                NotificationManagerCompat.from(context).cancel(Notifications.huntTag(id), Notifications.HUNT_REMINDER_ID)
        }
    }
}

/**
 * The system events after which every reminder is set again (docs/11 5.16): boot (alarms do not survive it), the clock
 * or time zone changed (an RTC alarm keeps its instant, and a viewing's start is an instant too, but the system may
 * have dropped them), this app updated (its alarms are cleared), and *Alarms & reminders* granted (exact alarms from
 * now on). Not exported: each of these broadcasts is sent by the system, which reaches a non-exported receiver, and
 * no other app needs to call it.
 */
class ViewingReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val app = context.applicationContext as DoorprintsApp
        runAsync { app.container.reminders.rescheduleAll() }
    }

    companion object {
        // The exact-alarm action is only a string on API < 31, where nothing sends it.
        @SuppressLint("InlinedApi")
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}

/** The WorkManager fallback of a reminder that no alarm could hold: shows it as the receiver would. */
class ViewingReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(ViewingReminderScheduler.EXTRA_VIEWING_ID) ?: return Result.success()
        val app = applicationContext as DoorprintsApp
        if (inputData.getBoolean(ViewingReminderScheduler.EXTRA_HUNT, false)) {
            ViewingReminderScheduler.showHunt(app, app.container.repository, id, System.currentTimeMillis())
        } else {
            ViewingReminderScheduler.show(app, app.container.repository, id, System.currentTimeMillis())
        }
        return Result.success()
    }
}
