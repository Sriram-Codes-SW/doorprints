package com.househunt.app.ui

import com.househunt.shared.sync.SyncOutcome

/**
 * The look of Settings' *Save and test* / *Sync now* result (UX review, whole-app audit, round 8), with no Android, so
 * it is unit tested (ServerStatusTest). Round 7 drew every outcome as plain body text, so "Could not connect…" and
 * "Connected: 12 houses" looked alike; now the result is a [ResultCard] in this tone, inside a [LiveMessage] that is
 * assertive for [ResultTone.ERROR], as the Map's error card and the export screens are.
 *
 * [testSucceeded] is the last *Save and test* on this screen (null when there is none, or *Sync now* ran since); it
 * decides the tone when present, as it decides the text. Otherwise [lastSync], the last sync's outcome: OK is
 * SUCCESS, "not configured" NEUTRAL (nothing failed; there is no server yet), every other kind ERROR. Null when there
 * is nothing to show.
 */
fun serverStatusTone(testSucceeded: Boolean?, lastSync: SyncOutcome.Kind?): ResultTone? = when {
    testSucceeded != null -> if (testSucceeded) ResultTone.SUCCESS else ResultTone.ERROR
    lastSync == null -> null
    lastSync == SyncOutcome.Kind.OK -> ResultTone.SUCCESS
    lastSync == SyncOutcome.Kind.NOT_CONFIGURED -> ResultTone.NEUTRAL
    else -> ResultTone.ERROR
}

/** What the server result's slot draws (round 9; [serverStatusSlot]). */
enum class ServerStatusSlot {
    /** Nothing: no result, or the result was withdrawn when the address in the field failed its check. */
    NONE,

    /** The indeterminate progress bar alone: a run is busy and there is no earlier result to keep. */
    BAR,

    /** The result card. */
    CARD,

    /**
     * The earlier result card while a new run is busy: its icon and border dimmed, its text at full contrast, the
     * progress bar along its foot, and "Updating…" as its state for TalkBack (round 10).
     */
    CARD_BUSY,
}

/**
 * What Settings draws in the server result's slot (UX review, whole-app audit, rounds 9 and 10). While a run is busy
 * the earlier card keeps its slot ([ServerStatusSlot.CARD_BUSY]), so what is below it does not jump up by the card's
 * height and back down a second later (round 8 swapped the card for a 4 dp bar).
 *
 * [resultWithdrawn] is true when *Save and test* rejected the address in the field (not https, malformed, empty)
 * since the last run finished: that address was never checked, and a green "Connected: 12 houses" under it would
 * contradict it, so there is no card. Round 10 ties this to the run, not to the field's error (round 9): typing in the
 * field clears the error but starts no run, so the older result does not come back mid-typing; and *Sync now*, which
 * syncs the saved server and so stays enabled after a rejected address, is a new run whose result always shows.
 * A busy run with the result withdrawn is the bar alone.
 */
fun serverStatusSlot(hasResult: Boolean, busy: Boolean, resultWithdrawn: Boolean): ServerStatusSlot = when {
    hasResult && !resultWithdrawn -> if (busy) ServerStatusSlot.CARD_BUSY else ServerStatusSlot.CARD
    busy -> ServerStatusSlot.BAR
    else -> ServerStatusSlot.NONE
}

/**
 * Whether the server result is withdrawn (round 10): [withdrawnRun] is the run count (Settings' `statusRun`) at which
 * *Save and test* last rejected the address, -1 for never. A run that finishes after that bumps the count, so its
 * result shows; typing does not.
 */
fun serverResultWithdrawn(withdrawnRun: Int, statusRun: Int): Boolean = withdrawnRun == statusRun
