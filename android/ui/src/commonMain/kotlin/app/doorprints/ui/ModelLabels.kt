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
import androidx.compose.runtime.staticCompositionLocalOf
import app.doorprints.shared.model.BuiltInKind
import app.doorprints.shared.model.Checklist
import app.doorprints.shared.model.Criterion
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.KindLabels
import app.doorprints.shared.model.RoomType
import app.doorprints.shared.model.AnswerStatus
import app.doorprints.shared.model.DefaultQuestions
import app.doorprints.shared.model.QuestionCategory
import app.doorprints.shared.model.QuestionScope
import app.doorprints.shared.model.ViewingGroup
import app.doorprints.shared.model.PhotoTags
import app.doorprints.shared.model.ViewingKind
import app.doorprints.shared.model.ViewingStatus
import app.doorprints.shared.model.CompareCriteria
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/*
 * The UI's names for the shared enums and checklist keys, as Compose resources (CMP-4 P4c, for Compare; every screen's
 * since CMP-6 P6a). `:app`'s `data/ModelLabels.kt` keeps an Android-resource version of the status labels
 * (`HouseStatus.labelRes`) for the Hunt notification, with the same text (`StringParityTest`).
 */

/**
 * The Compose string a kind label names (kinds PR 3, S4b-BL-207: [KindLabels] in `:shared` gives the key, as the kind
 * file does). A key that is not a string of `:ui` fails at once (`KindLabelsScreensTest` checks every one).
 */
fun kindStringResource(key: String): StringResource =
    Res.allStringResources[key] ?: error("No Compose string '$key' for a kind label")

/**
 * The house kind's string for a field (`field.<id>`, with the money [cadence] where it matters) or a section heading
 * (`section.<id>`), as the form and its sections read them (kinds PR 4, S4b-BL-255). A place the kind gives no text
 * fails at once: a screen asks only for the places it draws.
 */
fun kindField(id: String, cadence: String? = null): StringResource = KindLabels.HOUSE.fieldText(id, cadence)

fun kindSection(id: String): StringResource = KindLabels.HOUSE.sectionText(id)

/** A section's heading in the given kind's labels (the viewings card and list are headed by the kind's own, kinds PR 29). */
fun kindSection(labels: KindLabels, id: String): StringResource = labels.sectionText(id)

fun kindRef(ref: String): StringResource = KindLabels.HOUSE.refText(ref)

/** The same for any kind (the form draws the candidate's own kind since kinds PR 5, S4b-BL-209). */
fun KindLabels.fieldText(id: String, cadence: String? = null): StringResource =
    kindStringResource(checkNotNull(field(id, cadence)?.key) { "$kind has no text for field.$id" })

fun KindLabels.sectionText(id: String): StringResource =
    kindStringResource(checkNotNull(section(id)?.key) { "$kind has no text for section.$id" })

fun KindLabels.refText(ref: String): StringResource =
    kindStringResource(checkNotNull(resolve(ref)?.key) { "$kind has no text for $ref" })

/** A status's name in this kind's words (every kind labels the five fixed names): the screens that list statuses read it (S4b-BL-256). */
fun KindLabels.statusText(status: HouseStatus): StringResource = kindStringResource(status(status))

/**
 * The developer flag of the hidden schools kind (kinds PR 5, S4b-BL-209; `DevKinds` in `:shared`): false unless the app
 * provides true, which only an Android debug build made with `-Pdoorprints.devKinds=true` does (`BuildConfig.DEV_KINDS`);
 * the iPhone app never does. With it false nothing changes: no kind picker, a new candidate is a house.
 */
val LocalDevKinds = staticCompositionLocalOf { false }

/**
 * Whether a candidate of a kind other than the house is read as that kind (its fields, its search words, its copy): the developer
 * flag, or a second kind offered by the person's *Experimental: more than houses* switch (S4b-BL-212). [DoorprintsRoot] provides
 * it from the settings; with the switch off a build that lists the school reads every candidate as a house, as the house build does.
 * The developer's kind picker stays on [LocalDevKinds] alone. Read it with [kindsOn].
 */
val LocalKindsOn = staticCompositionLocalOf { false }

/** The flag the screens read kinds by: [LocalKindsOn] or [LocalDevKinds]. */
@Composable
fun kindsOn(): Boolean = LocalKindsOn.current || LocalDevKinds.current

