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

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A copied secret is marked sensitive from Android 13 (the clipboard preview and history skip it). Robolectric has only the
 * Android 15 image offline, so the API level is passed to the seam (33 and 32 stand for "13 and later" and "before 13").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidClipboardSeamTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun clip() = context.getSystemService(ClipboardManager::class.java).primaryClip!!

    @Test
    fun onAndroid13TheClipIsMarkedSensitive() {
        AndroidClipboardSeam(context, sdk = 33).copySensitive("ABCD-EFGH-1234")
        assertEquals("ABCD-EFGH-1234", clip().getItemAt(0).text.toString())
        val extras = clip().description.extras
        assertNotNull(extras)
        assertTrue(extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false))
    }

    @Test
    fun beforeAndroid13ItIsAPlainCopyWithoutTheFlag() {
        AndroidClipboardSeam(context, sdk = 32).copySensitive("ABCD-EFGH-1234")
        assertEquals("ABCD-EFGH-1234", clip().getItemAt(0).text.toString())
        val extras = clip().description.extras
        assertFalse(extras != null && extras.containsKey("android.content.extra.IS_SENSITIVE"))
    }

    @Test
    fun aSecondCopyReplacesTheFirst() {
        val seam = AndroidClipboardSeam(context, sdk = 33)
        seam.copySensitive("first")
        seam.copySensitive("second")
        assertEquals("second", clip().getItemAt(0).text.toString())
    }
}
