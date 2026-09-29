package app.doorprints.ui

import platform.Foundation.NSLog

/**
 * Writes [text] to the unified log as one line (NSLog), for the launch self-check, the start-up steps and the crash
 * hook (CMP-8b).
 *
 * The text goes in as the format string, with any `%` doubled, never as a variadic argument: Kotlin/Native does not turn
 * a Kotlin `String` passed through C varargs into an `NSString`, so `NSLog("%@", text)` hands NSLog the string's raw
 * bytes as an object pointer and the app dies with SIGSEGV in `objc_opt_respondsToSelector` (the first CI launches,
 * 2026-09-29: the faulting address was "DOORPRINTS" read as a pointer). The first parameter is not variadic, so it is
 * bridged to an `NSString` properly.
 */
internal fun logLine(text: String) {
    NSLog(text.replace("%", "%%"))
}
