package com.househunt.app.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Per-app language without AppCompat (docs/05 section 8.2).
 *
 *  - Android 13+ (API 33): the platform [LocaleManager] stores the choice, recreates activities and shows the same
 *    list in system Settings > Apps > Doorprints > Language (from res/xml/locales_config.xml).
 *  - Android 8-12: the choice is kept in a small SharedPreferences file and applied by wrapping each Activity's and
 *    Service's base context ([wrap]), then the activity is recreated.
 *
 * `null` means "follow the system language".
 */
object AppLocale {
    /** Same languages as the web app (web/src/app/i18n/languages.ts). */
    val SUPPORTED = listOf("en", "hi", "ta", "te")

    private const val PREFS = "app_locale"
    private const val KEY = "language"

    /** The language just chosen in Settings, to confirm once after the recreate ([consumeChange]). */
    private const val KEY_CHANGED = "changedTo"
    private const val KEY_CHANGED_AT = "changedAt"

    /** "" in [KEY_CHANGED] means "follow the system language" (null cannot be stored as a marker). */
    private const val SYSTEM = ""

    /** A change older than this is not announced (the app was closed before it came back). */
    private const val ANNOUNCE_WITHIN_MS = 30_000L

    fun current(context: Context): String? {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            return if (locales == null || locales.isEmpty) null else locales[0].language
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    }

    fun set(activity: Activity, language: String?) {
        val tag = language?.takeIf { it in SUPPORTED }
        // Written before the recreate, read once by Root after it (UX review, whole-app audit): "Language changed to
        // தமிழ்" is the confirmation, for TalkBack as well as on screen. commit(), not apply(): the process may
        // recreate the activity before an asynchronous write lands.
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CHANGED, tag ?: SYSTEM)
            .putLong(KEY_CHANGED_AT, System.currentTimeMillis())
            .commit()
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            // The system recreates the activity with the new configuration.
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
            activity.recreate()
        }
    }

    /**
     * The language chosen in Settings just before this activity was recreated, once: returns it (null inside the
     * [Change] for "System default") and forgets it, so a later recreation does not announce it again. Null when there
     * was no recent change.
     */
    fun consumeChange(context: Context): Change? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val changed = prefs.getString(KEY_CHANGED, null) ?: return null
        val at = prefs.getLong(KEY_CHANGED_AT, 0L)
        prefs.edit().remove(KEY_CHANGED).remove(KEY_CHANGED_AT).apply()
        if (System.currentTimeMillis() - at !in 0..ANNOUNCE_WITHIN_MS) return null
        return Change(changed.takeIf { it != SYSTEM })
    }

    /** A language change to confirm; [language] is null for "System default". */
    data class Change(val language: String?)

    /** Applies the saved language on Android 12 and lower; a no-op on 13+ (the platform does it). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return base
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
