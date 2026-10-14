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
import kotlinx.serialization.json.JsonPrimitive

/**
 * Which kinds this phone shows, and which one is the default (kinds PR C, S4b-BL-274; docs/03 §18.12), the twin of the
 * website's `core/kind-setting.ts`, held to `docs/schemas/kinds/search-kind-vectors.json` (`setting`). A kind is a
 * *space*: a filter over the one candidate store. The choice is this device's own (the settings store), never synced,
 * shared or put in a copy, and every doubt (a missing or broken value, a kind the build does not offer) falls back to
 * the house alone, which is the app a house hunter has always had.
 *
 * - **Available** kinds are what the build ships ([available]): a production build lists the house alone.
 * - **Released** kinds ([RELEASED]) need no switch; every other available kind is offered only while the person turns on
 *   *Experimental: more than houses*, which is shown only in a build that ships such a kind.
 * - **Enabled** kinds are the offered ones the person turned on (the house alone by default), and the **default** kind
 *   is the one a link, a notification or a shared listing is saved as.
 *
 * The three stored values are the settings store's text as it holds them ([experimental] `1`, [enabledText] a JSON list
 * of kind ids, [defaultText] a kind id); the setters give the new text to store, `null` removing the key.
 */
data class KindSetting(
    val available: List<String> = listOf(KindSettingRules.HOUSE),
    val experimental: String? = null,
    val enabledText: String? = null,
    val defaultText: String? = null,
) {
    /** Whether the person turned *Experimental: more than houses* on (`1`); off on any doubt. */
    val experimentalOn: Boolean get() = experimental == "1"

    /** The kinds the settings screen lists: the released ones, and the others only while the switch is on. */
    val offered: List<String> get() = available.filter { it in KindSettingRules.RELEASED || experimentalOn }

    /** The enabled kinds, in the build's order: the stored list kept to the offered kinds; none left means the house. */
    val enabled: List<String>
        get() {
            val ids = KindSettingRules.idsOf(enabledText) ?: listOf(KindSettingRules.HOUSE)
            return offered.filter { it in ids }.ifEmpty { listOf(KindSettingRules.HOUSE) }
        }

    /** The default kind: the stored one when it is enabled, else the first enabled kind. */
    val default: String get() = enabled.let { on -> defaultText?.takeIf { it in on } ?: on.first() }

    /** Whether the app has spaces: two or more kinds enabled. With one, the app is that kind's alone, on today's paths. */
    val spaces: Boolean get() = enabled.size > 1

    /** Whether this build ships a kind that needs the switch (what shows *Experimental: more than houses*). */
    val hasUnreleased: Boolean get() = available.any { it !in KindSettingRules.RELEASED }

    /** The settings with the switch on or off; off, the unreleased kinds leave the screens and their entries are kept. */
    fun withExperimental(on: Boolean): KindSetting = copy(experimental = if (on) "1" else null)

    /**
     * Kind [id] turned on or off. The last enabled kind cannot be turned off; turning off the default makes the next
     * enabled kind the default. A kind that is off keeps every entry.
     */
    fun withKindEnabled(id: String, on: Boolean): KindSetting {
        if (id !in offered) return this
        val now = enabled
        val next = offered.filter { if (it == id) on else it in now }
        if (next.isEmpty()) return this
        val stored = copy(enabledText = if (next == listOf(KindSettingRules.HOUSE)) null else KindSettingRules.listText(next))
        return if ((defaultText ?: KindSettingRules.HOUSE) in next) stored else stored.withDefault(next.first())
    }

    /** Kind [id] as the default, when it is enabled; the house as default is stored as no value. */
    fun withDefault(id: String): KindSetting =
        if (id !in enabled) this else copy(defaultText = if (id == KindSettingRules.HOUSE) null else id)

    /** The space on screen for kind [kind] (the current one), as the scoping rules read it. */
    fun view(kind: String): SpaceView = SpaceView(kind, spaces, default, available)

    /**
     * The space a candidate of kind [kind] (`null`: a house) belongs to: its own when the build has that kind and there are
     * spaces, else the default space (the website's `spaceOf`). A link to a candidate of another kind opens that space.
     */
    fun spaceOf(kind: String?): String = (kind ?: KindSettingRules.HOUSE).let { if (spaces && it in available) it else default }
}

/** The rules behind [KindSetting] and the backup's `search` object, apart from the stored values. */
object KindSettingRules {
    const val HOUSE = "house"

    /** The kinds that need no experimental switch: the owner opens a kind's release gate by adding it here (never before S4b-BL-268..271). */
    val RELEASED: List<String> = listOf(HOUSE)

    /** Every built-in kind, the house first. */
    val BUILT_IN: List<String> = listOf(HOUSE, "school")

    /** A kind id as door 3 allows it. */
    private val KIND_ID = Regex("^[a-z][a-z0-9_]{1,31}\$")

    /** The English plural of a built-in kind (its `kind.many`): the file name of a space's copy uses it, whatever the app's language. */
    fun pluralOf(kind: String): String? = when (kind) {
        HOUSE -> "Houses"
        "school" -> "Schools"
        else -> null
    }

    /** The kinds a build lists (a comma list, e.g. `house,school`): the house always, then the built-in kinds it names, in their own order. */
    fun availableOf(list: String?): List<String> {
        val listed = list.orEmpty().split(',').map { it.trim() }.toSet()
        return BUILT_IN.filter { it == HOUSE || it in listed }
    }

    /** The ids in a stored JSON list, or `null` when the text is absent, broken or not a list. */
    internal fun idsOf(text: String?): List<String>? {
        if (text == null) return null
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return null
        return parsed.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }

    /** A list of ids as JSON text, as `JSON.stringify` writes it on the website: no spaces. */
    internal fun listText(ids: List<String>): String = ids.joinToString(",", "[", "]") { "\"$it\"" }

    /**
     * The `search` object of a backup's `data.json` for the enabled [kinds] and the [default] (door 10, amended by
     * kinds PR 8): `{"kind":"house"}` for the house alone, byte for byte the file before; any other well-formed set
     * writes `{"kinds":[...],"defaultKind":"..."}`. A list that is not well formed (empty, an id outside door 3's pattern,
     * a repeated id, a default that is not in it) writes the house's. Every reader ignores it.
     */
    fun searchOf(kinds: List<String>, default: String): String {
        val ok = kinds.isNotEmpty() && kinds.all { KIND_ID.matches(it) } && kinds.toSet().size == kinds.size && default in kinds
        return if (!ok || kinds == listOf(HOUSE)) HOUSE_SEARCH else "{\"kinds\":${listText(kinds)},\"defaultKind\":\"$default\"}"
    }

    /** The house's `search` object. */
    const val HOUSE_SEARCH = "{\"kind\":\"house\"}"

    /**
     * The kinds and default a file's `search` names, as a hint for a phone that never chose: `null` for the house's
     * `{"kind":"house"}`, a first-version `{"kind":...}`, and anything not well formed.
     */
    fun spacesOf(kinds: List<String>?, default: String?): Pair<List<String>, String>? {
        if (kinds == null || default == null) return null
        return searchOf(kinds, default).takeIf { it != HOUSE_SEARCH }?.let { kinds to default }
    }
}
