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

package app.doorprints.shared.model

import app.doorprints.shared.export.BackupWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The kinds a person adds, once checked, as the phones' registries read them (docs/03 ADR-39, docs/06 TC-U-296): the kind
 * is drawn with its own words under keys no Compose string has, its capabilities and question scope are its file's, it comes
 * after the built-in kinds and never stands in for one, its space's words are neutral but for the heading and the add button,
 * search reads every typed extra, no AI reads it, and the store's list and a backup's `search.userKinds` keep it as checked
 * JSON. The twin of the website's `user-kinds.spec.ts`.
 */
class UserKindsTest {
    private val vectors = run {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs/schemas/kinds/user-kind-vectors.json").isFile) dir = dir.parentFile
        Json.parseToJsonElement(File(checkNotNull(dir), "docs/schemas/kinds/user-kind-vectors.json").readText()).jsonObject
    }

    private fun sample(id: String): JsonObject =
        vectors.getValue("valid").jsonArray.map { it.jsonObject.getValue("file").jsonObject }.first { it.getValue("id").jsonPrimitive.content == id }

    private fun checked(id: String): UserKindFile = (UserKindFiles.read(sample(id).toString()) as UserKindRead.Ok).file

    /** The store's text holding these samples, as the settings store keeps it. */
    private fun stored(vararg ids: String): String = JsonArray(ids.map(::sample)).toString()

    @After
    fun clear() = UserKinds.load(null)

    @Test
    fun anAddedKindIsDrawnWithItsOwnWordsAfterTheBuiltInKinds() {
        UserKinds.load(stored("u_gym", "u_office_space"))
        assertEquals(listOf("u_gym", "u_office_space"), UserKinds.ids())
        assertEquals(listOf("house", "school", "u_gym", "u_office_space"), DevKinds.creatable(true).map { it.id })
        assertEquals(listOf("house"), DevKinds.creatable(false).map { it.id })
        assertEquals(listOf("house", "school"), DevKinds.builtIn(true).map { it.id })
        val gym = DevKinds.of("u_gym", on = true)
        assertEquals("u_gym", gym.id)
        assertFalse("no listing fill reads a kind a person added", gym.extracts)
        // Its labels are keys of the person's words, shown as written in every language.
        val many = checkNotNull(gym.labels.resolve("kind.many")?.key)
        assertTrue(UserKinds.isTextKey(many))
        assertEquals("Gyms", UserKinds.text(many))
        for (language in listOf("en", "hi", "ta", "te")) assertEquals("Gyms", KindWords.of(many, language))
        val fee = checkNotNull(gym.labels.field("fee")?.key)
        assertEquals(checked("u_gym").extras.first { it.id == "fee" }.label, UserKinds.text(fee))
        assertEquals(gym.labels.checklistKeys(), listOf("clean", "equipment", "crowd", "staff"))
        val focus = gym.fields.extras.first { it.id == "focus" }
        assertTrue(focus.options.all { UserKinds.isTextKey(it.label) && UserKinds.text(it.label) != null })
        // A built-in kind's labels are untouched.
        assertEquals("houses_title", DevKinds.of("house", on = true).labels.resolve("kind.many")?.key)
    }

