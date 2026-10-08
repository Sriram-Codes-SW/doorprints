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

package app.doorprints.shared.model

import app.doorprints.shared.records.RecordType
import kotlinx.serialization.Serializable

/**
 * A broker (docs/11 5.25, 5.30 slice 1b): the first record type. The record id is the broker's UUID; the payload keys
 * are exactly these and in this order, all optional but [name], absent meaning unknown and never written as `null`.
 * The TypeScript twin is `web/src/app/shared/broker.ts`; the server stores the same keys in its `record` table.
 */
@Serializable
data class Broker(
    /** 1..[MAX_NAME] characters. */
    val name: String,
    /** Up to [MAX_PHONE] characters, as typed. */
    val phone: String? = null,
    val agency: String? = null,
    /** Free text such as "15 days' rent, once". */
    val feeTerms: String? = null,
    val notes: String? = null,
    /** 1..5 stars. */
    val rating: Int? = null,
) {
    /**
     * What a reader keeps (a file, the server, another device), like [HouseCost.coerced]: a value outside its range
     * becomes unknown and blank text too; null when the name is blank or too long, and the row is skipped as
     * untrusted (the server refuses such a write instead).
     */
    fun coerced(): Broker? {
        val n = name.trim()
        if (n.isEmpty() || n.length > MAX_NAME) return null
        return Broker(
            name = n,
            phone = text(phone, MAX_PHONE),
            agency = text(agency, MAX_AGENCY),
            feeTerms = text(feeTerms, MAX_FEE_TERMS),
            notes = text(notes, MAX_NOTES),
            rating = rating?.takeIf { it in 1..5 },
        )
    }

    /** True when every value is within its range: what a backup's check demands (a bad file is refused whole). */
    val isValid: Boolean
        get() = name.isNotBlank() && name.length <= MAX_NAME && (phone?.length ?: 0) <= MAX_PHONE &&
            (agency?.length ?: 0) <= MAX_AGENCY && (feeTerms?.length ?: 0) <= MAX_FEE_TERMS &&
            (notes?.length ?: 0) <= MAX_NOTES && (rating == null || rating in 1..5)

    /** The name with " (agency)" when there is an agency: the readable copies' `broker` column and Compare's row. */
    val label: String get() = if (agency.isNullOrBlank()) name else "$name ($agency)"

    /** What a house search matches of its broker (docs/11 5.25): the name, the agency and the fee terms. */
    val searchText: String get() = listOfNotNull(name, agency, feeTerms).joinToString(" ")

    private fun text(value: String?, max: Int): String? = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= max }

    companion object {
        const val MAX_NAME = 200
        const val MAX_PHONE = 50
        const val MAX_AGENCY = 200
        const val MAX_FEE_TERMS = 500
        const val MAX_NOTES = 2000
    }
}

/** The `broker` record type of the `records` table (slice 0). */
val BrokerType: RecordType<Broker> = RecordType("broker", Broker.serializer())

/**
 * A phone number as a comparison key (docs/11 5.25): the digits only, the last ten of them, and null when there are
 * fewer than [MIN_DIGITS], so "+91 98400 11111", "098400-11111" and "9840011111" are one number and a stray "12"
 * matches nothing. The TypeScript twin is `phoneKey` in `web/src/app/shared/broker.ts`.
 */
object PhoneKey {
    const val MIN_DIGITS = 6
    const val KEPT_DIGITS = 10

    /** The comparison key of [phone], or null when it has too few digits to identify anyone. */
    fun of(phone: String?): String? {
        if (phone == null) return null
        val digits = phone.filter { it in '0'..'9' }
        return if (digits.length < MIN_DIGITS) null else digits.takeLast(KEPT_DIGITS)
    }
}
