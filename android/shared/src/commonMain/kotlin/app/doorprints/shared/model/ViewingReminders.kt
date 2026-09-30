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

import kotlin.math.roundToLong

/**
 * The viewing reminders' rule (docs/11 5.8, slice 3b-2), one per stack: the web's `upcomingReminders` in
 * `web/src/app/shared/viewing-reminders.ts` says the same, with the same vectors R1..R6 in both stacks' tests.
 *
 * A viewing has a reminder at [fireAt] = `startsAt - remindMin` minutes when it is PLANNED and `remindMin` > 0. Only a
 * reminder still ahead is scheduled: one whose time has passed is never sent late or at once (a viewing planned close
 * to its start gets none). The platforms schedule [upcoming] and nothing else, and work the text out when it is shown.
 */
object ViewingReminders {
    /** At most this many are scheduled at once: iOS keeps 64 pending requests per app, Android has no need for more. */
    const val LIMIT = 60

    /**
     * The inexact alarm's window on Android (docs/11 5.16 *Scheduling*): `setWindow(fireAt - 10 min, 10 min)`, early and
     * never late, since a window under 10 minutes is clipped to 10 minutes for apps targeting Android 12+.
     */
    const val WINDOW_MS = 10 * 60_000L

    /** When [viewing]'s reminder is due, epoch ms; null when it has none (not PLANNED, or `remindMin` 0). */
    fun fireAt(viewing: Viewing): Long? =
        if (viewing.viewingStatus == ViewingStatus.PLANNED && viewing.remindMin > 0) {
            viewing.startsAt - viewing.remindMin * 60_000L
        } else {
            null
        }

    /**
     * The reminders to schedule at [nowMs]: (viewing, fireAt) for each viewing with a reminder whose fireAt is after
     * [nowMs], earliest first, ties by id, at most [limit].
     */
    fun upcoming(viewings: List<Viewing>, nowMs: Long, limit: Int = LIMIT): List<Pair<Viewing, Long>> =
        viewings.mapNotNull { v -> fireAt(v)?.takeIf { it > nowMs }?.let { v to it } }
            .sortedWith(compareBy<Pair<Viewing, Long>> { it.second }.thenBy { it.first.id })
            .take(limit.coerceAtLeast(0))

    /**
     * True when a reminder that went off at [nowMs] should still be shown for [viewing] as it is stored now: it has a
     * reminder, the window alarm may have come up to [WINDOW_MS] early (a minute's slack for the alarm itself), and the
     * viewing has not started. An alarm left behind by an edit, a cancel or a delete then shows nothing.
     */
    fun showNow(viewing: Viewing, nowMs: Long): Boolean {
        val at = fireAt(viewing) ?: return false
        return nowMs >= at - WINDOW_MS - 60_000L && nowMs < viewing.startsAt
    }

    /** The minutes from [nowMs] to [startsAt] for "in 25 min": rounded to the nearest minute, at least 1. */
    fun minutesUntil(startsAt: Long, nowMs: Long): Int =
        ((startsAt - nowMs) / 60_000.0).roundToLong().coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

    /** The start of the Android window alarm for [fireAt]: 10 minutes before it, or [nowMs] when that has passed. */
    fun windowStart(fireAt: Long, nowMs: Long): Long = maxOf(fireAt - WINDOW_MS, nowMs)
}
