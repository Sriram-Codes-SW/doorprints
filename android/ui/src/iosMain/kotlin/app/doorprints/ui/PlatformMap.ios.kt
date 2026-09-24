package app.doorprints.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.doorprints.data.HouseEntity

/**
 * iOS stand-in, compile-only (ADR-23 CMP-7): an empty box, so the common Map's chrome builds for iOS. The real view,
 * `UIKitView` around MapLibre iOS's `MLNMapView` with its own [StyleOps] for [applyIndiaView], comes with the iOS shell
 * (CMP-8). Until then [events] hears nothing, so the chrome shows "Loading the map…".
 */
@Composable
actual fun PlatformMap(
    houses: List<HouseEntity>,
    labelSizeSp: Float,
    showLocation: Boolean,
    attribution: MapAttribution,
    events: MapEvents,
    modifier: Modifier,
) {
    Box(modifier)
}
