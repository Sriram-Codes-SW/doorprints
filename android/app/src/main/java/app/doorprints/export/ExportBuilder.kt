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

package app.doorprints.export

import android.content.Context
import app.doorprints.data.Repository
import app.doorprints.data.toBundle
import app.doorprints.i18n.AppLocale
import app.doorprints.shared.export.ExportBundle
import app.doorprints.shared.export.ExportOptions

/**
 * Reads Room once and hands the platform-neutral [ExportBundle] to the exporters.
 *
 * The clock and the time zone are read here, not inside `:shared`: the pure code takes the export instant and a
 * fixed UTC offset as data, so the same bundle always produces the same file and the golden tests have something
 * to pin (see `ExportTime`).
 */
object ExportBuilder {

    /**
     * Reads the local rows once and returns the [ExportBundle] that [options] select, ready for [Exporters.write].
     */
    suspend fun bundle(repository: Repository, options: ExportOptions): ExportBundle =
        build(repository.localRows(), options)

    /** The bundle from rows already read ([toBundle], common since CMP-6 P6b; the Export screen's count uses it too). */
    fun build(rows: Repository.LocalRows, options: ExportOptions): ExportBundle = rows.toBundle(options)

    /**
     * The app's own version, for the backup manifest. Read from the package manager rather than `BuildConfig`,
     * which AGP does not generate unless the `buildConfig` build feature is turned back on (off by default since
     * AGP 8) — and a version string in a manifest is not worth a build-feature change.
     */
    fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /**
     * The options the export screen opens with: the app's language, everything included, and the times in UTC
     * ([ExportOptions.utcOffsetMinutes] 0; S4b-BL-92c): the website writes every copy in UTC, so a copy of the same
     * data reads the same from either, and the cover says so ("Times shown for UTC +00:00").
     */
    fun defaults(context: Context, now: Long = System.currentTimeMillis()) = ExportOptions(
        language = AppLocale.current(context) ?: AppLocale.resolved(context),
        utcOffsetMinutes = 0,
        exportedAtMillis = now,
    )
}
