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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.Volatile

/**
 * The kinds a person added on this phone by importing a kind file (docs/03 ADR-39), the twin of the website's
 * `core/user-kinds/user-kinds.ts`: stored in the settings store (`kinds.user`, the website's `doorprints.kinds.user`), checked
 * again ([UserKindFiles]) every time they are read, and drawn as any other kind: the registries ([KindCapabilities.of],
 * [DevKinds], [KindWordKeys], [KindWords], the question scopes and ids) ask this object for a kind they do not have built in.
 *
 * - **Words**: the person's own, shown as written in every language, under keys `user:<id>:<ref>` that no Compose string has
 *   (`:ui` `UiWord` turns one into its text); a space's words that name the kind are the neutral `ukind_*` strings (in four
 *   languages, hi, ta and te under review), except the heading and the add button, which are the kind's own plural and *new*.
 * - **In the backup**: `search.userKinds` of a full backup; an import adds the ones this phone does not have, each checked as an
 *   imported file is. Not in the Drive sync file or on the server: their entries are (the `kind` id is kept by every reader),
 *   and a device without the kind shows them in its default space until the kind is added there.
 * - **No AI** reads a kind a person adds (its `ai` slots are empty by the profile): its space has no assistant and no listing fill.
 *
 * The registry is one immutable snapshot, replaced whole by [load] (the settings store calls it with the stored text whenever
 * it reads the kinds), so a reader on any thread sees one consistent set.
 */
object UserKinds {
    /** The prefix of the key of a person's own word: `user:<id>:<ref>`. No Compose string has a `:` in its name. */
    const val TEXT_PREFIX = "user:"

    /** The key of a person's word for [ref] (`kind.many`, `field.fee`, `option.focus.YOGA`) of kind [id]. */
    fun textKey(id: String, ref: String): String = "$TEXT_PREFIX$id:$ref"

    /** Whether [key] is the key of a person's own word rather than a Compose string's name. */
    fun isTextKey(key: String?): Boolean = key != null && key.startsWith(TEXT_PREFIX)

    /** What adding a checked file did. */
    enum class Added { ADDED, REPLACED, FULL }

    private class Entry(val file: UserKindFile, val capabilities: KindCapabilities, val kind: BuiltInKind, val words: Map<String, String?>)

    private class Snapshot(val stored: String?, val entries: Map<String, Entry>, val texts: Map<String, String>)

    @Volatile
    private var now = Snapshot(null, emptyMap(), emptyMap())

    /**
     * Reads the stored kinds again ([stored]: the settings store's JSON list, null for none) and replaces the registry, unless
     * it already holds exactly this text. A file this version cannot read is kept in the store but not drawn; at most
     * [UserKindFiles.MAX_KINDS] are drawn, the first of an id wins.
     */
    fun load(stored: String?) {
        if (stored == now.stored) return
        val entries = LinkedHashMap<String, Entry>()
        val texts = HashMap<String, String>()
        for (raw in storedList(stored)) {
            if (entries.size >= UserKindFiles.MAX_KINDS) break
            val read = recheck(raw) as? UserKindRead.Ok ?: continue
            if (read.file.id in entries) continue
            entries[read.file.id] = build(read.file, read.capabilities, texts)
        }
        now = Snapshot(stored, entries, texts)
    }

    /** The ids of the kinds this phone added, in the order they were added. */
    fun ids(): List<String> = now.entries.keys.toList()

    /** The added kind [id] as the screens draw a kind, or null. */
    fun kind(id: String?): BuiltInKind? = id?.let { now.entries[it]?.kind }

    /** The checked file of the added kind [id], or null. */
    fun file(id: String?): UserKindFile? = id?.let { now.entries[it]?.file }

    /** Every added kind's checked file, for the backup's `search.userKinds`. */
    fun files(): List<UserKindFile> = now.entries.values.map { it.file }

    /** The capabilities of the added kind [id] (its file's own block), or null. */
    fun capabilities(id: String?): KindCapabilities? = id?.let { now.entries[it]?.capabilities }

    /** A person's words for [key] (`user:<id>:<ref>`), or null when no added kind has it. */
    fun text(key: String): String? = now.texts[key]

    /** The Android keys of the space words of the added kind [id] ([KindWordKeys]), or null. */
    fun wordKeys(id: String?): Map<String, String?>? = id?.let { now.entries[it]?.words }

