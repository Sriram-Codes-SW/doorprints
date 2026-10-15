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
import app.doorprints.shared.export.ExportOptions
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
 * its own. The website's twin is `core/space-words.ts` and `space-tool-words.ts`. Every word is read from the kind file's
 * `words` through its `android` key ([KindWordKeys]; S4b-BL-278 for the list and the map, S4b-BL-316 for the rest): the class
 * holds no table of strings of its own.
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
        /** The words of the kind whose file keys are [keys] (`KindWordKeys`): every word is the file's `android` key, none is this class's. */
        private fun from(keys: Map<String, String?>): SpaceWords {
            fun w(id: String): StringResource = kindStringResource(checkNotNull(keys[id]) { "no Android word for $id" })
            return SpaceWords(
                many = w("heading"), search = w("search"), shown = w("shown"), empty = w("emptyBody"), noMatch = w("noMatch"),
                addOnMap = w("add"), saveHere = w("create"), regionDesc = w("regionLabel"), addTip = w("addHint"),
                addTipA11y = w("addHintShort"), addTipListing = w("addHintListing"), addTipListingA11y = w("addHintListingShort"),
                unnamed = w("unnamed"), compareHint = w("compareHint"), comparePickMore = w("comparePickMore"),
                compareChoose = w("compareChoose"), compareEmpty = w("compareEmpty"), compareEmptyBody = w("compareEmptyBody"),
                compareFootnote = w("compareFootnote"), askLabel = w("askLabel"), askHint = w("askHint"),
                askNotGrounded = w("askNotGrounded"), askSources = w("askSources"), planHint = w("planHint"),
                exportEmpty = w("exportEmpty"), exportScope = w("exportScope"), exportScopeAll = w("exportScopeAll"),
                exportIncludeRejected = w("exportIncludeRejected"), exportPhotosShortlisted = w("exportPhotosShortlisted"),
                locationOff = w("locationOff"), needsPrecise = w("needsPrecise"), locationOffNoHunt = w("locationOffNoHunt"),
                needsPreciseNoHunt = w("needsPreciseNoHunt"), saveNeedsLocation = w("saveNeedsLocation"),
            )
        }

        val HOUSE = from(KindWordKeys.HOUSE)
        val SCHOOL = from(KindWordKeys.SCHOOL)

        /** The words of [kind]; a kind with no entry of its own reads the house's. */
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
 * What this device tracks, null until the settings store answers. A store that lists the house alone (a test's, an old build)
 * answers at once with no read; the shipped builds list the school too (compiled in, off), so they wait for the settings store
 * for a moment, one frame behind the splash. The twin of the website's `kind-setting.ts`.
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

/**
 * The default copy options with the kinds flag ([kindsOn], which [DoorprintsRoot] sets while the developer flag or the
 * person's *Experimental: more than houses* switch is on): a school is copied with its own fields only then, and as a house
 * otherwise, as in a build that lists the house alone. Android and the iPhone alike.
 */
@Composable
fun rememberKindsCopyOptions(exports: ExportServices): () -> ExportOptions {
    val defaults = exports.rememberDefaultOptions()
    val kindsOn = kindsOn()
    return remember(defaults, kindsOn) { { defaults().copy(devKinds = kindsOn) } }
}
