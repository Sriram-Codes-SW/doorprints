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

import androidx.datastore.preferences.core.mutablePreferencesOf
import app.doorprints.data.HouseEntity
import app.doorprints.data.KeychainSecretStore
import app.doorprints.location.HuntState
import app.doorprints.shared.model.Area
import app.doorprints.shared.model.AreaRegions
import app.doorprints.shared.model.Viewing
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.app_name
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import org.jetbrains.compose.resources.getString
import platform.CoreLocation.CLLocationManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUUID
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/*
 * The launch self-check (CMP-8b): the iOS CI job starts the debug app on a simulator with `-DoorprintsSelfCheck` and
 * reads these lines from its log, so a framework that links but cannot load its strings, open its database, read its
 * settings or reach the Keychain fails the build instead of the first tester. Only in a debug binary, and only with
 * the argument: a release build, or a normal launch, never runs it.
 *
 * Since CMP-8c also the map: `indiaView` (the style the map is given keeps India's boundary rules) and `map` (the map on
 * screen loaded all of it), the in-app boundary check that makes the iOS map's India view a CI gate. Since S4b-BL-69
 * also `hunt`: with the simulator's location set next to a house saved for the check (ios/ci/launch-smoke.sh), Hunt
 * mode starts and the engine reports that house as the nearest; SKIP without the location permission. Since S4b-BL-96
 * also `areaWakeup`: the region registration on a fake location manager, then the real one with the stored settings.
 *
 * Each check prints exactly one line, `DOORPRINTS-SELFCHECK <name> PASS`, `… FAIL <short reason>` or
 * `… SKIP <reason>`, then `DOORPRINTS-SELFCHECK done PASS` (every check passed or was skipped) or `done FAIL`. The
 * lines name the check and the error's type and message, never a key, a setting's value or a house.
 */

/** The launch argument that runs the self-check. */
private const val SELF_CHECK_ARGUMENT = "-DoorprintsSelfCheck"

/** The prefix every line starts with, which the CI job greps. */
private const val PREFIX = "DOORPRINTS-SELFCHECK"

/** How long one check may take before it counts as failed (a first launch creates the database). */
private const val CHECK_TIMEOUT_MS = 30_000L

/**
 * How long the map may take to show its style: the app starts on the Map, which downloads the base style, prepares it
 * and hands it to MapLibre, while the other checks run.
 */
private const val MAP_TIMEOUT_MS = 90_000L

/** The style's download: two tries of up to 20 s each (IosMapStyle), then the cached copy. */
private const val INDIA_VIEW_TIMEOUT_MS = 60_000L

/** How long Hunt mode may take to get its first fix from the simulator and name the check's house. */
private const val HUNT_TIMEOUT_MS = 60_000L

/** How many times the `reminders` check runs the reschedule. */
private const val REMINDER_RUNS = 30

/**
 * Where the check's house is; the launch smoke sets the simulator's location about 15 m away (12.9701, 77.6401),
 * within the default alert radius and well within the 150 m the Hunt card names the nearest house from.
 */
private const val HUNT_HOUSE_LAT = 12.9700
private const val HUNT_HOUSE_LON = 77.6400

/** A separate Keychain service, so the check never touches the app's API key item (`app.doorprints`). */
private const val SELF_CHECK_SERVICE = "app.doorprints.selfcheck"

/** errSecMissingEntitlement and errSecNotAvailable: no Keychain for this binary, as KeychainSettingsTest skips. */
private val NO_KEYCHAIN = setOf(-34018, -25291)

/** Whether [startSelfCheckIfRequested] has run; main thread only, like its caller. */
private var selfCheckStarted = false

/**
 * Starts the self-check off the main thread when this is a debug binary launched with [SELF_CHECK_ARGUMENT]. Once per
 * process: a second root view controller (a new scene) does not run it again.
 */
@OptIn(ExperimentalNativeApi::class)
internal fun startSelfCheckIfRequested() {
    if (selfCheckStarted) return
    selfCheckStarted = true
    if (!Platform.isDebugBinary) return
    if (NSProcessInfo.processInfo.arguments.none { it == SELF_CHECK_ARGUMENT }) return
    IosAppContainer.appScope.launch {
        try {
            runSelfCheck()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            report("done", "FAIL ${reason(e)}")
        }
    }
}

/** What one check found; [SKIP] counts as a pass for `done`. */
private sealed interface Result {
    object Pass : Result
    class Fail(val reason: String) : Result
    class Skip(val reason: String) : Result
}

