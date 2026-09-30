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

import kotlin.math.abs

/**
 * The Hunt mode reminder's rule (docs/11 5.16, design of slice 3c), vectors H1..H7: a PLANNED viewing with
 * `huntReminder` true offers Hunt mode at [fireAt] = `startsAt - leadMin` minutes, `leadMin` being this device's
 * `hunt.reminderMin` (one of [LEAD_CHOICES]). Only the phones have Hunt mode, so the web has no twin.
 *
 * A viewing's own reminder ([ViewingReminders]) and its Hunt reminder due within [MERGE_MS] of each other are one
 * notification at the earlier time carrying both actions ([merged], [Kind.BOTH]). As for [ViewingReminders], only a
 * reminder still ahead is scheduled, the platforms schedule [merged] and nothing else, and the text is worked out when
 * it is shown.
 */
object HuntReminders {
    /** The lead-time choices, minutes before the start. */
    val LEAD_CHOICES: List<Int> = listOf(5, 10, 15, 20, 30, 45, 60)
    const val DEFAULT_LEAD = 15

    /** Two reminders of one viewing this close are one notification: the length of the inexact window (5.16). */
    const val MERGE_MS = ViewingReminders.WINDOW_MS

    /** [value] when it is one of [LEAD_CHOICES], otherwise [DEFAULT_LEAD] (a missing or foreign stored value). */
    fun validLead(value: Int?): Int = value?.takeIf { it in LEAD_CHOICES } ?: DEFAULT_LEAD

    /** When [viewing]'s Hunt reminder is due, epoch ms; null when it has none (not PLANNED, or `huntReminder` false). */
    fun fireAt(viewing: Viewing, leadMin: Int): Long? =
        if (viewing.viewingStatus == ViewingStatus.PLANNED && viewing.huntReminder) {
            viewing.startsAt - validLead(leadMin) * 60_000L
        } else {
            null
        }

    /**
     * The Hunt reminders to schedule at [nowMs]: (viewing, fireAt) whose fireAt is after [nowMs], earliest first, ties
     * by id, at most [limit]; as [ViewingReminders.upcoming].
     */
    fun upcoming(viewings: List<Viewing>, leadMin: Int, nowMs: Long, limit: Int = ViewingReminders.LIMIT): List<Pair<Viewing, Long>> =
        viewings.mapNotNull { v -> fireAt(v, leadMin)?.takeIf { it > nowMs }?.let { v to it } }
            .sortedWith(compareBy<Pair<Viewing, Long>> { it.second }.thenBy { it.first.id })
            .take(limit.coerceAtLeast(0))

    /** Which reminder a notification is: the viewing's own, the Hunt mode offer, or both in one. */
    enum class Kind { VIEWING, HUNT, BOTH }

    /** One notification to schedule: [viewing]'s, of [kind], at [at] (epoch ms). */
    data class Reminder(val viewing: Viewing, val kind: Kind, val at: Long)

    /**
     * [viewing]'s reminders as notifications, ignoring the clock: its own reminder (when [viewingReminders] is on and it
     * has one) and its Hunt reminder (when [huntReminders] is on and it has one), joined into one [Kind.BOTH] at the
     * earlier time when they are within [MERGE_MS] of each other (H5, H6).
     */
    fun of(viewing: Viewing, leadMin: Int, viewingReminders: Boolean = true, huntReminders: Boolean = true): List<Reminder> {
        val own = if (viewingReminders) ViewingReminders.fireAt(viewing) else null
        val hunt = if (huntReminders) fireAt(viewing, leadMin) else null
        return when {
            own != null && hunt != null && abs(own - hunt) <= MERGE_MS -> listOf(Reminder(viewing, Kind.BOTH, minOf(own, hunt)))
            else -> listOfNotNull(own?.let { Reminder(viewing, Kind.VIEWING, it) }, hunt?.let { Reminder(viewing, Kind.HUNT, it) })
        }
    }

    /**
     * Every notification to schedule at [nowMs] over both kinds: [of] for each viewing, merged first and then kept only
     * when still ahead (a merged one whose time has passed was already sent, so its later half is not sent again),
     * earliest first, ties by viewing id then kind, at most [limit] together (H7: the iOS 64 holds both kinds).
     */
    fun merged(
        viewings: List<Viewing>,
        leadMin: Int,
        nowMs: Long,
        viewingReminders: Boolean = true,
        huntReminders: Boolean = true,
        limit: Int = ViewingReminders.LIMIT,
    ): List<Reminder> =
        viewings.flatMap { of(it, leadMin, viewingReminders, huntReminders) }
            .filter { it.at > nowMs }
            .sortedWith(compareBy<Reminder> { it.at }.thenBy { it.viewing.id }.thenBy { it.kind.ordinal })
            .take(limit.coerceAtLeast(0))

    /**
     * True when a Hunt reminder that went off at [nowMs] should still be shown for [viewing] as it is stored now: it has
     * one, the alarm is at most the window (and a minute's slack) early for the time it is due at ([withViewing]: the
     * merged time, which may be the viewing reminder's, earlier), and the viewing has not started.
     */
    fun showNow(viewing: Viewing, leadMin: Int, nowMs: Long, withViewing: Boolean = false): Boolean {
        val hunt = fireAt(viewing, leadMin) ?: return false
        val own = if (withViewing) ViewingReminders.fireAt(viewing) else null
        val at = if (own != null && abs(own - hunt) <= MERGE_MS) minOf(own, hunt) else hunt
        return nowMs >= at - ViewingReminders.WINDOW_MS - 60_000L && nowMs < viewing.startsAt
    }

    /** True when [viewing]'s two reminders are one notification now ([Kind.BOTH]) with both settings on. */
    fun isMerged(viewing: Viewing, leadMin: Int): Boolean = of(viewing, leadMin).singleOrNull()?.kind == Kind.BOTH
}
