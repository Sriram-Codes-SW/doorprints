package app.doorprints.ui

import androidx.compose.runtime.Composable

// No system Back on iOS; the swipe-back gesture comes with the iOS shell (ADR-23 CMP-8). Compile-only for now.
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