private suspend fun runSelfCheck() {
    val results = listOf(
        check("resources") {
            val name = getString(Res.string.app_name)
            if (name.isBlank()) Result.Fail("app_name is blank") else Result.Pass
        },
        check("database") {
            // Opens (or creates) the database and runs a read query.
            IosAppContainer.repository.houseSnapshot()
            Result.Pass
        },
        check("settings") {
            IosAppContainer.repository.settings.current()
            Result.Pass
        },
        check("keychain") { keychainRoundTrip() },
        // The in-app boundary check (CMP-8c; the owner's CI gate for the iOS map): the style the map gets has India's
        // boundary as the Government of India shows it (IndiaViewCheck), and the map on screen loaded all of it.
        check("indiaView", INDIA_VIEW_TIMEOUT_MS) { indiaViewCheck() },
        check("map", MAP_TIMEOUT_MS) { mapCheck() },
        // Hunt mode on iPhone (S4b-BL-69): the adapter around the common engine gets a fix and finds the house.
        check("hunt", HUNT_TIMEOUT_MS) { huntCheck() },
        // Viewing reminders (slice 3b-2): an earlier version crashed now and then inside UserNotifications, so the
        // reschedule runs many times, for two viewings, where one run could pass by luck.
        check("reminders") { remindersCheck() },
        // The area wake-up on iPhone (S4b-BL-96): the registration against a fake location manager, then once for real.
        check("areaWakeup") { areaWakeupCheck() },
    )
    report("done", if (results.any { it is Result.Fail }) "FAIL" else "PASS")
}

/** Runs [block] under [CHECK_TIMEOUT_MS] and prints its line; a throw is a FAIL with the error's type and message. */
private suspend fun check(name: String, timeoutMs: Long = CHECK_TIMEOUT_MS, block: suspend () -> Result): Result {
    // Shows where a check that ends the app (a native crash) stopped; the CI job looks only for done and SKIP lines.
    report(name, "START")
    val result = try {
        withTimeout(timeoutMs) { block() }
    } catch (e: CancellationException) {
        // withTimeout's own exception is a CancellationException: a timeout is a failure here, not a stop.
        Result.Fail("timed out or cancelled (${e::class.simpleName})")
    } catch (e: Throwable) {
        Result.Fail(reason(e))
    }
    report(
        name,
        when (result) {
            Result.Pass -> "PASS"
            is Result.Fail -> "FAIL ${result.reason}"
            is Result.Skip -> "SKIP ${result.reason}"
        },
    )
    return result
}

/**
 * Saves a random value with [KeychainSecretStore] under [SELF_CHECK_SERVICE], reads it back and deletes it. SKIP when
 * the binary has no Keychain (a build without any code signature; the CI build is ad-hoc signed, so it has one): the
 * store's error names the Keychain status.
 */
private fun keychainRoundTrip(): Result {
    val store = KeychainSecretStore(service = SELF_CHECK_SERVICE, account = "probe")
    val settings = mutablePreferencesOf()
    val value = NSUUID().UUIDString
    try {
        store.put(settings, value)
    } catch (e: IllegalStateException) {
        val status = keychainStatus(e)
        if (status in NO_KEYCHAIN) return Result.Skip("no Keychain for this binary (status $status)")
        throw e
    }
    try {
        if (store.get(settings) != value) return Result.Fail("the value read back differs")
    } finally {
        store.clear(settings)
    }
    return if (store.get(settings) == null) Result.Pass else Result.Fail("the value is still there after clear")
}

/**
 * Downloads and prepares the map's style as the map does ([IosMapStyle.prepare]) and passes when [IndiaViewCheck] finds
 * nothing wrong; the first problems are the FAIL reason (layer names only).
 */
private suspend fun indiaViewCheck(): Result {
    val problems = IosMapStyle.prepare(MARKER_LABEL_SIZE_SP).problems
    return if (problems.isEmpty()) Result.Pass else Result.Fail(problems.take(3).joinToString("; "))
}

/**
 * Waits until the map on screen has loaded its style ([IosMapStyle.loadedProblems]) and passes when MapLibre kept every
 * layer, in order, with the disputed lines hidden ([IndiaViewCheck.loadedProblems]).
 */
private suspend fun mapCheck(): Result {
    val problems = IosMapStyle.loadedProblems.filterNotNull().first()
    return if (problems.isEmpty()) Result.Pass else Result.Fail(problems.take(3).joinToString("; "))
}

/**
 * Saves a house at [HUNT_HOUSE_LAT], [HUNT_HOUSE_LON], starts Hunt mode ([IosHunt]) and waits until the engine names
 * it as the nearest house from a fix; then stops Hunt mode and deletes the house. SKIP when the app has no location
 * permission (the smoke grants it with `simctl privacy`); FAIL when the start is refused or no fix names the house in
 * time. Alerts need the notification permission, which the simulator cannot grant, so iOS drops them; the engine's
 * state is what is checked.
 */
private suspend fun huntCheck(): Result {
    if (iosLocationAccess() == LocationAccess.NONE) return Result.Skip("location not authorized")
    val repo = IosAppContainer.repository
    val now = Clock.System.now().toEpochMilliseconds()
    val house = HouseEntity(
        id = NSUUID().UUIDString.lowercase(), label = "Self-check house", lat = HUNT_HOUSE_LAT, lon = HUNT_HOUSE_LON,
        createdAt = now, updatedAt = now,
    )
    repo.saveHouse(house)
    try {
        val started = withContext(Dispatchers.Main) { IosHunt.start() }
        if (!started) return Result.Fail("Hunt mode did not start")
        try {
            HuntState.state.first { it.nearestHouse?.id == house.id }
        } finally {
            withContext(Dispatchers.Main) { IosHunt.stop() }
        }
    } finally {
        repo.deleteHouse(house.id)
    }
    return Result.Pass
}

