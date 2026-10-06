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

package app.doorprints.drive.auth

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await
import java.io.IOException

/** A consent screen of Play services: the Activity starts [intent] and gives its result to [PlayGoogleAuthorizer.fromActivityResult]. */
class PlayPendingConsent(val intent: PendingIntent) : PendingConsent

/**
 * [GoogleAuthorizer] over Google's `AuthorizationClient` (play-services-auth; docs/15 §5.5, the Play services row).
 * A thin adapter with no decisions (those are [AndroidDriveTokenProvider]'s, tested on a fake): it only maps Google's
 * types. The real calls need a device with Google Play services, so they are checked by the lead's emulator run
 * (S4b-BL-122), not by a unit test. Nothing here logs or stores a token.
 */
class PlayGoogleAuthorizer(context: Context) : GoogleAuthorizer {
    private val client = Identity.getAuthorizationClient(context.applicationContext)

    override suspend fun authorize(scopes: List<String>): AuthorizerResult = try {
        map(client.authorize(request(scopes)).await())
    } catch (e: ApiException) {
        map(e)
    } catch (e: IOException) {
        AuthorizerResult.Failed(AuthorizerResult.FailureKind.OFFLINE)
    }

    override suspend fun clearToken(accessToken: String) {
        client.clearToken(ClearTokenRequest.builder().setToken(accessToken).build()).await()
    }

    override suspend fun revoke(accessToken: String?, scopes: List<String>) {
        // Google needs the account to revoke for; a quiet authorize (no screen while the grant holds) names it.
        val result = client.authorize(request(scopes)).await()
        val account: Account? = result.toGoogleSignInAccount()?.account
        accessToken?.let { clearToken(it) }
        if (account != null) {
            client.revokeAccess(
                RevokeAccessRequest.builder().setAccount(account).setScopes(scopes.map { Scope(it) }).build(),
            ).await()
        }
    }

    /**
     * The result of the screen [PlayPendingConsent.intent] showed, from the Activity's result callback:
     * [resultCode] and [data] as `onActivityResult` / `ActivityResultContracts.StartIntentSenderForResult` give them.
     */
    fun fromActivityResult(resultCode: Int, data: Intent?): AuthorizerResult {
        if (resultCode == Activity.RESULT_CANCELED || data == null) return AuthorizerResult.Cancelled
        return try {
            map(client.getAuthorizationResultFromIntent(data))
        } catch (e: ApiException) {
            map(e)
        }
    }

    private fun request(scopes: List<String>) =
        AuthorizationRequest.builder().setRequestedScopes(scopes.map { Scope(it) }).build()

    private fun map(result: AuthorizationResult): AuthorizerResult {
        val pending = result.pendingIntent
        if (result.hasResolution() && pending != null) return AuthorizerResult.NeedsConsent(PlayPendingConsent(pending))
        return AuthorizerResult.Granted(result.accessToken, result.grantedScopes.toSet())
    }

    private fun map(e: ApiException): AuthorizerResult = when (e.statusCode) {
        CommonStatusCodes.CANCELED, SIGN_IN_CANCELLED -> AuthorizerResult.Cancelled
        CommonStatusCodes.NETWORK_ERROR -> AuthorizerResult.Failed(AuthorizerResult.FailureKind.OFFLINE)
        else -> AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE)
    }

    private companion object {
        /** `GoogleSignInStatusCodes.SIGN_IN_CANCELLED`: the person closed the screen. */
        const val SIGN_IN_CANCELLED = 12501
    }
}
