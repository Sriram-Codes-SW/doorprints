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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.stringResource

/**
 * The header of a screen while two or more kinds are on (kinds PR C, S4b-BL-274; docs/03 §18.12): a chip with the space
 * the person is in, which returns to the chooser, and, for a kind that is turned off but still opened from the chooser, one
 * line saying so (it is kept and shown, never hidden). The chip is a button named *<Houses>: change what you are looking
 * for* (a full name for TalkBack, 48 dp tall); there is no bottom-bar slot and no modal. With one kind on this is not drawn,
 * so every screen is as it always was. The twin of the website's header chip.
 */
@Composable
fun SpaceHeader(space: SpaceUi, onClick: () -> Unit) {
    val name = stringResource(space.words.many)
    val full = stringResource(Res.string.space_chip, name)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AssistChip(
                onClick = onClick,
                label = { Text(name) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = full },
            )
        }
        if (space.hidden) {
            Text(stringResource(Res.string.space_hidden_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
