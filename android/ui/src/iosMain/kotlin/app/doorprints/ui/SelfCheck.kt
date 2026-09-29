package app.doorprints.ui

import androidx.datastore.preferences.core.mutablePreferencesOf
import app.doorprints.data.KeychainSecretStore
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.app_name
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.getString
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
    )
    report("done", if (results.any { it is Result.Fail }) "FAIL" else "PASS")
}

/** Runs [block] under [CHECK_TIMEOUT_MS] and prints its line; a throw is a FAIL with the error's type and message. */
private suspend fun check(name: String, block: suspend () -> Result): Result {
    // Shows where a check that ends the app (a native crash) stopped; the CI job looks only for done and SKIP lines.
    report(name, "START")
    val result = try {
        withTimeout(CHECK_TIMEOUT_MS) { block() }
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