private val STATUS_RESOURCES: Map<HouseStatus, StringResource> by lazy {
    HouseStatus.entries.associateWith { kindStringResource(KindLabels.HOUSE.status(it)) }
}

/** Translated label of a status (`status_*`): the house kind's, through the resolver (S4b-BL-207). */
val HouseStatus.labelResource: StringResource
    get() = STATUS_RESOURCES.getValue(this)

/** Translated name of a room type (`room_type_*`, slice 1c): a room's name when it has none of its own. */
val RoomType.labelResource: StringResource
    get() = when (this) {
        RoomType.BEDROOM -> Res.string.room_type_BEDROOM
        RoomType.HALL -> Res.string.room_type_HALL
        RoomType.KITCHEN -> Res.string.room_type_KITCHEN
        RoomType.BATHROOM -> Res.string.room_type_BATHROOM
        RoomType.BALCONY -> Res.string.room_type_BALCONY
        RoomType.POOJA -> Res.string.room_type_POOJA
        RoomType.STUDY -> Res.string.room_type_STUDY
        RoomType.UTILITY -> Res.string.room_type_UTILITY
        RoomType.STORE -> Res.string.room_type_STORE
        RoomType.OTHER -> Res.string.room_type_OTHER
    }

/** Translated name of a question's category (`question_category_*`, slice 3a): the Questions screen's groups. */
val QuestionCategory.labelResource: StringResource
    get() = when (this) {
        QuestionCategory.MONEY -> Res.string.question_category_MONEY
        QuestionCategory.WATER_POWER -> Res.string.question_category_WATER_POWER
        QuestionCategory.RULES -> Res.string.question_category_RULES
        QuestionCategory.BUILDING -> Res.string.question_category_BUILDING
        QuestionCategory.LEGAL -> Res.string.question_category_LEGAL
        QuestionCategory.OTHER -> Res.string.question_category_OTHER
    }

/** Rent, Buy or Both (`question_scope_*`, slice 3a): which houses a question is for. */
/**
 * The header of a question category in the space of [kind]: that kind's own word when its question file has one (the school's, kinds PR 29), else the
 * house's `question_category_*` string, so a house is word for word what it was and a category the kind does not word falls back to it.
 */
@Composable
fun QuestionCategory.label(kind: String): String =
    DefaultQuestions.categoryLabel(kind, this, uiLanguage()) ?: stringResource(labelResource)

val QuestionScope.labelResource: StringResource
    get() = when (this) {
        QuestionScope.RENT -> Res.string.question_scope_RENT
        QuestionScope.SALE -> Res.string.question_scope_SALE
        QuestionScope.BOTH -> Res.string.question_scope_BOTH
    }

/** Open, Answered or Skipped (`answer_status_*`, slice 3a). */
val AnswerStatus.labelResource: StringResource
    get() = when (this) {
        AnswerStatus.OPEN -> Res.string.answer_status_OPEN
        AnswerStatus.ANSWERED -> Res.string.answer_status_ANSWERED
        AnswerStatus.SKIPPED -> Res.string.answer_status_SKIPPED
    }

/**
 * The status glyph shown before the status text (UX-002): ● New, ★ Shortlisted, ✕ Rejected, ✓ Taken, ○ Not chosen
 * (slice 5), so the status never rests
 * on colour alone. Decorative: every place that draws it keeps it out of TalkBack's speech, which reads the text.
 * Common since CMP-4 P4c (was `:app`'s `data/ModelLabels.kt`).
 */
val HouseStatus.glyph: String
    get() = when (this) {
        HouseStatus.NEW -> "●"
        HouseStatus.SHORTLISTED -> "★"
        HouseStatus.REJECTED -> "✕"
        HouseStatus.TAKEN -> "✓"
        HouseStatus.NOT_CHOSEN -> "○"
    }

/**
 * The translated name of a photo tag (docs/11 5.7, slice 5): a fixed key's `tag_*` string, or a custom tag as typed.
 */
@Composable
fun photoTagLabel(tag: String): String = PHOTO_TAG_LABELS[tag]?.let { stringResource(it) } ?: tag

