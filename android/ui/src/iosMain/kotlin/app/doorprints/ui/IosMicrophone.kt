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

package app.doorprints.ui

import platform.AVFAudio.AVAudioApplication
import platform.AVFAudio.AVAudioApplicationRecordPermissionDenied
import platform.AVFAudio.AVAudioApplicationRecordPermissionGranted
import platform.AVFAudio.AVAudioApplicationRecordPermissionUndetermined
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.inputAvailable
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

// The iPhone's microphone permission for voice input (S4b-BL-223, docs/03 ADR-37). It is asked for in one place only,
// [requestMicrophone], which the microphone button reaches after the person's tap and a rationale (never at install
// or start-up: nothing here runs before that tap, and reading the status never asks). `NSMicrophoneUsageDescription`
// in Info.plist is the text iOS shows. The permission is the app's, from `AVAudioApplication` (iOS 17 and later; the
// app's floor is iOS 17.0).

/** What iOS says about the microphone for this app; anything it does not name counts as denied. */
internal enum class MicrophonePermission { Granted, Denied, Undetermined }

/** [raw] is `AVAudioApplication.recordPermission`; any value this build does not know is [MicrophonePermission.Denied], never allowed. */
internal fun microphonePermissionOf(raw: Long): MicrophonePermission = when (raw) {
    AVAudioApplicationRecordPermissionGranted -> MicrophonePermission.Granted
    AVAudioApplicationRecordPermissionUndetermined -> MicrophonePermission.Undetermined
    AVAudioApplicationRecordPermissionDenied -> MicrophonePermission.Denied
    else -> MicrophonePermission.Denied
}

/** The status now. Never shows the prompt. */
internal fun microphonePermission(): MicrophonePermission = microphonePermissionOf(AVAudioApplication.sharedInstance.recordPermission)

/** Whether this iPhone has a microphone input at all (a status read; it does not activate the audio session). */
internal fun microphoneInputAvailable(): Boolean = AVAudioSession.sharedInstance().inputAvailable

/**
 * Shows iOS's microphone prompt when the answer is still open and calls [onResult] on the main thread with whether the
 * microphone may be used. After a refusal iOS does not show it again, so [onResult] gets false at once and the screen
 * points to the Settings app.
 */
internal fun requestMicrophone(onResult: (Boolean) -> Unit) {
    when (microphonePermission()) {
        MicrophonePermission.Granted -> onResult(true)
        MicrophonePermission.Denied -> onResult(false)
        MicrophonePermission.Undetermined -> AVAudioApplication.requestRecordPermissionWithCompletionHandler { granted ->
            // The system calls back on an arbitrary queue.
            dispatch_async(dispatch_get_main_queue()) { onResult(granted) }
        }
    }
}
