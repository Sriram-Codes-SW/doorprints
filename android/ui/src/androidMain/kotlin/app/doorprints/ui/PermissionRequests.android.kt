package app.doorprints.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

@Composable
actual fun rememberLocationPermissionRequest(onResult: () -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        onResult()
    }
    return { launcher.launch(LOCATION_PERMISSIONS) }
}

@Composable
actual fun rememberNotificationPermissionRequest(onResult: () -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> onResult() }
    return {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (_: ActivityNotFoundException) {
                onResult()
            }
        } else {
            onResult()
        }
    }
}
