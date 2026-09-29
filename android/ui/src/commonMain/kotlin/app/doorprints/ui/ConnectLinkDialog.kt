/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package app.doorprints.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.doorprints.data.ConnectLink
import app.doorprints.data.Repository
import app.doorprints.shared.api.ApiException
import app.doorprints.shared.sync.SyncOutcome
import app.doorprints.ui.res.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** How redeeming a connect link ended. */
sealed interface ConnectLinkEnd {
    /** Connected: the device key is saved for the link's server; [houses] on it, or null when the test failed. */
    data class Connected(val houses: Long?) : ConnectLinkEnd
    /** The invite was already used or has expired (410). */
    data object Used : ConnectLinkEnd
    data class Failed(val error: Throwable) : ConnectLinkEnd
}

/**
 * Redeems [link]'s invite with this phone's [deviceName], saves the device key for the link's server, and tests the
 * connection (docs/03 §12.1). Called only after the person chose *Connect*.
 */
suspend fun connectWithLink(repo: Repository, link: ConnectLink, deviceName: String): ConnectLinkEnd {
    val key = try {
        repo.redeemInvite(link, deviceName)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        return if (e.code == 410) ConnectLinkEnd.Used else ConnectLinkEnd.Failed(e)
    } catch (e: Exception) {
        return ConnectLinkEnd.Failed(e)
    }
    repo.settings.saveServer(link.server, key)
    val houses = repo.testConnection().getOrNull()?.houses
    runCatching { repo.refreshAiStatus() }
    return ConnectLinkEnd.Connected(houses)
}

/**
 * *Connect to a server?*, for a connect link opened from the owner page's QR code. It shows the server's address and
 * sends nothing until *Connect*: anyone can make such a link, and connecting to a stranger's server would copy this
 * phone's houses to it. Then it says how it went; [onDone] closes it.
 */
@Composable
fun ConnectLinkDialog(link: ConnectLink, repo: Repository, deviceName: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var end by remember { mutableStateOf<ConnectLinkEnd?>(null) }
    val connected = end as? ConnectLinkEnd.Connected
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = {
            Text(
                stringResource(if (connected != null) Res.string.connect_link_done_title else Res.string.connect_link_title),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connected != null) {
                    Text(
                        if (connected.houses != null) {
                            stringResource(Res.string.settings_connected, connected.houses)
                        } else {
                            stringResource(Res.string.connect_link_saved)
                        },
                    )
                } else {
                    Text(stringResource(Res.string.connect_link_body))
                    Text(link.server, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                    Text(stringResource(Res.string.connect_link_warning), style = MaterialTheme.typography.bodySmall)
                    val failure = when (val e = end) {
                        ConnectLinkEnd.Used -> stringResource(Res.string.connect_link_used)
                        is ConnectLinkEnd.Failed ->
                            stringResource(Res.string.settings_connect_failed, SyncOutcome.fromError(e.error).text())
                        else -> null
                    }
                    LiveMessage(assertive = true) {
                        if (failure != null) ResultCard(tone = ResultTone.ERROR, text = failure)
                    }
                }
            }
        },
        confirmButton = {
            if (connected != null) {
                TextButton(onClick = onDone) { Text(stringResource(Res.string.common_close)) }
            } else if (end != ConnectLinkEnd.Used) {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        end = connectWithLink(repo, link, deviceName)
                        busy = false
                    }
                }) {
                    Text(stringResource(if (busy) Res.string.connect_link_connecting else Res.string.connect_link_connect))
                }
            }
        },
        dismissButton = {
            if (connected == null) {
                TextButton(enabled = !busy, onClick = onDone) {
                    Text(
                        stringResource(if (end == ConnectLinkEnd.Used) Res.string.common_close else Res.string.connect_link_not_now),
                    )
                }
            }
        },
    )
}
