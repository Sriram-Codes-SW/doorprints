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

import app.doorprints.shared.model.KindSetting
import app.doorprints.shared.model.KindSettingRules
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The iPhone lists the school, compiled in and off (owner, 2026-10-15; docs/03 §18.12, §18.14, TC-U-264), and no Mac is here to
 * look at it, so this reads the source, as [IosVoiceSafetyTest] does for the microphone: the iPhone's store lists the same kinds
 * as the Android release from one constant; nothing else on the iPhone names a kind or decides what is offered; the chooser is
 * reachable only through the two-kinds-enabled test, which the switch alone can make true; and the Info.plist, which is the
 * iPhone's declaration of what it does, does not mention the list. The behaviour of the switch is `KindSettingOffTest` and
 * `SpacesRootTest` (the same common code); the macOS-runner screenshots and VoiceOver (S4b-BL-276) are the manual checks.
 */
class IosKindsGateTest {
    private val repo: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs/schemas/trace-repeat-vectors.json").exists()) dir = dir.parentFile
        checkNotNull(dir) { "the repository root was not found" }
    }

    private fun file(path: String) = File(repo, path)

    /** Code only: comments name the very things this test keeps out of the code. */
    private fun code(f: File): String =
        f.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    private val settingsIos = "android/shared/src/iosMain/kotlin/app/doorprints/data/SettingsIos.kt"
    private val root = "android/ui/src/commonMain/kotlin/app/doorprints/ui/Root.kt"

    private fun androidReleaseKinds(): String =
        Regex("""buildConfigField\("String", "AVAILABLE_KINDS", "\\"([a-z,]+)\\""\)""")
            .find(file("android/app/build.gradle.kts").readText().substringAfter("defaultConfig"))!!.groupValues[1]

    @Test
    fun theIphoneListsTheKindsTheAndroidReleaseLists() {
        val ios = code(file(settingsIos))
        assertTrue(ios.contains("internal const val IOS_AVAILABLE_KINDS = \"${androidReleaseKinds()}\""), "one list for both phones")
        // The app's store passes it; the path variant the simulator tests use lists the house alone unless told otherwise.
        assertTrue(ios.contains("availableKinds = KindSettingRules.availableOf(IOS_AVAILABLE_KINDS)"))
        assertTrue(ios.contains("availableKinds: List<String> = listOf(KindSettingRules.HOUSE)"))
        assertEquals(2, Regex("IOS_AVAILABLE_KINDS").findAll(ios).count(), "the constant is declared once and used once")
        assertEquals(listOf("house", "school"), KindSettingRules.availableOf(androidReleaseKinds()))
    }

    @Test
    fun nothingElseOnTheIphoneNamesAKindOrTheFlag() {
        val sources = file("android/ui/src/iosMain").walkTopDown().plus(file("android/shared/src/iosMain").walkTopDown())
            .filter { it.isFile && it.extension == "kt" && it.name != "SettingsIos.kt" }.toList()
        assertTrue(sources.size > 20, "the scan must read the iPhone sources (${sources.size})")
        for (f in sources) {
            val text = code(f)
            for (token in listOf("IOS_AVAILABLE_KINDS", "availableKinds", "LocalDevKinds", "LocalKindsOn", "withExperimental", "\"school\"", "KindSettingRules"))
                assertFalse(text.contains(token), "${f.name} names `$token`: the iPhone's kinds are SettingsIos.kt's alone, and the switch is common code")
            assertFalse(text.contains("\"chooser\""), "${f.name} navigates to the chooser")
        }
        val swift = file("ios/Doorprints").takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.extension == "swift" }?.map { it.readText() }?.joinToString("\n").orEmpty()
        assertFalse(Regex("availableKinds|devKinds|chooser|school", RegexOption.IGNORE_CASE).containsMatchIn(swift), "the Swift shell names no kind")
        val plist = file("ios/Doorprints").takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.name == "Info.plist" }?.map { it.readText() }?.joinToString("\n").orEmpty()
        assertFalse(Regex("school|AVAILABLE_KINDS|experimental", RegexOption.IGNORE_CASE).containsMatchIn(plist), "Info.plist is unchanged by the list")
    }

    @Test
    fun theChooserIsReachableOnlyThroughTheTwoKindsEnabledTest() {
        val text = code(file(root))
        val uses = text.lines().withIndex().filter { it.value.contains("\"chooser\"") }
        // The start destination, the chip's navigation and the route itself: the first two are guarded, the route is data.
        assertEquals(3, uses.size, "every use of the chooser route: ${uses.map { it.value.trim() }}")
        assertTrue(text.contains("val home = rememberSaveable { if (setting.spaces) \"chooser\" else tabHome }"), "the start is the chooser only with spaces")
        val chip = text.lines().indexOfFirst { it.contains("nav.navigate(\"chooser\")") }
        assertTrue(text.lines().subList(maxOf(0, chip - 4), chip).any { it.contains("if (space.multi && tabs.any") }, "the chip is drawn only with spaces")
        assertEquals(1, Regex("""ChooserScreen\(""").findAll(text).count(), "one place draws the chooser")
        // And the two tests are the settings', which the build's list and the switch alone decide.
        val kindSetting = code(file("android/shared/src/commonMain/kotlin/app/doorprints/shared/model/KindSetting.kt"))
        assertTrue(kindSetting.contains("val spaces: Boolean get() = enabled.size > 1"))
        assertTrue(kindSetting.contains("val offered: List<String> get() = available.filter { it in KindSettingRules.RELEASED || experimentalOn }"))
    }

    @Test
    fun withTheSwitchOffTheIphoneOpensOnItsTabsInTheHouseSpace() {
        val iphone = KindSetting(KindSettingRules.availableOf(androidReleaseKinds()))
        for (chosen in listOf(null, "house", "school")) {
            val space = SpaceUi.resolve(iphone, chosen)
            assertFalse(space.multi, "no chooser, no chip")
            assertEquals("house", space.kind, "a tapped or restored `$chosen` cannot open the school")
            assertTrue(space.huntHere || !space.multi)
        }
        assertEquals("house", iphone.default)
    }
}
