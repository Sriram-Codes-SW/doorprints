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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one launcher of the process: a screen for a result, waited for, answered by whichever Activity is in front. */
class DeferredActivityLauncherTest {
    private class FakeStarter : DeferredActivityLauncher.Starter {
        val started = mutableListOf<Intent>()
        var failWith: Exception? = null
        override fun start(consent: PendingIntent) {
            failWith?.let { throw it }
        }
        override fun start(intent: Intent) {
            failWith?.let { throw it }
            started += intent
        }
    }

    private fun launcher() = DeferredActivityLauncher { Dispatchers.Unconfined }

    @Test
    fun withoutAnActivityTheScreenIsClosedAsCancelled() = runBlocking {
        val l = launcher()
        assertFalse(l.isAttached)
        assertEquals(Activity.RESULT_CANCELED, l.launch(Intent()).resultCode)
    }

    @Test
    fun theAnswerOfTheScreenIsWhatTheCallerGets() = runBlocking {
        val l = launcher()
        val starter = FakeStarter().also(l::attach)
        val result = async(Dispatchers.Default) { l.launch(Intent("x")) }
        while (starter.started.isEmpty()) kotlinx.coroutines.yield()
        val data = Intent("answer")
        l.deliver(Activity.RESULT_OK, data)
        val out = result.await()
        assertEquals(Activity.RESULT_OK, out.resultCode)
        assertSame(data, out.data)
    }

    @Test
    fun anAnswerNobodyWaitsForIsDropped() {
        val l = launcher()
        l.deliver(Activity.RESULT_OK, null)
        // Nothing to assert beyond not throwing; a later screen must not see it.
        runBlocking {
            val starter = FakeStarter().also(l::attach)
            val r = async(Dispatchers.Default) { l.launch(Intent()) }
            while (starter.started.isEmpty()) kotlinx.coroutines.yield()
            l.deliver(Activity.RESULT_CANCELED, null)
            assertEquals(Activity.RESULT_CANCELED, r.await().resultCode)
        }
    }

    @Test(timeout = 10_000)
    fun aSecondScreenClosesTheFirstAsCancelled() = runBlocking {
        val l = launcher()
        val starter = FakeStarter().also(l::attach)
        val first = async(Dispatchers.Default) { l.launch(Intent("1")) }
        while (starter.started.size < 1) kotlinx.coroutines.yield()
        val second = async(Dispatchers.Default) { l.launch(Intent("2")) }
        while (starter.started.size < 2) kotlinx.coroutines.yield()
        assertEquals(Activity.RESULT_CANCELED, first.await().resultCode)
        l.deliver(Activity.RESULT_OK, null)
        assertEquals(Activity.RESULT_OK, second.await().resultCode)
    }

    @Test
    fun aFinishedActivityClosesTheWaitingScreenAsCancelled() = runBlocking {
        val l = launcher()
        val starter = FakeStarter().also(l::attach)
        val r = async(Dispatchers.Default) { l.launch(Intent()) }
        while (starter.started.isEmpty()) kotlinx.coroutines.yield()
        l.cancelPending()
        assertEquals(Activity.RESULT_CANCELED, r.await().resultCode)
    }

    @Test
    fun aScreenTheSystemCannotStartIsCancelledNotHanging() = runBlocking {
        val l = launcher()
        l.attach(FakeStarter().apply { failWith = IllegalStateException("no such screen") })
        assertEquals(Activity.RESULT_CANCELED, l.launch(Intent()).resultCode)
    }

    @Test
    fun onlyTheActivityThatAttachedCanDetach() {
        val l = launcher()
        val first = FakeStarter()
        val second = FakeStarter()
        l.attach(first)
        l.attach(second)
        l.detach(first)
        assertTrue("a new Activity took over; the old one's detach changes nothing", l.isAttached)
        l.detach(second)
        assertFalse(l.isAttached)
    }

    @Test
    fun aCancelledCallerLeavesNothingPending() = runBlocking {
        val l = launcher()
        val starter = FakeStarter().also(l::attach)
        val gate = CompletableDeferred<Unit>()
        val r = async(Dispatchers.Default) { gate.complete(Unit); l.launch(Intent()) }
        gate.await()
        while (starter.started.isEmpty()) kotlinx.coroutines.yield()
        r.cancel()
        // A late answer after the caller left is dropped, and a new screen works as usual.
        l.deliver(Activity.RESULT_OK, null)
        val next = async(Dispatchers.Default) { l.launch(Intent()) }
        while (starter.started.size < 2) kotlinx.coroutines.yield()
        l.deliver(Activity.RESULT_OK, null)
        assertEquals(Activity.RESULT_OK, next.await().resultCode)
    }
}
