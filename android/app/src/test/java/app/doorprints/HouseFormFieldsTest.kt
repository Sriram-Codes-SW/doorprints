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

package app.doorprints

import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import app.doorprints.data.HouseEntity
import app.doorprints.i18n.AppLocale
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.model.HouseStatus
import app.doorprints.ui.HouseEditScreen
import app.doorprints.ui.ProvideAppServices
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The safety test of kinds PR 4 on the phone (S4b-BL-208, docs/03 §18.8), written on the hand-written form before the
 * renderer replaced its BHK, area, floor and cost fields: every control and heading of the house form in screen order
 * (its role, label, value, state, error and IME action, and the row it shares with the control before it), for a rent,
 * a sale and a price with no type, in en, hi, ta and te. `src/test/house-form.golden.txt` was taken from main at
 * 7fbde93c (written once with `HOUSE_FORM_WRITE=1`; a change to it is a visible change of the form). Then each typed
 * value lands where the house keeps it, a field keeps as many digits as before, and
 * `docs/schemas/kinds/kind-field-vectors.json` (the phone's half) describes this form. The Roborazzi baselines of
 * `ScreensScreenshotTest` are the pixels; this is the semantics.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-hdpi", application = ScreenshotTestApp::class)
class HouseFormFieldsTest(private val lang: String) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun langs() = listOf("en", "hi", "ta", "te").map { arrayOf<Any>(it) }

        private val SCENARIOS = listOf("rent" to "RENT", "sale" to "SALE", "untyped" to null)
        private val GOLDEN = File("src/test/house-form.golden.txt")
        private val CHECK_CELLS = setOf("0", "1", "2", "3", "4", "5", "–")
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
    private val locales: LocaleList = LocaleList.getDefault()

    @Before fun setUp() {
        runCatching {
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
                .let { (it.get(null) as MutableMap<*, *>).clear() }
        }
        RuntimeEnvironment.setQualifiers("+$lang")
        AppLocale.applyDefault(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() = LocaleList.setDefault(locales)

    private fun house(id: String, priceType: String?) = HouseEntity(
        id = id, label = "Lake view flat", lat = 26.1445, lon = 91.7362, status = HouseStatus.NEW, priceType = priceType,
        locationSource = "GPS", createdAt = 1_760_000_000_000, updatedAt = 1_760_000_000_000,
    )

    /** The house on screen: one of three saved houses, a rent, a sale and one with no price type. */
    private var shownId by mutableStateOf<String?>(null)

    private fun open(priceType: String?) {
        val id = "g-${priceType ?: "none"}"
        if (shownId == null) {
            runBlocking { SCENARIOS.forEach { (_, type) -> repo.saveHouse(house("g-${type ?: "none"}", type)) } }
            shownId = id
            compose.setContent {
                ProvideAppServices { shownId?.let { key(it) { HouseEditScreen(houseId = it, newLat = null, newLon = null, visitId = null, onDone = {}) } } }
            }
        } else {
            compose.runOnIdle { shownId = id }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Lake view flat", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun SemanticsNode.describe(): String? {
        val c = config
        val role = c.getOrNull(SemanticsProperties.Role)?.toString()
        val editable = c.getOrNull(SemanticsProperties.EditableText)
        val heading = c.getOrNull(SemanticsProperties.Heading) != null
        val toggle = c.getOrNull(SemanticsProperties.ToggleableState)
        val selected = c.getOrNull(SemanticsProperties.Selected)
        if (editable == null && !heading && toggle == null && selected == null && role == null) return null
        // The checklist's 0-5 cells (ChecklistSection, which this change does not touch) are left out to keep the file small.
        if (role == "RadioButton" && c.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text in CHECK_CELLS) return null
        val kind = when {
            editable != null -> "field"
            heading -> "heading"
            else -> role ?: if (toggle != null) "toggle" else "selectable"
        }
        val text = c.getOrNull(SemanticsProperties.Text)?.joinToString(" | ") { it.text }.orEmpty()
        val desc = c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" | ").orEmpty()
        val parts = listOfNotNull(
            kind,
            "\"$text\"",
            editable?.let { "value=\"${it.text}\"" },
            desc.takeIf { it.isNotEmpty() }?.let { "desc=\"$it\"" },
            toggle?.let { "state=$it" },
            selected?.let { "selected=$it" },
            c.getOrNull(SemanticsProperties.Error)?.let { "error=\"$it\"" },
            c.getOrNull(SemanticsProperties.ImeAction)?.let { "ime=$it" },
            if (c.getOrNull(SemanticsProperties.Disabled) != null) "disabled" else null,
            if (c.contains(SemanticsActions.SetText)) "settext" else null,
        )
        return parts.joinToString(" ")
    }

    /** One line per control and heading of the form, in screen order; `~` marks one on the row of the line before. */
    private fun lines(): List<String> {
        val nodes = compose.onAllNodes(SemanticsMatcher("any") { true }).fetchSemanticsNodes()
            .mapNotNull { n -> n.describe()?.let { n to it } }
        var lastTop = Float.NaN
        return nodes.map { (n, line) ->
            val top = n.positionInRoot.y
            val sameRow = top == lastTop && line.startsWith("field")
            lastTop = top
            (if (sameRow) "~ " else "") + line
        }
    }

    private fun goldenFor(key: String): List<String> {
        val all = GOLDEN.readLines()
        val start = all.indexOf("## $key")
        check(start >= 0) { "no golden for $key in $GOLDEN" }
        return all.drop(start + 1).takeWhile { !it.startsWith("## ") }.filter { it.isNotEmpty() }
    }

    @Test
    fun theFormIsAsItWas() {
        for ((name, priceType) in SCENARIOS) {
            open(priceType)
            val got = lines()
            val key = "$name.$lang"
            if (System.getenv("HOUSE_FORM_WRITE") == "1") {
                GOLDEN.appendText("## $key\n${got.joinToString("\n")}\n\n")
            } else {
                assertEquals(key, goldenFor(key).joinToString("\n"), got.joinToString("\n"))
            }
        }
    }

    /** Types into the field labelled [label] as a person does. */
    private fun type(label: String, text: String) {
        compose.onNodeWithText(label).performScrollTo().performTextInput(text)
        compose.waitForIdle()
    }

    private fun shown(label: String): String =
        compose.onNodeWithText(label).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test
    fun eachFieldKeepsTheDigitsItDidAndTheValueLandsWhereItWas() {
        assumeTrue(lang == "en")
        open("RENT")
        val typed = listOf(
            // Digits past the field's length are dropped; a value over the range reads as unknown and the field empties.
            Triple("BHK", "123", "12"), Triple("Carpet area (sq ft)", "1234567", ""), Triple("Carpet area (sq ft)", "950", "950"),
            Triple("Deposit (₹)", "12345678901234", ""), Triple("Deposit (₹)", "64000", "64000"), Triple("Deposit (months)", "1234", ""),
            Triple("Lock-in (months)", "11", "11"), Triple("Notice (months)", "2", "2"),
        )
        for ((label, text, kept) in typed) {
            type(label, text)
            assertEquals(label, kept, shown(label))
        }
        type("Floor", "-3")
        assertEquals("3", shown("Floor"))
        compose.onAllNodesWithText("Save")[1].performClick()
        compose.waitUntil(5_000) { runBlocking { repo.getHouse("g-RENT") }?.floor == -3 }
        val saved = runBlocking { repo.getHouse("g-RENT") }!!
        assertEquals(12, saved.bedrooms)
        assertEquals(950, saved.areaSqft)
        assertEquals(64_000L, saved.cost?.deposit)
        assertEquals(null, saved.cost?.depositMonths)
        assertEquals(11, saved.cost?.lockInMonths)
        assertEquals(2, saved.cost?.noticeMonths)
    }

    /** The phone's half of `kind-field-vectors.json`: each extra's label in this language, and the rows per cadence. */
    @Test
    fun kindFieldVectorsDescribeThisForm() {
        val kind = Json.parseToJsonElement(File("../../docs/schemas/kinds/kind-field-vectors.json").readText())
            .jsonObject.getValue("kinds").jsonArray.first().jsonObject
        val fields = kind.getValue("fields").jsonArray.map { it.jsonObject }
        val labels = fields.associate { f ->
            val key = f.getValue("android").jsonObject.getValue("label").jsonPrimitive.content
            f.getValue("id").jsonPrimitive.content to runBlocking { getString(app.doorprints.ui.kindStringResource(key)) }
        }
        val rows = kind.getValue("rows").jsonArray.map { it.jsonObject }.filter { it.getValue("stack").jsonPrimitive.content == "android" }
        for ((cadence, priceType) in listOf("month" to "RENT", "once" to "SALE", null to null)) {
            open(priceType)
            for (row in rows.filter { it["cadence"]?.jsonPrimitive?.contentOrNullSafe() == cadence }) {
                val want = row.getValue("rows").jsonArray.map { r -> r.jsonArray.map { it.jsonPrimitive.content } }
                val ids = want.flatten()
                val tops = ids.associateWith { id ->
                    compose.onAllNodesWithText(labels.getValue(id)).fetchSemanticsNodes().first().positionInRoot.y
                }
                val got = mutableListOf<MutableList<String>>()
                for (id in ids) if (got.isNotEmpty() && tops[got.last().last()] == tops[id]) got.last().add(id) else got.add(mutableListOf(id))
                assertEquals("$lang ${row.getValue("section").jsonPrimitive.content} $cadence", want, got)
            }
            // A field the cadence leaves out is not drawn.
            for (f in fields.filter { it["forCadences"] != null }) {
                val shownHere = compose.onAllNodesWithText(labels.getValue(f.getValue("id").jsonPrimitive.content)).fetchSemanticsNodes().isNotEmpty()
                val forCadences = f.getValue("forCadences").jsonArray.map { it.jsonPrimitive.content }
                assertEquals("${f.getValue("id")} for $cadence", cadence == null || cadence in forCadences, shownHere)
            }
        }
        assertEquals(14, fields.size)
        assertEquals(1, fields.count { it.getValue("android").jsonObject["switch"] != null })
        fields.forEach { it.getValue("android").jsonObject["digits"]?.let { d -> if (d.toString() != "null") d.jsonPrimitive.int } }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? = if (this.toString() == "null") null else content
}
