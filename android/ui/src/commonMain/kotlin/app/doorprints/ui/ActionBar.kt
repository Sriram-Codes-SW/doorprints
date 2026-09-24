package app.doorprints.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.stringResource

/**
 * The sticky action bar of the Export and Import screens (Design review, rounds 4 and 5): the options scroll, the
 * bar does not. It holds **one status area** (a live count, a progress bar, or a result card) and **one row of
 * full-width, 48 dp buttons** with at most one filled primary action, always within thumb reach, so the primary
 * action is never below the fold — not even for a Tamil preview at 200% font.
 *
 * **Surface (Design review, 2026-09-22).** The bar is flat `surface` (#FFFFFF / #19211F) with no tonal elevation:
 * M3 tints an elevated `surface` with about 8% primary, and on that tint the result cards and count chips inside
 * the bar measured 1.00–1.04:1 and vanished. It is set off from the scrolling options by a shadow and a top
 * `outlineVariant` divider, because a shadow cannot be seen in the dark theme.
 *
 * **Motion.** The status area cross-fades between its kinds ([statusKey]: count → progress → result) over 150 ms
 * and the bar's height eases with it, instead of jumping under the finger. Compose scales both by the system
 * animator duration scale, so *Remove animations* makes them instant, as the web page's `--duration` is under
 * `prefers-reduced-motion`. [status] is called with the key it is drawing, so the outgoing content keeps drawing
 * its own kind while it fades out.
 *
 * **Height.** The status area is capped at 40% of the window height and scrolls inside that (WCAG 1.4.4, 1.4.10):
 * a Tamil result card with its actions at 200% font in landscape could otherwise push the bar over the whole
 * viewport and leave the options no room at all. The buttons row is outside the cap and always visible. The window
 * height is `LocalWindowInfo.containerSize`, not `Configuration.screenHeightDp` (lint
 * ConfigurationScreenWidthHeight: that one excludes the system bars differently across API levels and is not the
 * window in split screen).
 *
 * **Announcements (UX review, round 10).** The live region is the status **container**, the node that stays in the
 * tree, not the status texts. Every kind is drawn as new nodes inside [AnimatedContent], and Compose sends no
 * event for a node that *arrives* already marked as a live region: the only event is a subtree change whose source
 * is the nearest parent with semantics, and TalkBack only announces an event whose source is itself a live region.
 * With the region on the container, that source is the region, so each change of kind ("File checked", "Importing…",
 * the result, the refused file) is read, once. While a kind fades out its content is cleared from the semantics
 * tree, so the outgoing text is not read with it. [assertive] makes the region assertive (a refused file, a failed
 * run, an error message), so TalkBack interrupts; otherwise it is polite. The status composables therefore set no
 * live region of their own ([StatusLine], [ResultCard]), except a text that changes while its node stays in place
 * (the Export count), which keeps its own. Whether TalkBack says each change exactly once is README §8 check 1.
 *
 * **Buttons that do not fit side by side (Design review, rounds 10 and 18).** Two buttons side by side leave each
 * label about 112 dp on a 360 dp phone (138 dp on a 411 dp one). The buttons stack, one per row at full width (a
 * `FlowRow` with one item per row; the callers' `Modifier.weight(1f)` then fills the row), where each label has about
 * 280 dp and every Tamil and Telugu label fits in two lines. They stack from a font scale of 1.3, **or** whenever one
 * of the [labels] drawn now would not fit its half of the row: more than two lines ([ButtonLabel]'s limit, so it
 * would be ellipsised), or a single word wider than the label's room (it would break mid-word). The check measures
 * the real strings with `labelLarge` against the real width, because the overflow depends on the script and the
 * width, not on the font scale: at 100% font on ordinary phones, Tamil *Undo this import* ("இந்த இறக்குமதியைச்
 * செயல்தவிர்") needs three lines, and side by side it lost exactly its verb. The room per label is half the row less
 * the button's own padding (24 + 24 dp, or 16 + 24 dp and the 18 dp icon with its 8 dp gap for [iconLabel]). It is
 * worked out again only when the labels, the width or the text style change. Changing `maxItemsInEachRow` moves the
 * buttons but keeps their nodes, so TalkBack keeps its focus (round 16). Callers put the filled primary action last,
 * so stacked it is the bottom one, nearest the thumb.
 *
 * **Buttons keep their node (UX review, round 16).** Callers draw each button position with one [BarButton] call
 * site whose label, action and look follow the state, rather than a different button per state: see [BarButton].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <S> ActionBar(
    statusKey: S,
    status: @Composable ColumnScope.(S) -> Unit,
    labels: List<String>,
    assertive: Boolean = false,
    iconLabel: String? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val density = LocalDensity.current
    // Before the window is measured the height can be 0 (or a sentinel); no cap then rather than a zero-high status.
    val maxStatusHeight = if (windowHeightPx > 0) with(density) { (windowHeightPx * 0.4f).toDp() } else Dp.Infinity
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, shadowElevation = 3.dp) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier.fillMaxWidth()
                    .animateContentSize(tween(ANIMATION_MS))
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnimatedContent(
                    targetState = statusKey,
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = maxStatusHeight)
                        // The one live region of the bar, on the node that stays (see "Announcements" above).
                        .semantics {
                            liveRegion = if (assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite
                        }
                        .verticalScroll(rememberScrollState()),
                    transitionSpec = {
                        (fadeIn(tween(ANIMATION_MS)) togetherWith fadeOut(tween(ANIMATION_MS)))
                            .using(SizeTransform(clip = false) { _, _ -> tween(ANIMATION_MS) })
                    },
                    label = "ActionBar status",
                ) { key ->
                    // `key != statusKey`: this is the outgoing kind, still fading out; hidden from accessibility.
                    val leaving = if (key != statusKey) Modifier.clearAndSetSemantics { } else Modifier
                    Column(
                        Modifier.fillMaxWidth().then(leaving),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        status(key)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val measurer = rememberTextMeasurer()
                    val style = MaterialTheme.typography.labelLarge
                    val layoutDirection = LocalLayoutDirection.current
                    val half = (maxWidth - BUTTON_GAP) / 2
                    val tooLong = remember(labels, iconLabel, half, style, density, layoutDirection) {
                        labels.size > 1 && labels.any { label ->
                            val padding = if (label == iconLabel) {
                                ButtonDefaults.ButtonWithIconContentPadding.horizontal(layoutDirection) +
                                    ButtonDefaults.IconSize + ButtonDefaults.IconSpacing
                            } else {
                                ButtonDefaults.ContentPadding.horizontal(layoutDirection)
                            }
                            val room = with(density) { (half - padding).roundToPx() }.coerceAtLeast(1)
                            measurer.measure(label, style, constraints = Constraints(maxWidth = room)).lineCount >
                                BUTTON_LABEL_MAX_LINES ||
                                label.split(' ').any { word ->
                                    word.isNotEmpty() &&
                                        measurer.measure(word, style, softWrap = false, maxLines = 1).size.width > room
                                }
                        }
                    }
                    val stacked = density.fontScale >= STACK_FONT_SCALE || tooLong
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        maxItemsInEachRow = if (stacked) 1 else 2,
                    ) { actions() }
                }
            }
        }
    }
}

/** From this font scale the action bar's buttons stack, one per row, whatever their labels (see [ActionBar]). */
private const val STACK_FONT_SCALE = 1.3f

