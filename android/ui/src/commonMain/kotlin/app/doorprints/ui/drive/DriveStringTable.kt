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

package app.doorprints.ui.drive

import app.doorprints.ui.res.Res
import org.jetbrains.compose.resources.StringResource

import app.doorprints.ui.res.drive_connect_heading
import app.doorprints.ui.res.drive_connect_intro
import app.doorprints.ui.res.drive_connect_connect
import app.doorprints.ui.res.drive_connect_connecting
import app.doorprints.ui.res.drive_connect_connection_in_progress
import app.doorprints.ui.res.drive_connect_first_connect
import app.doorprints.ui.res.drive_connect_recovery_key_note
import app.doorprints.ui.res.drive_connect_recovery_key_copy
import app.doorprints.ui.res.drive_connect_recovery_key_copied
import app.doorprints.ui.res.drive_connect_copy_failed
import app.doorprints.ui.res.drive_connect_confirm_saved_recovery_key
import app.doorprints.ui.res.drive_connect_recovery_key_warning
import app.doorprints.ui.res.drive_connect_needs_enrolment_heading
import app.doorprints.ui.res.drive_connect_needs_enrolment_message
import app.doorprints.ui.res.drive_connect_ready
import app.doorprints.ui.res.drive_connect_last_backup
import app.doorprints.ui.res.drive_connect_never
import app.doorprints.ui.res.drive_connect_back_up_now
import app.doorprints.ui.res.drive_connect_backups_list
import app.doorprints.ui.res.drive_connect_no_backups
import app.doorprints.ui.res.drive_connect_import_from_drive
import app.doorprints.ui.res.drive_connect_automatic_backup
import app.doorprints.ui.res.drive_connect_upload_photos
import app.doorprints.ui.res.drive_connect_upload_photos_now
import app.doorprints.ui.res.drive_connect_disconnect
import app.doorprints.ui.res.drive_connect_disconnecting
import app.doorprints.ui.res.drive_connect_deleting
import app.doorprints.ui.res.drive_connect_not_configured
import app.doorprints.ui.res.drive_connect_folder_gone
import app.doorprints.ui.res.drive_connect_failed
import app.doorprints.ui.res.drive_join_heading
import app.doorprints.ui.res.drive_join_description
import app.doorprints.ui.res.drive_join_label
import app.doorprints.ui.res.drive_join_help_text
import app.doorprints.ui.res.drive_join_join_button
import app.doorprints.ui.res.drive_join_error_empty
import app.doorprints.ui.res.drive_join_error_invalid_format
import app.doorprints.ui.res.drive_join_error_wrong_key
import app.doorprints.ui.res.drive_join_lost_key_message
import app.doorprints.ui.res.drive_join_disconnect
import app.doorprints.ui.res.drive_join_disconnect_aria_label
import app.doorprints.ui.res.drive_backups_back_up_now
import app.doorprints.ui.res.drive_backups_houses_backed_up
import app.doorprints.ui.res.drive_backups_backups
import app.doorprints.ui.res.drive_backups_date
import app.doorprints.ui.res.drive_backups_time
import app.doorprints.ui.res.drive_backups_houses
import app.doorprints.ui.res.drive_backups_size
import app.doorprints.ui.res.drive_backups_import
import app.doorprints.ui.res.drive_backups_import_backup
import app.doorprints.ui.res.drive_backups_empty_state
import app.doorprints.ui.res.drive_backups_loading_state
import app.doorprints.ui.res.drive_backups_error_state
import app.doorprints.ui.res.drive_backups_retry
import app.doorprints.ui.res.drive_backups_auto_backup
import app.doorprints.ui.res.drive_backups_keep_older_backups_note
import app.doorprints.ui.res.drive_backups_missing_newer
import app.doorprints.ui.res.drive_backups_size_unknown
import app.doorprints.ui.res.drive_backups_error_not_connected
import app.doorprints.ui.res.drive_backups_error_backup_not_found
import app.doorprints.ui.res.drive_backups_error_retrieve_failed
import app.doorprints.ui.res.drive_problem_unauthorized
import app.doorprints.ui.res.drive_problem_quota_exceeded
import app.doorprints.ui.res.drive_problem_rate_limited
import app.doorprints.ui.res.drive_problem_server
import app.doorprints.ui.res.drive_problem_drive
import app.doorprints.ui.res.drive_problem_corrupt
import app.doorprints.ui.res.drive_problem_keys_rolled_back
import app.doorprints.ui.res.drive_problem_keys_untrusted
import app.doorprints.ui.res.drive_problem_keys_unreadable
import app.doorprints.ui.res.drive_problem_no_recovery_key
import app.doorprints.ui.res.drive_problem_device_revoked
import app.doorprints.ui.res.drive_problem_control_rolled_back
import app.doorprints.ui.res.drive_problem_control_invalid
import app.doorprints.ui.res.drive_problem_folder_without_keys
import app.doorprints.ui.res.drive_problem_folder_exists
import app.doorprints.ui.res.drive_problem_backup_refused
import app.doorprints.ui.res.drive_problem_signin_closed
import app.doorprints.ui.res.drive_problem_signin_denied
import app.doorprints.ui.res.drive_sync_heading
import app.doorprints.ui.res.drive_sync_sync_now
import app.doorprints.ui.res.drive_sync_skipped_files
import app.doorprints.ui.res.drive_sync_shrink_confirm
import app.doorprints.ui.res.drive_sync_shrink_confirm_details
import app.doorprints.ui.res.drive_sync_apply_shrink
import app.doorprints.ui.res.drive_sync_not_now
import app.doorprints.ui.res.drive_sync_upload_now
import app.doorprints.ui.res.drive_sync_photo_error
import app.doorprints.ui.res.drive_delete_heading
import app.doorprints.ui.res.drive_delete_older_backups
import app.doorprints.ui.res.drive_delete_all_backups
import app.doorprints.ui.res.drive_delete_everything
import app.doorprints.ui.res.drive_delete_one_backup
import app.doorprints.ui.res.drive_delete_plan_heading
import app.doorprints.ui.res.drive_delete_plan_warning
import app.doorprints.ui.res.drive_delete_proceed
import app.doorprints.ui.res.drive_delete_confirm_heading
import app.doorprints.ui.res.drive_delete_confirm_warning
import app.doorprints.ui.res.drive_delete_confirm_checkbox
import app.doorprints.ui.res.drive_delete_tick_required
import app.doorprints.ui.res.drive_delete_countdown
import app.doorprints.ui.res.drive_delete_deleting
import app.doorprints.ui.res.drive_delete_done
import app.doorprints.ui.res.drive_delete_save_copy_first
import app.doorprints.ui.res.drive_delete_delete_for_good
import app.doorprints.ui.res.drive_delete_try_again
import app.doorprints.ui.res.drive_delete_left
import app.doorprints.ui.res.drive_delete_nothing_deleted
import app.doorprints.ui.res.drive_enrol_bad_message
import app.doorprints.ui.res.drive_enrol_code_label
import app.doorprints.ui.res.drive_enrol_numbers_match
import app.doorprints.ui.res.drive_enrol_copied
import app.doorprints.ui.res.drive_enrol_copy_failed
import app.doorprints.ui.res.drive_enrol_join_now
import app.doorprints.ui.res.drive_enrol_expired
import app.doorprints.ui.res.drive_enrol_mismatch
import app.doorprints.ui.res.drive_devices_heading
import app.doorprints.ui.res.drive_devices_revoke
import app.doorprints.ui.res.drive_devices_revoke_note
import app.doorprints.ui.res.drive_devices_disconnect_all
import app.doorprints.ui.res.drive_devices_three_disconnect
import app.doorprints.ui.res.drive_devices_three_remove
import app.doorprints.ui.res.drive_devices_three_delete
import app.doorprints.ui.res.drive_devices_email
import app.doorprints.ui.res.drive_connect_unavailable
import app.doorprints.ui.res.drive_sync_synced
import app.doorprints.ui.res.drive_sync_synced_at
import app.doorprints.ui.res.drive_sync_waiting
import app.doorprints.ui.res.drive_sync_syncing
import app.doorprints.ui.res.drive_sync_error
import app.doorprints.ui.res.drive_photos_waiting_for_wifi
import app.doorprints.ui.res.drive_backups_shrink_body
import app.doorprints.ui.res.drive_backups_shrink_confirm
import app.doorprints.ui.res.drive_backups_keep_older
import app.doorprints.ui.res.drive_devices_new_recovery_key
import app.doorprints.ui.res.drive_devices_codes_match
import app.doorprints.ui.res.drive_enrol_copy_code
import app.doorprints.ui.res.drive_delete_reason_drive_error
import app.doorprints.ui.res.drive_connect_account_email
import app.doorprints.ui.res.drive_common_next
import app.doorprints.ui.res.drive_common_skip
import app.doorprints.ui.res.drive_common_retry
import app.doorprints.ui.res.drive_common_close
import app.doorprints.ui.res.drive_common_loading
import app.doorprints.ui.res.drive_connect_no_backup_source
import app.doorprints.ui.res.drive_problem_offline
import app.doorprints.ui.res.drive_problem_source_failed
import app.doorprints.ui.res.drive_problem_connect_failed
import app.doorprints.ui.res.drive_problem_crypto_unavailable
import app.doorprints.ui.res.drive_problem_signin_unavailable
import app.doorprints.ui.res.drive_backups_shrink_heading
import app.doorprints.ui.res.drive_devices_this_device
import app.doorprints.ui.res.drive_devices_approve
import app.doorprints.ui.res.drive_devices_revoke_confirm
import app.doorprints.ui.res.drive_devices_scan_qr
import app.doorprints.ui.res.drive_devices_show_qr
import app.doorprints.ui.res.drive_devices_code_instead
import app.doorprints.ui.res.drive_devices_no_lock
import app.doorprints.ui.res.drive_devices_device_check_note
import app.doorprints.ui.res.drive_devices_prompt_revoke
import app.doorprints.ui.res.drive_devices_prompt_approve
import app.doorprints.ui.res.drive_devices_prompt_disconnect_all
import app.doorprints.ui.res.drive_enrol_heading_join
import app.doorprints.ui.res.drive_enrol_heading_approve
import app.doorprints.ui.res.drive_enrol_new_help
import app.doorprints.ui.res.drive_enrol_approve_help
import app.doorprints.ui.res.drive_enrol_confirm_match
import app.doorprints.ui.res.drive_enrol_paste_offer
import app.doorprints.ui.res.drive_enrol_paste_reply
import app.doorprints.ui.res.drive_enrol_reply_label
import app.doorprints.ui.res.drive_enrol_approve
import app.doorprints.ui.res.drive_enrol_scan
import app.doorprints.ui.res.drive_enrol_camera_missing
import app.doorprints.ui.res.drive_enrol_qr_description
import app.doorprints.ui.res.drive_enrol_done
import app.doorprints.ui.res.drive_sync_offline
import app.doorprints.ui.res.drive_sync_paused
import app.doorprints.ui.res.drive_sync_not_run
import app.doorprints.ui.res.drive_sync_needs_confirmation
import app.doorprints.ui.res.drive_photos_uploading
import app.doorprints.ui.res.drive_photos_paused_offline
import app.doorprints.ui.res.drive_photos_done
import app.doorprints.ui.res.drive_delete_device_check_heading
import app.doorprints.ui.res.drive_delete_device_check_prompt
import app.doorprints.ui.res.drive_delete_device_check_note
import app.doorprints.ui.res.drive_delete_plan_backup
import app.doorprints.ui.res.drive_delete_plan_sync
import app.doorprints.ui.res.drive_delete_plan_photo
import app.doorprints.ui.res.drive_delete_plan_shared
import app.doorprints.ui.res.drive_delete_plan_other
import app.doorprints.ui.res.drive_delete_plan_total
import app.doorprints.ui.res.drive_delete_plan_foreign
import app.doorprints.ui.res.drive_delete_reason_offline
import app.doorprints.ui.res.drive_delete_reason_not_authorized
import app.doorprints.ui.res.drive_delete_reason_authorization_too_weak
import app.doorprints.ui.res.drive_delete_reason_authorization_stale
import app.doorprints.ui.res.drive_delete_reason_authorization_other_operation
import app.doorprints.ui.res.drive_delete_reason_stale_plan
import app.doorprints.ui.res.drive_delete_reason_root_not_found
import app.doorprints.ui.res.drive_delete_reason_not_a_backup
import app.doorprints.ui.res.drive_delete_reason_nothing_to_delete
import app.doorprints.ui.res.drive_delete_reason_other_deletion_pending
import app.doorprints.ui.res.drive_delete_reason_nothing_pending
import app.doorprints.ui.res.drive_delete_reason_no_device_lock
import app.doorprints.ui.res.drive_delete_reason_auth_cancelled
import app.doorprints.ui.res.drive_delete_reason_auth_failed
import app.doorprints.ui.res.drive_delete_reason_auth_lock_not_set
import app.doorprints.ui.res.drive_delete_reason_auth_not_available
import app.doorprints.ui.res.drive_delete_reason_auth_locked_out
import app.doorprints.ui.res.drive_delete_reason_auth_paused_no_lock
import app.doorprints.ui.res.drive_delete_reason_auth_paused_unknown

