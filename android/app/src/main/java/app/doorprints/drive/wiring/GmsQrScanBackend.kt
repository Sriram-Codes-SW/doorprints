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

import android.app.Activity
import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await

/**
 * The only place that calls Google's code scanner: QR codes only, auto zoom. Google's own screen scans, so the app holds no
 * CAMERA permission. The scanner module is fetched through Play services on first use; until it is there [moduleReady] asks
 * for it and answers false (no crash, no wait). The scanned text is never logged.
 */
class GmsQrScanBackend(private val activities: ActivityProvider, private val app: Context) : QrScanBackend {
    private val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .enableAutoZoom()
        .build()

    override val playServicesPresent: Boolean
        get() = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) == ConnectionResult.SUCCESS

    override suspend fun moduleReady(): Boolean {
        val activity = activities.current() ?: return false
        val scanner = GmsBarcodeScanning.getClient(activity, options)
        val client = ModuleInstall.getClient(app)
        if (client.areModulesAvailable(scanner).await().areModulesAvailable()) return true
        client.installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build()) // answers false until it has arrived
        return false
    }

    override suspend fun scan(): QrScanBackendResult {
        val activity: Activity = activities.current() ?: return QrScanBackendResult.Failed
        val scanner = GmsBarcodeScanning.getClient(activity, options)
        return suspendCancellableCoroutine { cont ->
            scanner.startScan()
                .addOnSuccessListener { code ->
                    val raw = code.rawValue
                    cont.resume(if (raw.isNullOrEmpty()) QrScanBackendResult.Failed else QrScanBackendResult.Text(raw))
                }
                .addOnCanceledListener { cont.resume(QrScanBackendResult.Cancelled) }
                .addOnFailureListener { cont.resume(QrScanBackendResult.Failed) }
        }
    }
}