/** The gap between two buttons side by side. */
private val BUTTON_GAP = 8.dp

/** The left plus right padding of a button's content. */
private fun PaddingValues.horizontal(layoutDirection: LayoutDirection): Dp =
    calculateStartPadding(layoutDirection) + calculateEndPadding(layoutDirection)

/**
 * The status line. Not a live region itself: it is announced by the [ActionBar] status container it is drawn in,
 * which is the node TalkBack hears a change from (a live region on this new node would never fire).
 */
@Composable
fun StatusLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A running export or import, written once for both screens (Design review, 2026-09-22): the status line
 * ("Saving your copy…", announced once by the bar's live region), the counter ("12 of 80") and the bar.
 *
 *  - The numbers sit above the bar, as on the web page, in SemiBold with tabular figures (`tnum`), so the line
 *    does not jitter as the digits change every 400 ms. They are hidden from TalkBack, which gets them as the
 *    bar's state description, read when the bar is focused rather than announced on every update.
 *  - The track is `primaryContainer` (primary on it: 5.15:1 light, 6.63:1 dark). M3's default track is
 *    `secondaryContainer`, which was the amber star family (a teal bar on an amber track reads as "caution") and is
 *    the same `--primary-soft` since round 19 (Theme.kt); naming `primaryContainer` keeps it so.
 *  - With [total] 0 (not known yet) the bar is indeterminate and there are no numbers.
 */
@Composable
fun WorkProgress(done: Int, total: Int, text: String) {
    StatusLine(text)
    // "12 of 80", no noun (UX review, round 11): the status line above already says what is being done, and the web
    // uses the same counter after its own activity label ("Preparing photos 12 of 56…"); see README §9.
    val progressText = if (total > 0) stringResource(Res.string.export_progress_count, done, total) else null
    if (progressText != null) {
        Text(
            progressText,
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
    ProgressBar(
        progress = if (total > 0) ({ done.toFloat() / total }) else null,
        modifier = Modifier.fillMaxWidth().semantics {
            if (progressText != null) stateDescription = progressText
        },
    )
}

/**
 * A LinearProgressIndicator on the brand track (see [WorkProgress]); indeterminate when [progress] is null. Every
 * progress bar in the app goes through here, so the track colour is fixed in one place.
 */
@Composable
fun ProgressBar(modifier: Modifier = Modifier.fillMaxWidth(), progress: (() -> Float)? = null) {
    val track = MaterialTheme.colorScheme.primaryContainer
    if (progress != null) {
        LinearProgressIndicator(progress = progress, modifier = modifier, trackColor = track)
    } else {
        LinearProgressIndicator(modifier = modifier, trackColor = track)
    }
}

/** How a [BarButton] looks: the filled primary, or outlined (a secondary action, Stop, Cancel). */
enum class BarButtonStyle { FILLED, OUTLINED }

/**
 * One button position of the [ActionBar] (UX review, round 16). Its label, action and look change with the
 * screen's state — *Save to…* becomes *Stop*, *Import* becomes *Stop* and then *Finish import* or *See your houses* —
 * while it stays **one call site**, and so one layout and semantics node. Drawn as a different button per state
 * (`OutlinedButton` for Stop, `Button` for Save), every tap removed the node TalkBack had focus on, and TalkBack
 * dropped back to the top of the screen just when the user needed Stop. Now focus stays where it was, and the next
 * double-tap hits whatever the button has become; when a dialog opened from it closes, focus comes back to it.
 *
 * [BarButtonStyle.OUTLINED] is exactly what M3's `OutlinedButton` draws (it is itself a `Button` with the outlined
 * colours, the outlined border and no elevation). Each button takes an equal share of the row, or the whole row
 * when it stands alone or the bar stacks its buttons. [icon] is a leading glyph (the Share icon); a button with one
 * uses M3's icon padding (16 dp before the icon, 24 dp after the label; Design review, round 18), as `Button` does
 * with `ButtonWithIconContentPadding`, and the caller names its label as the bar's `iconLabel`. [modifier] goes
 * after the row weight and the 48 dp minimum height, for a `focusRequester` (Export moves focus to *Save to…* when
 * the control that had it goes away; Android review, round 17). The button itself is [StateButton], which the
 * Assistant uses outside a row (whole-app audit, round 6), so the rule lives in one place.
 */
@Composable
fun RowScope.BarButton(
    text: String,
    onClick: () -> Unit,
    style: BarButtonStyle,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    StateButton(
        text = text,
        onClick = onClick,
        style = style,
        enabled = enabled,
        icon = icon,
        modifier = Modifier.weight(1f).then(modifier),
    )
}

/**
 * A button whose label, action and look change with the screen's state while it stays **one call site** (UX review,
 * round 16; whole-app audit, round 6): the rule of [BarButton], outside a row. The Assistant's *Ask* / *Plan visits*
 * become *Cancel* while their request runs; drawn as an `OutlinedButton` for Cancel and a `Button` otherwise, the
 * node TalkBack had focus on was removed by the very tap that made Cancel appear, and focus jumped away. Now it
 * stays, and the next double-tap cancels. 48 dp tall at least; [modifier] goes before that minimum.
 */
@Composable
fun StateButton(
    text: String,
    onClick: () -> Unit,
    style: BarButtonStyle,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val outlined = style == BarButtonStyle.OUTLINED
    // All read every time, so switching the style changes parameters only, never the shape of this composition.
    val filledColors = ButtonDefaults.buttonColors()
    val outlinedColors = ButtonDefaults.outlinedButtonColors()
    val filledElevation = ButtonDefaults.buttonElevation()
    val outlinedBorder = ButtonDefaults.outlinedButtonBorder(enabled)
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        colors = if (outlined) outlinedColors else filledColors,
        elevation = if (outlined) null else filledElevation,
        border = if (outlined) outlinedBorder else null,
        contentPadding = if (icon != null) ButtonDefaults.ButtonWithIconContentPadding else ButtonDefaults.ContentPadding,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        }
        ButtonLabel(text)
    }
}

/**
 * The one irreversible choice in a confirmation dialog, in every such dialog (Design review, round 21): the Import
 * screen's *Replace*, the house list's *Remove copies*, the house form's *Delete* and *Discard*. An outlined button with
 * an `error` label and a 1 dp `error` edge, 48 dp tall, so "cannot be undone" has one look across the app; the safe
 * choice beside it (*Cancel*, *Keep them*, *Keep editing*) stays a plain `TextButton`. The label is a [ButtonLabel], so
 * it wraps rather than clips in Tamil and Telugu at 200 %. Disabled, the edge takes M3's disabled outline colour.
 */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error),
        border = BorderStroke(1.dp, if (enabled) scheme.error else scheme.onSurface.copy(alpha = 0.12f)),
        modifier = modifier.heightIn(min = 48.dp),
    ) { ButtonLabel(text) }
}
