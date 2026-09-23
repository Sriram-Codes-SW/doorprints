package com.househunt.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The option rows of the Export and Import screens (and Settings' switches), written once (Design review, round 5).
 *
 * Every row is one whole-row target of at least 48 dp; the radio and the switch carry `onClick = null`, so TalkBack
 * reads the row once, with its role and state. The rows are meant to run **edge to edge**: the screen's column has
 * no horizontal padding, and each row pads its own content by [horizontalPadding] (16 dp), so the selection ripple
 * reaches both screen edges as M3 list items do. A screen whose column is already padded passes 0.dp.
 *
 * A disabled row (options locked while a copy is being made) draws its text at the M3 disabled alpha, not only
 * its control, so a locked row does not look live.
 */

/** M3's disabled content colour (onSurface at 38 %), or [base] while the row is enabled. */
@Composable
private fun rowColor(enabled: Boolean, base: Color): Color =
    if (enabled) base else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

/**
 * The label and its optional one-line help, coloured for the enabled state. [spokenLabel], when set, is what TalkBack
 * reads instead of [label] (a decorative glyph in the label, such as the status's ● ★ ✕, is then not read out).
 */
@Composable
private fun RowTexts(
    label: AnnotatedString,
    hint: String?,
    enabled: Boolean,
    hintColor: Color,
    modifier: Modifier,
    spokenLabel: String? = null,
) {
    Column(modifier) {
        Text(
            label,
            color = rowColor(enabled, LocalContentColor.current),
            modifier = if (spokenLabel != null) Modifier.clearAndSetSemantics { contentDescription = spokenLabel } else Modifier,
        )
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = rowColor(enabled, hintColor))
        }
    }
}

/**
 * A whole-row radio choice. Put a group of them in a `Modifier.selectableGroup()` column. [spokenLabel] replaces the
 * label for TalkBack (see [RowTexts]); the house form's Status rows use it to keep their glyph out of speech.
 */
@Composable
fun RadioRow(
    label: AnnotatedString,
    hint: String?,
    selected: Boolean,
    enabled: Boolean = true,
    horizontalPadding: Dp = 16.dp,
    spokenLabel: String? = null,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = horizontalPadding, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        RowTexts(
            label, hint, enabled,
            hintColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
            spokenLabel = spokenLabel,
        )
    }
}

/**
 * A radio choice drawn as a selectable card, for the one choice that decides what the rest of a screen means (the
 * export format). The same look as the web page's format cards: a 1 dp `outline` border, and for the chosen card a
 * 2 dp `primary` border on `primaryContainer` (web `--primary` on `--primary-soft`), with the web's 10 dp radius.
 * The whole card is the target, clipped so its ripple follows the rounded corners.
 *
 * **Disabled (Design review, 2026-09-22).** While a copy is being made the cards are locked, and a locked card must
 * not look live: the border becomes M3's disabled outline (onSurface at 12 %, 2 dp still marking the chosen one)
 * and the chosen card's fill a flat onSurface-at-8 % grey instead of the brand `primaryContainer`, next to the
 * text and radio already drawn at the disabled alpha.
 */
