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

import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.StringResource

/**
 * The ids a screen gives the control a tour step points at (`Modifier.tourTarget`, [TourRegistry]). Screens know only
 * these ids, never the tour.
 */
object TourTargets {
    const val MAP_SAVE = "map.save"
    const val MAP_HUNT = "map.hunt"
    const val MAP_CHECK = "map.check"
    const val MAP_OFFLINE = "map.offline"
    const val HOUSES_SEARCH = "houses.search"
    const val HOUSES_FIRST = "houses.first"
    const val COMPARE_PICKER = "compare.picker"
    const val COMPARE_TITLE = "compare.title"
    const val NAV_ASSISTANT = "nav.assistant"

    /** The bottom bar as a whole: the card stays above it (not a step's target). */
    const val NAV_BAR = "nav.bar"
    const val SETTINGS_LANGUAGE = "settings.language"
    const val SETTINGS_EXPORT = "settings.export"
    const val SETTINGS_IMPORT = "settings.import"
    const val SETTINGS_SHARE = "settings.share"
    const val SETTINGS_BACKUP = "settings.backup"
    const val SETTINGS_TRACE = "settings.trace"
    const val SETTINGS_DRIVE = "settings.drive"
    const val SETTINGS_SERVER = "settings.server"
    const val SETTINGS_AI = "settings.ai"
    const val SETTINGS_BROKERS = "settings.brokers"
    const val SETTINGS_CRITERIA = "settings.criteria"
    const val SETTINGS_VIEWINGS = "settings.viewings"
    const val SETTINGS_AREAS = "settings.areas"
    const val SETTINGS_LOCK = "settings.lock"
    const val SETTINGS_HELP = "settings.help"
}

/** What a platform or the person's setup must have for a step to make sense; a step with an unmet need is left out. */
enum class TourNeed { MAP, HUNT, OFFLINE_MAPS, ASSISTANT, COPIES, WEEKLY_BACKUP, GOOGLE_DRIVE }

/** What this phone has, as far as the tour is concerned ([PlatformFeatures], and two things only the running app knows). */
data class TourContext(val features: PlatformFeatures, val assistant: Boolean, val offlineMaps: Boolean) {
    fun has(need: TourNeed): Boolean = when (need) {
        TourNeed.MAP -> features.map
        TourNeed.HUNT -> features.huntMode
        TourNeed.OFFLINE_MAPS -> features.map && offlineMaps
        TourNeed.ASSISTANT -> assistant
        TourNeed.COPIES -> features.copiesAndImports
        TourNeed.WEEKLY_BACKUP -> features.weeklyBackup
        TourNeed.GOOGLE_DRIVE -> features.googleDrive
    }

    /** The tab the tour starts and ends on: the Map, or the Houses tab where the platform has no map. */
    val home: String get() = if (features.map) "map" else "houses"
}

/**
 * One stop of the tour: the tab it is on, what it points at, and what the person is asked to do there. [targets] are tried
 * in order and the first one on screen is highlighted; none on screen (an empty list, a hidden card) never stops the
 * tour, the card is then shown in the middle without a highlight. A [route] of null is the tour's home tab.
 */
data class TourStep(
    val id: String,
    val route: String?,
    val targets: List<String>,
    val title: StringResource,
    val body: StringResource,
    val action: StringResource,
    val needs: Set<TourNeed> = emptySet(),
)

/**
 * The tour in the order a house hunt goes: place a house, find it again, compare, then Settings from the top down (so the
 * screen only ever scrolls one way), then help. Every feature of the phone app has a step; the ones a platform does
 * not have are left out by [forPhone] (the iPhone has no weekly backup, a phone without a map has no map steps).
 */
object TourSteps {
    private const val MAP = "map"
    private const val HOUSES = "houses"
    private const val COMPARE = "compare"
    private const val SETTINGS = "settings"

