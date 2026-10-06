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

package app.doorprints.drive.wiring

import app.doorprints.ui.drive.QrScan
import app.doorprints.ui.drive.QrScanner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/** One answer of the platform scanner: the three outcomes a screen can meet, kept apart (a cancel is not a failure). */
sealed interface QrScanBackendResult {
    /** The raw text of a code. A secret-bearing offer: never printed, logged or shown by [toString]. */
    class Text(val raw: String) : QrScanBackendResult {
        override fun toString(): String = "Text(<redacted>)"
    }

    /** The person closed the scanner screen. */
    data object Cancelled : QrScanBackendResult

    /** The scanner could not run or could not read a code (no Activity, no Play services, an error from Google). */
    data object Failed : QrScanBackendResult
}

/**
 * The one thin seam over Google's code scanner (all Google API calls live in [GmsQrScanBackend]); a fake stands in on the JVM.
 */
interface QrScanBackend {
    /** Play services is on the phone (false on the browser-fallback phones). */
    val playServicesPresent: Boolean

    /** The scanner module is installed; when it is not, the install is requested and this answers false until it is there. */
    suspend fun moduleReady(): Boolean

    suspend fun scan(): QrScanBackendResult
}

/**
 * [QrScanner] over [QrScanBackend] (docs/15 §9.5; owner decision 2026-10-06: Google's code scanner, no CAMERA permission).
 * The scanned text goes back to the screens as the pasted text would, so the codec's size limit, the `dp1.` check and the
 * *not a Doorprints code* error apply unchanged. Nothing here throws into the UI and nothing logs the text.
 */
class AndroidQrScanner(private val backend: QrScanBackend) : QrScanner {
    private val open = AtomicBoolean(false)

    override val isAvailable: Boolean get() = backend.playServicesPresent

    override suspend fun scan(): QrScan {
        if (!open.compareAndSet(false, true)) return QrScan.Cancelled // a second tap while the scanner is open
        try {
            if (!backend.moduleReady()) return QrScan.NoCamera
            return when (val r = backend.scan()) {
                is QrScanBackendResult.Text -> QrScan.Scanned(refuseOversize(r.raw))
                QrScanBackendResult.Cancelled -> QrScan.Cancelled
                QrScanBackendResult.Failed -> QrScan.NoCamera
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return QrScan.NoCamera
        } finally {
            open.set(false)
        }
    }

    /** Too long to be an offer: not handed on, so it is refused as *not a Doorprints code* before any parsing. */
    private fun refuseOversize(raw: String): String =
        if (raw.length > Dp1EnrolmentCodec.MAX_TEXT) NOT_A_CODE else raw

    private companion object {
        const val NOT_A_CODE = "not-a-doorprints-code"
    }
}
