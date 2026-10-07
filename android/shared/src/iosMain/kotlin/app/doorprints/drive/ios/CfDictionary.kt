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

package app.doorprints.drive.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ptr
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRetain

/**
 * A temporary Core Foundation dictionary for one Security call. Keys and values the framework owns (its `kSec...`
 * constants) are added with [put]; anything this code created (a `CFNumber`, a bridged `NSData`, a nested dictionary)
 * with [putOwned], which hands one reference to the builder: the dictionary keeps its own, and [close] releases the
 * builder's. Use through [withCfDictionary].
 */
@OptIn(ExperimentalForeignApi::class)
internal class CfDictionaryBuilder {
    val ref: CFMutableDictionaryRef? = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
    private val owned = mutableListOf<CFTypeRef>()

    fun put(key: CFTypeRef?, value: CFTypeRef?) {
        CFDictionaryAddValue(ref, key, value)
    }

    fun putOwned(key: CFTypeRef?, value: CFTypeRef?) {
        CFDictionaryAddValue(ref, key, value)
        if (value != null) owned += value
    }

    /** A string or `NSData` bridged to Core Foundation, owned by this builder. */
    fun putBridged(key: CFTypeRef?, value: Any?) {
        putOwned(key, CFBridgingRetain(value))
    }

    fun close() {
        owned.forEach { CFRelease(it) }
        owned.clear()
        ref?.let { CFRelease(it) }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal inline fun <T> withCfDictionary(build: CfDictionaryBuilder.() -> Unit, use: (CFMutableDictionaryRef?) -> T): T {
    val builder = CfDictionaryBuilder()
    try {
        builder.build()
        return use(builder.ref)
    } finally {
        builder.close()
    }
}
