package app.doorprints

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.work.WorkInfo
import app.doorprints.data.AndroidRepository
import app.doorprints.export.AutoBackupWorker
import app.doorprints.export.CopyImportUndo
import app.doorprints.export.CopyRecord
import app.doorprints.export.CopyUndoOutcome
import app.doorprints.export.ExportProblem
import app.doorprints.export.ImportUndo
import app.doorprints.export.Saf
import app.doorprints.export.ScreenWatch
import app.doorprints.export.messageRes
import app.doorprints.i18n.AppLocale
import app.doorprints.ui.AppServices
import app.doorprints.ui.CopyImportUndoes
import app.doorprints.ui.LanguageChange
import app.doorprints.ui.LocationSource
import app.doorprints.ui.SettingsServices
import app.doorprints.ui.currentLocation
import app.doorprints.ui.findActivity
import app.doorprints.ui.hasLocationPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The common screens' [AppServices] on Android (ADR-23 CMP-5): one per process, in [AppContainer]. It holds the
 * application, never an activity, so [AssistantViewModel][app.doorprints.ui.AssistantViewModel] can keep its members
 * across a rotation; what needs the activity (the language switch, the folder picker) reads it from the composition.
 * Each member forwards to the code the screens called before they moved to `:ui`, with the same context.
 */
class AndroidAppServices(private val app: DoorprintsApp, override val repository: AndroidRepository) : AppServices {
    override val appScope: CoroutineScope get() = app.appScope

    override val location: LocationSource = object : LocationSource {
        override suspend fun current(): Pair<Double, Double>? = currentLocation(app)
        override fun hasPrecisePermission(): Boolean = hasLocationPermission(app)
    }

    override val copyImports: CopyImportUndoes = object : CopyImportUndoes {
        override val undoingRun: String? get() = CopyImportUndo.undoingRun
        override val outcome: CopyUndoOutcome? get() = CopyImportUndo.outcome
        override suspend fun load(runId: String): CopyRecord? =
            withContext(Dispatchers.IO) { ImportUndo.load(app, runId) }
        override suspend fun latestUndoable(): CopyRecord? =
            withContext(Dispatchers.IO) { ImportUndo.latestUndoable(app) }
        override fun hideRow(runId: String) {
            app.appScope.launch(Dispatchers.IO) { ImportUndo.hideRow(app, runId) }
        }
        override fun start(record: CopyRecord): Boolean = CopyImportUndo.start(app, record)
    }

    override val settingsScreen: SettingsServices = AndroidSettingsServices(app)

    override fun consumeLanguageChange(): LanguageChange? =
        AppLocale.consumeChange(app)?.let { LanguageChange(it.language) }
}

/** [SettingsServices] on Android: `AppLocale`, `AutoBackupWorker`, `Saf` and the package manager. */
private class AndroidSettingsServices(private val app: DoorprintsApp) : SettingsServices {
    override val supportedLanguages: List<String> get() = AppLocale.SUPPORTED

    override fun currentLanguage(): String? = AppLocale.current(app)

    @Composable
    override fun rememberLanguageSwitch(): (String?) -> Unit {
        val context = LocalContext.current
        // AppLocale.set recreates the activity (API 32 and lower) or has the system do it (33+).
        return { code -> context.findActivity()?.let { AppLocale.set(it, code) } }
    }

    @Composable
    override fun rememberBackupFolderPicker(onPicked: (folder: String?) -> Unit): () -> Unit {
        val context = LocalContext.current
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            if (tree != null) {
                // Taken now, while this activity still holds the picker's grant.
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { context.contentResolver.takePersistableUriPermission(tree, flags) }
            }
            onPicked(tree?.toString())
        }
        return { launcher.launch(null) }
    }

    override suspend fun backupFolderLabel(folder: String): String? =
        withContext(Dispatchers.IO) { Saf.folderLabel(app, Uri.parse(folder)) }

    /** Created on first use (Settings' first frame), after WorkManager is set up; one flow for the process. */
    override val backingUpNow: Flow<Boolean> by lazy {
        AutoBackupWorker.observeNow(app).map { runs ->
            runs.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        }
    }

    override fun backUpNow() = AutoBackupWorker.runNow(app)

    override fun scheduleBackup(enabled: Boolean) = AutoBackupWorker.schedule(app, enabled)

    override suspend fun releaseBackupFolder(folder: String) = AutoBackupWorker.releaseFolder(app, folder)

    override val backupNoFolderError: String get() = AutoBackupWorker.ERROR_NO_FOLDER

    /** The service strings' reason (Android resources, shared with the backup notification; S4b-BL-35). */
    @Composable
    override fun backupErrorText(code: String): String = stringResource(ExportProblem.fromCode(code).messageRes())

    override fun settingsVisible(visible: Boolean) {
        ScreenWatch.settingsScreen = visible
    }

    /** The installed version name ("0.1.0"), or null if the package manager cannot say. */
    override fun appVersion(): String? = runCatching {
        val pm = app.packageManager
        val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(app.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(app.packageName, 0)
        }
        info.versionName
    }.getOrNull()
}
