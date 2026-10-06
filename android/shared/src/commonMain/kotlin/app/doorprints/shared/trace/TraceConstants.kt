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

package app.doorprints.shared.trace

/**
 * The constants of the path trace's shared contract (docs/11 5.27.2). The vector file
 * `docs/schemas/trace-repeat-vectors.json` carries most of them in its `constants` block and a test compares each, so
 * a drift between this table, the file and the website's `TRACE` object fails a build.
 */
object TraceConstants {
    /** The radius of `Geo.distanceM`, so a distance means the same everywhere. */
    const val EARTH_RADIUS_M = 6_371_000.0

    /** A gap of this much or more between two points is a new walk (as the Map's `TRACK_GAP_MS` was). */
    const val WALK_GAP_MS = 1_800_000L

    /** Sample spacing along a walk when it is matched. */
    const val DENSIFY_M = 10.0

    /** Two readings of one path are the same within this many metres (inclusive). */
    const val TOLERANCE_M = 25.0

    /** A run may be broken by one bad fix: a non-near series between two near samples at most this far apart is filled. */
    const val BRIDGE_M = 30.0

    /** A junction is not a repeat: a run shorter than this is not a repeated stretch. */
    const val MIN_RUN_M = 80.0

    /** The alert needs a clearer repeat than the line. */
    const val ALERT_MIN_RUN_M = 100.0

    /** Between two alerts. */
    const val ALERT_COOLDOWN_MS = 600_000L

    /** A stretch is repeated when this walk and at least one other different walk cover it. */
    const val REPEAT_MIN_WALKS = 2

    /** A walk is saved only up to this many points. */
    const val MAX_WALK_POINTS = 5_000

    /** The most points the detection reads: the newest walks first, whole walks only. */
    const val MAX_DETECTION_POINTS = 20_000

    /** The place check only: a walk 25 to 50 m from the place is reported as close, never as walked. */
    const val NEAR_BAND_M = 50.0

    /** The place check only: a fix worse than this is refused (the Hunt gate). */
    const val MAX_FIX_ACCURACY_M = 50.0

    /** The place check only, drawing only: the highlighted stretch runs this far each side of the nearest point. */
    const val CHECK_STRETCH_M = 60.0

    /** A saved house holds at most this many saved walks. */
    const val MAX_WALKS_PER_HOUSE = 20

    /** The device holds at most this many saved walks. */
    const val MAX_SAVED_WALKS = 200

    /** The margin (metres) of the `shown` rule: the nearest point of the newer walk lies inside its repeated stretch. */
    const val SHOWN_MARGIN_M = 0.5

    /** The sheet *Save this walk?* is offered for a walk of at least this many points ... */
    const val ASK_MIN_POINTS = 5

    /** ... and at least this many metres. */
    const val ASK_MIN_LENGTH_M = 100.0
}

/** *How repeated paths look* (docs/11 5.27.4): per device, a display choice, never exported. */
enum class RepeatLook { CLEAR, SUBTLE, OFF }