/*
 * GENERATED by the Drive string script (scratchpad gen_drive_strings.py): every dictionary key the Drive screens use, by name.
 * Compose resources have no lookup by name, and the controller's reasons arrive as keys (DriveReason.key).
 * DriveStringsTest keeps this table and the resource files the same.
 */
internal val DRIVE_STRINGS: Map<String, StringResource> = mapOf(
    "driveConnect.heading" to Res.string.drive_connect_heading,
    "driveConnect.intro" to Res.string.drive_connect_intro,
    "driveConnect.connect" to Res.string.drive_connect_connect,
    "driveConnect.connecting" to Res.string.drive_connect_connecting,
    "driveConnect.connectionInProgress" to Res.string.drive_connect_connection_in_progress,
    "driveConnect.firstConnect" to Res.string.drive_connect_first_connect,
    "driveConnect.recoveryKeyNote" to Res.string.drive_connect_recovery_key_note,
    "driveConnect.recoveryKeyCopy" to Res.string.drive_connect_recovery_key_copy,
    "driveConnect.recoveryKeyCopied" to Res.string.drive_connect_recovery_key_copied,
    "driveConnect.copyFailed" to Res.string.drive_connect_copy_failed,
    "driveConnect.confirmSavedRecoveryKey" to Res.string.drive_connect_confirm_saved_recovery_key,
    "driveConnect.recoveryKeyWarning" to Res.string.drive_connect_recovery_key_warning,
    "driveConnect.needsEnrolmentHeading" to Res.string.drive_connect_needs_enrolment_heading,
    "driveConnect.needsEnrolmentMessage" to Res.string.drive_connect_needs_enrolment_message,
    "driveConnect.ready" to Res.string.drive_connect_ready,
    "driveConnect.lastBackup" to Res.string.drive_connect_last_backup,
    "driveConnect.never" to Res.string.drive_connect_never,
    "driveConnect.backUpNow" to Res.string.drive_connect_back_up_now,
    "driveConnect.backupsList" to Res.string.drive_connect_backups_list,
    "driveConnect.noBackups" to Res.string.drive_connect_no_backups,
    "driveConnect.importFromDrive" to Res.string.drive_connect_import_from_drive,
    "driveConnect.automaticBackup" to Res.string.drive_connect_automatic_backup,
    "driveConnect.uploadPhotos" to Res.string.drive_connect_upload_photos,
    "driveConnect.uploadPhotosNow" to Res.string.drive_connect_upload_photos_now,
    "driveConnect.disconnect" to Res.string.drive_connect_disconnect,
    "driveConnect.disconnecting" to Res.string.drive_connect_disconnecting,
    "driveConnect.deleting" to Res.string.drive_connect_deleting,
    "driveConnect.notConfigured" to Res.string.drive_connect_not_configured,
    "driveConnect.folderGone" to Res.string.drive_connect_folder_gone,
    "driveConnect.failed" to Res.string.drive_connect_failed,
    "driveJoin.heading" to Res.string.drive_join_heading,
    "driveJoin.description" to Res.string.drive_join_description,
    "driveJoin.label" to Res.string.drive_join_label,
    "driveJoin.helpText" to Res.string.drive_join_help_text,
    "driveJoin.joinButton" to Res.string.drive_join_join_button,
    "driveJoin.errorEmpty" to Res.string.drive_join_error_empty,
    "driveJoin.errorInvalidFormat" to Res.string.drive_join_error_invalid_format,
    "driveJoin.errorWrongKey" to Res.string.drive_join_error_wrong_key,
    "driveJoin.lostKeyMessage" to Res.string.drive_join_lost_key_message,
    "driveJoin.disconnect" to Res.string.drive_join_disconnect,
    "driveJoin.disconnectAriaLabel" to Res.string.drive_join_disconnect_aria_label,
    "driveBackups.backUpNow" to Res.string.drive_backups_back_up_now,
    "driveBackups.housesBackedUp" to Res.string.drive_backups_houses_backed_up,
    "driveBackups.backups" to Res.string.drive_backups_backups,
    "driveBackups.date" to Res.string.drive_backups_date,
    "driveBackups.time" to Res.string.drive_backups_time,
    "driveBackups.houses" to Res.string.drive_backups_houses,
    "driveBackups.size" to Res.string.drive_backups_size,
    "driveBackups.import" to Res.string.drive_backups_import,
    "driveBackups.importBackup" to Res.string.drive_backups_import_backup,
    "driveBackups.emptyState" to Res.string.drive_backups_empty_state,
    "driveBackups.loadingState" to Res.string.drive_backups_loading_state,
    "driveBackups.errorState" to Res.string.drive_backups_error_state,
    "driveBackups.retry" to Res.string.drive_backups_retry,
    "driveBackups.autoBackup" to Res.string.drive_backups_auto_backup,
    "driveBackups.keepOlderBackupsNote" to Res.string.drive_backups_keep_older_backups_note,
    "driveBackups.missingNewer" to Res.string.drive_backups_missing_newer,
    "driveBackups.sizeUnknown" to Res.string.drive_backups_size_unknown,
    "driveBackups.error.notConnected" to Res.string.drive_backups_error_not_connected,
    "driveBackups.error.backupNotFound" to Res.string.drive_backups_error_backup_not_found,
    "driveBackups.error.retrieveFailed" to Res.string.drive_backups_error_retrieve_failed,
    "driveProblem.UNAUTHORIZED" to Res.string.drive_problem_unauthorized,
    "driveProblem.QUOTA_EXCEEDED" to Res.string.drive_problem_quota_exceeded,
    "driveProblem.RATE_LIMITED" to Res.string.drive_problem_rate_limited,
    "driveProblem.SERVER" to Res.string.drive_problem_server,
    "driveProblem.DRIVE" to Res.string.drive_problem_drive,
    "driveProblem.CORRUPT" to Res.string.drive_problem_corrupt,
    "driveProblem.KEYS_ROLLED_BACK" to Res.string.drive_problem_keys_rolled_back,
    "driveProblem.KEYS_UNTRUSTED" to Res.string.drive_problem_keys_untrusted,
    "driveProblem.KEYS_UNREADABLE" to Res.string.drive_problem_keys_unreadable,
    "driveProblem.NO_RECOVERY_KEY" to Res.string.drive_problem_no_recovery_key,
    "driveProblem.DEVICE_REVOKED" to Res.string.drive_problem_device_revoked,
    "driveProblem.CONTROL_ROLLED_BACK" to Res.string.drive_problem_control_rolled_back,
    "driveProblem.CONTROL_INVALID" to Res.string.drive_problem_control_invalid,
    "driveProblem.FOLDER_WITHOUT_KEYS" to Res.string.drive_problem_folder_without_keys,
    "driveProblem.FOLDER_EXISTS" to Res.string.drive_problem_folder_exists,
    "driveProblem.BACKUP_REFUSED" to Res.string.drive_problem_backup_refused,
    "driveProblem.SIGNIN_CLOSED" to Res.string.drive_problem_signin_closed,
    "driveProblem.SIGNIN_DENIED" to Res.string.drive_problem_signin_denied,
    "driveSync.heading" to Res.string.drive_sync_heading,
    "driveSync.syncNow" to Res.string.drive_sync_sync_now,
    "driveSync.skippedFiles" to Res.string.drive_sync_skipped_files,
    "driveSync.shrinkConfirm" to Res.string.drive_sync_shrink_confirm,
    "driveSync.shrinkConfirmDetails" to Res.string.drive_sync_shrink_confirm_details,
    "driveSync.applyShrink" to Res.string.drive_sync_apply_shrink,
    "driveSync.notNow" to Res.string.drive_sync_not_now,
    "driveSync.uploadNow" to Res.string.drive_sync_upload_now,
    "driveSync.photoError" to Res.string.drive_sync_photo_error,
    "driveDelete.heading" to Res.string.drive_delete_heading,
    "driveDelete.olderBackups" to Res.string.drive_delete_older_backups,
    "driveDelete.allBackups" to Res.string.drive_delete_all_backups,
    "driveDelete.everything" to Res.string.drive_delete_everything,
    "driveDelete.oneBackup" to Res.string.drive_delete_one_backup,
    "driveDelete.planHeading" to Res.string.drive_delete_plan_heading,
    "driveDelete.planWarning" to Res.string.drive_delete_plan_warning,
    "driveDelete.proceed" to Res.string.drive_delete_proceed,
    "driveDelete.confirmHeading" to Res.string.drive_delete_confirm_heading,
    "driveDelete.confirmWarning" to Res.string.drive_delete_confirm_warning,
    "driveDelete.confirmCheckbox" to Res.string.drive_delete_confirm_checkbox,
    "driveDelete.tickRequired" to Res.string.drive_delete_tick_required,
    "driveDelete.countdown" to Res.string.drive_delete_countdown,
    "driveDelete.deleting" to Res.string.drive_delete_deleting,
    "driveDelete.done" to Res.string.drive_delete_done,
    "driveDelete.saveCopyFirst" to Res.string.drive_delete_save_copy_first,
    "driveDelete.deleteForGood" to Res.string.drive_delete_delete_for_good,
    "driveDelete.tryAgain" to Res.string.drive_delete_try_again,
    "driveDelete.left" to Res.string.drive_delete_left,
    "driveDelete.nothingDeleted" to Res.string.drive_delete_nothing_deleted,
    "driveEnrol.badMessage" to Res.string.drive_enrol_bad_message,
    "driveEnrol.codeLabel" to Res.string.drive_enrol_code_label,
    "driveEnrol.numbersMatch" to Res.string.drive_enrol_numbers_match,
    "driveEnrol.copied" to Res.string.drive_enrol_copied,
    "driveEnrol.copyFailed" to Res.string.drive_enrol_copy_failed,
    "driveEnrol.joinNow" to Res.string.drive_enrol_join_now,
    "driveEnrol.expired" to Res.string.drive_enrol_expired,
    "driveEnrol.mismatch" to Res.string.drive_enrol_mismatch,
    "driveDevices.heading" to Res.string.drive_devices_heading,
    "driveDevices.revoke" to Res.string.drive_devices_revoke,
    "driveDevices.revokeNote" to Res.string.drive_devices_revoke_note,
    "driveDevices.disconnectAll" to Res.string.drive_devices_disconnect_all,
    "driveDevices.threeDisconnect" to Res.string.drive_devices_three_disconnect,
    "driveDevices.threeRemove" to Res.string.drive_devices_three_remove,
    "driveDevices.threeDelete" to Res.string.drive_devices_three_delete,
    "driveDevices.email" to Res.string.drive_devices_email,
    "driveConnect.unavailable" to Res.string.drive_connect_unavailable,
    "driveSync.synced" to Res.string.drive_sync_synced,
    "driveSync.syncedAt" to Res.string.drive_sync_synced_at,
    "driveSync.waiting" to Res.string.drive_sync_waiting,
    "driveSync.syncing" to Res.string.drive_sync_syncing,
    "driveSync.error" to Res.string.drive_sync_error,
    "drivePhotos.waitingForWifi" to Res.string.drive_photos_waiting_for_wifi,
    "driveBackups.shrinkBody" to Res.string.drive_backups_shrink_body,
    "driveBackups.shrinkConfirm" to Res.string.drive_backups_shrink_confirm,
    "driveBackups.keepOlder" to Res.string.drive_backups_keep_older,
    "driveDevices.newRecoveryKey" to Res.string.drive_devices_new_recovery_key,
    "driveDevices.codesMatch" to Res.string.drive_devices_codes_match,
    "driveEnrol.copyCode" to Res.string.drive_enrol_copy_code,
    "driveDelete.reason.DRIVE_ERROR" to Res.string.drive_delete_reason_drive_error,
    "driveConnect.accountEmail" to Res.string.drive_connect_account_email,
    "drive.common.next" to Res.string.drive_common_next,
    "drive.common.skip" to Res.string.drive_common_skip,
    "drive.common.retry" to Res.string.drive_common_retry,
    "drive.common.close" to Res.string.drive_common_close,
    "drive.common.loading" to Res.string.drive_common_loading,
    "driveConnect.noBackupSource" to Res.string.drive_connect_no_backup_source,
    "driveProblem.OFFLINE" to Res.string.drive_problem_offline,
    "driveProblem.SOURCE_FAILED" to Res.string.drive_problem_source_failed,
    "driveProblem.CONNECT_FAILED" to Res.string.drive_problem_connect_failed,
    "driveProblem.CRYPTO_UNAVAILABLE" to Res.string.drive_problem_crypto_unavailable,
    "driveProblem.SIGNIN_UNAVAILABLE" to Res.string.drive_problem_signin_unavailable,
    "driveBackups.shrinkHeading" to Res.string.drive_backups_shrink_heading,
    "driveDevices.thisDevice" to Res.string.drive_devices_this_device,
    "driveDevices.approve" to Res.string.drive_devices_approve,
    "driveDevices.revokeConfirm" to Res.string.drive_devices_revoke_confirm,
    "driveDevices.scanQr" to Res.string.drive_devices_scan_qr,
    "driveDevices.showQr" to Res.string.drive_devices_show_qr,
    "driveDevices.codeInstead" to Res.string.drive_devices_code_instead,
    "driveDevices.noLock" to Res.string.drive_devices_no_lock,
    "driveDevices.deviceCheckNote" to Res.string.drive_devices_device_check_note,
    "driveDevices.promptRevoke" to Res.string.drive_devices_prompt_revoke,
    "driveDevices.promptApprove" to Res.string.drive_devices_prompt_approve,
    "driveDevices.promptDisconnectAll" to Res.string.drive_devices_prompt_disconnect_all,
    "driveEnrol.headingJoin" to Res.string.drive_enrol_heading_join,
    "driveEnrol.headingApprove" to Res.string.drive_enrol_heading_approve,
    "driveEnrol.newHelp" to Res.string.drive_enrol_new_help,
    "driveEnrol.approveHelp" to Res.string.drive_enrol_approve_help,
    "driveEnrol.confirmMatch" to Res.string.drive_enrol_confirm_match,
    "driveEnrol.pasteOffer" to Res.string.drive_enrol_paste_offer,
    "driveEnrol.pasteReply" to Res.string.drive_enrol_paste_reply,
    "driveEnrol.replyLabel" to Res.string.drive_enrol_reply_label,
    "driveEnrol.approve" to Res.string.drive_enrol_approve,
    "driveEnrol.scan" to Res.string.drive_enrol_scan,
    "driveEnrol.cameraMissing" to Res.string.drive_enrol_camera_missing,
    "driveEnrol.qrDescription" to Res.string.drive_enrol_qr_description,
    "driveEnrol.done" to Res.string.drive_enrol_done,
    "driveSync.offline" to Res.string.drive_sync_offline,
    "driveSync.paused" to Res.string.drive_sync_paused,
    "driveSync.notRun" to Res.string.drive_sync_not_run,
    "driveSync.needsConfirmation" to Res.string.drive_sync_needs_confirmation,
    "drivePhotos.uploading" to Res.string.drive_photos_uploading,
    "drivePhotos.pausedOffline" to Res.string.drive_photos_paused_offline,
    "drivePhotos.done" to Res.string.drive_photos_done,
    "driveDelete.deviceCheckHeading" to Res.string.drive_delete_device_check_heading,
    "driveDelete.deviceCheckPrompt" to Res.string.drive_delete_device_check_prompt,
    "driveDelete.deviceCheckNote" to Res.string.drive_delete_device_check_note,
    "driveDelete.plan.backup" to Res.string.drive_delete_plan_backup,
    "driveDelete.plan.sync" to Res.string.drive_delete_plan_sync,
    "driveDelete.plan.photo" to Res.string.drive_delete_plan_photo,
    "driveDelete.plan.shared" to Res.string.drive_delete_plan_shared,
    "driveDelete.plan.other" to Res.string.drive_delete_plan_other,
    "driveDelete.plan.total" to Res.string.drive_delete_plan_total,
    "driveDelete.plan.foreign" to Res.string.drive_delete_plan_foreign,
    "driveDelete.reason.OFFLINE" to Res.string.drive_delete_reason_offline,
    "driveDelete.reason.NOT_AUTHORIZED" to Res.string.drive_delete_reason_not_authorized,
    "driveDelete.reason.AUTHORIZATION_TOO_WEAK" to Res.string.drive_delete_reason_authorization_too_weak,
    "driveDelete.reason.AUTHORIZATION_STALE" to Res.string.drive_delete_reason_authorization_stale,
    "driveDelete.reason.AUTHORIZATION_OTHER_OPERATION" to Res.string.drive_delete_reason_authorization_other_operation,
    "driveDelete.reason.STALE_PLAN" to Res.string.drive_delete_reason_stale_plan,
    "driveDelete.reason.ROOT_NOT_FOUND" to Res.string.drive_delete_reason_root_not_found,
    "driveDelete.reason.NOT_A_BACKUP" to Res.string.drive_delete_reason_not_a_backup,
    "driveDelete.reason.NOTHING_TO_DELETE" to Res.string.drive_delete_reason_nothing_to_delete,
    "driveDelete.reason.OTHER_DELETION_PENDING" to Res.string.drive_delete_reason_other_deletion_pending,
    "driveDelete.reason.NOTHING_PENDING" to Res.string.drive_delete_reason_nothing_pending,
    "driveDelete.reason.NO_DEVICE_LOCK" to Res.string.drive_delete_reason_no_device_lock,
    "driveDelete.reason.AUTH_CANCELLED" to Res.string.drive_delete_reason_auth_cancelled,
    "driveDelete.reason.AUTH_FAILED" to Res.string.drive_delete_reason_auth_failed,
    "driveDelete.reason.AUTH_LOCK_NOT_SET" to Res.string.drive_delete_reason_auth_lock_not_set,
    "driveDelete.reason.AUTH_NOT_AVAILABLE" to Res.string.drive_delete_reason_auth_not_available,
    "driveDelete.reason.AUTH_LOCKED_OUT" to Res.string.drive_delete_reason_auth_locked_out,
    "driveDelete.reason.AUTH_PAUSED_NO_LOCK" to Res.string.drive_delete_reason_auth_paused_no_lock,
    "driveDelete.reason.AUTH_PAUSED_UNKNOWN" to Res.string.drive_delete_reason_auth_paused_unknown,
)
