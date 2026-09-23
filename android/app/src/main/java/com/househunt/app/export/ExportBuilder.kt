package com.househunt.app.export

import android.content.Context
import com.househunt.app.data.Repository
import com.househunt.app.data.toExport
import com.househunt.app.i18n.AppLocale
import com.househunt.shared.export.ExportBundle
import com.househunt.shared.export.ExportOptions
import java.util.TimeZone

/**
 * Reads Room once and hands the platform-neutral [ExportBundle] to the exporters.
 *
 * The clock and the time zone are read here, not inside `:shared`: the pure code takes the export instant and a
 * fixed UTC offset as data, so the same bundle always produces the same file and the golden tests have something
 * to pin (see `ExportTime`).
 */
object ExportBuilder {

    suspend fun bundle(repository: Repository, options: ExportOptions): ExportBundle =
        build(repository.localRows(), options)

    /**
     * The bundle from rows already read. Pure CPU work (mapping and filtering every row), so the Export screen's
     * live count calls it on `Dispatchers.Default` with rows it read once, instead of re-reading Room and mapping on
     * the main thread for every option tap.
     */
    fun build(rows: Repository.LocalRows, options: ExportOptions): ExportBundle = ExportBundle.build(
        options,
        rows.houses.map { it.toExport() },
        rows.visits.map { it.toExport() },
        rows.photos.map { it.toExport() },
    )

    /**
     * The app's own version, for the backup manifest. Read from the package manager rather than `BuildConfig`,
     * which AGP does not generate unless the `buildConfig` build feature is turned back on (off by default since
     * AGP 8) — and a version string in a manifest is not worth a build-feature change.
     */
    fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /**
     * The options the export screen opens with: this phone's current UTC offset, the app's language, everything
     * included. The offset is taken once, at the moment of export, so a copy made in India always reads in IST
     * even if it is opened later from another country.
     */
    fun defaults(context: Context, now: Long = System.currentTimeMillis()) = ExportOptions(
        language = AppLocale.current(context)
            ?: context.resources.configuration.locales[0]?.language?.takeIf { it in AppLocale.SUPPORTED }
            ?: "en",
        utcOffsetMinutes = TimeZone.getDefault().getOffset(now) / 60_000,
        exportedAtMillis = now,
    )
}