@Composable
fun RadioCard(
    label: AnnotatedString,
    hint: String?,
    selected: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val fill = when {
        !enabled && selected -> scheme.onSurface.copy(alpha = 0.08f).compositeOver(scheme.surface)
        selected -> scheme.primaryContainer
        else -> scheme.surface
    }
    val border = when {
        !enabled -> BorderStroke(if (selected) 2.dp else 1.dp, scheme.onSurface.copy(alpha = 0.12f))
        selected -> BorderStroke(2.dp, scheme.primary)
        else -> BorderStroke(1.dp, scheme.outline)
    }
    val onSelected = selected && enabled
    Surface(
        shape = shape,
        color = fill,
        contentColor = if (onSelected) scheme.onPrimaryContainer else scheme.onSurface,
        border = border,
        modifier = modifier.fillMaxWidth().clip(shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            RowTexts(
                label, hint, enabled,
                // On the chosen card the help line takes the card's own content colour, which is what
                // primaryContainer is specified against; elsewhere it is the usual muted variant.
                hintColor = if (onSelected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/**
 * [RadioCard]s 8 dp apart: one column on a phone, and two equal columns from 600 dp (tablets, foldables, landscape;
 * Design review, 2026-09-22), the Android form of the web's `repeat(auto-fit, minmax(14rem, 1fr))` grid. Six
 * stacked format cards are about 470 dp; stretched across a tablet they are long thin bars. Cards in one row take
 * the height of the taller one. [card] is given the modifier each card must use.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> RadioCardGroup(
    items: List<T>,
    modifier: Modifier = Modifier,
    card: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 600.dp) 2 else 1
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = columns,
        ) {
            items.forEach { item -> card(item, Modifier.weight(1f).fillMaxRowHeight()) }
        }
    }
}

/**
 * A screen's designed empty state (the web page's `.empty-state`), shared by the house list (first run, no match),
 * Export ("nothing to save yet") and
 * Import ("no file yet"): a 40 dp glyph in `primary` on a 72 dp `primaryContainer` circle (web `--primary` on
 * `--primary-soft`), a one-line lead in titleMedium, optional detail in muted bodySmall, and an optional action.
 *
 * Design review, round 10: the lead is a heading for TalkBack (it is one visually, and heading navigation should
 * reach it), and both texts are capped at 480 dp, about the web copy's measure, and stay centred, so on a tablet or
 * in landscape the fine print is not one ragged line across the whole screen.
 *
 * [horizontalPadding] follows [RadioRow] and [SwitchRow]: 16 dp by default, 0 dp where the parent is already padded
 * 16 dp (the house list), so the gutter is 16 dp on every screen (design review, round 14).
 *
 * [iconTint] on [iconContainer] is the brand `primary` on `primaryContainer` by default. A finished import passes the
 * success pair (`onSuccess` on `success`, #1A7A43 on #E7F5ED light, #6FD69A on #15301F dark, AA in both themes,
 * docs/05 §4.5), so "done" is green like Export's result card and no longer looks like "no file yet" (Design review,
 * round 18).
 */
@Composable
fun HeroEmptyState(
    icon: ImageVector,
    title: String,
    body: String? = null,
    horizontalPadding: Dp = 16.dp,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    iconContainer: Color = MaterialTheme.colorScheme.primaryContainer,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(72.dp).background(iconContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(40.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = HERO_MEASURE).semantics { heading() },
        )
        body?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = HERO_MEASURE),
            )
        }
        action?.invoke()
    }
}

/** [WarnNote]'s warning sign, in sp so it scales with the text beside it. */
private val WARN_ICON_SIZE = 16.sp

/** The longest line [HeroEmptyState] lets its text run to. */
private val HERO_MEASURE = 480.dp

/**
 * The widest a screen's content column gets, centred beyond it (UX review, whole-app audit, round 3 for the house
 * form; round 8 shares it with the Assistant and Settings): in landscape, on a tablet or a foldable the fields,
 * buttons and results no longer stretch to 800+ dp, text keeps a comfortable line length, and the screens match the
 * web's pages, which sit in `--content-narrow`. The scroll stays full width, so a drag beside the column scrolls it.
 */
internal val ContentMaxWidth = 640.dp

/**
 * A calm privacy note (the web's `.warn-box`), such as "This copy will contain phone numbers… Share it carefully."
 * under the contact-details switch: amber `warn` / `onWarn` with a `warnBorder` line and a 16 sp warning sign on the first line,
 * so it reads as a caution about sharing and does not look like an error or like every other grey hint.
 *
 * With [action] and [onAction] the note ends with a 48 dp text button in `onWarn`, at the trailing edge under the
 * text (UX review, whole-app audit, round 3): the note and its next step are one unit, the same on the Map's Hunt
 * card (*Allow location*, *Allow notifications*), the house form and the Assistant ([LocationPermissionNote]).
 * Under the text rather than beside it, so a long Tamil or Telugu label never squeezes the note into a narrow column.
 *
 * [textStyle] is bodySmall (12 sp) for a hint such as the privacy note; [LocationPermissionNote], the main blocking
 * message on the Map, the house form and the Assistant, passes bodyMedium (round 5), so it is never smaller than its
 * 14 sp button label and Tamil and Telugu stay readable. The sign's box follows the style's line height.
 */
@Composable
fun WarnNote(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    textStyle: TextStyle = MaterialTheme.typography.bodySmall,
) {
    val colors = LocalHouseHuntColors.current
    val hasAction = action != null && onAction != null
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colors.warn,
        contentColor = colors.onWarn,
        border = BorderStroke(1.dp, colors.warnBorder),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = if (hasAction) 0.dp else 8.dp),
        ) {
            val style = textStyle
            // The sign is centred on the text's first line, whatever its height: a box one line tall (the style's
            // line height: bodySmall 16 sp in Latin, 20 sp for Indic scripts; bodyMedium 20 / 24 sp) holds a 16 sp
            // icon, so both grow with the font (UX review, whole-app audit, round 4: a fixed 2 dp offset fitted only
            // the 20 sp line, and a 16 dp icon stayed small at 200 %).
            val density = LocalDensity.current
            val iconSize = with(density) { WARN_ICON_SIZE.toDp() }
            val lineHeight = with(density) { (if (style.lineHeight.isSp) style.lineHeight else WARN_ICON_SIZE).toDp() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.height(maxOf(lineHeight, iconSize)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(iconSize))
                }
                Text(text, style = style)
            }
            if (action != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onWarn),
                    modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                ) { ButtonLabel(action) }
            }
        }
    }
}

