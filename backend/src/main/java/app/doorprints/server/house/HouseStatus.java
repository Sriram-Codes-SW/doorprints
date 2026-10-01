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

package app.doorprints.server.house;

/**
 * {@code TAKEN} (the house the person moves into) and {@code NOT_CHOSEN} (a house left when another was taken) arrive
 * with slice 5 (docs/11 section 5.24); {@code REJECTED} keeps its meaning, rejected after looking. A backup with
 * either new status is {@code doorprints-backup/2}.
 */
public enum HouseStatus {
    NEW, SHORTLISTED, REJECTED, TAKEN, NOT_CHOSEN;

    /**
     * False for the statuses out of the running, {@code REJECTED} and {@code NOT_CHOSEN} (S4b-BL-99 a): the planner's
     * fallback route leaves them out and its prompt skips them unless asked, as the phones' and the website's Compare
     * and Plan do ({@code HouseStatusRules.inTheRunning}, {@code house-status.ts}), pinned by the parity vectors'
     * {@code inTheRunning}.
     */
    public boolean inTheRunning() {
        return this != REJECTED && this != NOT_CHOSEN;
    }
}