    /** A checked file as the screens read a kind: its labels and keys, its typed extras, and its words. */
    private fun build(file: UserKindFile, capabilities: KindCapabilities, texts: MutableMap<String, String>): Entry {
        val id = file.id
        val keys = LinkedHashMap<String, String?>()
        fun put(ref: String, text: String): String = textKey(id, ref).also { texts[it] = text }
        fun label(ref: String, text: String) {
            keys[ref] = put(ref, text)
        }
        label("kind.new", file.new)
        label("kind.many", file.many)
        label("kind.delete", file.delete)
        for ((status, text) in file.statuses) label("status.$status", text)
        for (s in file.sections) label("section.${s.id}", s.label)
        val sections = HashMap<String, String>()
        val hidden = HashSet<String>()
        file.sections.filter { it.visible == false }.forEach { hidden += "section.${it.id}" }
        for (c in file.core) {
            label("field.${c.id}", c.label)
            sections[c.id] = c.section
            if (c.visible == false) hidden += "field.${c.id}"
            c.cadences?.forEach { (cadence, text) -> label("cadence.$cadence", text) }
        }
        val extras = file.extras.map { e ->
            label("field.${e.id}", e.label)
            sections[e.id] = e.section
            if (e.visible == false) hidden += "field.${e.id}"
            KindExtra(
                id = e.id, type = e.type, section = e.section, order = e.order, integer = e.integer == true, min = e.min, max = e.max,
                unit = e.unit, cadences = e.cadences, cadenceFrom = e.cadenceFrom, forCadences = e.forCadences, maxLength = e.maxLength,
                options = e.options.map { (option, text) -> KindOption(option, put("option.${e.id}.$option", text)) }, better = e.better,
            )
        }
        for ((key, text) in file.checklist) label("check.$key", text)
        val labels = KindLabels(kind = id, keys = keys, byCadence = emptyMap(), sections = sections, hidden = hidden, capabilities = capabilities)
        val kind = BuiltInKind(labels, KindFields(labels, extras, file.searched), extracts = false)
        val words = KindWordKeys.IDS.associateWith { word ->
            when {
                KindWordKeys.HOUSE[word] == null -> null
                word == "heading" -> textKey(id, "kind.many")
                word == "add" -> textKey(id, "kind.new")
                else -> NEUTRAL_PREFIX + word
            }
        }
        return Entry(file, capabilities, kind, words)
    }

    /** The prefix of the neutral word of a space of a kind a person added (`ukind_emptyBody`): it names no house or school. */
    const val NEUTRAL_PREFIX = "ukind_"

    /** The neutral words' Compose names, one per word the phone shows (the house's word is not null). */
    val NEUTRAL_KEYS: List<String> get() = KindWordKeys.IDS.filter { KindWordKeys.HOUSE[it] != null && it != "heading" && it != "add" }.map { NEUTRAL_PREFIX + it }

    // ----- The store's JSON list, as text -----

    /** The stored list as it is (each item unchecked), or an empty list. */
    fun storedList(stored: String?): List<JsonElement> =
        stored?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() }.orEmpty()

    /** The `id` of a stored item, unchecked (a string or nothing). */
    fun idOf(raw: JsonElement): String? = ((raw as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Checks a stored or a backup's kind file again, from its own JSON text, as an imported file is. */
    fun recheck(raw: JsonElement): UserKindRead = UserKindFiles.read(raw.toString(), KindSettingRules.BUILT_IN)

    private fun textOf(list: List<JsonElement>): String? = if (list.isEmpty()) null else JsonArray(list).toString()

    /**
     * The stored text with the checked [file] added at the end (refused, [Added.FULL], when the phone has
     * [UserKindFiles.MAX_KINDS]), or put in place of the one with its id (the person confirmed it). Its entries are never touched.
     */
    fun withAdded(stored: String?, file: UserKindFile): Pair<String?, Added> {
        val list = storedList(stored)
        val at = list.indexOfFirst { idOf(it) == file.id }
        if (at < 0 && list.mapNotNull(::idOf).size >= UserKindFiles.MAX_KINDS) return stored to Added.FULL
        val next = list.toMutableList()
        if (at >= 0) next[at] = file.json else next += file.json
        return textOf(next) to if (at >= 0) Added.REPLACED else Added.ADDED
    }

    /** The stored text without the kind [id]: its file only. Its entries stay, in the default space, until it is added again. */
    fun withRemoved(stored: String?, id: String): String? = textOf(storedList(stored).filter { idOf(it) != id })

    /**
     * The stored text with the kinds of a backup ([raws], `search.userKinds`) this phone does not have yet, each checked as an
     * imported file is; one already here is kept as it is (never replaced by a file), one that does not pass is left out.
     * Returns the new text and the ids added.
     */
    fun withAdopted(stored: String?, raws: List<JsonElement>): Pair<String?, List<String>> {
        var text = stored
        val added = ArrayList<String>()
        for (raw in raws.take(UserKindFiles.MAX_KINDS)) {
            val read = recheck(raw) as? UserKindRead.Ok ?: continue
            if (storedList(text).any { idOf(it) == read.file.id }) continue
            val (next, result) = withAdded(text, read.file)
            if (result == Added.ADDED) {
                text = next
                added += read.file.id
            }
        }
        return text to added
    }

    /**
     * The kind files a backup's `search` carries (`userKinds`), unchecked: at most eight items of a list, else none. Each one is
     * checked as an imported kind file is before it is kept ([withAdopted]).
     */
    fun ofSearch(search: JsonElement?): List<JsonElement> =
        ((search as? JsonObject)?.get("userKinds") as? JsonArray)?.take(UserKindFiles.MAX_KINDS).orEmpty()
}
