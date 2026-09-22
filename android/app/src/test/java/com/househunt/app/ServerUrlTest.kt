package com.househunt.app

import com.househunt.app.data.ServerUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/** F-02 / SEC-004: only HTTPS, except the local development hosts allowed by network_security_config.xml. */
class ServerUrlTest {

    @Test
    fun acceptsHttpsAndTrimsTrailingSlash() {
        assertEquals(ServerUrl.Result.Ok("https://api.example.com"), ServerUrl.check(" https://api.example.com/ "))
        assertEquals(ServerUrl.Result.Ok("https://api.example.com:8443/base"), ServerUrl.check("https://api.example.com:8443/base"))
    }

    @Test
    fun allowsCleartextOnlyForLocalHosts() {
        assertEquals(ServerUrl.Result.Ok("http://10.0.2.2:8080"), ServerUrl.check("http://10.0.2.2:8080"))
        assertEquals(ServerUrl.Result.Ok("http://localhost:8080"), ServerUrl.check("http://localhost:8080"))
        assertEquals(ServerUrl.Result.NotHttps, ServerUrl.check("http://192.168.1.10:8080"))
        assertEquals(ServerUrl.Result.NotHttps, ServerUrl.check("http://my-api.onrender.com"))
    }

    @Test
    fun rejectsJunk() {
        assertEquals(ServerUrl.Result.Empty, ServerUrl.check("   "))
        assertEquals(ServerUrl.Result.Invalid, ServerUrl.check("ftp://example.com"))
        assertEquals(ServerUrl.Result.Invalid, ServerUrl.check("example.com"))
        assertEquals(ServerUrl.Result.Invalid, ServerUrl.check("https://user:pass@example.com"))
        assertEquals(ServerUrl.Result.Invalid, ServerUrl.check("https://example.com/?x=1"))
        assertEquals(ServerUrl.Result.Invalid, ServerUrl.check("https://exa mple.com"))
    }

    @Test
    fun schemeAndLocalHostAreCaseInsensitive() {
        assertEquals(ServerUrl.Result.Ok("HTTPS://Example.COM"), ServerUrl.check("HTTPS://Example.COM"))
        assertEquals(ServerUrl.Result.Ok("http://LOCALHOST:8080"), ServerUrl.check("http://LOCALHOST:8080"))
        assertEquals(ServerUrl.Result.Ok("http://127.0.0.1:8080"), ServerUrl.check("http://127.0.0.1:8080/"))
        assertEquals(ServerUrl.Result.Ok("https://example.com"), ServerUrl.check("https://example.com///"))
    }

    @Test
    fun lookAlikeLocalHostsNeedHttps() {
        assertEquals(ServerUrl.Result.NotHttps, ServerUrl.check("http://localhost.evil.example"))
        assertEquals(ServerUrl.Result.NotHttps, ServerUrl.check("http://127.0.0.2:8080"))
        assertEquals(ServerUrl.Result.NotHttps, ServerUrl.check("http://[::1]:8080"))
    }

    @Test
    fun rejectsUrlsWithoutAHost() {
        assertEquals(ServerUrl.Result.Invalid, ServerUrl.check("https://"))
        assertEquals(ServerUrl.Result.Invalid, ServerUrl.check("javascript:alert(1)"))
        assertEquals(ServerUrl.Result.Invalid, ServerUrl.check("https://example.com#top"))
        assertEquals(ServerUrl.Result.Invalid, ServerUrl.check("/api"))
    }
}