    @Test
    fun itsCapabilitiesAndQuestionScopeAreItsFilesAndItsQuestionIdsAreItsOwn() {
        UserKinds.load(stored("u_gym", "u_office_space"))
        val gym = KindCapabilities.of("u_gym")
        assertEquals(50, gym.hunt?.alertRadiusM)
        assertEquals(listOf("focus", "trainer"), gym.filters)
        assertFalse(gym.baseline)
        assertEquals("broker", KindCapabilities.of("u_office_space").contacts?.role)
        assertEquals(listOf(QuestionScope.BOTH), QuestionScope.of("u_office_space"))
        assertEquals(QuestionScope.entries, QuestionScope.of("house"))
        assertTrue(DefaultQuestions.of("u_office_space").isEmpty())
        // The longest id first: `u_office_space` is not `u_office`'s; a custom id of its own bank is a custom id.
        assertEquals("u_office_space", Question.kindOf("q_u_office_space_0123abcd"))
        assertTrue(Question.isCustomId("q_u_office_space_0123abcd"))
        assertTrue(Question.isCustomId(Question.newCustomId({ false }, kind = "u_gym")))
        assertFalse(Question.isCustomId("q_U_gym_0123abcd"))
        assertEquals("house", Question.kindOf("q_0123abcd"))
        // Not added here: the defaults of a kind this build does not know.
        UserKinds.load(null)
        assertEquals(KindCapabilities.DEFAULT, KindCapabilities.of("u_gym"))
        assertEquals("house", DevKinds.of("u_gym", on = true).id)
        assertEquals("house", Question.kindOf("q_u_gym_0123abcd"))
    }

    @Test
    fun itsSpaceWordsAreNeutralButForTheHeadingAndTheAddButton() {
        UserKinds.load(stored("u_gym"))
        val words = KindWordKeys.of("u_gym")
        assertEquals(KindWordKeys.IDS, words.keys.toList())
        assertEquals("Gyms", UserKinds.text(checkNotNull(words["heading"])))
        assertEquals("New gym", UserKinds.text(checkNotNull(words["add"])))
        for (id in KindWordKeys.IDS) {
            when {
                KindWordKeys.HOUSE[id] == null -> assertNull(id, words[id])
                id == "heading" || id == "add" -> assertTrue(id, UserKinds.isTextKey(words[id]))
                else -> assertEquals(id, "ukind_$id", words[id])
            }
        }
        assertEquals(UserKinds.NEUTRAL_KEYS.toSet(), words.values.filterNotNull().filter { !UserKinds.isTextKey(it) }.toSet())
    }

    @Test
    fun searchReadsEveryTypedExtraInThePersonsWords() {
        UserKinds.load(stored("u_gym"))
        val fields = DevKinds.of("u_gym", on = true).fields
        assertEquals(checked("u_gym").searched, fields.search)
        val focus = fields.extras.first { it.id == "focus" }
        val option = focus.options.first()
        val extras = JsonObject(mapOf("focus" to JsonPrimitive(option.id), "hours" to JsonPrimitive("6 to 10"), "trainer" to JsonPrimitive(true)))
        val words = KindValues.searchWords(fields, extras, "hi")
        assertTrue(words.toString(), UserKinds.text(option.label) in words)
        assertTrue(words.toString(), "6 to 10" in words)
        assertTrue(words.toString(), UserKinds.text(fields.label(fields.extras.first { it.id == "trainer" })) in words)
    }

    @Test
    fun noAiReadsItsSpace() {
        UserKinds.load(stored("u_gym"))
        val available = listOf("house", "school", "u_gym")
        assertFalse(SpaceScope.aiShownIn("u_gym", available, on = true, ownKey = true, serverKinds = listOf("u_gym")))
        assertTrue(SpaceScope.aiShownIn("house", available, on = true, ownKey = true))
    }

    @Test
    fun theSettingOffersAddedKindsOnlyWhileKindsYouAddIsOn() {
        val available = listOf("house", "school", "u_gym")
        val off = KindSetting(available)
        assertEquals(listOf("house", "school"), off.offered)
        assertFalse("the Experimental switch is not for the kinds a person adds", off.hasUnreleased)
        val on = off.withCustom(true)
        assertEquals("1", on.custom)
        assertEquals(available, on.offered)
        // Shown once turned on, the default may be it; turning the switch off again falls back to a kind still on, deleting nothing.
        val shown = on.withKindEnabled("u_gym", true).withDefault("u_gym")
        assertEquals(listOf("house", "u_gym"), shown.enabled)
        assertEquals("u_gym", shown.default)
        val hidden = shown.withCustom(false)
        assertEquals(listOf("house"), hidden.enabled)
        assertEquals("house", hidden.default)
        assertEquals("u_gym", hidden.withCustom(true).default)
        // The Experimental switch does not offer it.
        assertEquals(listOf("house", "school"), off.withExperimental(true).offered)
    }

