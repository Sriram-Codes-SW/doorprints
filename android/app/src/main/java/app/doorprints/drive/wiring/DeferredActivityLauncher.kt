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

package app.doorprints.drive.wiring

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Starts a screen for a result on the foreground Activity and suspends until the answer comes back ([ActivityLauncher]).
 * One per process: the answer may arrive at a **new** Activity (a rotation while Google's consent screen is up), whose
 * result callback calls [deliver]. The Activity only supplies the two start calls ([Starter]) while it is in front.
 *
 * One screen at a time: asking for a second closes the first as cancelled. A screen that never answers (the Activity was
 * finished) is closed as cancelled by [cancelPending], so nothing waits for ever.
 */
class DeferredActivityLauncher(
    /** Starting a screen must happen on the main thread. */
    private val main: () -> CoroutineDispatcher = { Dispatchers.Main.immediate },
) : ActivityLauncher {

    /** What the foreground Activity does: `StartIntentSenderForResult` and `StartActivityForResult` launchers. */
    interface Starter {
        fun start(consent: PendingIntent)
        fun start(intent: Intent)
    }

    @Volatile
    private var starter: Starter? = null
    private var pending: CompletableDeferred<ActivityOutcome>? = null

    /** True while an Activity is in front and can start a screen for a result; false in the background. */
    val isAttached: Boolean get() = starter != null

    /** The foreground Activity's [starter], set when it comes to the front (see ActivityProvider.register). */
    fun attach(starter: Starter) {
        this.starter = starter
    }

    /** Only the Activity that attached can detach (a new one may already have taken over). */
    fun detach(starter: Starter) {
        if (this.starter === starter) this.starter = null
    }

    override suspend fun launch(consent: PendingIntent): ActivityOutcome = await { it.start(consent) }

    override suspend fun launch(intent: Intent): ActivityOutcome = await { it.start(intent) }

    /** The answer of the screen (from the Activity's result callback). An answer nobody waits for is dropped. */
    fun deliver(resultCode: Int, data: Intent?) {
        val waiting = synchronized(this) { pending.also { pending = null } }
        waiting?.complete(ActivityOutcome(resultCode, data))
    }

    /** Closes the screen being waited for as cancelled (its Activity is gone for good). */
    fun cancelPending() = deliver(Activity.RESULT_CANCELED, null)

    private suspend fun await(start: (Starter) -> Unit): ActivityOutcome {
        val answer = CompletableDeferred<ActivityOutcome>()
        val shown = starter ?: return ActivityOutcome(Activity.RESULT_CANCELED, null)
        synchronized(this) {
            pending?.complete(ActivityOutcome(Activity.RESULT_CANCELED, null))
            pending = answer
        }
        try {
            withContext(main()) { start(shown) }
        } catch (e: CancellationException) {
            forget(answer)
            throw e
        } catch (_: Exception) {
            // The system refused to start the screen (no such screen): nothing to wait for.
            forget(answer)
            return ActivityOutcome(Activity.RESULT_CANCELED, null)
        }
        return try {
            answer.await()
        } catch (e: CancellationException) {
            forget(answer)
            throw e
        }
    }

    private fun forget(answer: CompletableDeferred<ActivityOutcome>) = synchronized(this) {
        if (pending === answer) pending = null
    }
}
