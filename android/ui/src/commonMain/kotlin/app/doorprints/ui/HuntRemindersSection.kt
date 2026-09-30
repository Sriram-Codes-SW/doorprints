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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.shared.model.HuntReminders
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.settings_hunt_remind
import app.doorprints.ui.res.settings_hunt_remind_hint
import app.doorprints.ui.res.settings_hunt_remind_lead
import app.doorprints.ui.res.settings_hunt_remind_min
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Settings > Hunt mode's reminder (docs/11 5.16, slice 3c): *Offer Hunt mode before viewings*, this device's own
 * setting (on unless turned off), and while it is on the lead time, one of [HuntReminders.LEAD_CHOICES] (15 unless
 * changed). Each viewing still has its own switch on the viewing form. The on-time alarms note lives with the viewing
 * reminders ([ViewingRemindersSection]), which share the alarms.
 */
@Composable
fun HuntRemindersSection() {
    val settings = LocalAppServices.current.repository.settings
    val scope = rememberCoroutineScope()
    val on by remember(settings) { settings.huntRemind() }.collectAsStateWithLifecycle(initialValue = true)
    val lead by remember(settings) { settings.huntReminderMin() }.collectAsStateWithLifecycle(initialValue = HuntReminders.DEFAULT_LEAD)
    SwitchRow(
        text = stringResource(Res.string.settings_hunt_remind),
        hint = stringResource(Res.string.settings_hunt_remind_hint),
        checked = on,
        horizontalPadding = 0.dp,
        onChange = { value -> scope.launch { settings.setHuntRemind(value) } },
    )
    if (on) {
        Text(stringResource(Res.string.settings_hunt_remind_lead), style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.selectableGroup()) {
            HuntReminders.LEAD_CHOICES.forEach { minutes ->
                val selected = lead == minutes
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = {
                            if (!selected) scope.launch { settings.setHuntReminderMin(minutes) }
                        }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(stringResource(Res.string.settings_hunt_remind_min, minutes), modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}