/** The fixed tags' names, in [PhotoTags.FIXED]'s order (`ModelLabelsTest` checks that every key has one). */
val PHOTO_TAG_LABELS: Map<String, StringResource> = mapOf(
    "EXTERIOR" to Res.string.tag_EXTERIOR,
    "ENTRANCE" to Res.string.tag_ENTRANCE,
    "KITCHEN_FITTINGS" to Res.string.tag_KITCHEN_FITTINGS,
    "BATHROOM_FITTINGS" to Res.string.tag_BATHROOM_FITTINGS,
    "DAMP" to Res.string.tag_DAMP,
    "CRACK" to Res.string.tag_CRACK,
    "LEAK" to Res.string.tag_LEAK,
    "VIEW" to Res.string.tag_VIEW,
    "WATER_TANK" to Res.string.tag_WATER_TANK,
    "METER" to Res.string.tag_METER,
    "PARKING" to Res.string.tag_PARKING,
    "LIFT" to Res.string.tag_LIFT,
    "GOOD_POINT" to Res.string.tag_GOOD_POINT,
    "PROBLEM" to Res.string.tag_PROBLEM,
    "MOVE_IN" to Res.string.tag_MOVE_IN,
)

/**
 * Translated labels (`check_*`) for the shared [Checklist] keys, in display order. Keys are language-neutral and shared
 * with the API and the web app, like the web's check.water keys.
 */
object ChecklistResources {
    val items: Map<String, StringResource> = Checklist.keys.associateWith { key ->
        // The house kind's label, through the resolver (S4b-BL-207). A key added to :shared must get a translated
        // label in the kind first (ModelLabelsTest and KindLabelsScreensTest check this).
        kindStringResource(KindLabels.HOUSE.check(key) ?: error("No label for checklist key '$key'"))
    }
}

/** The weight names (slice 2), 0 Ignore .. 3 High, as the Criteria screen offers them. */
val CriterionWeights: List<StringResource> = listOf(
    Res.string.criteria_weight_0, Res.string.criteria_weight_1, Res.string.criteria_weight_2, Res.string.criteria_weight_3,
)

/** A criterion's name on screen: a built-in's translated name, a custom one's own label (else its key). */
@Composable
fun Criterion.displayName(): String = ChecklistResources.items[key]?.let { stringResource(it) } ?: label ?: key

/**
 * A criterion's name in a kind's words (kinds PR 27, docs/03 §18.21): the house's as [displayName] (its strings are not
 * touched); another kind's through its own labels, the key when it has none.
 */
@Composable
fun Criterion.displayName(kind: BuiltInKind): String =
    if (CompareCriteria.usesPersonScoring(kind)) displayName() else kind.labels.check(key)?.let { stringResource(kindStringResource(it)) } ?: displayName()

/** First viewing, Second viewing, Follow-up (`viewings_kind_*`, slice 3b-1). */
val ViewingKind.labelResource: StringResource
    get() = when (this) {
        ViewingKind.FIRST -> Res.string.viewings_kind_FIRST
        ViewingKind.SECOND -> Res.string.viewings_kind_SECOND
        ViewingKind.FOLLOW_UP -> Res.string.viewings_kind_FOLLOW_UP
    }

/** Planned, Done, Cancelled (`viewings_status_*`, slice 3b-1). */
val ViewingStatus.labelResource: StringResource
    get() = when (this) {
        ViewingStatus.PLANNED -> Res.string.viewings_status_PLANNED
        ViewingStatus.DONE -> Res.string.viewings_status_DONE
        ViewingStatus.CANCELLED -> Res.string.viewings_status_CANCELLED
    }

/** The Viewings screen's groups (`viewings_group_*`): Upcoming, Missed?, Done, Cancelled. */
val ViewingGroup.labelResource: StringResource
    get() = when (this) {
        ViewingGroup.UPCOMING -> Res.string.viewings_group_upcoming
        ViewingGroup.MISSED -> Res.string.viewings_group_missed
        ViewingGroup.DONE -> Res.string.viewings_group_done
        ViewingGroup.CANCELLED -> Res.string.viewings_group_cancelled
    }

/** A reminder choice (`viewings_remind_*`): Off, 15 min, 30 min, 1 hour, 2 hours, 1 day before. */
fun remindResource(minutes: Int): StringResource = when (minutes) {
    0 -> Res.string.viewings_remind_0
    15 -> Res.string.viewings_remind_15
    30 -> Res.string.viewings_remind_30
    120 -> Res.string.viewings_remind_120
    1440 -> Res.string.viewings_remind_1440
    else -> Res.string.viewings_remind_60
}
