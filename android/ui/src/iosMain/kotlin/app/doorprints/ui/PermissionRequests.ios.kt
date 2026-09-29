package app.doorprints.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

// The iOS prompts (CMP-8b). Location: Core Location's "Allow Doorprints to use your location?" ([LocationPrompt]).
// Notifications: none on iOS yet (Hunt mode, their only sender, is hidden by PlatformFeatures.Ios), so the action
// goes ahead as after a refusal.

@Composable
actual fun rememberLocationPermissionRequest(onResult: () -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onResult)
    val prompt = remember { LocationPrompt { latest() } }
    // A screen left before the answer drops its manager; the answer then reaches the next screen's refresh on resume.
    DisposableEffect(prompt) { onDispose { prompt.release() } }
    return remember(prompt) { { prompt.show() } }
}

@Composable
actual fun rememberNotificationPermissionRequest(onResult: () -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onResult)
    return remember { { latest() } }
}
