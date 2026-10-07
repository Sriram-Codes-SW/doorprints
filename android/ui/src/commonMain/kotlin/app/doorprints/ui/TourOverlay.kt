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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.doorprints.ui.res.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

private val SPOT_PAD = 6.dp
private val EDGE_GAP = 12.dp
private val CLEAR = 8.dp
private val MIN_CARD = 160.dp
private val CARD_MAX_WIDTH = 480.dp
private val GUTTER = 16.dp
private val RING = 3.dp
private val SPOT_RADIUS = 8.dp
private val SCRIM = Color.Black.copy(alpha = 0.6f)

/** How long a step waits for its target to appear (a screen still loading) before it shows without a highlight. */
private const val WAIT_MS = 2000L
private const val POLL_MS = 100L

/**
 * The first-run offer and the guided tour itself (S4b-FR-39), drawn over the whole app. The tour is not modal, as the
 * website's: nothing here takes the pointer except the card, so the person can use the highlighted control on the real
 * screen, as the step says, and then tap Next. The highlight is a dimmed scrim with a hole over the target's box
 * ([TourRegistry]); the card is a pane that takes focus on its title, sits on the side of the highlight with more room and
 * never over it ([TourLayout]), and scrolls its text with the buttons kept in view at a large font. Back skips.
 * A step whose target is not on screen shows as a plain card in the middle.
 *
 * [currentRoute] is the tab the app is on: a step is highlighted only while the app is on its tab. Drawn only while there
 * is something to show.
 */
@Composable
fun TourOverlay(
    session: TourSession,
    registry: TourRegistry,
    offer: Boolean,
    currentRoute: String?,
    onStart: () -> Unit,
    onDecline: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    val step = session.step
    val onRoute = step != null && step.route == currentRoute
    // The system Back leaves the tour while its card is on the screen it was made for; anywhere else (a house form the
    // person opened to try a step) Back keeps its own meaning.
    PlatformBackHandler(enabled = onRoute, onBack = onSkip)
    if (step == null && !offer) return

    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier.fillMaxSize().onGloballyPositioned {
            origin = it.positionInWindow()
            size = it.size
        },
    ) {
        if (size == IntSize.Zero) return@Box
        val gap = with(density) { EDGE_GAP.toPx() }
        val clear = with(density) { CLEAR.toPx() }
        val pad = with(density) { SPOT_PAD.toPx() }
        val minCard = with(density) { MIN_CARD.toPx() }
        val screen = Rect(0f, 0f, size.width.toFloat(), size.height.toFloat())
        // The card's room: inside the system bars and, when the app's own bar is on screen, above it.
        val insets = WindowInsets.safeDrawing
        val barTop = registry.box(TourTargets.NAV_BAR)?.let { it.top - origin.y }
        val area = Rect(
            0f,
            insets.getTop(density).toFloat(),
            screen.width,
            minOf(screen.height - insets.getBottom(density), barTop ?: screen.height),
        )

        if (step != null) {
            val targetId = if (onRoute) registry.firstOnScreen(step.targets) else null
            val target = targetId?.let { registry.box(it) }?.translate(-origin)
            val spot = TourLayout.spot(target, screen, pad, gap, minCard)
            Scrim(spot, with(density) { SPOT_RADIUS.toPx() }, with(density) { RING.toPx() })
            // Wait for the screen the step is on, scroll the target into view, and the box then follows the scrolling.
            LaunchedEffect(step.id, onRoute) {
                if (!onRoute || step.targets.isEmpty()) return@LaunchedEffect
                var waited = 0L
                while (waited < WAIT_MS && registry.firstOnScreen(step.targets) == null) {
                    delay(POLL_MS)
                    waited += POLL_MS
                }
                registry.firstOnScreen(step.targets)?.let { registry.scrollIntoView(it) }
            }
            PlacedCard(area, spot, bottom = false, gap, clear) { probe ->
                StepCard(step, session.position, session.size, session.isFirst, session.isLast, probe, onNext, onBack, onSkip)
            }
        } else {
            PlacedCard(area, null, bottom = true, gap, clear) { probe -> OfferCard(probe, onStart, onDecline) }
        }
    }
}

