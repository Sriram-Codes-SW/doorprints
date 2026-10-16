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

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Where the tour's targets are on screen, by id ([TourTargets]): each screen tags a control with [tourTarget] and the tour
 * reads the box here, so the screens stay unaware of the tour. A target that is not composed (another tab, a row scrolled
 * out of a lazy list) has no entry; the tour then shows its card without a highlight.
 */
class TourRegistry {
    private val boxes = mutableStateMapOf<String, Rect>()
    private val scrollers = HashMap<String, suspend () -> Unit>()

    /** Records where [id] is now (window pixels); a write only when it moved, so a still screen causes no work. */
    fun report(id: String, box: Rect) {
        if (boxes[id] != box) boxes[id] = box
    }

    /** Registers how to scroll [id] into view (from the screen's own scroll container). */
    fun offer(id: String, scrollIntoView: suspend () -> Unit) {
        scrollers[id] = scrollIntoView
    }

    /** [id] has left the screen: its box and its scroller are dropped. */
    fun forget(id: String) {
        boxes.remove(id)
        scrollers.remove(id)
    }

    /** Where [id] is, or null while it is not on screen. Reads Compose state: a composable using it follows scrolling. */
    fun box(id: String): Rect? = boxes[id]

    /** The first of [ids] that is on screen: the one the step highlights. */
    fun firstOnScreen(ids: List<String>): String? = ids.firstOrNull { it in boxes }

    /** Scrolls [id] into view, where its screen can (a no-op for a target outside a scroll container). */
    suspend fun scrollIntoView(id: String) {
        scrollers[id]?.invoke()
    }
}

/** The tour's registry, provided by [DoorprintsRoot]; null in a screen drawn on its own (a test, a screenshot). */
val LocalTourRegistry = staticCompositionLocalOf<TourRegistry?> { null }

/**
 * Marks this control as the tour's target [id]. Costs one position callback; without a registry (a screen on its own) it is
 * the modifier unchanged.
 */
@Composable
fun Modifier.tourTarget(id: String): Modifier {
    val registry = LocalTourRegistry.current ?: return this
    val requester = remember { BringIntoViewRequester() }
    DisposableEffect(registry, id, requester) {
        registry.offer(id) { requester.bringIntoView() }
        onDispose { registry.forget(id) }
    }
    return this
        .bringIntoViewRequester(requester)
        .onGloballyPositioned { registry.report(id, it.boundsInWindow()) }
}
