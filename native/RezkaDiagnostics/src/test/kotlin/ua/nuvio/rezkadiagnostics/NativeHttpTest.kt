package ua.nuvio.rezkadiagnostics

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NativeHttpTest {
    @Test fun cookieFrom302IsVisibleAndSentOnNextRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302)
                .addHeader("Location", "/final")
                .addHeader("Set-Cookie", "session=synthetic; Path=/; HttpOnly"))
            server.enqueue(MockResponse().setBody("content"))
            val http = NativeHttp()
            http.client.newCall(Request.Builder().url(server.url("/start")).build()).execute().use {
                assertEquals(302, it.code)
                assertNotNull(it.header("Set-Cookie"))
            }
            assertEquals(1, server.requestCount) // No implicit redirect consumed the 302.
            http.client.newCall(Request.Builder().url(server.url("/final")).build()).execute().close()
            server.takeRequest()
            assertEquals("session=synthetic", server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun cookiesRespectHostPathSecureAndDeletion() {
        val jar = SessionCookies()
        val origin = "https://rezka.example/private/start".toHttpUrl()
        jar.saveFromResponse(origin, listOf(Cookie.parse(origin, "session=x; Path=/private; Secure")!!))
        assertEquals(1, jar.loadForRequest(origin).size)
        assertTrue(jar.loadForRequest("https://other.example/private/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://rezka.example/public".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://rezka.example/private/".toHttpUrl()).isEmpty())
        jar.saveFromResponse(origin, listOf(Cookie.parse(origin, "session=; Path=/private; Max-Age=0; Secure")!!))
        assertTrue(jar.loadForRequest(origin).isEmpty())
    }

    @Test fun status200DoesNotMeanChallengePassed() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<script id=\"anubis_challenge\">{}</script>"))
            val result = NativeHttp().probe(server.url("/").toString())
            assertEquals(200, result.status)
            assertTrue(result.challenge)
        }
    }
}
