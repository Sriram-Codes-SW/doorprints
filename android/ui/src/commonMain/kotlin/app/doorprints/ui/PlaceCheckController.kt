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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.doorprints.data.Repository
import app.doorprints.shared.trace.PlaceBand
import app.doorprints.shared.trace.PlaceCheck
import app.doorprints.shared.trace.PlaceCheckResult
import app.doorprints.shared.trace.PlaceRow
import app.doorprints.shared.trace.TraceConstants
import app.doorprints.shared.trace.TraceWalk
import app.doorprints.shared.trace.WalkSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max

/** Which of the three places the person asked about (docs/11 5.27.13). */
enum class PlaceKind { HERE, HOUSE, SPOT }

/** Why there is no answer to show. */
enum class PlaceFailure { TIMEOUT, APPROX_HOUSE, DENIED }

/** What the result sheet shows. Held in memory only: closing the sheet drops it (no saved state, no store, no history). */
sealed interface PlaceCheckState {
    val kind: PlaceKind

    /** *Finding your location...* for the *Here* source. */
    data class Locating(override val kind: PlaceKind) : PlaceCheckState

    data class Failed(override val kind: PlaceKind, val reason: PlaceFailure) : PlaceCheckState

    /** The answer: [result] over [walks] (the rows' [PlaceRow.walkIndex] indexes them) for the place ([lat], [lon]). */
    data class Answer(
        override val kind: PlaceKind,
        val lat: Double,
        val lon: Double,
        val result: PlaceCheckResult,
        val walks: List<TraceWalk>,
        /** The fix's reported accuracy, only for *Here*. */
        val accuracyM: Double? = null,
    ) : PlaceCheckState {
        val anySaved: Boolean get() = walks.any { it.source == WalkSource.SAVED }
    }
}

/**
 * Compares a place with the person's walks (docs/11 5.27.13). **This is the only caller of `PlaceCheck.check`**, and it is
 * reached only from [PlaceCheckController.start], which the buttons' handlers call (a source test holds both): no timer, no
 * arrival trigger, no background. It reads the stored walks (the 30-day trace and every saved walk) and writes nothing; for
 * [PlaceKind.HERE] the walk being recorded ([liveWalkId]) is left out, for a house or a spot it counts. [accuracyM] is the
 * fix's accuracy, only for *Here*.
 */
internal suspend fun runPlaceCheck(
    repo: Repository,
    kind: PlaceKind,
    lat: Double,
    lon: Double,
    accuracyM: Double?,
    liveWalkId: Long,
): PlaceCheckState.Answer {
    val all = withContext(Dispatchers.Default) { repo.walks() }
    val walks = if (kind == PlaceKind.HERE && liveWalkId != 0L) {
        all.filter { it.source == WalkSource.SAVED || it.points.none { p -> p.walkId == liveWalkId } }
    } else {
        all
    }
    val result = withContext(Dispatchers.Default) { PlaceCheck.check(lat, lon, walks, accuracyM) }
    return PlaceCheckState.Answer(kind, lat, lon, result, walks, accuracyM)
}

/**
 * The state of one *Have I been here?* question: started by a button, dropped by [close]. [locate] gives the *Here* fix (the
 * best of up to 15 s, [LocationSource.best]); [liveWalkId] the walk being recorded now.
 */
class PlaceCheckController(
    private val scope: CoroutineScope,
    private val repo: Repository,
    private val locate: suspend () -> PlaceFix?,
    private val liveWalkId: () -> Long,
) {
    var state by mutableStateOf<PlaceCheckState?>(null)
        private set

    private var job: Job? = null

    /**
     * Asks the question. [PlaceKind.HERE] needs no coordinates (it asks the phone once); a house or a spot brings its own.
     * [approximate]: the house has only an area, so nothing is compared.
     */
    fun start(kind: PlaceKind, lat: Double = 0.0, lon: Double = 0.0, approximate: Boolean = false) {
        job?.cancel()
        if (kind == PlaceKind.HOUSE && approximate) {
            state = PlaceCheckState.Failed(kind, PlaceFailure.APPROX_HOUSE)
            return
        }
        job = scope.launch {
            if (kind == PlaceKind.HERE) {
                state = PlaceCheckState.Locating(kind)
                val fix = locate()
                if (fix == null) {
                    state = PlaceCheckState.Failed(kind, PlaceFailure.TIMEOUT)
                    return@launch
                }
                state = runPlaceCheck(repo, kind, fix.lat, fix.lon, fix.accuracyM, liveWalkId())
            } else {
                state = runPlaceCheck(repo, kind, lat, lon, null, liveWalkId())
            }
        }
    }

    /** Location is refused for *Here*: the sheet says so, nothing is compared. */
    fun denied(kind: PlaceKind) {
        job?.cancel()
        state = PlaceCheckState.Failed(kind, PlaceFailure.DENIED)
    }

    /** Closing the sheet discards the result and stops a lookup in progress. */
    fun close() {
        job?.cancel()
        job = null
        state = null
    }
}

/**
 * What the headline says (docs/11 5.27.13): the distinct calendar days of the rows of [band], newest first, at most three
 * listed ([days], one time of each: its newest row's), [moreDays] more; and [distanceM], the largest distance among the
 * walks on the listed days rounded up to a whole metre, at least 1 ("within" is then true for every listed date). Null
 * when no row is of the [band].
 */
class HeadlineFacts(val distanceM: Int, val days: List<Long>, val moreDays: Int)

fun headlineFacts(rows: List<PlaceRow>, band: PlaceBand): HeadlineFacts? {
    val mine = rows.filter { it.band == band } // already newest first
    if (mine.isEmpty()) return null
    val byDay = LinkedHashMap<Long, MutableList<PlaceRow>>()
    for (r in mine) byDay.getOrPut(LocalClock.dayOf(r.atMs)) { mutableListOf() }.add(r)
    val listed = byDay.entries.take(MAX_LISTED_DAYS)
    val largest = listed.maxOf { (_, list) -> list.maxOf { it.distanceM } }
    return HeadlineFacts(
        distanceM = max(1, ceil(largest).toInt()),
        days = listed.map { (_, list) -> list.first().atMs },
        moreDays = byDay.size - listed.size,
    )
}

/** The rows under the headline: those of the headline's band, at most five, then the number left out. */
fun listedRows(rows: List<PlaceRow>, band: PlaceBand): Pair<List<PlaceRow>, Int> {
    val mine = rows.filter { it.band == band }
    return mine.take(MAX_LISTED_ROWS) to max(0, mine.size - MAX_LISTED_ROWS)
}

private const val MAX_LISTED_DAYS = 3
private const val MAX_LISTED_ROWS = 5

/** The tolerance as the sheet quotes it, in whole metres. */
val CHECK_TOLERANCE_M: Int get() = TraceConstants.TOLERANCE_M.toInt()
