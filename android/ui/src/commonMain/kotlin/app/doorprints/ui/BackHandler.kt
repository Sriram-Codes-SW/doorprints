package app.doorprints.ui

import androidx.compose.runtime.Composable

/**
 * While [enabled], the system Back gesture or button calls [onBack] instead of leaving the screen (CMP-6: the house
 * form asks before unsaved changes are lost). Android: androidx.activity's `BackHandler`, as the form used before.
 * iOS has no system Back; the swipe from the left edge does the same, through `NavigationBackHandler` (CMP-8b).
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
