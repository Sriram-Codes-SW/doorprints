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

package app.doorprints.deviceauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Typed outcomes of [DeletionPolicy] without the JSON file, so they also run on the iPhone simulator. */
class DeletionPolicyTest {
    private val phone = DeletionContext(AuthPlatform.PHONE, deviceLock = true, webPrf = false, online = true, backupsLeft = 5)

    @Test
    fun everyActionOnAPhoneHasItsLevel() {
        val levels = DeletionAction.entries.associateWith { (DeletionPolicy.decide(it, phone) as DeletionDecision.Allowed).requirements.level }
        assertEquals(DeleteLevel.L1, levels[DeletionAction.DELETE_ONE_BACKUP])
        assertEquals(DeleteLevel.L1, levels[DeletionAction.REMOVE_SHARED_HUNT])
        assertEquals(DeleteLevel.L1, levels[DeletionAction.DISCONNECT_THIS_DEVICE])
        assertEquals(DeleteLevel.L1, levels[DeletionAction.TURN_AUTO_BACKUP_OFF])
        for (a in listOf(DeletionAction.DELETE_ALL_BACKUPS, DeletionAction.STOP_SHARING, DeletionAction.REVOKE_DEVICE, DeletionAction.APPROVE_DEVICE, DeletionAction.DISCONNECT_ALL_DEVICES)) {
            assertEquals(DeleteLevel.L2, levels[a], a.name)
        }
        assertEquals(DeleteLevel.L3, levels[DeletionAction.DELETE_EVERYTHING])
        assertEquals(DeleteLevel.L3, levels[DeletionAction.WEAKEN_PROTECTION])
    }

    @Test
    fun theLastBackupRuleAndUnknownCount() {
        assertEquals(DeleteLevel.L1, DeletionPolicy.levelOf(DeletionAction.DELETE_ONE_BACKUP, 2))
        assertEquals(DeleteLevel.L2, DeletionPolicy.levelOf(DeletionAction.DELETE_ONE_BACKUP, 1))
        assertEquals(DeleteLevel.L2, DeletionPolicy.levelOf(DeletionAction.DELETE_ONE_BACKUP, null))
        assertEquals(DeleteLevel.L1, DeletionPolicy.levelOf(DeletionAction.DISCONNECT_THIS_DEVICE, null))
    }

    @Test
    fun onlyL3AsksTheBoxAndTheDelay() {
        val l2 = (DeletionPolicy.decide(DeletionAction.DELETE_ALL_BACKUPS, phone) as DeletionDecision.Allowed).requirements
        val l3 = (DeletionPolicy.decide(DeletionAction.DELETE_EVERYTHING, phone) as DeletionDecision.Allowed).requirements
        assertTrue(!l2.tickBox && l2.delaySeconds == 0)
        assertTrue(l3.tickBox && l3.delaySeconds == 5)
        assertEquals(60_000L, l3.authValidMs)
        assertEquals(Factor.DEVICE_AUTH, l3.factor)
    }

    @Test
    fun refusalsAreTyped() {
        assertIs<DeletionDecision.Refused>(DeletionPolicy.decide(DeletionAction.DELETE_ALL_BACKUPS, phone.copy(online = false)))
        assertEquals(
            DeletionDecision.Refused(RefusalReason.USE_PHONE),
            DeletionPolicy.decide(DeletionAction.DELETE_EVERYTHING, phone.copy(platform = AuthPlatform.WEBSITE, deviceLock = false)),
        )
    }
}
