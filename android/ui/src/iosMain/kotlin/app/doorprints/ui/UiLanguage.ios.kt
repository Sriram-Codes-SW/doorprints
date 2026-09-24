package app.doorprints.ui

import androidx.compose.runtime.Composable

// The app's preferred language as iOS resolves it ([appLanguage]). The iOS app's own language setting is ADR-23
// phase 8.
@Composable
actual fun uiLanguage(): String = appLanguage()
