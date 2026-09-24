package app.doorprints.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

// No permission prompts on iOS until the iOS shell (ADR-23 CMP-8, CLLocationManager and UNUserNotificationCenter):
// the action goes ahead as after a refusal, and the screens then show their notes. Compile-only for now.

@Composable
actual fun rememberLocationPermissionRequest(onResult: () -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onResult)
    return { latest() }
}

@Composable
actual fun rememberNotificationPermissionRequest(onResult: () -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onResult)
    return { latest() }
}