/**
 * A whole-row switch.
 *
 * [warning], when not null, is drawn as a [WarnNote] **inside** the row, under the label and the switch, and eases in
 * and out with it (UX review, round 11). Inside, because `toggleable` merges everything in the row into one
 * TalkBack node: "Include contact details, This copy will contain phone numbers…, switch, on" is heard as one
 * control, as the web ties its `.warn-box` to the checkbox with `aria-describedby`. A note drawn as the next node
 * after the row was only reached on the next swipe, after the user had already heard "on".
 */
@Composable
fun SwitchRow(
    text: String,
    hint: String?,
    checked: Boolean,
    enabled: Boolean = true,
    horizontalPadding: Dp = 16.dp,
    warning: String? = null,
    onChange: (Boolean) -> Unit,
) {
    // The last warning shown, so the note keeps its text while it animates out. Not state: nothing redraws from it.
    val lastWarning = remember { arrayOfNulls<String>(1) }
    if (warning != null) lastWarning[0] = warning
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = horizontalPadding, vertical = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            RowTexts(
                AnnotatedString(text), hint, enabled,
                hintColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
        AnimatedVisibility(
            visible = warning != null,
            enter = expandVertically(tween(ANIMATION_MS)) + fadeIn(tween(ANIMATION_MS)),
            exit = shrinkVertically(tween(ANIMATION_MS)) + fadeOut(tween(ANIMATION_MS)),
        ) {
            lastWarning[0]?.let { WarnNote(it, Modifier.padding(top = 4.dp, bottom = 4.dp)) }
        }
    }
}

/**
 * Material's "restore" glyph (a clock turning back), built here from its 24 dp path because the core icon set has
 * no restore or upload glyph and the app takes no dependency on the extended set. The Import screen's empty state and
 * the house list's *Import a backup* use it.
 */
val RestoreIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Restore",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(
            "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 " +
                "-3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 " +
                "9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L12.5,8L12,8z"
        ),
        fill = SolidColor(Color.Black),
    ).build()
}
