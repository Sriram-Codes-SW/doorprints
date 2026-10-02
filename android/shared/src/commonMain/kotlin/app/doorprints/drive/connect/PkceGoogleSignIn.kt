package app.doorprints.drive.connect

import app.doorprints.crypto.CryptoProvider
import app.doorprints.drive.DriveException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the browser step gave back. */
sealed interface BrowserResult {
    /** The full URI the browser was redirected to (our redirect, with the answer in its query). */
    data class Redirected(val uri: String) : BrowserResult

    /** The person closed it. */
    data object Cancelled : BrowserResult

    /** Nothing to open it with (no browser, the iPhone bridge not installed): fail closed. */
    data object Unavailable : BrowserResult
}

/**
 * Opens Google's consent page and waits for the redirect. Android: `ACTION_VIEW` on the system browser (no library)
 * and the activity that receives the redirect; iPhone: Swift's `ASWebAuthenticationSession`. Cancelling the
 * coroutine must close the wait.
 */
fun interface AuthBrowser {
    suspend fun authorize(url: String, redirectUri: String): BrowserResult
}

/** The sealed refresh token. Failing to read or write it is [TokenStoreException]; nothing else is thrown. */
interface RefreshTokenStore {
    fun load(): String?
    fun save(token: String)
    fun clear()
}

class TokenStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

class MemoryRefreshTokenStore : RefreshTokenStore {
    private var token: String? = null
    override fun load() = token
    override fun save(token: String) {
        this.token = token
    }

    override fun clear() {
        token = null
    }
}

class OAuthResponse(val status: Int, val body: String)

/** One form POST. Throws on a network failure (any exception but cancellation counts as offline). */
fun interface OAuthTransport {
    suspend fun postForm(url: String, fields: Map<String, String>): OAuthResponse
}

/**
 * The code flow with PKCE for the phones (docs/15 §5.5). The refresh token is kept in [store] (sealed by the
 * platform; it dies with the screen lock, see `LockLossActions`); the access token only in memory. The client has no
 * secret. The scope must come back as exactly `drive.file`.
 */
