package app.doorprints.drive.connect

import app.doorprints.drive.DriveException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The fallback of docs/15 §5.5, only if the spike (S4b-BL-122) shows Google refuses the browser redirect for the
 * Android client: Google Play services' `Identity.getAuthorizationClient(...).authorize(...)`. This is the interface
 * only, with no `play-services-auth` dependency in the build; adding the library means implementing it in `:app`.
 */
interface PlayServicesAuthorizer {
    suspend fun authorize(): PlayAuthResult
}

sealed interface PlayAuthResult {
    class Granted(val accessToken: String, val scopes: Set<String>, val expiresAtMs: Long) : PlayAuthResult {
        override fun toString() = "Granted"
    }

    data object Cancelled : PlayAuthResult
    data object Unavailable : PlayAuthResult
    data object Failed : PlayAuthResult
}

/** [GoogleSignIn] over Play services: nothing is stored, Play services keeps and refreshes the grant. */
class PlayServicesGoogleSignIn(
    private val authorizer: PlayServicesAuthorizer,
    private val clock: () -> Long,
    override val configured: Boolean = true,
) : GoogleSignIn {
    private val lock = Mutex()
    private var token: String? = null
    private var until = 0L

    override fun isConnected(): Boolean = token != null

    override suspend fun connect(): SignInResult = lock.withLock {
        when (val r = ask()) {
            is PlayAuthResult.Granted -> {
                if (r.scopes != setOf(GoogleAuthConfig.SCOPE_DRIVE_FILE)) {
                    token = null
                    return SignInResult.Failed(SignInFailure.WRONG_SCOPE)
                }
                token = r.accessToken
                until = r.expiresAtMs
                SignInResult.Connected
            }
            PlayAuthResult.Cancelled -> SignInResult.Cancelled
            PlayAuthResult.Unavailable -> SignInResult.Failed(SignInFailure.NOT_AVAILABLE)
            PlayAuthResult.Failed -> SignInResult.Failed(SignInFailure.DENIED)
        }
    }

    override suspend fun accessToken(): String = lock.withLock {
        token?.let { if (clock() < until - 60_000L) return it }
        val r = ask()
        if (r !is PlayAuthResult.Granted || r.scopes != setOf(GoogleAuthConfig.SCOPE_DRIVE_FILE)) {
            token = null
            throw DriveException(DriveException.Kind.UNAUTHORIZED)
        }
        token = r.accessToken
        until = r.expiresAtMs
        r.accessToken
    }

    override suspend fun onRejected(token: String) {
        lock.withLock { if (this.token == token) this.token = null }
    }

    override suspend fun disconnect() = forgetLocally()

    override fun forgetLocally() {
        token = null
        until = 0
    }

    private suspend fun ask(): PlayAuthResult = try {
        authorizer.authorize()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        PlayAuthResult.Failed
    }
}
