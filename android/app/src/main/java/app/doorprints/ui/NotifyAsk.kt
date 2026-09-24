package app.doorprints.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.Notifications
import app.doorprints.ui.res.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Asks for notification permission **in context**, once (UX review, round 11).
 *
 * A long export or import tells the user it has finished with a notification when they have left the screen. On
 * API 33+ that needs `POST_NOTIFICATIONS`, which the app otherwise asks for only once, bundled with location on the
 * Map; a user who said no there never heard about a finished or failed backup. So the first *Save to…*, *Share* or
 * *Import* tap while it is not granted shows one line of why ("So we can tell you when a long copy is finished")
 * with *Allow notifications* and *Not now*, then the system prompt on *Allow*. **Whatever the answer, the action
 * goes ahead**: the screens show a result nobody was told about anyway (`ExportRequest.KEY_NOTIFIED`), so saying
 * no costs nothing but the notification.
 *
 * Returns a function that runs an action through this: straight away below API 33, when the permission is granted,
 * or once the app has asked before (`SettingsStore.notificationsAsked`, so the question is not repeated on every
 * tap; the system itself stops showing its prompt after two refusals). Until the stored flag has been read the app
 * assumes it has asked, so a first tap is never held up.
 *
 * The pending action is plain `remember`: after a rotation in the middle of the question it is dropped with the
 * dialog, and the user taps again.
 *
 * [rationale] is the one line of why (UX review, whole-app audit): Hunt mode asks with its own, "Hunt mode tells you
 * with a notification when you pass a house you have seen.", when it is turned on, instead of the Map asking for
 * notifications with no context at first launch. The stored flag is shared, so the question is asked once in all.
 */
@Composable
fun rememberNotificationAsk(rationale: StringResource = Res.string.notify_rationale): (action: () -> Unit) -> Unit {
    val context = LocalContext.current
    val settings = repository().settings
    val asked by settings.notificationsAsked.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    /** The one-line question is on screen; hidden before the system prompt so the two never stack. */
    var showing by remember { mutableStateOf(false) }

    fun proceed() {
        val next = pending
        pending = null
        next?.invoke()
    }

    fun markAsked() {
        scope.launch { settings.setNotificationsAsked() }
    }

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> proceed() }

    if (showing) {
        AlertDialog(
            onDismissRequest = {
                showing = false
                markAsked()
                proceed()
            },
            text = { Text(stringResource(rationale)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showing = false
                        markAsked()
                        if (Build.VERSION.SDK_INT >= 33) {
                            try {
                                request.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } catch (_: ActivityNotFoundException) {
                                proceed()
                            }
                        } else {
                            proceed()
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { ButtonLabel(stringResource(Res.string.notify_allow)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showing = false
                        markAsked()
                        proceed()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { ButtonLabel(stringResource(Res.string.notify_not_now)) }
            },
        )
    }

    return { action ->
        if (Build.VERSION.SDK_INT >= 33 && !asked && !Notifications.canPost(context)) {
            pending = action
            showing = true
        } else {
            action()
        }
    }
}
