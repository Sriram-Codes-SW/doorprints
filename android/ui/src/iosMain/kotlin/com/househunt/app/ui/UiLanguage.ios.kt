package com.househunt.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.intl.Locale

// The app's preferred language as iOS resolves it (Locale.current reads NSLocale). The iOS app's own language setting
// is ADR-23 phase 8.
@Composable
internal actual fun uiLanguage(): String = Locale.current.language
