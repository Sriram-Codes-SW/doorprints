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
import app.doorprints.shared.model.Checklist
import app.doorprints.shared.model.Criterion
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.RoomType
import app.doorprints.shared.model.AnswerStatus
import app.doorprints.shared.model.QuestionCategory
import app.doorprints.shared.model.QuestionScope
import app.doorprints.shared.model.ViewingGroup
import app.doorprints.shared.model.ViewingKind
import app.doorprints.shared.model.ViewingStatus
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/*
 * The UI's names for the shared enums and checklist keys, as Compose resources (CMP-4 P4c, for Compare; every screen's
 * since CMP-6 P6a). `:app`'s `data/ModelLabels.kt` keeps an Android-resource version of the status labels
 * (`HouseStatus.labelRes`) for the Hunt notification, with the same text (`StringParityTest`).
 */

/** Translated label of a status (`status_*`). */
val HouseStatus.labelResource: StringResource
    get() = when (this) {
        HouseStatus.NEW -> Res.string.status_NEW
        HouseStatus.SHORTLISTED -> Res.string.status_SHORTLISTED
        HouseStatus.REJECTED -> Res.string.status_REJECTED
    }

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
 * The status glyph shown before the status text (UX-002): ● New, ★ Shortlisted, ✕ Rejected, so the status never rests
 * on colour alone. Decorative: every place that draws it keeps it out of TalkBack's speech, which reads the text.
 * Common since CMP-4 P4c (was `:app`'s `data/ModelLabels.kt`).
 */
val HouseStatus.glyph: String
    get() = when (this) {
        HouseStatus.NEW -> "●"
        HouseStatus.SHORTLISTED -> "★"
        HouseStatus.REJECTED -> "✕"
    }

/**
 * Translated labels (`check_*`) for the shared [Checklist] keys, in display order. Keys are language-neutral and shared
 * with the API and the web app, like the web's check.water keys.
 */
object ChecklistResources {
    val items: Map<String, StringResource> = Checklist.keys.associateWith { key ->
        when (key) {
            "water" -> Res.string.check_water
            "power" -> Res.string.check_power
            "parking" -> Res.string.check_parking
            "sunlight" -> Res.string.check_sunlight
            "ventilation" -> Res.string.check_ventilation
            "noise" -> Res.string.check_noise
            "security" -> Res.string.check_security
            "maintenance" -> Res.string.check_maintenance
            "neighbourhood" -> Res.string.check_neighbourhood
            "commute" -> Res.string.check_commute
            // A key added to :shared must get a translated label here first (ModelLabelsTest checks this).
            else -> error("No label for checklist key '$key'")
        }
    }
}

/** The weight names (slice 2), 0 Ignore .. 3 High, as the Criteria screen offers them. */
val CriterionWeights: List<StringResource> = listOf(
    Res.string.criteria_weight_0, Res.string.criteria_weight_1, Res.string.criteria_weight_2, Res.string.criteria_weight_3,
)

/** A criterion's name on screen: a built-in's translated name, a custom one's own label (else its key). */
@Composable
fun Criterion.displayName(): String = ChecklistResources.items[key]?.let { stringResource(it) } ?: label ?: key

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
