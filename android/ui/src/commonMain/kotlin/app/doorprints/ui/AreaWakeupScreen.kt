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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.ui.res.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

// The area wake-up's screens (docs/11 "Design of slice 4b", 5.17, 5.18): *Wake me in my hunting areas* in Settings >
// My areas with its once-only "switched off because…" card, and the rationale screen that comes before it is turned
// on, with the permission steps. The geofences are `:app`'s (AreaGeofenceManager), behind [AreaWakeupServices].

/** Where the rationale's permission steps are: nothing asked, precise location asked, background location asked. */
enum class AreaWakeupStep { IDLE, FOREGROUND, BACKGROUND }

/** The rationale's rules, apart from the screen (5.18 *Area wake-up turned on*). */
object AreaWakeupFlow {
    /**
     * What *Continue* does: turn the wake-up on at once when everything is granted ([backgroundGranted]); ask for
     * background location when precise location is there ([precise]); otherwise ask for precise location first.
     */
    fun onContinue(precise: Boolean, backgroundGranted: Boolean): AreaWakeupStep? = when {
        backgroundGranted -> null
        precise -> AreaWakeupStep.BACKGROUND
        else -> AreaWakeupStep.FOREGROUND
    }

    /** The switch stays on after the person returns only with precise and background location both granted. */
    fun keepOn(precise: Boolean, backgroundGranted: Boolean): Boolean = precise && backgroundGranted
}

/**
 * *Wake me in my hunting areas* and its hint, and above it once the "Area wake-up is off because…" card when a lost
 * permission switched it off (shown once: the stored notice is cleared as it is shown). Turning it on opens the
 * rationale ([onTurnOn]); turning it off is at once. Nothing where the platform has no wake-up
 * ([PlatformFeatures.areaWakeup], [AreaWakeupServices.available]: no Google Play services).
 */
@Composable
fun AreaWakeupSection(onTurnOn: () -> Unit) {
    val features = LocalPlatformFeatures.current
    val services = LocalAppServices.current
    if (!features.areaWakeup || !services.areaWakeup.available) return
    val settings = services.repository.settings
    val scope = rememberCoroutineScope()
    val on by remember(settings) { settings.areaWakeup() }.collectAsStateWithLifecycle(false)
    val notice by remember(settings) { settings.areaWakeupOffNotice() }.collectAsStateWithLifecycle(false)
    var showCard by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(notice) {
        if (notice) {
            showCard = true
            settings.clearAreaWakeupOffNotice()
        }
    }
    LiveMessage {
        if (showCard) {
            WarnNote(
                stringResource(Res.string.area_wakeup_off_card),
                action = stringResource(Res.string.common_ok),
                onAction = { showCard = false },
                textStyle = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    SwitchRow(
        text = stringResource(Res.string.area_wakeup_switch),
        hint = stringResource(Res.string.area_wakeup_hint),
        checked = on,
        horizontalPadding = 0.dp,
        onChange = { want ->
            if (want) {
                onTurnOn()
            } else {
                scope.launch { withContext(NonCancellable) { settings.setAreaWakeup(false) } }
            }
        },
    )
}

/** The rationale before the wake-up is turned on (5.18), as its own screen with a back arrow. */
@Composable
fun AreaWakeupRationaleScreen(onDone: () -> Unit) {
    SubScreen(stringResource(Res.string.area_wakeup_switch), onDone) { AreaWakeupRationale(onDone) }
}

/**
 * The rationale's text (why, what Google Play services does and that Doorprints keeps no location history, the
 * battery, how to turn it off, which option to pick) and two buttons of equal weight, **Continue** and **Not now**.
 * *Continue* asks for precise location first when it is missing, then for background location
 * ([AreaWakeupServices.rememberBackgroundLocationRequest]); the answer is read when the dialog closes or when the
 * person comes back from the settings page (resume). Granted: the setting on and [onDone]; not: it stays off and a
 * line says why. *Not now* leaves it off.
 */
@Composable
fun AreaWakeupRationale(onDone: () -> Unit) {
    val services = LocalAppServices.current
    val wakeup = services.areaWakeup
    val platform = LocalPlatformServices.current
    val settings = services.repository.settings
    val scope = rememberCoroutineScope()
    // Saved by position, as a plain Int (the saved-state bundle on every platform).
    var stepIndex by rememberSaveable { mutableIntStateOf(AreaWakeupStep.IDLE.ordinal) }
    fun step() = AreaWakeupStep.entries[stepIndex]
    fun setStep(value: AreaWakeupStep) {
        stepIndex = value.ordinal
    }
    var stillOff by rememberSaveable { mutableStateOf(false) }
    // Set when the screen was left while background location was being asked (the settings page): the resume after it
    // reads the answer. A resume before that (the moment between the two prompts) reads nothing.
    var away by rememberSaveable { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    val ask = rememberLocationAsk()

    fun precise() = platform.locationAccess() == LocationAccess.PRECISE

    fun finish() {
        setStep(AreaWakeupStep.IDLE)
        away = false
        if (AreaWakeupFlow.keepOn(precise(), wakeup.backgroundGranted())) {
            if (finishing) return
            finishing = true
            stillOff = false
            scope.launch {
                withContext(NonCancellable) { settings.setAreaWakeup(true) }
                onDone()
            }
        } else {
            stillOff = true
        }
    }

    val background = wakeup.rememberBackgroundLocationRequest {
        if (step() == AreaWakeupStep.BACKGROUND) finish()
    }

    fun askBackground() {
        setStep(AreaWakeupStep.BACKGROUND)
        away = false
        background()
    }

    val foreground = wakeup.rememberForegroundLocationRequest {
        ask.refresh()
        if (step() != AreaWakeupStep.FOREGROUND) return@rememberForegroundLocationRequest
        if (precise()) askBackground() else finish()
    }

    LifecycleResumeEffect(wakeup) {
        if (step() == AreaWakeupStep.BACKGROUND && away) finish()
        onPauseOrDispose { if (step() == AreaWakeupStep.BACKGROUND) away = true }
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.area_wakeup_why), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(Res.string.area_wakeup_what), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(Res.string.area_wakeup_battery), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(Res.string.area_wakeup_how_off), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(Res.string.area_wakeup_pick), style = MaterialTheme.typography.bodyLarge)
        LiveMessage {
            if (stillOff) {
                WarnNote(
                    stringResource(Res.string.area_wakeup_still_off),
                    action = stringResource(Res.string.perm_open_settings),
                    onAction = { platform.openAppSettings() },
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        // Equal weight (5.18 *Design review*): the same kind of button, the same width.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { onDone() },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { ButtonLabel(stringResource(Res.string.area_wakeup_not_now)) }
            OutlinedButton(
                onClick = {
                    stillOff = false
                    when (AreaWakeupFlow.onContinue(precise(), wakeup.backgroundGranted())) {
                        null -> finish()
                        AreaWakeupStep.BACKGROUND -> askBackground()
                        else -> {
                            setStep(AreaWakeupStep.FOREGROUND)
                            ask.markAsked()
                            foreground()
                        }
                    }
                },
                enabled = step() == AreaWakeupStep.IDLE && !finishing,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { ButtonLabel(stringResource(Res.string.area_wakeup_continue)) }
        }
    }
}
