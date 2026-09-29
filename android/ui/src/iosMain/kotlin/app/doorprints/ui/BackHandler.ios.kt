package app.doorprints.ui

import androidx.compose.runtime.Composable
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

// iOS has no system Back button; Compose Multiplatform turns the swipe from the screen's leading edge into a back
// event (CMP-8b), which the navigation graph and this handler hear through navigationevent, as the Android back
// gesture does. While enabled, a completed swipe calls onBack (the house form's "Discard changes?") instead of popping.
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = enabled,
        onBackCompleted = onBack,
    )
}
