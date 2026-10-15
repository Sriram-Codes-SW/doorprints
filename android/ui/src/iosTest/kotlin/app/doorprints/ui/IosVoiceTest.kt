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

import app.doorprints.shared.voice.pcm.CaptureEndRules
import platform.AVFAudio.AVAudioApplicationRecordPermissionDenied
import platform.AVFAudio.AVAudioApplicationRecordPermissionGranted
import platform.AVFAudio.AVAudioApplicationRecordPermissionUndetermined
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionRouteChangeReasonCategoryChange
import platform.AVFAudio.AVAudioSessionRouteChangeReasonNewDeviceAvailable
import platform.AVFAudio.AVAudioSessionRouteChangeReasonNoSuitableRouteForCategory
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOverride
import platform.AVFAudio.AVAudioSessionRouteChangeReasonRouteConfigurationChange
import platform.AVFAudio.AVAudioSessionRouteChangeReasonUnknown
import platform.AVFAudio.AVAudioSessionRouteChangeReasonWakeFromSleep
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The iPhone's voice input pieces that need Apple's own constants (S4b-BL-223, TC-U-256): the microphone permission
 * mapping, the raw values [CaptureEndRules] copies from AVFoundation's headers so that its logic can be tested on every
 * machine, and the source's behaviour before any microphone is opened. Runs on the simulator in CI (the macOS job); the
 * real microphone, the prompt and an interruption are MT-82..MT-87 on a phone.
 */
class IosVoiceTest {
    @Test
    fun everyRecordPermissionMapsToItsState() {
        assertEquals(MicrophonePermission.Granted, microphonePermissionOf(AVAudioApplicationRecordPermissionGranted))
        assertEquals(MicrophonePermission.Denied, microphonePermissionOf(AVAudioApplicationRecordPermissionDenied))
        assertEquals(MicrophonePermission.Undetermined, microphonePermissionOf(AVAudioApplicationRecordPermissionUndetermined))
    }

    @Test
    fun anUnknownPermissionIsTreatedAsDeniedNeverAsGranted() {
        assertEquals(MicrophonePermission.Denied, microphonePermissionOf(0))
        assertEquals(MicrophonePermission.Denied, microphonePermissionOf(99))
    }

    @Test
    fun theRouteChangeReasonsTheRulesNameAreAppleOwnValues() {
        assertEquals(AVAudioSessionRouteChangeReasonUnknown.toLong(), CaptureEndRules.ROUTE_UNKNOWN)
        assertEquals(AVAudioSessionRouteChangeReasonNewDeviceAvailable.toLong(), CaptureEndRules.ROUTE_NEW_DEVICE_AVAILABLE)
        assertEquals(AVAudioSessionRouteChangeReasonOldDeviceUnavailable.toLong(), CaptureEndRules.ROUTE_OLD_DEVICE_UNAVAILABLE)
        assertEquals(AVAudioSessionRouteChangeReasonCategoryChange.toLong(), CaptureEndRules.ROUTE_CATEGORY_CHANGE)
        assertEquals(AVAudioSessionRouteChangeReasonOverride.toLong(), CaptureEndRules.ROUTE_OVERRIDE)
        assertEquals(AVAudioSessionRouteChangeReasonWakeFromSleep.toLong(), CaptureEndRules.ROUTE_WAKE_FROM_SLEEP)
        assertEquals(AVAudioSessionRouteChangeReasonNoSuitableRouteForCategory.toLong(), CaptureEndRules.ROUTE_NO_SUITABLE_ROUTE_FOR_CATEGORY)
        assertEquals(AVAudioSessionRouteChangeReasonRouteConfigurationChange.toLong(), CaptureEndRules.ROUTE_CONFIGURATION_CHANGE)
    }

    @Test
    fun theInterruptionTypesTheRulesNameAreAppleOwnValues() {
        assertEquals(AVAudioSessionInterruptionTypeBegan.toLong(), CaptureEndRules.INTERRUPTION_BEGAN)
        assertEquals(AVAudioSessionInterruptionTypeEnded.toLong(), CaptureEndRules.INTERRUPTION_ENDED)
    }

    @Test
    fun aSourceThatWasNeverOpenedCanBeClosedTwiceAndThenReadsAsGone() {
        val source = IosPcmSource()
        source.close()
        source.close()
        assertEquals(-1, source.read(ByteArray(8), 0, 8))
    }
}
