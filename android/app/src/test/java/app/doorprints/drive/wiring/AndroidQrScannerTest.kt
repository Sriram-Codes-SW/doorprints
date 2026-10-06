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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scanner logic over a fake [QrScanBackend]; Google's API is not called on the JVM. */
class AndroidQrScannerTest {
    private class Fake(
        override var playServicesPresent: Boolean = true,
        var module: Boolean = true,
        var result: () -> QrScanBackendResult = { QrScanBackendResult.Cancelled },
    ) : QrScanBackend {
        var scans = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun moduleReady() = module
        override suspend fun scan(): QrScanBackendResult {
            scans++
            gate?.await()
            return result()
        }
    }

    private fun scan(f: Fake) = runBlocking { AndroidQrScanner(f).scan() }

    @Test fun `a scanned code returns its raw text unchanged`() {
        assertEquals(QrScan.Scanned("dp1.abc"), scan(Fake(result = { QrScanBackendResult.Text("dp1.abc") })))
    }

    @Test fun `a closed scanner is a cancel and not a failure`() {
        assertEquals(QrScan.Cancelled, scan(Fake(result = { QrScanBackendResult.Cancelled })))
    }

    @Test fun `a failure is reported as no scanner and does not throw`() {
        assertEquals(QrScan.NoCamera, scan(Fake(result = { QrScanBackendResult.Failed })))
    }

    @Test fun `a backend that throws never reaches the screen`() {
        assertEquals(QrScan.NoCamera, scan(Fake(result = { throw IllegalStateException("boom") })))
    }

    @Test fun `a module that is not installed yet is reported as unavailable and no scan starts`() {
        val f = Fake(module = false, result = { QrScanBackendResult.Text("dp1.x") })
        assertEquals(QrScan.NoCamera, scan(f))
        assertEquals(0, f.scans)
    }

    @Test fun `without Play services the scanner is not available`() {
        assertFalse(AndroidQrScanner(Fake(playServicesPresent = false)).isAvailable)
        assertTrue(AndroidQrScanner(Fake(playServicesPresent = true)).isAvailable)
    }

    @Test fun `a second tap while a scan is open is ignored`() = runBlocking {
        val f = Fake(result = { QrScanBackendResult.Text("dp1.one") })
        f.gate = CompletableDeferred()
        val scanner = AndroidQrScanner(f)
        val first = async(start = CoroutineStart.UNDISPATCHED) { scanner.scan() }
        assertEquals(QrScan.Cancelled, withTimeout(2000) { scanner.scan() })
        assertEquals(1, f.scans)
        f.gate!!.complete(Unit)
        assertEquals(QrScan.Scanned("dp1.one"), first.await())
    }

    @Test fun `a new scan is possible after the last one ended, also after a failure`() = runBlocking {
        val f = Fake(result = { throw IllegalStateException("x") })
        val scanner = AndroidQrScanner(f)
        assertEquals(QrScan.NoCamera, scanner.scan())
        f.result = { QrScanBackendResult.Text("dp1.again") }
        assertEquals(QrScan.Scanned("dp1.again"), scanner.scan())
    }

    @Test fun `a text over the size limit is refused before parsing`() {
        val big = "dp1." + "A".repeat(Dp1EnrolmentCodec.MAX_TEXT)
        val r = scan(Fake(result = { QrScanBackendResult.Text(big) })) as QrScan.Scanned
        assertFalse(r.text.startsWith("dp1."))
        assertTrue(r.text.length < 100)
    }

    @Test fun `a text exactly at the limit is passed on`() {
        val ok = "dp1." + "A".repeat(Dp1EnrolmentCodec.MAX_TEXT - 4)
        assertEquals(QrScan.Scanned(ok), scan(Fake(result = { QrScanBackendResult.Text(ok) })))
    }

    @Test fun `the scanned text never appears in toString`() {
        val secret = "dp1.SECRET-OFFER"
        assertFalse(QrScanBackendResult.Text(secret).toString().contains("SECRET"))
    }

    @Test fun `a cancelled coroutine is not swallowed`() = runBlocking {
        val f = Fake()
        f.gate = CompletableDeferred()
        val seen = CompletableDeferred<Result<QrScan>>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { seen.complete(runCatching { AndroidQrScanner(f).scan() }) }
        job.cancel()
        job.join()
        assertTrue(seen.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }
}