/** Runs [IosViewingReminders.reschedule] [REMINDER_RUNS] times for a viewing and a Hunt reminder, then clears them. */
private suspend fun remindersCheck(): Result {
    val now = Clock.System.now().toEpochMilliseconds()
    val viewings = listOf(
        Viewing(id = "v_00000001", houseId = "self-check", startsAt = now + 3 * 3_600_000L, remindMin = 60),
        Viewing(id = "v_00000002", houseId = "self-check", startsAt = now + 5 * 3_600_000L, remindMin = 30, huntReminder = true),
    )
    repeat(REMINDER_RUNS) {
        IosViewingReminders.reschedule(viewings, emptyList(), now, viewingsOn = true, huntOn = true)
    }
    IosViewingReminders.reschedule(emptyList(), emptyList(), now)
    return Result.Pass
}

/**
 * The area wake-up's registration ([AreaRegionSync]) on a [RecordingRegionMonitor] holding another app region and a
 * stale one of ours: 25 areas give the 19 nearest (the other region keeps its place), the stale one stops, a second
 * run changes nothing and switching off stops only ours. Then the real registration runs once ([IosAreaWakeup], the
 * stored areas and setting; the simulator has no "Always", so it registers nothing) and the real manager is read.
 */
private suspend fun areaWakeupCheck(): Result = withContext(Dispatchers.Main) {
    val other = AreaRegions.Region("somebody.else", 12.0, 77.0, 300.0)
    val stale = AreaRegions.Region(AreaRegions.identifier("a_0000ffff"), 12.0, 77.0, 500.0)
    val fake = RecordingRegionMonitor(listOf(other, stale), near = HUNT_HOUSE_LAT to HUNT_HOUSE_LON)
    // Area i lies i * 100 m north of the fake's position.
    val areas = (0 until 25).map { i ->
        Area("a_" + i.toString(16).padStart(8, '0'), "Area $i", HUNT_HOUSE_LAT + i * 0.0009, HUNT_HOUSE_LON, 500)
    }
    val sync = AreaRegionSync(fake)
    sync.apply(areas.reversed(), wakeupOn = true, alwaysGranted = true)
    val ours = fake.monitored().filter { AreaRegions.areaId(it.identifier) != null }
    if (ours.map { AreaRegions.areaId(it.identifier) } != areas.take(19).map { it.id }) {
        return@withContext Result.Fail("not the 19 nearest areas (${ours.size})")
    }
    if (fake.monitored().none { it == other }) return@withContext Result.Fail("another region was stopped")
    val calls = fake.calls
    sync.apply(areas, wakeupOn = true, alwaysGranted = true)
    if (fake.calls != calls) return@withContext Result.Fail("a second run changed the regions")
    sync.apply(areas, wakeupOn = false, alwaysGranted = true)
    if (fake.monitored() != listOf(other)) return@withContext Result.Fail("switching off left ${fake.monitored().size - 1} regions")
    // The real path: the stored areas and setting, Core Location's own manager.
    if (IosAreaWakeupServices.available) {
        IosAreaWakeup.install()
        withContext(Dispatchers.Default) { IosAreaWakeup.reregisterAll() }
        CoreLocationRegions(CLLocationManager()).monitored()
    }
    Result.Pass
}

/**
 * A [RegionMonitor] that keeps its regions in a list (the self-check's fake Core Location): what it was given, the
 * position [near] and no radius limit; [calls] counts the starts and stops.
 */
internal class RecordingRegionMonitor(
    initial: List<AreaRegions.Region> = emptyList(),
    private val near: Pair<Double, Double>? = null,
    override val maxRadiusM: Double = 0.0,
    override val available: Boolean = true,
) : RegionMonitor {
    private val regions = initial.toMutableList()
    var calls = 0
        private set

    override fun monitored(): List<AreaRegions.Region> = regions.toList()

    override fun start(region: AreaRegions.Region) {
        calls++
        regions.removeAll { it.identifier == region.identifier }
        regions += region
    }

    override fun stop(identifier: String) {
        calls++
        regions.removeAll { it.identifier == identifier }
    }

    override fun lastPosition(): Pair<Double, Double>? = near
}

/** The Keychain status in a [KeychainSecretStore] error ("… (status -34018)"), or null. */
private fun keychainStatus(e: Throwable): Int? =
    Regex("""status (-?\d+)""").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

/** A one-line reason: the error's type and the start of its message. */
private fun reason(e: Throwable): String =
    "${e::class.simpleName}: ${e.message.orEmpty()}".replace('\n', ' ').take(160)

/** One line to stdout and to the unified log ([logLine]), where the launch smoke's `log stream` reads it. */
private fun report(name: String, outcome: String) {
    val line = "$PREFIX $name $outcome"
    println(line)
    logLine(line)
}
