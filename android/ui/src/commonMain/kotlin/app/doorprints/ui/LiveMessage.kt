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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A live region that is on screen before its message arrives (UX review, rounds 16 and 21; shared since the whole-app
 * audit, when the Assistant, the Map and Compare needed it too): a node that appears already marked as a live region
 * is never announced, and a node with no size is not in the accessibility tree at all, so the box is at least 1 dp
 * tall while [content] draws nothing. When content arrives, the change is reported on this box, and TalkBack reads it
 * (interrupting for [assertive]).
 */
@Composable
fun LiveMessage(
    modifier: Modifier = Modifier,
    assertive: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        modifier.fillMaxWidth().heightIn(min = 1.dp).semantics {
            liveRegion = if (assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite
        },
    ) { content() }
}
