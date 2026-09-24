package app.doorprints.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

// The language Android resolved for the app's strings (S4b-BL-18): AppLocale.applyDefault keeps the default locale on
// it, on every API level. The configuration's first locale can be a language the app does not ship (a phone set to
// [Marathi, Hindi] shows Hindi strings), so it is only read to recompose after a configuration change.
@Composable
actual fun uiLanguage(): String {
    LocalConfiguration.current
    return appLanguage()
}
