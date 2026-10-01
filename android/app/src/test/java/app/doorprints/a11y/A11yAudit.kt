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

package app.doorprints.a11y

import android.graphics.Paint
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The accessibility checks every screen sweep runs (Wave D, docs/05 §5): the Compose counterpart of Android's
 * Accessibility Test Framework, written as semantic assertions so the test needs no new library (ATF would bring Guava
 * and protobuf into the test classpath; `enableAccessibilityChecks()` lives in `ui-test-accessibility`, which needs it).
 *
 *  - **Names**: every control (a node with a click action, or a toggle or a selection) has something TalkBack can read:
 *    a content description or text in its merged semantics.
 *  - **Targets**: every control is at least 48 x 48 dp to touch (its touch bounds, which count the extra touch area
 *    `minimumInteractiveComponentSize` adds, as `assertTouchHeightIsEqualTo` reads them).
 *  - **Images**: an image with the Image role has a description; decorative ones have no semantics at all.
 *  - **Text**: no text is hard-clipped (overflow with [TextOverflow.Clip]) or runs off the screen's right edge.
 *  - **Glyphs** (hi, ta, te): every grapheme of every text on screen has a glyph in the fonts (no tofu boxes).
 *
 * [violations] returns one line per finding, naming the node by what it reads, so a failing test says what to fix.
 */
object A11yAudit {
    /** The minimum touch target of Android's guidelines and M3's `minimumInteractiveComponentSize`. */
    val MIN_TARGET = 48.dp

    private val control = SemanticsMatcher("control") {
        SemanticsActions.OnClick in it.config || SemanticsProperties.ToggleableState in it.config ||
            SemanticsProperties.Selected in it.config
    }

    fun violations(compose: ComposeTestRule, lang: String): List<String> {
        val found = mutableListOf<String>()
        val rootWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        // Controls, as TalkBack sees them: the merged tree.
        val density = compose.density
        compose.onAllNodes(control).fetchSemanticsNodes().forEach { node ->
            // A node with no size on screen is not reachable (a collapsed or off-screen part).
            if (node.boundsInRoot.isEmpty) return@forEach
            val name = spokenName(node)
            if (name.isBlank() && SemanticsProperties.EditableText !in node.config) found += "control without a name: ${describe(node)}"
            val touch = node.touchBoundsInRoot
            val min = with(density) { MIN_TARGET.toPx() } - 0.5f
            // The larger of the touch area and the node's own size: a control half under the screen's edge is clipped.
            if (maxOf(touch.width, node.size.width.toFloat()) < min || maxOf(touch.height, node.size.height.toFloat()) < min) {
                found += "target under 48 dp: ${describe(node)} (touch ${touch.width.toInt()}x${touch.height.toInt()} px)"
            }
        }
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image)).fetchSemanticsNodes().forEach {
            if (it.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty()) found += "image without a description: ${describe(it)}"
        }
        // Text, node by node: the unmerged tree.
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        val paint = if (lang == "en") null else Paint()
        texts.fetchSemanticsNodes().forEach { node ->
            val layout = layoutOf(node)
            val words = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
            if (layout != null && layout.layoutInput.overflow == TextOverflow.Clip && clipped(layout)) {
                found += "text clipped: \"${words.take(60)}\""
            }
            if (!node.boundsInRoot.isEmpty && node.boundsInRoot.right > rootWidth + 1f) {
                found += "text off the screen's edge: \"${words.take(60)}\""
            }
            if (paint != null) missingGlyphs(paint, words)?.let { found += "no glyph for \"$it\" in \"${words.take(60)}\"" }
        }
        return found.distinct()
    }

    /**
     * Text cut off by its box: lines beyond its height or its line limit, or a line wider than the box (a box squeezed
     * to no width included). Not
     * `didOverflowWidth`, which compares with the width the paragraph was laid out at (the constraint), not its lines.
     */
    private fun clipped(layout: TextLayoutResult): Boolean {
        // A text in a part that is folded away (a collapsed section, at no height) is not on screen.
        if (layout.layoutInput.constraints.maxHeight == 0) return false
        return layout.didOverflowHeight ||
            (0 until layout.lineCount).any { layout.getLineRight(it) - layout.getLineLeft(it) > layout.size.width + 1f }
    }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult? {
        val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: return null
        val results = mutableListOf<TextLayoutResult>()
        return if (action.action?.invoke(results) == true) results.firstOrNull() else null
    }

    /**
     * The first character of [text] that the fonts cannot draw, or null. Character by character, not by grapheme:
     * [Paint.hasGlyph] is true for a cluster only when one glyph draws all of it, and a Tamil கை is two.
     */
    internal fun missingGlyphs(paint: Paint, text: String): String? {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val c = String(Character.toChars(cp))
            if (!Character.isWhitespace(cp) && Character.getType(cp) != Character.FORMAT.toInt() && !paint.hasGlyph(c)) return c
            i += Character.charCount(cp)
        }
        return null
    }

    private fun spokenName(node: SemanticsNode): String = listOfNotNull(
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
    ).joinToString(" ").trim()

    private fun describe(node: SemanticsNode): String {
        val name = spokenName(node).ifBlank { "(nothing to read)" }.take(60)
        val role = node.config.getOrNull(SemanticsProperties.Role)?.toString() ?: "no role"
        val b = node.boundsInRoot
        return "\"$name\" [$role, ${b.width.toInt()}x${b.height.toInt()} px at ${b.left.toInt()},${b.top.toInt()}]"
    }
}