    @Test
    fun theStoresListAddsReplacesRemovesAndAdoptsCheckedFiles() {
        val gym = checked("u_gym")
        val (one, added) = UserKinds.withAdded(null, gym)
        assertEquals(UserKinds.Added.ADDED, added)
        assertEquals(listOf("u_gym"), UserKinds.storedList(one).map { UserKinds.idOf(it) })
        val renamed = checked("u_gym_two").let { it.copy(id = "u_gym", json = JsonObject(it.json + ("id" to JsonPrimitive("u_gym")))) }
        val (same, replaced) = UserKinds.withAdded(one, renamed)
        assertEquals(UserKinds.Added.REPLACED, replaced)
        assertEquals(1, UserKinds.storedList(same).size)
        assertNull(UserKinds.withRemoved(same, "u_gym"))
        // At most eight.
        var full: String? = null
        for (i in 1..UserKindFiles.MAX_KINDS) full = UserKinds.withAdded(full, gym.copy(id = "u_gym$i", json = JsonObject(gym.json + ("id" to JsonPrimitive("u_gym$i"))))).first
        assertEquals(UserKinds.Added.FULL, UserKinds.withAdded(full, checked("u_cafe")).second)
        // A backup's kinds: the ones not here are added, checked; one already here is kept; a hostile one is left out.
        val hostile = JsonObject(sample("u_cafe") + ("id" to JsonPrimitive("u_bad")) + ("style" to JsonPrimitive("https://tiles.example/style.json")))
        val (adopted, ids) = UserKinds.withAdopted(one, listOf(renamed.json, sample("u_cafe"), hostile))
        assertEquals(listOf("u_cafe"), ids)
        assertEquals(listOf("u_gym", "u_cafe"), UserKinds.storedList(adopted).map { UserKinds.idOf(it) })
        assertEquals(gym.json, UserKinds.storedList(adopted).first())
        // A stored file this version cannot read is kept in the store but not drawn.
        UserKinds.load(JsonArray(listOf(hostile, sample("u_cafe"))).toString())
        assertEquals(listOf("u_cafe"), UserKinds.ids())
    }

    @Test
    fun aBackupCarriesTheCheckedFilesAsSearchUserKindsAndOlderFilesAreByteForByteTheSame() {
        assertEquals(KindSettingRules.HOUSE_SEARCH, KindSettingRules.searchOf(listOf("house"), "house"))
        val files = listOf(sample("u_gym"))
        val search = Json.parseToJsonElement(KindSettingRules.searchOf(listOf("house", "u_gym"), "house", files)).jsonObject
        assertEquals(listOf("kinds", "defaultKind", "userKinds"), search.keys.toList())
        assertEquals(files, UserKinds.ofSearch(search))
        val house = Json.parseToJsonElement(KindSettingRules.searchOf(listOf("house"), "house", files)).jsonObject
        assertEquals(listOf("kind", "userKinds"), house.keys.toList())
        // The readers of before read the kinds and the default only: the same hint with or without the files.
        val root = JsonObject(mapOf("search" to search))
        assertEquals(listOf("house", "u_gym") to "house", BackupWire.spacesOf(root))
        assertTrue(UserKinds.ofSearch(JsonObject(mapOf("userKinds" to JsonPrimitive("x")))).isEmpty())
        assertEquals(UserKindFiles.MAX_KINDS, UserKinds.ofSearch(JsonObject(mapOf("userKinds" to JsonArray(List(20) { sample("u_cafe") })))).size)
        // A backup's hint never turns on a kind a person added: they are offered behind their own switch, off until turned on.
        assertEquals(listOf("house", "school") to "house", KindSettingRules.knownSpaces(listOf("house", "school", "u_gym"), "house"))
    }
}
