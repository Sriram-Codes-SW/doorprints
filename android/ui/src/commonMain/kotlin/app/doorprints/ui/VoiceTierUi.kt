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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.shared.voice.TierSubject
import app.doorprints.shared.voice.VoiceChoice
import app.doorprints.shared.voice.VoiceTier
import app.doorprints.shared.voice.VoiceVerdict

/**
 * What the free-plan rule says about the key voice input would use, for the microphone and the setting (owner decision
 * 2026-10-19, ADR-37). [verdict] is [VoiceVerdict.CHECKING] until the rule has answered; [providerName] is the name used in the
 * words about the plan; [canConfirm] is true where the person may say the plan is paid; [subject] is what to confirm it for.
 */
internal class VoiceTierState(
    val verdict: VoiceVerdict,
    val providerName: String,
    val canConfirm: Boolean = false,
    val acknowledged: Boolean = false,
    val subject: TierSubject? = null,
) {
    val allowed: Boolean get() = verdict == VoiceVerdict.ALLOW
}

/**
 * Asks the rule (and, once, the provider, only for Gemini and OpenRouter) after the *Voice input* switch is on and the choice has a
 * key; read again whenever a kept entry changes. With no gate on the platform or no key the answer is "cannot tell", which refuses.
 * Nothing is recorded or sent from here.
 */
@Composable
internal fun rememberVoiceTier(choice: VoiceChoice, ownKey: Boolean): VoiceTierState {
    val services = LocalAppServices.current
    val gate = services.voice.tierGate
    val version by services.repository.settings.voiceTier.version.collectAsStateWithLifecycle(0)
    var state by remember { mutableStateOf(VoiceTierState(VoiceVerdict.CHECKING, "")) }
    LaunchedEffect(choice, ownKey, version, gate) {
        val subject = gate?.subjectFor(choice)
        state = if (gate == null || subject == null) {
            VoiceTierState(VoiceVerdict.REFUSE_UNKNOWN, "")
        } else {
            val verdict = gate.verdict(subject)
            VoiceTierState(verdict, VoiceTier.displayName(subject.provider), gate.canConfirmPaid(subject, verdict), gate.isAcknowledged(subject), subject)
        }
    }
    return state
}
