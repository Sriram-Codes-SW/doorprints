package app.doorprints.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.stringResource

/**
 * The result of an export or import as a card (Design review, round 4 and 2026-09-22), the same three looks as the
 * web page's `.success` / `.error` cards, which must not look alike:
 *
 * | Tone | Fill | 1 dp border | Icon |
 * |---|---|---|---|
 * | SUCCESS | `success` #E7F5ED / #15301F | `successBorder` #B5DCC4 / #2B5A3B | tick |
 * | ERROR | `errorContainer` | `errorBorder` #E8B4B0 / #6E2C27 | warning sign |
 * | NEUTRAL ("Stopped. Nothing was saved.") | `surfaceVariant` | `outlineVariant` | info sign, `onSurfaceVariant` |
 *
 * The border is what keeps the card visible on any surface: the fills are within 1.1:1 of the bar behind them.
 * All three tones share one structure — icon, text, optional close — so the outcome never rests on colour alone
 * (WCAG 1.4.1). The icon is decorative; the text says everything.
 *
 * The card sets no live region of its own (UX review, round 10): on Export and Import it is drawn inside the
 * [ActionBar] status area, whose container is the live region, and a live region on the card's new node would never be announced. The
 * screen passes `assertive = true` to the bar for a failure (A11Y-A09), so TalkBack interrupts to say that
 * something went wrong; success and neutral results are polite. The house list's undo card (round 19) does the same
 * with a `Box` live region of its own around the card. [actions] go under the text (for example Open and Share); see
 * [ResultActionsRow] for lining them up with it.
 *
 * With [onDismiss] the card gets a trailing 48 dp close button (Design review, round 5), named `common_close`
 * ("Close", the web's `common.close`; round 19 replaced *Dismiss*, whose translations read as *Reject*): the Export
 * screen's result makes way for the live count again, as the web page's does when an option changes. Icon, text and close
 * button are centred on one 48 dp row, so a one-line result does not sit crooked above a lower close glyph.
 *
 * [stale] marks a result that a new run is about to replace (Settings while *Save and test* or *Sync now* is busy;
 * the Assistant while *Ask* or *Plan visits* runs again after an error; UX review, whole-app audit, round 10): the
 * icon and the border are dimmed to [STALE_RESULT_ALPHA], the text stays at full contrast, so a low-vision user can
 * still read it during a 10-15 s run. The caller draws the progress bar along its foot and names the state for
 * TalkBack.
 */
@Composable
fun ResultCard(
    tone: ResultTone,
    text: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    stale: Boolean = false,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    val extra = LocalDoorprintsColors.current
    val scheme = MaterialTheme.colorScheme
    val container = when (tone) {
        ResultTone.SUCCESS -> extra.success
        ResultTone.ERROR -> scheme.errorContainer
        ResultTone.NEUTRAL -> scheme.surfaceVariant
    }
    val content = when (tone) {
        ResultTone.SUCCESS -> extra.onSuccess
        ResultTone.ERROR -> scheme.onErrorContainer
        ResultTone.NEUTRAL -> scheme.onSurface
    }
    val border = when (tone) {
        ResultTone.SUCCESS -> extra.successBorder
        ResultTone.ERROR -> extra.errorBorder
        ResultTone.NEUTRAL -> scheme.outlineVariant
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = container,
        contentColor = content,
        border = BorderStroke(1.dp, if (stale) border.copy(alpha = STALE_RESULT_ALPHA) else border),
        modifier = modifier.fillMaxWidth(),
    ) {
        // The web's .success padding; only 4 dp beside the close button, whose 48 dp target has room around it.
        Column(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = if (onDismiss != null) 4.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val iconModifier = if (stale) Modifier.alpha(STALE_RESULT_ALPHA) else Modifier
                when (tone) {
                    ResultTone.SUCCESS -> Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = iconModifier)
                    ResultTone.ERROR -> Icon(Icons.Default.Warning, contentDescription = null, modifier = iconModifier)
                    ResultTone.NEUTRAL -> Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = iconModifier,
                        tint = scheme.onSurfaceVariant,
                    )
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.common_close))
                    }
                }
            }
            actions()
        }
    }
}

/**
 * A result that a new run may be replacing (UX review, whole-app audit, round 10; one rule for Settings' server result
 * and the Assistant's errors). While [busyText] is non-null a run is busy: the card keeps its slot, so what is below it
 * does not jump up by its height and back down when the new result lands; it is drawn [stale] (icon and border dimmed,
 * text at full contrast) with the indeterminate progress bar along its foot, inside its 10 dp corners and on its
 * border, so its height is unchanged; and [busyText] ("Updating…", "Thinking…") is its state, so a TalkBack user who
 * swipes onto it hears that it is being updated. Card and text are one TalkBack item. The node is the same whether
 * busy or not (the caller keys it on the run, so a new result is a new node and is announced).
 */
@Composable
fun RefreshableResultCard(tone: ResultTone, text: String, busyText: String?, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            if (busyText != null) stateDescription = busyText
        },
    ) {
        ResultCard(tone = tone, text = text, stale = busyText != null)
        if (busyText != null) {
            ProgressBar(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = 1.dp),
            )
        }
    }
}

/**
 * A stale result card's icon and border opacity (round 10): half, so the card's outline stays visible on the fills
 * that are within 1.1:1 of the surface; its text is never dimmed (round 9 dimmed the whole card to M3's disabled 38 %,
 * about 2:1 for error text on errorContainer).
 */
internal const val STALE_RESULT_ALPHA = 0.5f

/**
 * The actions under a result card's text (Open, *Share this file*), in the M3 banner pattern: they wrap onto a
 * second line instead of squeezing each other (Tamil and Telugu at 200% font), and the TextButton labels start
 * under the message text, not at the card edge: 24 dp = the 24 dp icon + the 12 dp gap − the TextButton's own
 * 12 dp inner padding.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultActionsRow(content: @Composable () -> Unit) {
    FlowRow(Modifier.padding(start = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
