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

import app.doorprints.drive.connect.DriveMessage
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.drive_msg_backup_done
import app.doorprints.ui.res.drive_msg_backup_gone
import app.doorprints.ui.res.drive_msg_backup_refused
import app.doorprints.ui.res.drive_msg_control_invalid
import app.doorprints.ui.res.drive_msg_control_rolled_back
import app.doorprints.ui.res.drive_msg_corrupt
import app.doorprints.ui.res.drive_msg_crypto_unavailable
import app.doorprints.ui.res.drive_msg_delete_cancelled
import app.doorprints.ui.res.drive_msg_delete_denied
import app.doorprints.ui.res.drive_msg_delete_done_all
import app.doorprints.ui.res.drive_msg_delete_done_backup
import app.doorprints.ui.res.drive_msg_delete_done_everything
import app.doorprints.ui.res.drive_msg_delete_drive_error
import app.doorprints.ui.res.drive_msg_delete_folder_kept
import app.doorprints.ui.res.drive_msg_delete_lock_lost
import app.doorprints.ui.res.drive_msg_delete_no_lock
import app.doorprints.ui.res.drive_msg_delete_nothing_to_delete
import app.doorprints.ui.res.drive_msg_delete_offline
import app.doorprints.ui.res.drive_msg_delete_other_pending
import app.doorprints.ui.res.drive_msg_delete_stale
import app.doorprints.ui.res.drive_msg_delete_stopped
import app.doorprints.ui.res.drive_msg_delete_use_phone
import app.doorprints.ui.res.drive_msg_device_revoked
import app.doorprints.ui.res.drive_msg_disconnected_by_google
import app.doorprints.ui.res.drive_msg_disconnected_done
import app.doorprints.ui.res.drive_msg_drive
import app.doorprints.ui.res.drive_msg_folder_exists
import app.doorprints.ui.res.drive_msg_folder_without_keys
import app.doorprints.ui.res.drive_msg_import_ready
import app.doorprints.ui.res.drive_msg_keys_rolled_back
import app.doorprints.ui.res.drive_msg_keys_unreadable
import app.doorprints.ui.res.drive_msg_keys_untrusted
import app.doorprints.ui.res.drive_msg_no_recovery_key
import app.doorprints.ui.res.drive_msg_nothing_deleted
import app.doorprints.ui.res.drive_msg_offline
import app.doorprints.ui.res.drive_msg_paused_no_lock
import app.doorprints.ui.res.drive_msg_paused_unknown
import app.doorprints.ui.res.drive_msg_quota
import app.doorprints.ui.res.drive_msg_rate_limited
import app.doorprints.ui.res.drive_msg_recovery_groups_wrong
import app.doorprints.ui.res.drive_msg_server
import app.doorprints.ui.res.drive_msg_shrink_hold
import app.doorprints.ui.res.drive_msg_signin_failed
import app.doorprints.ui.res.drive_msg_signin_not_available
import app.doorprints.ui.res.drive_msg_signin_offline
import app.doorprints.ui.res.drive_msg_signin_state
import app.doorprints.ui.res.drive_msg_signin_store
import app.doorprints.ui.res.drive_msg_signin_wrong_scope
import app.doorprints.ui.res.drive_msg_source_failed
import app.doorprints.ui.res.drive_msg_wrong_recovery_key
import org.jetbrains.compose.resources.StringResource