class PkceGoogleSignIn(
    private val config: GoogleAuthConfig,
    private val browser: AuthBrowser,
    private val transport: OAuthTransport,
    private val store: RefreshTokenStore,
    private val p: CryptoProvider,
    private val clock: () -> Long,
) : GoogleSignIn {
    private val lock = Mutex()
    private var cachedToken: String? = null
    private var cachedUntil = 0L

    override val configured: Boolean get() = config.isConfigured

    override fun isConnected(): Boolean = try {
        store.load() != null
    } catch (_: Exception) {
        false
    }

    override suspend fun connect(): SignInResult = lock.withLock {
        if (!config.isConfigured) return SignInResult.Failed(SignInFailure.NOT_CONFIGURED)
        val pkce = Pkce.create(p)
        val back = try {
            browser.authorize(OAuthFlow.authorizationUrl(config, pkce), config.redirectUri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return SignInResult.Failed(SignInFailure.NOT_AVAILABLE)
        }
        val uri = when (back) {
            BrowserResult.Cancelled -> return SignInResult.Cancelled
            BrowserResult.Unavailable -> return SignInResult.Failed(SignInFailure.NOT_AVAILABLE)
            is BrowserResult.Redirected -> back.uri
        }
        val code = when (val r = OAuthFlow.parseRedirect(uri, config, pkce.state)) {
            is OAuthFlow.Redirect.Code -> r.code
            is OAuthFlow.Redirect.Error -> return if (r.error == "access_denied") SignInResult.Cancelled else SignInResult.Failed(SignInFailure.DENIED)
            OAuthFlow.Redirect.Invalid -> return SignInResult.Failed(SignInFailure.STATE_MISMATCH)
        }
        val answer = try {
            post(OAuthFlow.codeExchangeForm(config, code, pkce.verifier))
        } catch (_: OfflineException) {
            return SignInResult.Failed(SignInFailure.OFFLINE)
        } ?: return SignInResult.Failed(SignInFailure.BAD_ANSWER)
        if (answer.error != null) return SignInResult.Failed(SignInFailure.DENIED)
        val access = answer.accessToken
        val refresh = answer.refreshToken
        if (access.isNullOrEmpty() || refresh.isNullOrEmpty()) return SignInResult.Failed(SignInFailure.BAD_ANSWER)
        if (!answer.scopeOk) {
            // A grant with another scope is never kept; asking Google to drop it is best effort.
            revokeQuietly(refresh)
            return SignInResult.Failed(SignInFailure.WRONG_SCOPE)
        }
        try {
            store.save(refresh)
        } catch (_: Exception) {
            revokeQuietly(refresh)
            return SignInResult.Failed(SignInFailure.STORE_UNAVAILABLE)
        }
        cache(access, answer.expiresInSeconds)
        SignInResult.Connected
    }

    override suspend fun accessToken(): String = lock.withLock {
        val now = clock()
        cachedToken?.let { if (now < cachedUntil - EARLY_MS) return it }
        val refresh = try {
            store.load()
        } catch (_: Exception) {
            null
        } ?: throw DriveException(DriveException.Kind.UNAUTHORIZED)
        val answer = try {
            post(OAuthFlow.refreshForm(config, refresh))
        } catch (_: OfflineException) {
            throw DriveException(DriveException.Kind.OFFLINE)
        } ?: throw DriveException(DriveException.Kind.SERVER)
        if (answer.error != null) {
            if (answer.error == "invalid_grant") {
                // Revoked, expired (7 days in Testing) or the lock was removed: connect again. Nothing local is deleted.
                forgetLocally()
                throw DriveException(DriveException.Kind.UNAUTHORIZED)
            }
            throw DriveException(DriveException.Kind.SERVER)
        }
        val access = answer.accessToken
        if (access.isNullOrEmpty() || (answer.scope != null && !answer.scopeOk)) throw DriveException(DriveException.Kind.SERVER)
        cache(access, answer.expiresInSeconds)
        access
    }

    override suspend fun onRejected(token: String) {
        lock.withLock {
            if (cachedToken == token) {
                cachedToken = null
                cachedUntil = 0
            }
        }
    }

    override suspend fun disconnect() {
        val refresh = try {
            store.load()
        } catch (_: Exception) {
            null
        }
        forgetLocally()
        if (refresh != null) revokeQuietly(refresh)
    }

    override fun forgetLocally() {
        cachedToken = null
        cachedUntil = 0
        try {
            store.clear()
        } catch (_: Exception) {
            // A store that cannot even clear is unusable; the next read fails closed too.
        }
    }

    private fun cache(access: String, expiresIn: Int?) {
        cachedToken = access
        cachedUntil = clock() + (expiresIn ?: DEFAULT_LIFETIME_S) * 1000L
    }

    private class OfflineException : Exception()

    /** Posts [form]; the parsed answer (also for Google's 4xx error bodies), null for an unreadable one. */
    private suspend fun post(form: Map<String, String>): OAuthFlow.TokenAnswer? {
        val r = try {
            transport.postForm(GoogleAuthConfig.TOKEN_ENDPOINT, form)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw OfflineException()
        }
        if (r.status >= 500) throw OfflineException()
        return OAuthFlow.parseTokenAnswer(r.body)?.let {
            if (r.status in 200..299 || it.error != null) it else null
        }
    }

    private suspend fun revokeQuietly(token: String) {
        try {
            transport.postForm(GoogleAuthConfig.REVOKE_ENDPOINT, mapOf("token" to token))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private companion object {
        /** Use a token a minute short of its end, so a request in flight does not meet the end. */
        const val EARLY_MS = 60_000L
        const val DEFAULT_LIFETIME_S = 3_000
    }
}
