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

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/** The web's `--duration`: how long options and the action bar take to appear, disappear or resize. */
const val ANIMATION_MS = 150

/** A button label that wraps to two centred lines instead of being clipped (Tamil and Telugu at 200% font). */
@Composable
fun ButtonLabel(text: String) {
    Text(text, maxLines = BUTTON_LABEL_MAX_LINES, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
}

/** How many lines a [ButtonLabel] wraps to before it is ellipsised; the [ActionBar] stacks its buttons beyond it. */
const val BUTTON_LABEL_MAX_LINES = 2
