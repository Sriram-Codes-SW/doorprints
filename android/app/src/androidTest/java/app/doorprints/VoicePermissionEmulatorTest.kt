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
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.doorprints.shared.voice.PcmVoiceRecorder
import app.doorprints.shared.voice.VoiceFailure
import app.doorprints.shared.voice.VoiceRecorderException
import app.doorprints.voice.AudioRecordPcmSource
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Voice input's microphone permission on a real Android (S4b-BL-222, docs/03 ADR-37, TC-I-52): the app starts and the
 * microphone permission is neither granted nor asked (`RECORD_AUDIO` is a runtime permission requested only at the person's
 * tap, never at install or start-up); without the grant the recorder fails as `DENIED` and no microphone is opened; with the
 * grant (given from the shell, which does not kill the app) the recorder either records or fails cleanly and lets the
 * microphone go. One test, in this order, because a runtime grant cannot be taken back without ending the process. The
 * emulator may have no audio input: then the grant leg accepts a clean `NO_DEVICE` or `FAILED`, never a crash and never
 * `DENIED`. The rationale and the permission dialog themselves (UI) are MT-127 by hand.
 */
@RunWith(AndroidJUnit4::class)
class VoicePermissionEmulatorTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun theMicrophoneIsNotAskedAtStartUpAndADeniedRecorderFailsCleanly() {
        // 1. The app starts; the microphone is still not granted and the person was never asked.
        ActivityScenario.launch(MainActivity::class.java).use {
            instrumentation.waitForIdleSync()
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        }
        val line = shell("dumpsys package ${context.packageName}").lines().firstOrNull { it.contains("android.permission.RECORD_AUDIO") && it.contains("granted=") }
        assumeTrue("this Android does not print the permission's state in dumpsys package", line != null)
        assertTrue("RECORD_AUDIO is not granted at start-up: $line", line!!.contains("granted=false"))
        assertTrue("the person was not asked: $line", !line.contains("USER_SET") && !line.contains("USER_FIXED"))

        // 2. Without the grant the recorder fails as DENIED, before any microphone is opened.
        val denied = runCatching { AudioRecordPcmSource(context).open() }.exceptionOrNull()
        assertTrue("expected DENIED, got $denied", denied is VoiceRecorderException && denied.reason == VoiceFailure.DENIED)

        // 3. With the grant it records or fails cleanly (an emulator may have no audio input), and lets the microphone go.
        shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val recorder = PcmVoiceRecorder(AudioRecordPcmSource(context), Dispatchers.IO)
        try {
            recorder.start()
            Thread.sleep(600)
            val clip = runCatching { kotlinx.coroutines.runBlocking { recorder.stop() } }.getOrNull()
            if (clip != null) {
                assertEquals("audio/wav", clip.mime)
                assertEquals("RIFF", clip.bytes.decodeToString(0, 4))
            }
        } catch (e: VoiceRecorderException) {
            assertNotEquals("a granted microphone is not DENIED", VoiceFailure.DENIED, e.reason)
        } finally {
            recorder.release()
        }
    }

    /** A shell command as the shell user (UiAutomation), its output. */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().decodeToString().trim() }
}
