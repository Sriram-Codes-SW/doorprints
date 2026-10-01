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
import android.graphics.Rect
import androidx.compose.ui.text.TextStyle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.ui.IndicTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Hindi, Tamil and Telugu on the phone (Wave D, docs/05 §4.3): every string of the app in those languages (the
 * Compose resources of `:ui` and the Android resources of the notifications) has a glyph in the system fonts for every
 * character, so nothing shows as a tofu box; and every type style of [IndicTypography] leaves room for the tallest
 * stacks of each script (a Telugu conjunct below the baseline and a vowel sign above it), so one line does not run
 * into the next. On a phone the fonts are Noto's, as in Robolectric's native graphics.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class IndicScriptsTest {
    private val languages = listOf("hi", "ta", "te")

    private fun strings(file: File): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = listOf("string", "item").flatMap { tag ->
            val list = doc.getElementsByTagName(tag)
            (0 until list.length).map { list.item(it).textContent }
        }
        // Placeholders (%1$s, %d) are filled with digits or names, not drawn as written.
        return nodes.map { it.replace(Regex("%(\\d+\\$)?[sd]"), "").replace("\\n", " ").replace("\\'", "'") }
    }

    @Test
    fun everyCharacterHasAGlyph() {
        val paint = Paint()
        // The check can fail: a private-use character has no glyph.
        assertEquals("\uE000", A11yAudit.missingGlyphs(paint, "a\uE000"))
        val missing = languages.flatMap { lang ->
            listOf(File("../ui/src/commonMain/composeResources/values-$lang/strings.xml"), File("src/main/res/values-$lang/strings.xml"))
                .also { files -> files.forEach { assertTrue("$it is missing", it.isFile) } }
                .flatMap { strings(it) }
                .mapNotNull { text -> A11yAudit.missingGlyphs(paint, text)?.let { "$lang: \"$it\" in \"${text.take(60)}\"" } }
        }
        assertTrue("Characters with no glyph:\n" + missing.distinct().joinToString("\n"), missing.isEmpty())
    }

    /** The tallest clusters of each script: conjuncts stacked under the baseline with vowel signs above them. */
    private val samples = mapOf(
        "hi" to "क्ष्म्य ङ्क्ष ठ्ठ ह्रृ द्ध्र्य शृंगार",
        "ta" to "கௌ ஞ்ஞ ஷ்ரீ தூ ஙெ ழூ",
        "te" to "క్ష్మ్య ఙ్క్ష ద్ధృ శ్రీ ఘ్రూ స్త్రీ",
    )

    @Test
    fun indicLineHeightsHoldTheTallestStacks() {
        val styles: Map<String, TextStyle> = with(IndicTypography) {
            mapOf(
                "headlineSmall" to headlineSmall, "titleLarge" to titleLarge, "titleMedium" to titleMedium,
                "titleSmall" to titleSmall, "bodyLarge" to bodyLarge, "bodyMedium" to bodyMedium, "bodySmall" to bodySmall,
                "labelLarge" to labelLarge, "labelMedium" to labelMedium, "labelSmall" to labelSmall,
            )
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val bounds = Rect()
        // The check can fail: Telugu's stacks are taller than a line as high as the font.
        paint.textSize = 1_400f
        val te = samples.getValue("te")
        paint.getTextBounds(te, 0, te.length, bounds)
        assertTrue("Telugu ink ${bounds.height()} px in a 1400 px line", bounds.height() > 1_400)
        val tooTight = styles.flatMap { (name, style) ->
            // At 100 px a sp, so the ratio holds at any size and density.
            paint.textSize = style.fontSize.value * 100
            val line = style.lineHeight.value * 100
            samples.mapNotNull { (lang, text) ->
                paint.getTextBounds(text, 0, text.length, bounds)
                if (bounds.height() > line) "$name ($lang): ink ${bounds.height() / 100f} sp in a ${line / 100f} sp line" else null
            }
        }
        assertTrue("Line heights too small for the script:\n" + tooTight.joinToString("\n"), tooTight.isEmpty())
    }
}