/** The dimmed screen with a rounded hole over [spot] and a white ring round it; draws only, so it takes no touch. */
@Composable
private fun Scrim(spot: Rect?, radius: Float, ring: Float) {
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        val shape = spot?.let { RoundRect(it, CornerRadius(radius)) }
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            if (shape != null) addRoundRect(shape)
        }
        drawPath(path, SCRIM)
        if (spot != null) {
            drawRoundRect(Color.White, spot.topLeft, spot.size, CornerRadius(radius), style = Stroke(ring))
        }
    }
}

/**
 * Puts one card in [area]: measured once at its natural height to learn how much room it needs, placed by
 * [TourLayout.place], then measured again within the room it has, so a tall card scrolls instead of covering the highlight.
 * [content] is told when it is the measuring copy, which has no scrolling, no focus and no semantics.
 */
@Composable
private fun PlacedCard(area: Rect, spot: Rect?, bottom: Boolean, gap: Float, clear: Float, content: @Composable (probe: Boolean) -> Unit) {
    val gutter = with(LocalDensity.current) { GUTTER.roundToPx() }
    val widest = with(LocalDensity.current) { CARD_MAX_WIDTH.roundToPx() }
    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        val width = minOf(constraints.maxWidth - 2 * gutter, widest).coerceAtLeast(0)
        val natural = subcompose("probe") { content(true) }
            .first().measure(Constraints(minWidth = width, maxWidth = width))
        val placement = TourLayout.place(area, spot, natural.height.toFloat(), gap, clear, bottom)
        val card = subcompose("card") { content(false) }
            .first().measure(Constraints(minWidth = width, maxWidth = width, maxHeight = placement.maxHeight.toInt().coerceAtLeast(0)))
        layout(constraints.maxWidth, constraints.maxHeight) {
            card.place((constraints.maxWidth - width) / 2, placement.top(area, card.height.toFloat(), gap).toInt())
        }
    }
}

@Composable
private fun CardSurface(title: String, probe: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        // A pane that is read first, whatever is behind it (it is the thing to act on); the copy used for measuring is not
        // part of what the screen reader sees.
        modifier = Modifier.fillMaxWidth().then(
            if (probe) Modifier.clearAndSetSemantics { }
            else Modifier.semantics {
                paneTitle = title
                isTraversalGroup = true
                traversalIndex = -1f
            },
        ),
    ) { Column(Modifier.padding(16.dp)) { content() } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepCard(
    step: TourStep,
    position: Int,
    size: Int,
    isFirst: Boolean,
    isLast: Boolean,
    probe: Boolean,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    val title = stringResource(step.title)
    val focus = remember { FocusRequester() }
    // Focus goes to the title when a step appears, so a screen reader starts at what the step is about.
    if (!probe) {
        LaunchedEffect(step.id) {
            delay(50)
            runCatching { focus.requestFocus() }
        }
    }
    CardSurface(title, probe) {
        Text(
            stringResource(Res.string.tour_count, position + 1, size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp)
                .semantics { heading() }
                .then(if (probe) Modifier else Modifier.focusRequester(focus).focusable()),
        )
        Column(if (probe) Modifier else Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Text(stringResource(step.body), style = MaterialTheme.typography.bodyMedium)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(Res.string.tour_try_it)) }
                    append(" ")
                    append(stringResource(step.action))
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) { ButtonLabel(stringResource(Res.string.tour_skip)) }
            if (!isFirst) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { ButtonLabel(stringResource(Res.string.tour_back)) }
            }
            Button(onClick = onNext, modifier = Modifier.heightIn(min = 48.dp)) {
                ButtonLabel(stringResource(if (isLast) Res.string.tour_finish else Res.string.tour_next))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfferCard(probe: Boolean, onStart: () -> Unit, onDecline: () -> Unit) {
    val title = stringResource(Res.string.tour_offer_title)
    CardSurface(title, probe) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
        Text(stringResource(Res.string.tour_offer_body), style = MaterialTheme.typography.bodyMedium)
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDecline, modifier = Modifier.heightIn(min = 48.dp)) { ButtonLabel(stringResource(Res.string.tour_offer_skip)) }
            Button(onClick = onStart, modifier = Modifier.heightIn(min = 48.dp)) { ButtonLabel(stringResource(Res.string.tour_offer_start)) }
        }
    }
}
