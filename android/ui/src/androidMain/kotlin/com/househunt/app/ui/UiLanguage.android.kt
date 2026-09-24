package com.househunt.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

// The configuration's locale is the per-app language on every supported API level (AppLocale): the platform applies it
// on 13+, and AppLocale.wrap puts it in the activity's configuration on 8–12.
@Composable
internal actual fun uiLanguage(): String = LocalConfiguration.current.locales[0]?.language.orEmpty()
