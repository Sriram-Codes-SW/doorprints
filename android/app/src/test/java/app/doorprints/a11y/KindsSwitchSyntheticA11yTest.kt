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
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import app.doorprints.DoorprintsApp
import app.doorprints.TwoKinds
import app.doorprints.i18n.AppLocale
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.model.KindFileKinds
import app.doorprints.shared.model.TestOnlyKinds
import app.doorprints.ui.DoorprintsTheme
import app.doorprints.ui.KindsSettingsSection
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.kinds_heading
import app.doorprints.ui.res.kinds_switch
import app.doorprints.ui.res.kinds_switch_hint
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone

/**
 * *Experimental: more than houses* with a kind that is listed but not released (docs/03 ADR-13b; docs/05 section 5, TC-U-264), in all
 * four languages at 200 % text: the school is released, so no real kind is left for the switch to show, and the synthetic kind of the
 * conformance suite (registered for the run only, `KindFileKinds`; no build lists it) stands in, with the released list passed to the store
 * (`KindSetting.released`, the twin of the website's `pinReleased`). What TalkBack needs and a sweep cannot see: the switch's role, its
 * state and the words that name it (the label and its hint), the state after a tap, and a Show box and a Default radio for the kind it reveals.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h2400dp-hdpi", application = ScreenshotTestApp::class)
@OptIn(TestOnlyKinds::class)
class KindsSwitchSyntheticA11yTest(private val lang: String) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val timeZone: TimeZone = TimeZone.getDefault()
    private val locales: LocaleList = LocaleList.getDefault()
    private var undo: (() -> Unit)? = null
    private var kinds: TwoKinds? = null

    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    @Before fun setUp() {
        runCatching {
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
                .let { (it.get(null) as MutableMap<*, *>).clear() }
        }
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        RuntimeEnvironment.setQualifiers("+$lang")
        AppLocale.applyDefault(app)
        undo = KindFileKinds.register(repoFile("docs/schemas/kinds/test-all.json").readText())
        // The house and the school released; the synthetic kind is listed by the store and is not.
        kinds = TwoKinds(app, available = listOf("house", "school", "zz_all"), released = listOf("house", "school"))
    }

    @After fun tearDown() {
        kinds?.close()
        undo?.invoke()
        TimeZone.setDefault(timeZone)
        LocaleList.setDefault(locales)
    }

    private fun text(res: org.jetbrains.compose.resources.StringResource) = runBlocking { getString(res) }

    private fun show(ready: String?, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                kinds!!.Provide {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, ScreensA11ySweepTest.FONT_SCALE)) {
                        DoorprintsTheme(dark = false) { Surface(color = MaterialTheme.colorScheme.background) { content() } }
                    }
                }
            }
        }
        if (ready != null) compose.waitUntil(10_000) { compose.onAllNodes(hasText(ready, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        settle()
    }

    private fun settle() = repeat(3) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        Thread.sleep(20)
    }

    private fun audit(what: String) {
        val found = A11yAudit.violations(compose, lang)
        assertTrue("$what ($lang, 200 %):\n" + found.joinToString("\n"), found.isEmpty())
    }

    /** The *Experimental* switch, by its label: the section also has *Kinds you add (experimental)* (docs/03 ADR-39), a switch of its own. */
    private val isSwitch get() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and hasText(text(Res.string.kinds_switch), substring = true)
    private val isCheckbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
    private val isRadio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    private fun count(m: SemanticsMatcher) = compose.onAllNodes(m).fetchSemanticsNodes().size

    private fun section() = show(text(Res.string.kinds_switch)) { Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) { KindsSettingsSection() } }

    @Test fun theSwitchIsASwitchOffByDefaultNamedByItsLabelAndItsHintWithNothingOfTheUnreleasedKind() {
        section()
        audit("What you track, the switch off")
        compose.onNode(heading and hasText(text(Res.string.kinds_heading))).assertExists()
        val node = compose.onNode(isSwitch).fetchSemanticsNode()
        assertEquals("role: a switch, state: off by default", ToggleableState.Off, node.config.getOrNull(SemanticsProperties.ToggleableState))
        val name = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
        assertTrue("named by its label ($name)", name.contains(text(Res.string.kinds_switch)))
        assertTrue("and described by its hint ($name)", name.contains(text(Res.string.kinds_switch_hint)))
        // Off, only the released kinds are listed: a Show box and a Default radio for the house and for the school, none for the synthetic kind.
        assertEquals(2, count(isCheckbox))
        assertEquals(2, count(isRadio))
    }

    @Test fun turningItOnChangesTheStateAndRevealsTheUnreleasedKindWithANamedShowBoxAndDefaultRadio() {
        section()
        compose.onNode(isSwitch).performClick()
        settle()
        compose.waitUntil(10_000) { count(isCheckbox) == 3 }
        audit("What you track, the switch on")
        assertEquals("state: on", ToggleableState.On, compose.onNode(isSwitch).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState))
        assertEquals("a Show box for each of the three kinds", 3, count(isCheckbox))
        assertEquals("a Default radio for each of the three kinds", 3, count(isRadio))
        // The Show box of a kind that is off is named by its words and is not disabled; the Default radio of a kind that is off is disabled.
        val boxes = compose.onAllNodes(isCheckbox).fetchSemanticsNodes()
        assertEquals("only the last kind that is on is disabled", 1, boxes.count { it.config.contains(SemanticsProperties.Disabled) })
        // And off again: the synthetic kind leaves the list, the choice is kept for later.
        compose.onNode(isSwitch).performClick()
        settle()
        compose.waitUntil(10_000) { count(isCheckbox) == 2 }
        assertEquals("state: off again", ToggleableState.Off, compose.onNode(isSwitch).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState))
    }

    @Test fun theSwitchIsHiddenWhenNoKindIsLeftUnreleased() {
        kinds!!.close()
        kinds = TwoKinds(app, available = listOf("house", "school", "zz_all"), released = listOf("house", "school", "zz_all"))
        show(null) { Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) { KindsSettingsSection() } }
        assertEquals("no switch: nothing is unreleased", 0, count(isSwitch))
        assertEquals("the section stays, with a Show box for each of the three released kinds", 3, count(isCheckbox))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf("en", "hi", "ta", "te").map { arrayOf<Any>(it) }
    }
}
