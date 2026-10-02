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

package app.doorprints.drive

import io.ktor.http.fromHttpToGmtDate
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Every failure of a [DriveClient] (S4b-BL-115), on both stacks the same kinds (web: `DriveError`). An [IOException]
 * like [app.doorprints.shared.api.ApiException], so code that only knows "a network problem" still treats it as one;
 * [app.doorprints.shared.sync.SyncOutcome.fromError] reads the [kind]. The message names the kind and the status only:
 * never a token, a session URI, a file name or a file's bytes (docs/02 §10).
 */
class DriveException(
    val kind: Kind,
    /** The HTTP status Drive answered with; 0 when there was no answer (offline, a check of our own). */
    val httpStatus: Int = 0,
    /** How long Drive asked us to wait (`Retry-After`), in milliseconds. */
    val retryAfterMs: Long? = null,
    /** Drive's own reason (`storageQuotaExceeded`, `userRateLimitExceeded`, ...), when it gave one. */
    val reason: String? = null,
    cause: Throwable? = null,
) : IOException("Drive ${kind.name} (HTTP $httpStatus)", cause) {

    enum class Kind {
        /** 401 twice: the token was refused, and the one the [TokenProvider] gave next too. Connect again. */
        UNAUTHORIZED,

        /** 403 for any reason but quota or rate: no access to that file (moved out of reach, `drive.file`). */
        FORBIDDEN,

        /** 403 `storageQuotaExceeded`: the person's Drive is full (docs/15 §5.2). */
        QUOTA_EXCEEDED,

        /** 429, or 403 `userRateLimitExceeded`/`rateLimitExceeded`; [retryAfterMs] when Drive said. Retried. */
        RATE_LIMITED,

        /** 404 (and 410): no such file, or a resumable session Drive forgot. A delete counts it as done. */
        NOT_FOUND,

        /** 409 or 412. */
        CONFLICT,

        /** 400, 416 and any other 4xx: a request Drive will never take (a bug, or an app property too long). */
        BAD_REQUEST,

        /** 5xx. Retried. */
        SERVER,

        /** No answer: no network, a dropped connection, a timeout, or a redirect (a captive portal). Retried. */
        OFFLINE,

        /** Drive closed the request (499), or the caller stopped a transfer between chunks. */
        CANCELLED,

        /** An answer that is not what Drive sends, or content whose SHA-256 is not the one expected. */
        CORRUPT,
    }

    /** Waiting and asking again can help: [Kind.RATE_LIMITED], [Kind.SERVER], [Kind.OFFLINE]. */
    val isRetryable: Boolean get() = kind == Kind.RATE_LIMITED || kind == Kind.SERVER || kind == Kind.OFFLINE

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The exception for a non-2xx answer: [status], the `Retry-After` header and Drive's JSON error body (only
         * its `reason`s are read, never echoed). Pinned on both stacks by drive-vectors.json `errors`.
         */
        fun fromHttp(status: Int, retryAfter: String?, body: String?, nowMs: Long): DriveException {
            val reason = reasonOf(body)
            val wait = retryAfterMs(retryAfter, nowMs)
            val kind = when {
                status == 401 -> Kind.UNAUTHORIZED
                status == 403 && reason == "storageQuotaExceeded" -> Kind.QUOTA_EXCEEDED
                status == 403 && (reason == "userRateLimitExceeded" || reason == "rateLimitExceeded") -> Kind.RATE_LIMITED
                status == 403 -> Kind.FORBIDDEN
                status == 404 || status == 410 -> Kind.NOT_FOUND
                status == 409 || status == 412 -> Kind.CONFLICT
                status == 429 -> Kind.RATE_LIMITED
                status == 499 -> Kind.CANCELLED
                status in 300..399 -> Kind.OFFLINE
                status in 400..499 -> Kind.BAD_REQUEST
                else -> Kind.SERVER
            }
            return DriveException(kind, status, wait, reason)
        }

        /** The first `errors[].reason` (Drive v3), else the first `details[].reason` (Google's newer ErrorInfo). */
        fun reasonOf(body: String?): String? {
            if (body.isNullOrBlank()) return null
            val error = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()?.get("error") as? JsonObject
                ?: return null
            fun first(key: String): String? = (error[key] as? JsonArray)?.firstNotNullOfOrNull {
                ((it as? JsonObject)?.get("reason"))?.jsonPrimitive?.contentOrNull
            }
            return first("errors") ?: first("details")
        }

        /** `Retry-After` as delay-seconds or an HTTP-date (RFC 9110), in milliseconds from [nowMs]; null if neither. */
        fun retryAfterMs(value: String?, nowMs: Long): Long? {
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return null
            text.toLongOrNull()?.let { return if (it >= 0) it * 1000 else null }
            // Ktor's own HTTP-date parser (IMF-fixdate, the form Google sends).
            val at = runCatching { text.fromHttpToGmtDate().timestamp }.getOrNull() ?: return null
            return (at - nowMs).coerceAtLeast(0)
        }
    }
}
