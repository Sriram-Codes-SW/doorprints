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

package app.doorprints.drive.photo

import app.doorprints.crypto.Vectors
import app.doorprints.crypto.s
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `docs/schemas/photo-policy-vectors.json` (read by `photo-policy-vectors.spec.ts` too) and the gate (S4b-BL-128). */
class PhotoPolicyTest {
    private val root = Vectors.load("photo-policy-vectors.json")

    @Test
    fun theTtlIsTheOneInTheVectors() {
        assertEquals(root.getValue("constants").jsonObject.getValue("oneOffTtlMs").jsonPrimitive.long, PhotoNetworkPolicy.ONE_OFF_TTL_MS)
    }

    @Test
    fun everyVectorCase() {
        val cases = root.getValue("cases").jsonArray
        assertEquals(26, cases.size)
        for (c in cases) {
            val v = c.jsonObject
            val name = v.s("name")
            val cond = v.getValue("conditions").jsonObject
            val conditions = NetworkConditions(
                online = cond.getValue("online").jsonPrimitive.boolean,
                metering = Metering.valueOf(cond.s("metering")),
                roaming = cond.getValue("roaming").jsonPrimitive.boolean,
                dataSaver = cond.getValue("dataSaver").jsonPrimitive.boolean,
            )
            val settings = PhotoSettings(v.getValue("settings").jsonObject.getValue("uploadOnMobileData").jsonPrimitive.boolean)
            val granted = v.getValue("grantedAt").takeIf { it !is JsonNull }?.jsonPrimitive?.long
            val now = v.getValue("now").jsonPrimitive.long
            val expect = v.getValue("expect").jsonObject
            val d = PhotoNetworkPolicy.decide(conditions, settings, granted?.let { OneOffGrant(it) }, now)
            assertEquals(name, expect.getValue("allowed").jsonPrimitive.boolean, d.allowed)
            assertEquals(name, PhotoAllowReason.valueOf(expect.s("reason")), d.reason)
            assertEquals(name, PhotoNetworkStatus.valueOf(expect.s("status")), PhotoNetworkPolicy.status(d, v.getValue("pending").jsonPrimitive.int))
        }
    }

    @Test
    fun theDefaultSettingIsOffAndTheExistingWifiOnlyIsItsInverse() {
        assertFalse(PhotoSettings().uploadOnMobileData)
        assertFalse(PhotoSettings.fromWifiOnly(true).uploadOnMobileData)
        assertTrue(PhotoSettings.fromWifiOnly(false).uploadOnMobileData)
    }

    @Test
    fun platformMappings() {
        assertEquals(NetworkConditions(true, Metering.UNMETERED, roaming = false, dataSaver = false), PhotoNetworkPolicy.fromAndroid(true, true, true, false))
        assertEquals(NetworkConditions(true, Metering.METERED, roaming = true, dataSaver = true), PhotoNetworkPolicy.fromAndroid(true, false, false, true))
        assertEquals(NetworkConditions(true, Metering.METERED, dataSaver = true), PhotoNetworkPolicy.fromApple(true, expensive = true, constrained = true))
        assertEquals(NetworkConditions(false, Metering.UNMETERED), PhotoNetworkPolicy.fromApple(false, expensive = false, constrained = false))
    }

    private class Net(var now: NetworkConditions) : NetworkState {
        override fun current() = now
    }

    @Test
    fun theGrantExpiresAndTheGateFollowsTheNetwork() {
        var t = 1_000_000L
        val net = Net(NetworkConditions(true, Metering.METERED))
        val gate = PhotoUploadGate(net, { PhotoSettings() }, { t })
        assertFalse(gate.photosAllowed())
        assertEquals(PhotoNetworkStatus.WAITING_FOR_WIFI, gate.status(2))
        gate.grantOneOff()
        assertTrue(gate.photosAllowed())
        assertEquals(PhotoNetworkStatus.UPLOADING, gate.status(2))
        t += PhotoNetworkPolicy.ONE_OFF_TTL_MS - 1
        assertTrue(gate.photosAllowed())
        t += 1
        assertFalse("the grant has expired", gate.photosAllowed())
        assertNull("an expired grant is dropped", gate.grant)
        // Wi-Fi: no grant needed, and a metered network after it waits again.
        net.now = NetworkConditions(true, Metering.UNMETERED)
        assertTrue(gate.photosAllowed())
        net.now = NetworkConditions.OFFLINE
        assertEquals(PhotoNetworkStatus.PAUSED_OFFLINE, gate.status(1))
        assertEquals(PhotoNetworkStatus.DONE, gate.status(0))
    }

    @Test
    fun aClearedGrantIsGone() {
        val gate = PhotoUploadGate({ NetworkConditions(true, Metering.METERED) }, { PhotoSettings() }, { 5L })
        gate.grantOneOff()
        gate.clearGrant()
        assertFalse(gate.photosAllowed())
    }

    @Test
    fun theSettingMovesPhotosOnMobileDataButNotWhileRoamingOrSaving() {
        var settings = PhotoSettings(uploadOnMobileData = true)
        var cond = NetworkConditions(true, Metering.METERED)
        val gate = PhotoUploadGate({ cond }, { settings }, { 0L })
        assertTrue(gate.photosAllowed())
        cond = NetworkConditions(true, Metering.METERED, roaming = true)
        assertFalse(gate.photosAllowed())
        cond = NetworkConditions(true, Metering.METERED, dataSaver = true)
        assertFalse(gate.photosAllowed())
        settings = PhotoSettings()
        cond = NetworkConditions(true, Metering.METERED)
        assertFalse(gate.photosAllowed())
    }
}
