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

import android.os.LocaleList
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import app.doorprints.DoorprintsApp
import app.doorprints.TwoKinds
import app.doorprints.data.HouseEntity
import app.doorprints.i18n.AppLocale
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.model.KindSetting
import app.doorprints.ui.ChooserScreen
import app.doorprints.ui.DoorprintsTheme
import app.doorprints.ui.KindsSettingsSection
import app.doorprints.ui.LocalSpace
import app.doorprints.ui.SpaceUi
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.chooser_heading
import app.doorprints.ui.res.chooser_manage
import app.doorprints.ui.res.kinds_heading
import app.doorprints.ui.res.kinds_last_on
import app.doorprints.ui.res.kinds_switch
import app.doorprints.ui.res.kinds_switch_hint
import app.doorprints.ui.res.school_add_tip_listing
import app.doorprints.ui.res.school_add_tip_listing_a11y
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import java.util.TimeZone

/**
 * The accessibility pass for the released school (owner, 2026-10-15 and 2026-10-20; docs/05 §5, TC-U-264, docs/03 ADR-13b): the chooser
 * and Settings > What you track (no *Experimental: more than houses* switch since the release), in all four languages at 200 % text (the sweep's `A11yAudit`: a
 * name for every control, 48 dp targets, no clipped or off-screen text, a glyph for every letter), plus what TalkBack needs that a
 * sweep cannot see: the headings, a disabled box that says why, the reading order, a card whose name is its visible words, and that
 * the switch hint and the school listing tips carry no *preview* mark in any language (hi, ta, te under review).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h2400dp-hdpi", application = ScreenshotTestApp::class)
class KindsA11yTest(private val lang: String) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val kinds by lazy { TwoKinds(app) }
    private val timeZone: TimeZone = TimeZone.getDefault()
    private val locales: LocaleList = LocaleList.getDefault()
    private val both = listOf("house", "school")

    @Before fun setUp() {
        runCatching {
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
                .let { (it.get(null) as MutableMap<*, *>).clear() }
        }
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        RuntimeEnvironment.setQualifiers("+$lang")
        AppLocale.applyDefault(app)
        runBlocking {
            val at = 1_760_000_000_000
            app.container.repository.saveHouse(HouseEntity(id = "h1", label = "Green View", lat = 12.97, lon = 77.59, createdAt = at, updatedAt = at))
            app.container.repository.saveHouse(HouseEntity(id = "s1", label = "Pine Hill School", lat = 26.14, lon = 91.78, createdAt = at, updatedAt = at + 5, kind = "school"))
        }
    }

    @After fun tearDown() {
        kinds.close()
        TimeZone.setDefault(timeZone)
        LocaleList.setDefault(locales)
    }

    private fun text(res: org.jetbrains.compose.resources.StringResource) = runBlocking { getString(res) }

    /** Shows [content] at 200 % text on the store that lists the school, waits for [ready], and settles. */
    private fun show(ready: String, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                kinds.Provide {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, ScreensA11ySweepTest.FONT_SCALE)) {
                        DoorprintsTheme(dark = false) { Surface(color = MaterialTheme.colorScheme.background) { content() } }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(ready, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(20)
        }
    }

    private fun audit(what: String) {
        val found = A11yAudit.violations(compose, lang)
        assertTrue("$what ($lang, 200 %):\n" + found.joinToString("\n"), found.isEmpty())
    }

    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    private fun top(m: SemanticsMatcher) = compose.onAllNodes(m).fetchSemanticsNodes().first().boundsInRoot.top

    /** The preview mark of each language (hi, ta, te under review; the website's words), which the released school's words no longer carry. */
    private val previewMark = mapOf("en" to "preview", "hi" to "पूर्वावलोकन", "ta" to "முன்னோட்டம்", "te" to "ప్రివ్యూ")

    @Test fun chooserHasNoViolationsAndReadsInOrderWithItsHeadings() {
        val on = KindSetting(both, experimental = "1", enabledText = "[\"house\",\"school\"]")
        show(text(Res.string.chooser_heading)) {
            CompositionLocalProvider(LocalSpace provides SpaceUi(on, "house")) { ChooserScreen(onOpen = {}, onOpenSettings = {}) }
        }
        audit("chooser")
        val title = text(Res.string.chooser_heading)
        compose.onNode(heading and hasText(title)).assertExists()
        // A card is one button and its name is its visible words: the kind, then its count.
        val buttons = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        val cards = compose.onAllNodes(buttons and hasText("1", substring = true)).fetchSemanticsNodes()
        assertEquals("two cards, each a Button naming its count", 2, cards.size)
        // Reading order is the screen's order: the heading, the cards, then the way to Settings.
        val first = cards.map { it.boundsInRoot.top }
        val manage = top(hasText(text(Res.string.chooser_manage), substring = true))
        assertTrue("heading, cards, then Settings", top(heading and hasText(title)) < first.min() && first == first.sorted() && first.max() < manage)
    }

    @Test fun theReleasedSchoolHasItsRowsOffAndNoSwitch() {
        show(text(Res.string.kinds_heading)) { Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) { KindsSettingsSection() } }
        audit("What you track, the school released and not shown")
        compose.onNode(heading and hasText(text(Res.string.kinds_heading))).assertExists()
        // The switch hides itself while every listed kind is released: nothing named Experimental is left to read.
        assertEquals(0, compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText(text(Res.string.kinds_switch), substring = true)).fetchSemanticsNodes().size)
        // A Show box and a Default radio for each kind; the house is on (its box says why it stays), the school is off.
        val boxes = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).fetchSemanticsNodes()
        assertEquals("a Show box for each kind", 2, boxes.size)
        assertEquals("the house is on and the school off", listOf(androidx.compose.ui.state.ToggleableState.On, androidx.compose.ui.state.ToggleableState.Off),
            boxes.map { it.config.getOrNull(SemanticsProperties.ToggleableState) })
        assertEquals("a Default radio for each kind", 2, compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).fetchSemanticsNodes().size)
    }

    @Test fun kindsOnNameTheirRowsAndAreReadInOrder() {
        kinds.enableSchool()
        show(text(Res.string.kinds_heading)) { Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) { KindsSettingsSection() } }
        audit("What you track, school on")
        val headings = compose.onAllNodes(heading).fetchSemanticsNodes()
        assertTrue("the section's heading and one for each kind (${headings.size})", headings.size >= 3)
        val boxes = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).fetchSemanticsNodes()
        assertEquals("a Show box for each kind", 2, boxes.size)
        val radios = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).fetchSemanticsNodes()
        assertEquals("a Default radio for each kind", 2, radios.size)
        // The first kind's rows, then the second's.
        val order = (boxes.take(1) + radios.take(1) + boxes.drop(1) + radios.drop(1))
            .map { it.boundsInRoot.top }
        assertEquals("the reading order is the screen's order", order.sorted(), order)
    }

    @Test fun theLastKindThatIsOnSaysWhyItCannotBeTurnedOff() {
        kinds.enableSchool()
        runBlocking { kinds.store.editKindSetting { it.withKindEnabled("school", false) } }
        show(text(Res.string.kinds_heading)) { Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) { KindsSettingsSection() } }
        audit("What you track, the house alone on")
        val why = text(Res.string.kinds_last_on)
        val disabled = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled)).fetchSemanticsNodes()
        assertEquals("the one kind that is on has a disabled box", 1, disabled.size)
        assertEquals("and it carries the reason as its state", why, disabled.single().config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test fun noPreviewMarkIsOnTheSwitchHintOrTheSchoolListingTipsInAnyLanguage() {
        val mark = previewMark.getValue(lang)
        for (res in listOf(Res.string.kinds_switch_hint, Res.string.school_add_tip_listing, Res.string.school_add_tip_listing_a11y)) {
            assertFalse("`${text(res)}` ($lang) still says it is a preview", text(res).contains(mark, ignoreCase = true))
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf("en", "hi", "ta", "te").map { arrayOf<Any>(it) }
    }
}
