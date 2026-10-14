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

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.stringResource

/**
 * The Settings rows that belong to what a kind has (kinds PR C, S4b-BL-274; docs/03 §18.12): the brokers, the criteria
 * (the score's checklist), the questions to ask and the viewings with their reminders. A kind shows a row only when it has
 * the place the row is about (`field.brokerId`, `section.checklist`, `section.questions`, `section.viewings`), so the
 * house space shows all four, in the order and with the dividers it always had, and a school space shows none of them
 * (its kind file, `school.json`, has none of these places). Moved out of `SettingsScreen` unchanged for the house.
 */
@Composable
internal fun KindSettingsRows(
    onOpenBrokers: () -> Unit,
    onOpenCriteria: () -> Unit,
    onOpenQuestions: () -> Unit,
    onOpenViewings: () -> Unit,
) {
    val space = LocalSpace.current
    // The brokers you have met (docs/11 5.25, slice 1b), on every platform: it opens the list and each broker's page.
    if (space.shows("field.brokerId")) {
        HorizontalDivider()
        NavRow(
            stringResource(Res.string.settings_brokers), stringResource(Res.string.settings_brokers_hint), onOpenBrokers,
            Modifier.tourTarget(TourTargets.SETTINGS_BROKERS),
        )
    }
    // What counts in a house's score (docs/11 5.4, slice 2): weights, must-haves, your own criteria, the rating share.
    if (space.shows("section.checklist")) {
        HorizontalDivider()
        NavRow(
            stringResource(Res.string.settings_criteria), stringResource(Res.string.settings_criteria_hint), onOpenCriteria,
            Modifier.tourTarget(TourTargets.SETTINGS_CRITERIA),
        )
    }
    // The questions you ask at a viewing (docs/11 5.5, slice 3a): the bank the house form's *Questions to ask* draws on.
    if (space.shows("section.questions")) {
        HorizontalDivider()
        NavRow(stringResource(Res.string.settings_questions), stringResource(Res.string.settings_questions_hint), onOpenQuestions)
    }
    // The viewings planned and done (docs/11 5.8, slice 3b-1): the history, its filters and search, the form.
    if (space.shows("section.viewings")) {
        HorizontalDivider()
        NavRow(
            stringResource(Res.string.settings_viewings), stringResource(Res.string.settings_viewings_hint), onOpenViewings,
            Modifier.tourTarget(TourTargets.SETTINGS_VIEWINGS),
        )
        // Their reminders (slice 3b-2): the switch, and the on-time alarms note and button while they are not allowed.
        ViewingRemindersSection()
    }
}
