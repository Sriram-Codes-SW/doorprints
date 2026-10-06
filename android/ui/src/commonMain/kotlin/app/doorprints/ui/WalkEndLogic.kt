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

import app.doorprints.data.HouseEntity
import app.doorprints.data.Repository
import app.doorprints.data.SaveWalkResult
import app.doorprints.data.TrackPointEntity
import app.doorprints.location.HuntEngine
import app.doorprints.shared.location.Geo
import app.doorprints.shared.model.HouseSearch
import app.doorprints.shared.model.LocationSource
import app.doorprints.shared.trace.TraceConstants
import app.doorprints.shared.trace.TracePoint
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * The walk the *Save this walk?* sheet asks about (docs/11 5.27.6): its [points] (oldest first), the distance along them in
 * whole metres and the minutes from the first to the last point.
 */
class WalkSummary(val walkId: Long, val points: List<TracePoint>) {
    val distanceM: Int = lengthM(points)
    val minutes: Int = ((points.last().atMs - points.first().atMs) / 60_000.0).roundToInt()

    companion object {
        fun lengthM(points: List<TracePoint>): Int {
            var total = 0.0
            for (i in 1 until points.size) total += Geo.distanceM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
            return total.roundToInt()
        }
    }
}

/**
 * The walk to ask about, or null (docs/11 5.27.6): the newest walk id in the 30-day trace that is not the live walk
 * [liveWalkId], is above `walkAskedUpTo`, and has at least 5 points and 100 m (`Repository.lastEndedWalk`), with its points.
 */
suspend fun walkToAskAbout(repo: Repository, liveWalkId: Long): WalkSummary? {
    val id = repo.lastEndedWalk(liveWalkId) ?: return null
    val points = repo.trackPoints.first().filter { it.walkId == id }.map { it.toTracePoint() }
    return if (points.size >= 2) WalkSummary(id, points) else null
}

internal fun TrackPointEntity.toTracePoint() = TracePoint(lat, lon, at, walkId)

/** One row of the house picker: [house], its distance from the walk in whole metres, and whether it is the nearest where the walk stopped. */
class PickerRow(val house: HouseEntity, val distanceM: Int, val nearestToStop: Boolean)

/**
 * The house picker's rows (docs/11 5.27.6). [nearest] is the house closest to where the walk stopped (its last point), only
 * among houses whose location is not approximate and only when within [alertRadiusM] (Settings > Hunt mode's radius): it is
 * preselected. [near] are the houses within [HuntEngine.NEAREST_SHOWN_M] of any point of the walk, by their smallest
 * distance, each with it; the nearest is not repeated there.
 */
class WalkPicker(val nearest: PickerRow?, val near: List<PickerRow>) {
    companion object {
        fun of(points: List<TracePoint>, houses: List<HouseEntity>, alertRadiusM: Int): WalkPicker {
            if (points.isEmpty()) return WalkPicker(null, emptyList())
            val last = points.last()
            val stop = houses.filter { it.locationSource != LocationSource.APPROX }
                .minByOrNull { Geo.distanceM(last.lat, last.lon, it.lat, it.lon) }
            val stopDistance = stop?.let { Geo.distanceM(last.lat, last.lon, it.lat, it.lon) }
            val nearest = if (stop != null && stopDistance != null && stopDistance <= alertRadiusM) {
                PickerRow(stop, stopDistance.roundToInt(), nearestToStop = true)
            } else {
                null
            }
            val near = houses.filter { it.id != nearest?.house?.id }.mapNotNull { h ->
                val d = points.minOf { Geo.distanceM(it.lat, it.lon, h.lat, h.lon) }
                if (d <= HuntEngine.NEAREST_SHOWN_M) PickerRow(h, d.roundToInt(), nearestToStop = false) else null
            }.sortedBy { it.distanceM }
            return WalkPicker(nearest, near)
        }

        /** The houses the picker's search box finds among [houses] (the list's `HouseSearch.matches`); none for a blank query. */
        fun search(query: String, houses: List<HouseEntity>, language: String): List<HouseEntity> {
            if (query.isBlank()) return emptyList()
            return houses.filter {
                HouseSearch.matches(
                    query,
                    HouseSearch.fields(
                        it.label, it.address, it.street, it.locality, it.notes, it.contactName,
                        rooms = it.rooms, answers = it.answers, moveIn = it.moveIn, floor = it.floor, language = language,
                    ),
                )
            }
        }
    }
}

/** What happened to the answer of the sheet. */
sealed interface WalkAnswer {
    /** The walk is dealt with: the sheet closes. */
    data object Done : WalkAnswer

    /** A save was refused for [reason] and changed nothing; the sheet says why. */
    data class Refused(val reason: SaveWalkResult) : WalkAnswer
}

/**
 * The answers of *Save this walk?* (docs/11 5.27.6). Every handled walk moves `walkAskedUpTo` to its id, so each walk is
 * asked once: *Keep for 30 days* (also what closing the sheet means) writes nothing else; *Delete this walk* removes the
 * walk's points from the trace; *Save with a house* moves them into a saved walk, and a refusal (too long, the house or the
 * device is full) changes nothing and leaves the walk to be asked again only when the refusal is the walk's own
 * ([SaveWalkResult.TooLong]: it stays 30 days) .
 */
object WalkEndAnswers {
    suspend fun keep(repo: Repository, walkId: Long) {
        repo.settings.saveWalkAskedUpTo(walkId)
    }

    suspend fun delete(repo: Repository, walkId: Long) {
        repo.deleteTraceWalk(walkId)
        repo.settings.saveWalkAskedUpTo(walkId)
    }

    suspend fun save(repo: Repository, walkId: Long, houseId: String): WalkAnswer {
        val result = repo.saveWalk(houseId, walkId)
        return when (result) {
            is SaveWalkResult.Saved -> {
                repo.settings.saveWalkAskedUpTo(walkId)
                WalkAnswer.Done
            }
            // The walk itself cannot be saved: it stays in the 30-day trace and is not asked about again.
            SaveWalkResult.TooLong, SaveWalkResult.NoSuchWalk -> {
                repo.settings.saveWalkAskedUpTo(walkId)
                WalkAnswer.Refused(result)
            }
            // Another house may take it: the sheet stays open on the picker with the sentence.
            SaveWalkResult.HouseFull, SaveWalkResult.DeviceFull, SaveWalkResult.NoSuchHouse -> WalkAnswer.Refused(result)
        }
    }
}

/** Whole metres below a kilometre ("350 m"), otherwise kilometres with one decimal ("1.2 km"), in the formats given. */
fun formatDistanceM(metres: Int, metreFormat: String, kilometreFormat: String): String =
    if (metres < 1000) formatPositional(metreFormat, metres) else formatPositional(kilometreFormat, Formats.oneDecimal(metres / 1000.0))

/** The limit that applies to a refusal, for the sentence (5 000 points, 20 walks a house, 200 a device). */
fun refusalLimit(reason: SaveWalkResult): Int = when (reason) {
    SaveWalkResult.TooLong -> TraceConstants.MAX_WALK_POINTS
    SaveWalkResult.HouseFull -> TraceConstants.MAX_WALKS_PER_HOUSE
    else -> TraceConstants.MAX_SAVED_WALKS
}
