package app.doorprints.ui

import androidx.compose.runtime.Composable

/**
 * The system's location prompt for this screen (CMP-5): returns a function that shows it, asking for precise and
 * approximate location together, as Android recommends; [onResult] runs when the user has answered, whatever the
 * answer (read the new state from [LocationAsk.refresh] and [PlatformServices.locationAccess]). The caller records
 * the ask first ([LocationAsk.markAsked]). Android: an activity-result launcher for `LOCATION_PERMISSIONS`, registered
 * with the composition, so the answer arrives even after a rotation. iOS: until the iOS shell (CMP-8) there is no
 * prompt, and [onResult] runs at once.
 */
@Composable
expect fun rememberLocationPermissionRequest(onResult: () -> Unit): () -> Unit

/**
 * The system's notification prompt (CMP-5, [rememberNotificationAsk]): returns a function that shows it where the
 * platform has one and [onResult] once it is answered; where there is none (Android below API 33, or no activity to
 * handle the request) [onResult] runs at once. iOS: until the iOS shell (CMP-8), [onResult] runs at once.
 */
@Composable
expect fun rememberNotificationPermissionRequest(onResult: () -> Unit): () -> Unit
