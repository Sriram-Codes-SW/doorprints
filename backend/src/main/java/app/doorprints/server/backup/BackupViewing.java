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

package app.doorprints.server.backup;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Set;

/**
 * One viewing in the shared backup format ({@code doorprints-backup/2}, docs/11 section 5.8, slice 3b-1). On the
 * server a viewing lives in the {@code record} table as type {@code viewing}, the record id being {@code id} (the
 * apps make {@code v_} and 8 hex digits); the payload holds the other fields in this order, {@code huntReminder}
 * only when true, {@code withWhom}, {@code notes} and {@code visitId} only when set. {@code houseId} may name a
 * house that is gone. {@code withWhom} is contact data: a copy made without contact details leaves it out.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "houseId", "startsAt", "durationMin", "kind", "status", "remindMin", "huntReminder",
        "withWhom", "notes", "visitId", "updatedAt"})
public record BackupViewing(
        String id,
        String houseId,
        Long startsAt,
        Integer durationMin,
        String kind,
        String status,
        Integer remindMin,
        Boolean huntReminder,
        String withWhom,
        String notes,
        String visitId,
        Long updatedAt
) {
    public static final String TYPE = "viewing";
    public static final int MIN_DURATION = 5;
    public static final int MAX_DURATION = 480;
    public static final int DEFAULT_DURATION = 30;
    public static final int MAX_WITH_WHOM = 200;
    public static final int MAX_NOTES = 2000;
    /** A house or visit id is a UUID or a record id; the record id limit keeps a stored row importable again. */
    public static final int MAX_REF = 64;
    /** At most this many viewings in a copy: the record cap of one type. */
    public static final int MAX = 5_000;
    public static final Set<String> KINDS = Set.of("FIRST", "SECOND", "FOLLOW_UP");
    public static final Set<String> STATUSES = Set.of("PLANNED", "DONE", "CANCELLED");
    public static final Set<Integer> REMINDERS = Set.of(0, 15, 30, 60, 120, 1440);
}
