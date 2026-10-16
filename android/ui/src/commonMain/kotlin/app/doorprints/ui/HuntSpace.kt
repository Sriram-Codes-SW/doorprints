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

import app.doorprints.shared.model.KindCapabilities
import app.doorprints.shared.model.KindSetting
import app.doorprints.shared.model.KindSettingRules
import app.doorprints.shared.model.SpaceScope

/**
 * Hunt mode is a capability of a kind (docs/03 section 18.20, kinds PR 26): the kind file's `capabilities.hunt` says whether a
 * space has the Hunt card and switch, how near the alerts are, whether a stay offers to save the place, whether the street is
 * told and whether an area wake-up may start a walk of that kind. The walk belongs to the space it was started in, so it ends
 * when the person leaves that space (S4b-BL-304, generalised from the house space). One rule for Android and the iPhone (the
 * common root calls it with the platform's `stopHunt`); nothing here names a kind: it reads [KindCapabilities].
 */
object HuntSpace {
    /** What starts a walk from outside the Hunt card: a viewing's reminder (viewings are a capability), or an area wake-up. */
    enum class Starter { REMINDER, AREA }

    /** The Hunt capability of [kind], or null when the kind has none. */
    fun of(kind: String): KindCapabilities.Hunt? = KindCapabilities.of(kind).hunt

    /** Whether the space of [kind] has Hunt mode: its card and switch (the Map) and its walks. */
    fun has(kind: String): Boolean = of(kind) != null

    /**
     * True when the walk must stop now: the space on screen was [was], is [now] another, and a walk of [was] runs ([active],
     * [walkKind] the kind the live walk belongs to). A walk that starts while another space is on screen (a reminder's *Start
     * Hunt mode*, a start from the notification) is left alone: only the move out of the walk's own space stops one.
     */
    fun stopsOnLeaving(walkKind: String?, was: String, now: String, active: Boolean): Boolean =
        active && walkKind != null && walkKind == was && now != was

    private fun fits(kind: String, by: Starter): Boolean {
        val caps = KindCapabilities.of(kind)
        val hunt = caps.hunt ?: return false
        return when (by) {
            Starter.REMINDER -> caps.viewings != null
            Starter.AREA -> hunt.areas
        }
    }

    /**
     * The kind whose walk a reminder's or an area wake-up's *Start Hunt mode* starts (docs/03 section 18.20), or null when
     * none is on: an enabled kind with Hunt that fits the [by] (a reminder: one with viewings; an area: one with `areas`),
     * the default kind first, then the others in the order they are enabled. A kind that is off is never chosen, so a walk
     * and its *stay here?* alert never make an entry in a space that is off (docs/03 section 18.18).
     */
    fun startKind(setting: KindSetting, by: Starter): String? =
        (listOf(setting.default) + setting.enabled).firstOrNull { it in setting.enabled && fits(it, by) }

    /** Whether Hunt mode may start in [kind] now: the kind has Hunt and is turned on in *What you track*. */
    fun mayStart(setting: KindSetting, kind: String): Boolean = kind in setting.enabled && has(kind)

    /** The kind whose words say why [by] could not start a walk: the first built-in kind that could have (a viewing's is the first that has viewings). */
    fun wanted(by: Starter): String = KindSettingRules.BUILT_IN.firstOrNull { fits(it, by) } ?: KindSettingRules.BUILT_IN.first()

    /**
     * The space a Hunt alert's new entry is made in when the visit is not yet a saved entry (docs/03 section 18.18): the kind of
     * the walk's space ([walkKind], carried by the alert) while it is on, else the default kind, which is always on. The walk that
     * raised the alert may have outlived the setting (the alert was posted before the kind was turned off); the entry is then
     * made in a space the person sees, never in one that is off. An alert with no kind (one posted by an older build) is the
     * default kind's.
     */
    fun alertKind(setting: KindSetting, walkKind: String?): String =
        if (walkKind == null) setting.default else SpaceScope.createKind(walkKind, setting.enabled, setting.default)

    /** A kind id off the wire (a notification's extra), or null when it is not one of the app's own kinds. */
    fun knownKind(raw: String?): String? = raw?.takeIf { it in KindSettingRules.BUILT_IN }
}