    val ALL: List<TourStep> = listOf(
        TourStep("welcome", null, emptyList(), Res.string.tour_welcome_title, Res.string.tour_welcome_body, Res.string.tour_welcome_action),
        TourStep("add", MAP, listOf(TourTargets.MAP_SAVE), Res.string.tour_add_title, Res.string.tour_add_body, Res.string.tour_add_action, setOf(TourNeed.MAP)),
        TourStep("hunt", MAP, listOf(TourTargets.MAP_HUNT), Res.string.tour_hunt_title, Res.string.tour_hunt_body, Res.string.tour_hunt_action, setOf(TourNeed.MAP, TourNeed.HUNT)),
        TourStep("check", MAP, listOf(TourTargets.MAP_CHECK), Res.string.tour_check_title, Res.string.tour_check_body, Res.string.tour_check_action, setOf(TourNeed.MAP)),
        TourStep("offline", MAP, listOf(TourTargets.MAP_OFFLINE), Res.string.tour_offline_title, Res.string.tour_offline_body, Res.string.tour_offline_action, setOf(TourNeed.OFFLINE_MAPS)),
        TourStep("find", HOUSES, listOf(TourTargets.HOUSES_SEARCH), Res.string.tour_find_title, Res.string.tour_find_body, Res.string.tour_find_action),
        TourStep("house", HOUSES, listOf(TourTargets.HOUSES_FIRST, TourTargets.HOUSES_SEARCH), Res.string.tour_house_title, Res.string.tour_house_body, Res.string.tour_house_action),
        TourStep("compare", COMPARE, listOf(TourTargets.COMPARE_PICKER, TourTargets.COMPARE_TITLE), Res.string.tour_compare_title, Res.string.tour_compare_body, Res.string.tour_compare_action),
        TourStep("assistant", null, listOf(TourTargets.NAV_ASSISTANT), Res.string.tour_assistant_title, Res.string.tour_assistant_body, Res.string.tour_assistant_action, setOf(TourNeed.ASSISTANT)),
        TourStep("language", SETTINGS, listOf(TourTargets.SETTINGS_LANGUAGE), Res.string.tour_language_title, Res.string.tour_language_body, Res.string.tour_language_action),
        TourStep("save", SETTINGS, listOf(TourTargets.SETTINGS_EXPORT), Res.string.tour_save_title, Res.string.tour_save_body, Res.string.tour_save_action, setOf(TourNeed.COPIES)),
        TourStep("import", SETTINGS, listOf(TourTargets.SETTINGS_IMPORT), Res.string.tour_import_title, Res.string.tour_import_body, Res.string.tour_import_action, setOf(TourNeed.COPIES)),
        TourStep("share", SETTINGS, listOf(TourTargets.SETTINGS_SHARE), Res.string.tour_share_title, Res.string.tour_share_body, Res.string.tour_share_action, setOf(TourNeed.COPIES)),
        TourStep("backup", SETTINGS, listOf(TourTargets.SETTINGS_BACKUP), Res.string.tour_backup_title, Res.string.tour_backup_body, Res.string.tour_backup_action, setOf(TourNeed.WEEKLY_BACKUP)),
        TourStep("trace", SETTINGS, listOf(TourTargets.SETTINGS_TRACE), Res.string.tour_trace_title, Res.string.tour_trace_body, Res.string.tour_trace_action, setOf(TourNeed.HUNT)),
        TourStep("drive", SETTINGS, listOf(TourTargets.SETTINGS_DRIVE), Res.string.tour_drive_title, Res.string.tour_drive_body, Res.string.tour_drive_action, setOf(TourNeed.GOOGLE_DRIVE)),
        TourStep("server", SETTINGS, listOf(TourTargets.SETTINGS_SERVER), Res.string.tour_server_title, Res.string.tour_server_body, Res.string.tour_server_action),
        TourStep("ai", SETTINGS, listOf(TourTargets.SETTINGS_AI), Res.string.tour_ai_title, Res.string.tour_ai_body, Res.string.tour_ai_action),
        TourStep("brokers", SETTINGS, listOf(TourTargets.SETTINGS_BROKERS), Res.string.tour_brokers_title, Res.string.tour_brokers_body, Res.string.tour_brokers_action),
        TourStep("criteria", SETTINGS, listOf(TourTargets.SETTINGS_CRITERIA), Res.string.tour_criteria_title, Res.string.tour_criteria_body, Res.string.tour_criteria_action),
        TourStep("viewings", SETTINGS, listOf(TourTargets.SETTINGS_VIEWINGS), Res.string.tour_viewings_title, Res.string.tour_viewings_body, Res.string.tour_viewings_action),
        TourStep("areas", SETTINGS, listOf(TourTargets.SETTINGS_AREAS), Res.string.tour_areas_title, Res.string.tour_areas_body, Res.string.tour_areas_action),
        TourStep("lock", SETTINGS, listOf(TourTargets.SETTINGS_LOCK), Res.string.tour_lock_title, Res.string.tour_lock_body, Res.string.tour_lock_action),
        TourStep("help", SETTINGS, listOf(TourTargets.SETTINGS_HELP), Res.string.tour_help_title, Res.string.tour_help_body, Res.string.tour_help_action),
        TourStep("done", null, emptyList(), Res.string.tour_done_title, Res.string.tour_done_body, Res.string.tour_done_action),
    )

    /** The steps this phone shows, in order, each with its tab resolved ([TourContext.home] for a step on no tab of its own). */
    fun forPhone(context: TourContext): List<TourStep> =
        ALL.filter { step -> step.needs.all(context::has) }
            .map { step -> if (step.route == null) step.copy(route = context.home) else step }
}
