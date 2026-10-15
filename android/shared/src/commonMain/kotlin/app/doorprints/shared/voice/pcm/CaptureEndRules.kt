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

/**
 * When an iPhone recording must end because something other than the person changed under it (voice input, docs/03
 * ADR-37, T-I47). The rules are pure so they are tested without a phone; the iPhone's source feeds them the numbers
 * AVFoundation posts (`AVAudioSessionRouteChangeReasonKey`, the interruption type, the input's port UID), and an
 * `iosTest` holds the constants to the platform's own.
 *
 * Recording is foreground only and tap to talk: an interruption (a call, Siri, an alarm, another app taking the
 * microphone), a change of the microphone in use (headphones pulled out, a Bluetooth headset connecting) and the app
 * going out of front all end it, with nothing kept, rather than carry on with a microphone the person did not choose.
 */
object CaptureEndRules {
    // AVAudioSessionRouteChangeReason raw values (AVAudioSessionTypes.h).
    const val ROUTE_UNKNOWN = 0L
    const val ROUTE_NEW_DEVICE_AVAILABLE = 1L
    const val ROUTE_OLD_DEVICE_UNAVAILABLE = 2L
    const val ROUTE_CATEGORY_CHANGE = 3L
    const val ROUTE_OVERRIDE = 4L
    const val ROUTE_WAKE_FROM_SLEEP = 6L
    const val ROUTE_NO_SUITABLE_ROUTE_FOR_CATEGORY = 7L
    const val ROUTE_CONFIGURATION_CHANGE = 8L

    // AVAudioSessionInterruptionType raw values.
    const val INTERRUPTION_ENDED = 0L
    const val INTERRUPTION_BEGAN = 1L

    /** An interruption that begins ends the recording; one that ends does not restart it (a new tap does). */
    fun endsOnInterruption(type: Long): Boolean = type == INTERRUPTION_BEGAN

    /**
     * Whether a route change ends the recording. The category change and the override that setting up the recording
     * itself can cause, and a wake from sleep, do not, as long as the microphone in use is the one it started with
     * ([inputAtStart] and [inputNow] are the input ports' unique ids; null when there is none). Every other reason, a
     * different microphone, or no microphone, ends it.
     */
    fun endsOnRouteChange(reason: Long, inputAtStart: String?, inputNow: String?): Boolean {
        if (inputNow == null || inputNow != inputAtStart) return true
        return when (reason) {
            ROUTE_CATEGORY_CHANGE, ROUTE_OVERRIDE, ROUTE_WAKE_FROM_SLEEP -> false
            else -> true
        }
    }
}
