package com.househunt.app.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
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
internal fun LiveMessage(
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

/** True while TalkBack (or another touch-exploration service) is on. */
internal fun isTouchExploring(context: Context): Boolean =
    context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
