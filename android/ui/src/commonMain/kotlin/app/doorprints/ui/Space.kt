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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.data.HouseEntity
import app.doorprints.data.Repository
import app.doorprints.shared.model.BuiltInKind
import app.doorprints.shared.model.DevKinds
import app.doorprints.shared.model.KindSetting
import app.doorprints.shared.model.KindWordKeys
import app.doorprints.shared.model.KindSettingRules
import app.doorprints.shared.model.SpaceView
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.StringResource

/**
 * The words of a space's screens that name the kind (kinds PR C, S4b-BL-274, S4b-BL-277 on the phone; docs/03 §18.12): the
 * Houses tab, the list's search, its count, empty and no-match lines, the map's *Save here* button and tips, Compare, the
 * Assistant and the copies. The house's are today's strings, so a house space is word for word what it was; a school's are
 * its own. The website's twin is `core/space-words.ts` and `space-tool-words.ts`. The ten words the kind file's `words` has
 * (the list, the map's region and tips, *Save here*) are read from its `android` keys (S4b-BL-278, [KindWordKeys]); the rest of
 * this table (Compare, the Assistant, the copies) is still the phone's own.
 */
@Immutable
class SpaceWords(
    /** The bottom bar's list tab and the list's heading: the kind's plural (`kind.many`). */
    val many: StringResource,
    val search: StringResource,
    val shown: StringResource,
    val empty: StringResource,
    val noMatch: StringResource,
    val addOnMap: StringResource,
    val saveHere: StringResource,
    val regionDesc: StringResource,
    val addTip: StringResource,
    val addTipA11y: StringResource,
    val addTipListing: StringResource,
    val addTipListingA11y: StringResource,
    val unnamed: StringResource,
    val compareHint: StringResource,
    val comparePickMore: StringResource,
    val compareChoose: StringResource,
    val compareEmpty: StringResource,
    val compareEmptyBody: StringResource,
    val compareFootnote: StringResource,
    val askLabel: StringResource,
    val askHint: StringResource,
    val askNotGrounded: StringResource,
    val askSources: StringResource,
    val planHint: StringResource,
    val exportEmpty: StringResource,
    val exportScope: StringResource,
    val exportScopeAll: StringResource,
    val exportIncludeRejected: StringResource,
    val exportPhotosShortlisted: StringResource,
    /**
     * The map's location notes (S4b-BL-301): *Save here* needs location (`saveNeedsLocation`), and the note under the Hunt card
     * (`locationOff`, `needsPrecise`) or, where there is none, over the map (`locationOffNoHunt`, `needsPreciseNoHunt`). Hunt mode
     * is the house space's, so a school space draws the no-Hunt pair; its Hunt pair is that pair, so no path of it can say Hunt mode.
     */
    val locationOff: StringResource,
    val needsPrecise: StringResource,
    val locationOffNoHunt: StringResource,
    val needsPreciseNoHunt: StringResource,
    val saveNeedsLocation: StringResource,
) {
    companion object {
        /** The Compose string a kind file's `words` id names on the phone (the file's `android` key, [KindWordKeys]). */
        private fun word(keys: Map<String, String?>, id: String): StringResource = kindStringResource(checkNotNull(keys[id]) { "no Android word for $id" })

        private val h = KindWordKeys.HOUSE
        private val s = KindWordKeys.SCHOOL

        val HOUSE = SpaceWords(
            many = word(h, "heading"), search = word(h, "search"), shown = word(h, "shown"),
            empty = word(h, "emptyBody"), noMatch = word(h, "noMatch"), addOnMap = word(h, "add"),
            saveHere = word(h, "create"), regionDesc = word(h, "regionLabel"), addTip = word(h, "addHint"),
            addTipA11y = word(h, "addHintShort"), addTipListing = Res.string.map_add_tip_listing,
            addTipListingA11y = Res.string.map_add_tip_listing_a11y, unnamed = Res.string.house_unnamed,
            compareHint = Res.string.compare_hint, comparePickMore = Res.string.compare_pick_more,
            compareChoose = Res.string.compare_choose, compareEmpty = Res.string.compare_empty,
            compareEmptyBody = Res.string.compare_empty_body, compareFootnote = Res.string.compare_footnote,
            askLabel = Res.string.ai_ask_label, askHint = Res.string.ai_ask_hint, askNotGrounded = Res.string.ai_not_grounded,
            askSources = Res.string.ai_sources, planHint = Res.string.ai_plan_hint, exportEmpty = Res.string.export_empty,
            exportScope = Res.string.export_scope, exportScopeAll = Res.string.export_scope_all,
            exportIncludeRejected = Res.string.export_include_rejected,
            exportPhotosShortlisted = Res.string.export_photos_shortlisted,
            locationOff = Res.string.map_location_off, needsPrecise = Res.string.map_needs_precise,
            locationOffNoHunt = Res.string.map_location_off_no_hunt, needsPreciseNoHunt = Res.string.map_needs_precise_no_hunt,
            saveNeedsLocation = Res.string.map_save_needs_location,
        )

        val SCHOOL = SpaceWords(
            many = word(s, "heading"), search = word(s, "search"), shown = word(s, "shown"),
            empty = word(s, "emptyBody"), noMatch = word(s, "noMatch"), addOnMap = word(s, "add"),
            saveHere = word(s, "create"), regionDesc = word(s, "regionLabel"), addTip = word(s, "addHint"),
            addTipA11y = word(s, "addHintShort"), addTipListing = Res.string.school_add_tip_listing,
            addTipListingA11y = Res.string.school_add_tip_listing_a11y, unnamed = Res.string.school_unnamed,
            compareHint = Res.string.school_compare_hint, comparePickMore = Res.string.school_compare_pick_more,
            compareChoose = Res.string.school_compare_choose, compareEmpty = Res.string.school_compare_empty,
            compareEmptyBody = Res.string.school_compare_empty_body, compareFootnote = Res.string.school_compare_footnote,
            askLabel = Res.string.school_ai_ask_label, askHint = Res.string.school_ai_ask_hint,
            askNotGrounded = Res.string.school_ai_not_grounded, askSources = Res.string.school_ai_sources,
            planHint = Res.string.school_ai_plan_hint, exportEmpty = Res.string.school_export_empty,
            exportScope = Res.string.school_export_scope, exportScopeAll = Res.string.school_export_scope_all,
            exportIncludeRejected = Res.string.school_export_include_rejected,
            exportPhotosShortlisted = Res.string.school_export_photos_shortlisted,
            locationOff = Res.string.school_map_location_off_no_hunt, needsPrecise = Res.string.school_map_needs_precise_no_hunt,
            locationOffNoHunt = Res.string.school_map_location_off_no_hunt, needsPreciseNoHunt = Res.string.school_map_needs_precise_no_hunt,
            saveNeedsLocation = Res.string.school_map_save_needs_location,
        )

        /** The words of [kind]; a kind with no table of its own reads the house's. */
        fun of(kind: String): SpaceWords = if (kind == "school") SCHOOL else HOUSE
    }
}

