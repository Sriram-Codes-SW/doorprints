package app.doorprints.drive.connect

import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Pure parts of the authorisation-code flow, so they are tested without a browser or a network. */
object OAuthFlow {
    /** Google's consent URL: code flow, PKCE S256, offline access (a refresh token), `drive.file` and nothing else. */
    fun authorizationUrl(config: GoogleAuthConfig, pkce: Pkce): String {
        val b = URLBuilder(GoogleAuthConfig.AUTH_ENDPOINT)
        b.parameters.append("client_id", config.clientId)
        b.parameters.append("redirect_uri", config.redirectUri)
        b.parameters.append("response_type", "code")
        b.parameters.append("scope", GoogleAuthConfig.SCOPE_DRIVE_FILE)
        b.parameters.append("code_challenge", pkce.challenge)
        b.parameters.append("code_challenge_method", "S256")
        b.parameters.append("state", pkce.state)
        b.parameters.append("access_type", "offline")
        // Without this Google may not send a refresh token again to a device that signed in before.
        b.parameters.append("prompt", "consent")
        return b.buildString()
    }

    sealed interface Redirect {
        data class Code(val code: String) : Redirect

        /** Google's `error` (`access_denied` ...): the person said no, or Google refused. */
        data class Error(val error: String) : Redirect

        /** Not our redirect, no `state`, or a `state` that is not this attempt's. */
        data object Invalid : Redirect
    }

    /**
     * Reads what the browser handed back. The URI must be our redirect (same text before `?`), carry the `state` of
     * this attempt, and either a `code` or an `error`; anything else is [Redirect.Invalid]. Fragments are ignored.
     */
    fun parseRedirect(uri: String, config: GoogleAuthConfig, expectedState: String): Redirect {
        val noFragment = uri.substringBefore('#')
        val base = noFragment.substringBefore('?')
        if (base != config.redirectUri) return Redirect.Invalid
        val query = noFragment.substringAfter('?', "")
        if (query.isEmpty()) return Redirect.Invalid
        val params = try {
            parseQueryString(query)
        } catch (_: Exception) {
            return Redirect.Invalid
        }
        val state = params["state"]
        if (state == null || !constantTimeEquals(state, expectedState)) return Redirect.Invalid
        params["error"]?.let { return Redirect.Error(it.take(MAX_ERROR)) }
        val code = params["code"]
        return if (code.isNullOrBlank()) Redirect.Invalid else Redirect.Code(code)
    }

    fun codeExchangeForm(config: GoogleAuthConfig, code: String, verifier: String): Map<String, String> = mapOf(
        "grant_type" to "authorization_code",
        "code" to code,
        "client_id" to config.clientId,
        "redirect_uri" to config.redirectUri,
        "code_verifier" to verifier,
    )

    fun refreshForm(config: GoogleAuthConfig, refreshToken: String): Map<String, String> = mapOf(
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
        "client_id" to config.clientId,
    )

    /** What Google's token endpoint answered, with the token strings kept out of `toString`. */
    class TokenAnswer(
        val accessToken: String?,
        val refreshToken: String?,
        val expiresInSeconds: Int?,
        val scope: String?,
        val error: String?,
    ) {
        override fun toString() = "TokenAnswer(error=$error)"

        /** The scopes granted, split; null when Google did not say. */
        val scopes: Set<String>? get() = scope?.split(' ')?.filter { it.isNotEmpty() }?.toSet()

        /** Exactly `drive.file`: a wider grant is refused (we asked for one scope only). */
        val scopeOk: Boolean get() = scopes == setOf(GoogleAuthConfig.SCOPE_DRIVE_FILE)
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun parseTokenAnswer(body: String): TokenAnswer? {
        val o: JsonObject = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            return null
        }
        fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val expires = (o["expires_in"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
        return TokenAnswer(str("access_token"), str("refresh_token"), expires, str("scope"), str("error"))
    }

    /** Equal text, compared without stopping at the first difference (the `state` is not secret, but costs nothing). */
    fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.encodeToByteArray()
        val y = b.encodeToByteArray()
        var diff = x.size xor y.size
        for (i in 0 until maxOf(x.size, y.size)) {
            diff = diff or ((x.getOrElse(i) { 0 }.toInt()) xor (y.getOrElse(i) { 1 }.toInt()))
        }
        return diff == 0
    }

    private const val MAX_ERROR = 64
}
