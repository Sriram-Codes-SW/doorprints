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

import app.doorprints.shared.model.Candidates
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
import app.doorprints.shared.model.KindCapabilities
import app.doorprints.shared.model.KindSetting
import app.doorprints.shared.model.KindWordKeys
import app.doorprints.shared.model.KindSettingRules
import app.doorprints.shared.model.SpaceScope
import app.doorprints.shared.model.SpaceView
import app.doorprints.shared.model.UserKinds
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
    /** The bottom bar's list tab and the list's heading: the kind's plural (`kind.many`): a kind a person added gives its own words (docs/03 ADR-39). */
    val many: UiWord,
    val search: StringResource,
    val shown: StringResource,
    val empty: StringResource,
    val noMatch: StringResource,
    /** The list's add button: a kind a person added gives its own *new* (docs/03 ADR-39). */
    val addOnMap: UiWord,
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
     * (`locationOff`, `needsPrecise`) or, where there is none (the iPhone), over the map (`locationOffNoHunt`, `needsPreciseNoHunt`). Hunt mode
     * is a capability of the kind (docs/03 section 18.20), so every space whose kind has it, the school's too, says *Hunt mode* in its Hunt pair.
     */
    val locationOff: StringResource,
    val needsPrecise: StringResource,
    val locationOffNoHunt: StringResource,
    val needsPreciseNoHunt: StringResource,
    val saveNeedsLocation: StringResource,
    /**
     * The walk check's *Save this walk?* sheet (kinds PR 31, docs/03 section 18.25): *Link it to a school to keep it*, its button, the picker's title,
     * search and empty line, and the line for a candidate that has the most saved walks.
     */
    val traceEndBody: StringResource,
    val traceEndSave: StringResource,
    val tracePickTitle: StringResource,
    val tracePickSearch: StringResource,
    val tracePickNone: StringResource,
    val traceSaveFull: StringResource,
    /** The iPhone's empty list and its note that the map is not there yet (docs/03 section 18.25). */
    val listEmptyIos: StringResource,
    val mapNotHere: StringResource,
) {
    companion object {
        /** The words of the kind whose file keys are [keys] (`KindWordKeys`): every word is the file's `android` key, none is this class's. */
        private fun from(keys: Map<String, String?>): SpaceWords {
            fun w(id: String): StringResource = kindStringResource(checkNotNull(keys[id]) { "no Android word for $id" })
            fun own(id: String): UiWord = kindWord(checkNotNull(keys[id]) { "no Android word for $id" })
            return SpaceWords(
                many = own("heading"), search = w("search"), shown = w("shown"), empty = w("emptyBody"), noMatch = w("noMatch"),
                addOnMap = own("add"), saveHere = w("create"), regionDesc = w("regionLabel"), addTip = w("addHint"),
                addTipA11y = w("addHintShort"), addTipListing = w("addHintListing"), addTipListingA11y = w("addHintListingShort"),
                unnamed = w("unnamed"), compareHint = w("compareHint"), comparePickMore = w("comparePickMore"),
                compareChoose = w("compareChoose"), compareEmpty = w("compareEmpty"), compareEmptyBody = w("compareEmptyBody"),
                compareFootnote = w("compareFootnote"), askLabel = w("askLabel"), askHint = w("askHint"),
                askNotGrounded = w("askNotGrounded"), askSources = w("askSources"), planHint = w("planHint"),
                exportEmpty = w("exportEmpty"), exportScope = w("exportScope"), exportScopeAll = w("exportScopeAll"),
                exportIncludeRejected = w("exportIncludeRejected"), exportPhotosShortlisted = w("exportPhotosShortlisted"),
                locationOff = w("locationOff"), needsPrecise = w("needsPrecise"), locationOffNoHunt = w("locationOffNoHunt"),
                needsPreciseNoHunt = w("needsPreciseNoHunt"), saveNeedsLocation = w("saveNeedsLocation"),
                traceEndBody = w("traceEndBody"), traceEndSave = w("traceEndSave"), tracePickTitle = w("tracePickTitle"),
                tracePickSearch = w("tracePickSearch"), tracePickNone = w("tracePickNone"), traceSaveFull = w("traceSaveFull"),
                listEmptyIos = w("listEmptyIos"), mapNotHere = w("mapNotHere"),
            )
        }

        private val BY_KIND: Map<String, SpaceWords> = KindWordKeys.KINDS.associateWith { from(KindWordKeys.of(it)) }

        val HOUSE = BY_KIND.getValue(KindWordKeys.KINDS.first())
        val SCHOOL = BY_KIND.getValue("school")

        /**
         * The words of [kind]; a kind this phone added from a kind file reads its own heading and add button and the neutral
         * `ukind_*` words elsewhere (docs/03 ADR-39, built anew each time: the person may replace its file); any other kind with
         * no entry of its own reads the house's.
         */
        fun of(kind: String): SpaceWords = BY_KIND[kind] ?: UserKinds.wordKeys(kind)?.let(::from) ?: HOUSE
    }
}

/**
 * The words of Hunt mode that name the kind (docs/03 section 18.20, kinds PR 26): the alerts, the street line, the walk's
 * notification and its channel, the rationale, and the two lines that say why a walk ended or could not start. Every word is
 * the kind file's `android` key ([KindWordKeys]); the house's are the strings Hunt mode always had, so a house walk is word for
 * word what it was. The lock-screen public text ("Doorprints alert") is not a kind's and stays the app's.
 */