/**
 * The space on screen (kinds PR C, S4b-BL-274; docs/03 §18.12): which kind's candidates the list, the map, Compare, the
 * Assistant and the copies show, and what that kind has. A space is a filter over the one candidate store, never a table.
 * With one kind on ([multi] false) there is one space, the default's, which holds every candidate and is the app as it
 * always was; with two or more, [Root][DoorprintsRoot] opens on the chooser and the person's choice is [kind].
 */
@Immutable
class SpaceUi(val setting: KindSetting, val kind: String) {
    /** The scoping rules' view of this space (`SpaceScope`). */
    val view: SpaceView = setting.view(kind)

    /** Two or more kinds are on: the chooser, the header chip and the scoped tools. */
    val multi: Boolean get() = setting.spaces

    /** The kind's labels and extras, as the form draws them. */
    val built: BuiltInKind = DevKinds.of(kind, on = true)

    val words: SpaceWords = SpaceWords.of(built.id)

    /** The house words left on this kind's pages (kinds PR 16, phone half): titles, questions, the walk check, Areas and Places. */
    val pages: PageWords = PageWords.of(built.id)

    /** Hunt mode is the house space's (docs/03 §18.12): its card and its switch are not offered elsewhere. */
    val huntHere: Boolean get() = built.id == KindSettingRules.HOUSE

    /** The space is one the person turned off: it still opens, with a note, and nothing in it is hidden or deleted. */
    val hidden: Boolean get() = multi && kind !in setting.enabled

    /** Whether the kind shows the place [ref] of a candidate (`section.checklist`, `field.money`, ...): the house-only tabs and rows read this. */
    fun shows(ref: String): Boolean = built.labels.isVisible(ref)

    /** Whether the list can sort by score and price and filter by cost: a kind with a checklist and money (a school has neither). */
    val sortsByScoreAndPrice: Boolean get() = shows("section.checklist") && shows("field.money")

    companion object {
        /** The house alone: what the app is with one kind on, and the default of every screen shown outside the app (tests, previews). */
        val HOUSE = SpaceUi(KindSetting(), KindSettingRules.HOUSE)

        /** The space for [chosen] (the card the person tapped, if any): with one kind on it is the default; else the chosen kind when the build has it, else the default. */
        fun resolve(setting: KindSetting, chosen: String?): SpaceUi =
            SpaceUi(setting, if (!setting.spaces) setting.default else chosen?.takeIf { it in setting.available } ?: setting.default)
    }
}

/** The space on screen; the house alone unless [DoorprintsRoot] provides another. */
val LocalSpace = staticCompositionLocalOf { SpaceUi.HOUSE }

/**
 * The saved candidates of the space on screen, null until Room answers. With one kind on this is `repo.houses` itself (the
 * app as it always was); with two or more, the space's candidates only.
 */
@Composable
fun rememberSpaceHouses(repo: Repository): State<List<HouseEntity>?> {
    val view = LocalSpace.current.let { if (it.multi) it.view else null }
    // With two or more kinds the store reads the space's rows through the `kind` index (S4b-BL-303), not every row.
    val flow = remember(repo, view) { if (view == null) repo.houses else repo.housesOf(view) }
    return flow.collectAsStateWithLifecycle(initialValue = null)
}

/**
 * What this device tracks, null until the settings store answers. A build that ships the house alone (every release)
 * answers at once with no read, so the first frame is today's. The twin of the website's `kind-setting.ts`.
 */
@Composable
fun rememberKindSetting(repo: Repository): State<KindSetting?> {
    val store = repo.settings
    return if (store.availableKinds.size <= 1) {
        remember(store) { mutableStateOf<KindSetting?>(KindSetting(store.availableKinds)) }
    } else {
        produceState<KindSetting?>(initialValue = null, store) { store.kindSetting().collect { value = it } }
    }
}
