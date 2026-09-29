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

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestUseProtocolCachePolicy
import platform.Foundation.NSURLSession
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * How long one try at the base style's download may wait (NSURLSession's timeout, between data); two tries, then the
 * cached copy (or the map shows *Try again*).
 */
private const val STYLE_TIMEOUT_S = 20.0

/** The last base style that downloaded, in the app's Caches folder, so the map also starts offline. */
private const val STYLE_CACHE_FILE = "liberty-style.json"

/**
 * The iOS map's style (ADR-23 CMP-8c): the OpenFreeMap Liberty style ([MAP_STYLE_URL]) with India's boundary rules and
 * the house layers ([prepareMapStyle]), and what the in-app boundary check found, for the launch self-check.
 *
 * The base style is downloaded on each map start (MapLibre Android does the same through its own cache); the last
 * good copy is kept in Caches and used when the download fails, so an offline start still has India's outline and the
 * cached tiles. The geo files come from the app bundle's `geo/` folder (android/app/src/main/assets/geo, added by
 * ios/project.yml, byte-identical to the web's: IndiaBoundaryDataTest).
 */
internal object IosMapStyle {
    /** The last style handed to the map, and what [IndiaViewCheck] found in it. */
    val prepared = MutableStateFlow<PreparedMapStyle?>(null)

    /** What [IndiaViewCheck.loadedProblems] found in the style the map last loaded; null until one has loaded. */
    val loadedProblems = MutableStateFlow<List<String>?>(null)

    /** Downloads (or reads the cached) base style and prepares it, off the main thread; throws when neither works. */
    suspend fun prepare(labelSizeSp: Float): PreparedMapStyle = withContext(Dispatchers.Default) {
        val base = try {
            // One retry: a single dropped connection should not cost the map its fresh style (or CI its gate).
            (runCatching { download() }.getOrNull() ?: download()).also { saveCache(it) }
        } catch (e: Exception) {
            warn("the base style did not download; the cached copy is used", e)
            readCache() ?: throw IllegalStateException("the base style could not be downloaded and none is cached", e)
        }
        prepareMapStyle(base.decodeToString(), labelSizeSp, ::readBundledAsset, ::warn).also { style ->
            style.problems.forEach { logLine("DOORPRINTS-MAP india-view problem: $it") }
            prepared.value = style
        }
    }

    /** Records what the map loaded ([IosMapListener.onStyleLoaded]) against the style it was given. */
    fun loaded(style: PreparedMapStyle, layerIds: List<String>, hiddenIds: List<String>) {
        val problems = IndiaViewCheck.loadedProblems(style.style, layerIds, hiddenIds.toSet())
        problems.forEach { logLine("DOORPRINTS-MAP loaded-style problem: $it") }
        loadedProblems.value = problems
    }

    /** A bundled file, by its asset path (`geo/in-boundaries.geojson`); throws when it is not in the bundle. */
    fun readBundledAsset(path: String): String {
        val directory = path.substringBeforeLast('/', "")
        val file = path.substringAfterLast('/')
        val full = NSBundle.mainBundle.pathForResource(
            file.substringBeforeLast('.'), file.substringAfterLast('.', ""), directory.ifEmpty { null },
        ) ?: throw IllegalStateException("$path is not in the app bundle")
        return (NSData.dataWithContentsOfFile(full) ?: throw IllegalStateException("$path could not be read"))
            .toByteArray().decodeToString()
    }

    private fun warn(message: String, error: Throwable?) {
        logLine("DOORPRINTS-MAP warning: $message" + (error?.let { " (${it::class.simpleName}: ${it.message})" } ?: ""))
    }

    private suspend fun download(): ByteArray = suspendCancellableCoroutine { cont ->
        val url = NSURL(string = MAP_STYLE_URL)
        val request = NSURLRequest.requestWithURL(url, NSURLRequestUseProtocolCachePolicy, STYLE_TIMEOUT_S)
        val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
            val status = (response as? NSHTTPURLResponse)?.statusCode ?: 0
            when {
                error != null -> cont.resumeWithException(IllegalStateException(error.localizedDescription))
                data == null || status !in 200L..299L ->
                    cont.resumeWithException(IllegalStateException("HTTP $status from the style address"))
                else -> cont.resume(data.toByteArray())
            }
        }
        cont.invokeOnCancellation { task.cancel() }
        task.resume()
    }

    private fun cachePath(): String? =
        (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String)
            ?.let { "$it/$STYLE_CACHE_FILE" }

    private fun saveCache(bytes: ByteArray) {
        val path = cachePath() ?: return
        // A failed write only costs the offline start its style.
        runCatching { bytes.toNSData().writeToFile(path, true) }
    }

    private fun readCache(): ByteArray? = cachePath()?.let { NSData.dataWithContentsOfFile(it)?.toByteArray() }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return bytes?.reinterpret<ByteVar>()?.readBytes(size) ?: ByteArray(0)
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    usePinned { NSData.dataWithBytes(if (isEmpty()) null else it.addressOf(0), size.toULong()) }