@Immutable
class HuntWords(
    /** A saved place passed: "You have seen this one: %1$s". */
    val seen: StringResource,
    /** The street's counts: "Saved houses: %1$d, visits: %2$d", and with the first visit's date. */
    val streetText: StringResource,
    val streetTextSince: StringResource,
    /** The Hunt card's street line: "%1$s · Been here: houses %2$d, visits %3$d". */
    val streetSeen: StringResource,
    /** A stay at an unsaved place: "Are you at a house?". */
    val stayTitle: StringResource,
    /** Why notifications are asked for when Hunt mode is turned on. */
    val notifyRationale: StringResource,
    /** One line when the walk ended because the person left its space. */
    val leftSpace: StringResource,
    /** One line when a reminder's or an area's *Start Hunt mode* cannot start because the kind is off. */
    val needsKind: StringResource,
    /** The ongoing notification's text while the walk runs. */
    val ongoingText: StringResource,
    /** The name and description of the kind's alerts channel. */
    val channelAlerts: StringResource,
    val channelAlertsDesc: StringResource,
    /** The Hunt card's note when notifications are off: Hunt mode cannot tell you when you pass a saved place. */
    val notificationsOff: StringResource,
) {
    companion object {
        private fun from(keys: Map<String, String?>): HuntWords {
            fun w(id: String): StringResource = kindStringResource(checkNotNull(keys[id]) { "no Android word for $id" })
            return HuntWords(
                seen = w("huntSeen"), streetText = w("huntStreetText"), streetTextSince = w("huntStreetTextSince"),
                streetSeen = w("huntStreetSeen"), stayTitle = w("huntStayTitle"), notifyRationale = w("huntNotifyRationale"),
                leftSpace = w("huntLeftSpace"), needsKind = w("huntNeedsKind"), ongoingText = w("huntOngoingText"),
                channelAlerts = w("huntChannelAlerts"), channelAlertsDesc = w("huntChannelAlertsDesc"), notificationsOff = w("huntNotificationsOff"),
            )
        }

        private val BY_KIND: Map<String, HuntWords> = KindWordKeys.KINDS.associateWith { from(KindWordKeys.of(it)) }

        val HOUSE = BY_KIND.getValue(KindWordKeys.KINDS.first())
        val SCHOOL = BY_KIND.getValue("school")

        /** The Hunt words of [kind]; a kind a person added reads the neutral ones (docs/03 ADR-39); any other kind with no entry of its own reads the house's. */
        fun of(kind: String): HuntWords = BY_KIND[kind] ?: UserKinds.wordKeys(kind)?.let(::from) ?: HOUSE
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

    /** The Hunt mode words of this space's kind (docs/03 section 18.20). */
    val hunt: HuntWords = HuntWords.of(built.id)

    /** This space's kind has Hunt mode (its `capabilities.hunt`, docs/03 section 18.20): its card and its switch are offered here and in no space without it. */
    val huntHere: Boolean get() = HuntSpace.has(built.id)

    /**
     * The app-wide Settings texts (the alert distance, the GPS note, the server's counts, the AI notes, the privacy note) say "houses" in the
     * house app. Once more than the house is in use (two or more kinds on, or the default is another kind) they say "entries"
     * (kinds PR 31; docs/03 section 18.25), the way the backup's and the import's words do.
     */
    val neutral: Boolean get() = multi || !KindCapabilities.of(setting.default).baseline

    /** The space is one the person turned off: it still opens, with a note, and nothing in it is hidden or deleted. */
    val hidden: Boolean get() = multi && kind !in setting.enabled

    /**
     * The kind a new entry made on this space's screens is (docs/03 §18.18): this space's own, except in a space that is turned
     * off, which keeps its entries and takes no new one: that is the default kind's (`SpaceScope.createKind`), so nothing is
     * made that the lists the person chose would not show.
     */
    val creating: BuiltInKind get() = if (hidden) DevKinds.of(SpaceScope.createKind(kind, setting.enabled, setting.default), on = true) else built

    /** Whether the kind shows the place [ref] of a candidate (`section.checklist`, `field.money`, ...): the house-only tabs and rows read this. */
    fun shows(ref: String): Boolean = built.labels.isVisible(ref)

    /** Whether the list can sort by score and price and filter by cost: a kind with a checklist and money (a school has neither). */
    val sortsByScoreAndPrice: Boolean get() = shows("section.checklist") && shows("field.money")

    companion object {
        /** The house alone: what the app is with one kind on, and the default of every screen shown outside the app (tests, previews). */
        val HOUSE = SpaceUi(KindSetting(), Candidates.BASELINE_KIND)

        /** The space for [chosen] (the card the person tapped, if any): with one kind on it is the default; else the chosen kind when the build has it, else the default. */
        fun resolve(setting: KindSetting, chosen: String?): SpaceUi =
            SpaceUi(setting, if (!setting.spaces) setting.default else chosen?.takeIf { it in setting.available } ?: setting.default)
    }
}

/** An app-wide Settings text: [base] (the house app's) or its [neutral] reading once more than the house is in use ([SpaceUi.neutral]). */
@Composable
fun appText(base: StringResource, neutral: StringResource): StringResource = if (LocalSpace.current.neutral) neutral else base

/** Settings' privacy note: the phone's or, where there are no photos taken yet (the iPhone), the iPhone's, each in its neutral reading once more than the house is in use. */
@Composable
fun privacyNote(addPhotos: Boolean): StringResource =
    if (addPhotos) appText(Res.string.settings_privacy_note, Res.string.settings_privacy_note_any)
    else appText(Res.string.settings_privacy_note_ios, Res.string.settings_privacy_note_ios_any)

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
