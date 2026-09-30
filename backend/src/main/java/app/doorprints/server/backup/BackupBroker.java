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

/**
 * One broker in the shared backup format, the first list of {@code doorprints-backup/2} (docs/11 section 5.25).
 *
 * <p>Only {@code id}, {@code name} and {@code updatedAt} are always present; every other field is left out when
 * unknown. {@code id} is the record id the phone, the browser and this server share (a UUID in practice, a string
 * of the record id pattern in general), {@code rating} is 1..5 and {@code updatedAt} is epoch milliseconds UTC.
 * On the server a broker lives in the {@code record} table as type {@code broker}; the payload holds every field
 * here except {@code id} and {@code updatedAt}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "name", "phone", "agency", "feeTerms", "notes", "rating", "updatedAt"})
public record BackupBroker(
        String id,
        String name,
        String phone,
        String agency,
        String feeTerms,
        String notes,
        Integer rating,
        Long updatedAt
) {
    public static final String TYPE = "broker";
    public static final int MAX_NAME = 200;
    public static final int MAX_PHONE = 50;
    public static final int MAX_AGENCY = 200;
    public static final int MAX_FEE_TERMS = 500;
    public static final int MAX_NOTES = 2000;
}