/** The plain-words sentence for every message the Drive state holder can raise; the `when` has no `else`, so a new one will not compile unseen. */
fun DriveMessage.text(): StringResource = when (this) {
    DriveMessage.SIGNIN_FAILED -> Res.string.drive_msg_signin_failed
    DriveMessage.SIGNIN_OFFLINE -> Res.string.drive_msg_signin_offline
    DriveMessage.SIGNIN_NOT_AVAILABLE -> Res.string.drive_msg_signin_not_available
    DriveMessage.SIGNIN_WRONG_SCOPE -> Res.string.drive_msg_signin_wrong_scope
    DriveMessage.SIGNIN_STATE -> Res.string.drive_msg_signin_state
    DriveMessage.SIGNIN_STORE -> Res.string.drive_msg_signin_store
    DriveMessage.DISCONNECTED_BY_GOOGLE -> Res.string.drive_msg_disconnected_by_google
    DriveMessage.DISCONNECTED_DONE -> Res.string.drive_msg_disconnected_done
    DriveMessage.OFFLINE -> Res.string.drive_msg_offline
    DriveMessage.QUOTA -> Res.string.drive_msg_quota
    DriveMessage.RATE_LIMITED -> Res.string.drive_msg_rate_limited
    DriveMessage.SERVER -> Res.string.drive_msg_server
    DriveMessage.DRIVE -> Res.string.drive_msg_drive
    DriveMessage.CORRUPT -> Res.string.drive_msg_corrupt
    DriveMessage.KEYS_ROLLED_BACK -> Res.string.drive_msg_keys_rolled_back
    DriveMessage.KEYS_UNTRUSTED -> Res.string.drive_msg_keys_untrusted
    DriveMessage.KEYS_UNREADABLE -> Res.string.drive_msg_keys_unreadable
    DriveMessage.WRONG_RECOVERY_KEY -> Res.string.drive_msg_wrong_recovery_key
    DriveMessage.NO_RECOVERY_KEY -> Res.string.drive_msg_no_recovery_key
    DriveMessage.DEVICE_REVOKED -> Res.string.drive_msg_device_revoked
    DriveMessage.CONTROL_ROLLED_BACK -> Res.string.drive_msg_control_rolled_back
    DriveMessage.CONTROL_INVALID -> Res.string.drive_msg_control_invalid
    DriveMessage.FOLDER_WITHOUT_KEYS -> Res.string.drive_msg_folder_without_keys
    DriveMessage.FOLDER_EXISTS -> Res.string.drive_msg_folder_exists
    DriveMessage.CRYPTO_UNAVAILABLE -> Res.string.drive_msg_crypto_unavailable
    DriveMessage.BACKUP_REFUSED -> Res.string.drive_msg_backup_refused
    DriveMessage.BACKUP_GONE -> Res.string.drive_msg_backup_gone
    DriveMessage.SOURCE_FAILED -> Res.string.drive_msg_source_failed
    DriveMessage.RECOVERY_GROUPS_WRONG -> Res.string.drive_msg_recovery_groups_wrong
    DriveMessage.BACKUP_DONE -> Res.string.drive_msg_backup_done
    DriveMessage.SHRINK_HOLD -> Res.string.drive_msg_shrink_hold
    DriveMessage.IMPORT_READY -> Res.string.drive_msg_import_ready
    DriveMessage.PAUSED_NO_LOCK -> Res.string.drive_msg_paused_no_lock
    DriveMessage.PAUSED_UNKNOWN -> Res.string.drive_msg_paused_unknown
    DriveMessage.NOTHING_DELETED -> Res.string.drive_msg_nothing_deleted
    DriveMessage.DELETE_NO_LOCK -> Res.string.drive_msg_delete_no_lock
    DriveMessage.DELETE_USE_PHONE -> Res.string.drive_msg_delete_use_phone
    DriveMessage.DELETE_OFFLINE -> Res.string.drive_msg_delete_offline
    DriveMessage.DELETE_STALE -> Res.string.drive_msg_delete_stale
    DriveMessage.DELETE_NOTHING_TO_DELETE -> Res.string.drive_msg_delete_nothing_to_delete
    DriveMessage.DELETE_OTHER_PENDING -> Res.string.drive_msg_delete_other_pending
    DriveMessage.DELETE_DRIVE_ERROR -> Res.string.drive_msg_delete_drive_error
    DriveMessage.DELETE_DONE_BACKUP -> Res.string.drive_msg_delete_done_backup
    DriveMessage.DELETE_DONE_ALL -> Res.string.drive_msg_delete_done_all
    DriveMessage.DELETE_DONE_EVERYTHING -> Res.string.drive_msg_delete_done_everything
    DriveMessage.DELETE_STOPPED -> Res.string.drive_msg_delete_stopped
    DriveMessage.DELETE_LOCK_LOST -> Res.string.drive_msg_delete_lock_lost
    DriveMessage.DELETE_DENIED -> Res.string.drive_msg_delete_denied
    DriveMessage.DELETE_CANCELLED -> Res.string.drive_msg_delete_cancelled
    DriveMessage.DELETE_FOLDER_KEPT -> Res.string.drive_msg_delete_folder_kept
}

/** "1.4 GB" or "12 MB": units are the same in every language. */
fun driveSizeText(bytes: Long): String =
    if (bytes >= 1_000_000_000L) Formats.oneDecimal(bytes / 1_000_000_000.0) + " GB" else megabytesText(bytes) + " MB"
