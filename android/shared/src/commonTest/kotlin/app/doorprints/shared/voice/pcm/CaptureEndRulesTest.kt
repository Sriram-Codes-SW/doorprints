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

package app.doorprints.shared.voice.pcm

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When an interruption or a changed microphone ends an iPhone recording (S4b-BL-223, TC-U-255; T-I47). */
class CaptureEndRulesTest {
    private val mic = "BuiltInMicrophoneInput"

    @Test
    fun anInterruptionThatBeginsEndsTheRecordingAndOneThatEndsDoesNotRestartIt() {
        assertTrue(CaptureEndRules.endsOnInterruption(CaptureEndRules.INTERRUPTION_BEGAN))
        assertFalse(CaptureEndRules.endsOnInterruption(CaptureEndRules.INTERRUPTION_ENDED))
    }

    @Test
    fun aChangeOfMicrophoneEndsItWhateverTheReason() {
        for (reason in listOf(0L, 1L, 2L, 3L, 4L, 6L, 7L, 8L, 99L)) {
            assertTrue(CaptureEndRules.endsOnRouteChange(reason, mic, "BluetoothHFP"), "reason $reason")
        }
    }

    @Test
    fun noMicrophoneLeftEndsItWhateverTheReason() {
        for (reason in listOf(0L, 3L, 4L, 6L)) assertTrue(CaptureEndRules.endsOnRouteChange(reason, mic, null), "reason $reason")
        assertTrue(CaptureEndRules.endsOnRouteChange(CaptureEndRules.ROUTE_CATEGORY_CHANGE, null, null))
    }

    @Test
    fun theNoticesOurOwnSetUpCausesDoNotEndItWhileTheMicrophoneIsTheSame() {
        assertFalse(CaptureEndRules.endsOnRouteChange(CaptureEndRules.ROUTE_CATEGORY_CHANGE, mic, mic))
        assertFalse(CaptureEndRules.endsOnRouteChange(CaptureEndRules.ROUTE_OVERRIDE, mic, mic))
        assertFalse(CaptureEndRules.endsOnRouteChange(CaptureEndRules.ROUTE_WAKE_FROM_SLEEP, mic, mic))
    }

    @Test
    fun everyOtherReasonEndsItEvenWithTheSameMicrophone() {
        for (reason in listOf(
            CaptureEndRules.ROUTE_UNKNOWN, CaptureEndRules.ROUTE_NEW_DEVICE_AVAILABLE, CaptureEndRules.ROUTE_OLD_DEVICE_UNAVAILABLE,
            CaptureEndRules.ROUTE_NO_SUITABLE_ROUTE_FOR_CATEGORY, CaptureEndRules.ROUTE_CONFIGURATION_CHANGE, 42L,
        )) {
            assertTrue(CaptureEndRules.endsOnRouteChange(reason, mic, mic), "reason $reason")
        }
    }
}
