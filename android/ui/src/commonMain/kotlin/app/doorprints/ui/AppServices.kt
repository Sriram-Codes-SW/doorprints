package app.doorprints.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import app.doorprints.data.Repository
import app.doorprints.export.CopyRecord
import app.doorprints.export.CopyUndoOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * What the common screens need from the app around them (ADR-23 CMP-5), as opposed to the operating system's
 * [PlatformServices]: the app's data ([repository]) and the few app features that are still Android code in `:app`
 * (WorkManager, the Storage Access Framework, the app language, Play services location, the copy-import undo files),
 * each behind a small interface. Android: `AndroidAppServices` in `:app`, one per process (it holds the application,
 * never an activity), provided by `ProvideAppServices` in MainActivity and in the screenshot tests. iOS: with the iOS
 * shell (CMP-8).
 *
 * Its members may be handed to a view model ([AssistantViewModel] takes [repository] and [location]); the composable
 * members read the activity from the composition instead of holding it.
 */
interface AppServices {
    /** The app's data: houses, visits, photos, settings, sync and the AI calls. */
    val repository: Repository

    /**
     * Work that must outlive the screen that started it (a backup folder's grant given back after leaving Settings,
     * the house list's "hide this row" write): the application's scope.
     */
    val appScope: CoroutineScope

    /** A location fix for *Plan visits*. */
    val location: LocationSource

    /** The copy imports' undo records and the undo itself, as the house list shows them. */
    val copyImports: CopyImportUndoes

    /** The app features the Settings screen shows: language, the weekly backup, the version. */
    val settingsScreen: SettingsServices

    /**
     * The language chosen in Settings just before the app was recreated for it, once: the root's "Language changed to
     * …" snackbar. Null when there was no recent change; a [LanguageChange] with a null language for "System default".
     */
    fun consumeLanguageChange(): LanguageChange?
}

/** A language change to confirm; [language] is null for "System default". */
data class LanguageChange(val language: String?)

/** Where the phone is, for the Assistant's *Plan visits* (Android: Play services' fused location, `:app`). */
interface LocationSource {
    /**
     * A current fix (latitude, longitude), or null without precise location or when none came. Returns on the main
     * thread.
     */
    suspend fun current(): Pair<Double, Double>?

    /** True while precise location is allowed: a null [current] is then a real failure, not the user's choice. */
    fun hasPrecisePermission(): Boolean
}

/**
 * The copy imports' undo (UX review, rounds 16 to 21), as the house list uses it; the Import screen uses the same one
 * in `:app` until CMP-6. Android: `ImportUndo`'s record files and `CopyImportUndo`, which runs the undo in the
 * application's scope and holds its state for the life of the process.
 */
interface CopyImportUndoes {
    /** The run whose copies are being removed right now, or null. Compose snapshot state. */
    val undoingRun: String?

    /** The outcome of the last undo in this process, or null before the first one. Compose snapshot state. */
    val outcome: CopyUndoOutcome?

    /** The record of [runId] while it can still be undone, else null. Reads storage off the main thread. */
    suspend fun load(runId: String): CopyRecord?

    /** The newest record that can still be undone, or null. Reads storage off the main thread. */
    suspend fun latestUndoable(): CopyRecord?

    /** Marks [runId]'s record so the list no longer shows its undo row; written in the application's scope. */
    fun hideRow(runId: String)

    /** Starts undoing [record]; false, and nothing done, while another undo runs or for a record already undone. */
    fun start(record: CopyRecord): Boolean
}

/**
 * The app features on the Settings screen that are still Android code in `:app` (CMP-5): the app language
 * (per-app locales, `AppLocale`), the weekly backup (WorkManager and a Storage Access Framework folder,
 * `AutoBackupWorker`, `Saf`) and the installed version.
 */
interface SettingsServices {
    /** The languages the app ships, in the order Settings lists them after "System default". */
    val supportedLanguages: List<String>

    /** The language chosen in Settings, or null for "System default". */
    fun currentLanguage(): String?

    /**
     * Returns the function that switches the app to a language (null: "System default"). Android recreates the
     * activity with the new language, and the root then confirms it ([AppServices.consumeLanguageChange]).
     */
    @Composable
    fun rememberLanguageSwitch(): (String?) -> Unit

    /**
     * Returns the function that opens the system's folder picker for the weekly backup. [onPicked] gets the chosen
     * folder (a persisted grant is taken first, while the activity still holds the picker's), or null when the user
     * backed out.
     */
    @Composable
    fun rememberBackupFolderPicker(onPicked: (folder: String?) -> Unit): () -> Unit

    /** A readable name for a backup [folder] ("Download/Doorprints"), or null. Reads storage off the main thread. */
    suspend fun backupFolderLabel(folder: String): String?

    /** True while a *Back up now* run is queued or running. */
    val backingUpNow: Flow<Boolean>

    /** Starts a *Back up now* run (a second tap while one runs does nothing). */
    fun backUpNow()

    /** Turns the weekly backup job on or off. */
    fun scheduleBackup(enabled: Boolean)

    /** Gives back the grant on a backup [folder] the settings no longer name, unless a run is still writing there. */
    suspend fun releaseBackupFolder(folder: String)

    /** The stored error code of a backup whose folder is gone ("Choose it again"). */
    val backupNoFolderError: String

    /** The translated reason for any other stored backup error code. */
    @Composable
    fun backupErrorText(code: String): String

    /** Settings is on screen (started): a backup that ends now is shown here, not only in a notification. */
    fun settingsVisible(visible: Boolean)

    /** The installed version name ("0.1.0"), or null. */
    fun appVersion(): String?
}

/**
 * The [AppServices] of this composition. Every root provides it (Android: `ProvideAppServices` in `:app`, in
 * MainActivity and in the screenshot tests); reading it without a provider fails loudly.
 */
val LocalAppServices = staticCompositionLocalOf<AppServices> {
    error("No AppServices: wrap the content in ProvideAppServices")
}
