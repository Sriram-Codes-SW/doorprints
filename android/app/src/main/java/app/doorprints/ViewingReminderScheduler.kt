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

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.doorprints.data.Repository
import app.doorprints.i18n.AppLocale
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
 * Schedules the viewing reminders (docs/11 5.8, slice 3b-2; the alarm rules of 5.16 *Scheduling*). Everything comes
 * from the stored viewings each time ([rescheduleAll]): cancel the alarm of every viewing id here, tombstones included,
 * then set the upcoming 60 ([ViewingReminders.upcoming]) while *Remind me about viewings* is on. One immutable
 * [PendingIntent] per viewing to the non-exported [ViewingReminderReceiver]; the request code is the id's hash and the
 * intent's data is the id, so two ids with the same hash still get two alarms.
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

    /** True when an exact alarm may be set: below Android 12 always, from 12 with *Alarms & reminders* allowed. */
    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    /**
     * Cancels every reminder and sets the upcoming ones; returns what was set, by viewing id, earliest first. One run at
     * a time (a boot broadcast and the start-up collector can overlap).
     */
    suspend fun rescheduleAll(): List<Pair<String, How>> = lock.withLock {
        for (id in repository.viewingIdsForReminders()) cancel(id)
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
        if (!repository.settings.viewingsRemind().first()) return@withLock emptyList()
        val t = now()
        ViewingReminders.upcoming(repository.viewings(), t).map { (v, at) -> v.id to schedule(v.id, at, t) }
    }

    private fun schedule(id: String, at: Long, nowMs: Long): How {
        val operation = operation(context, id, PendingIntent.FLAG_UPDATE_CURRENT)!!
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
            .setInputData(workDataOf(EXTRA_VIEWING_ID to id))
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueue(work)
        return How.WORK
    }

    private fun cancel(id: String) {
        val operation = operation(context, id, PendingIntent.FLAG_NO_CREATE) ?: return
        alarms.cancel(operation)
        operation.cancel()
    }

    companion object {
        const val ACTION_REMIND = "app.doorprints.action.VIEWING_REMINDER"
        const val EXTRA_VIEWING_ID = "viewingId"
        const val WORK_TAG = "viewing-reminder"

        /** Viewing [id]'s alarm operation; with `FLAG_NO_CREATE` null when none is set. Always immutable (T-E8). */
        internal fun operation(context: Context, id: String, flags: Int): PendingIntent? {
            val intent = Intent(context, ViewingReminderReceiver::class.java)
                .setAction(ACTION_REMIND)
                .setData(Uri.fromParts("doorprints-viewing", id, null))
                .putExtra(EXTRA_VIEWING_ID, id)
            return PendingIntent.getBroadcast(context, id.hashCode(), intent, flags or PendingIntent.FLAG_IMMUTABLE)
        }

        /**
         * Shows viewing [id]'s reminder at [nowMs] when it is still due as stored now ([ViewingReminders.showNow]) and
         * the setting is on; an alarm left behind by an edit, a cancel, a delete or "remove all data" shows nothing.
         * Returns whether it was posted.
         */
        suspend fun show(context: Context, repository: Repository, id: String, nowMs: Long): Boolean {
            if (!repository.settings.viewingsRemind().first()) return false
            val viewing = repository.getViewing(id) ?: return false
            if (!ViewingReminders.showNow(viewing, nowMs)) return false
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

/** The reminders' own short-lived scope for a receiver's `goAsync` work (the app's scope is the process's). */
private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch {
        try {
            block()
        } catch (_: Exception) {
            // Nothing to tell anyone from a broadcast; the next start reschedules.
        } finally {
            pending.finish()
        }
    }
}

/**
 * A viewing reminder's alarm went off: shows it ([ViewingReminderScheduler.show]). Not exported; only this app's own
 * immutable PendingIntent reaches it.
 */
class ViewingReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ViewingReminderScheduler.ACTION_REMIND) return
        val id = intent.getStringExtra(ViewingReminderScheduler.EXTRA_VIEWING_ID) ?: return
        val app = context.applicationContext as DoorprintsApp
        runAsync { ViewingReminderScheduler.show(app, app.container.repository, id, System.currentTimeMillis()) }
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
        ViewingReminderScheduler.show(app, app.container.repository, id, System.currentTimeMillis())
        return Result.success()
    }
}
