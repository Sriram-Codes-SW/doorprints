package app.doorprints.ui

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource

/**
 * A UI string outside composition (a snackbar message, a share failure): the Compose resource in the app's language.
 * Compose resources take the language from `Locale.getDefault()`, which [app.doorprints.i18n.AppLocale] keeps equal
 * to the app's language on every API level. Blocking, but the strings file is read once and then cached; called from
 * click handlers and effects only. Goes away as these helpers move into composition (ADR-23 CMP-3).
 */
fun Context.getString(resource: StringResource, vararg formatArgs: Any): String =
    runBlocking { org.jetbrains.compose.resources.getString(resource, *formatArgs) }
