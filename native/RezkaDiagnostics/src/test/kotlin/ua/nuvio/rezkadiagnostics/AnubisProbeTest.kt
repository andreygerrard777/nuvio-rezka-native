package ua.nuvio.rezkadiagnostics

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class AnubisProbeTest {
    private val challenge = """<script type="application/json" id="anubis_challenge">{"rules":{"algorithm":"fast","difficulty":1},"challenge":{"id":"synthetic-id","randomData":"synthetic-seed"}}</script>"""

    private fun initial() = MockResponse().setBody(challenge)
        .addHeader("Set-Cookie", "techaro.lol-anubis-cookie-verification=synthetic-id; Path=/")
    private fun accepted() = MockResponse().setResponseCode(302).addHeader("Location", "/")
        .addHeader("Set-Cookie", "techaro.lol-anubis-auth=synthetic-auth; Path=/; HttpOnly")

    @Test fun parsesChallengeAndJsonStringPrefix() {
        val html = challenge + """<script id="anubis_base_prefix" type="application/json">"/proxy"</script>"""
        val parsed = AnubisPow.parse(html)
        assertEquals("synthetic-id", parsed.id)
        assertEquals("synthetic-seed", parsed.seed)
        assertEquals(1, parsed.difficulty)
        assertEquals("/proxy", parsed.prefix)
        assertEquals("", AnubisPow.parse(challenge).prefix)
    }

    @Test fun rejectsNonStringPrefix() {
        val html = challenge + """<script id="anubis_base_prefix" type="application/json">123</script>"""
        try {
            AnubisPow.parse(html)
            fail("Expected invalid prefix")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun completesChallengeWithCookieFrom302AndSameUserAgent() {
        MockWebServer().use { server ->
            server.enqueue(initial())
            server.enqueue(accepted())
            server.enqueue(MockResponse().setBody("""<div class="b-content__inline_items">Catalog</div>"""))
            val report = AnubisProbe().run(server.url("/").toString())
            assertTrue(report, report.endsWith("result=OK"))
            assertTrue(report, report.contains("pass=302 auth=true"))
            assertTrue(report.length <= 120)
            assertFalse(report.contains("synthetic-"))
            assertEquals(3, server.requestCount)
            val first = server.takeRequest(1, TimeUnit.SECONDS)!!
            val pass = server.takeRequest(1, TimeUnit.SECONDS)!!
            val verified = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/.within.website/x/cmd/anubis/api/pass-challenge", pass.requestUrl!!.encodedPath)
            assertTrue(pass.getHeader("Cookie")!!.contains("techaro.lol-anubis-cookie-verification=synthetic-id"))
            assertTrue(verified.getHeader("Cookie")!!.contains("techaro.lol-anubis-auth=synthetic-auth"))
            assertEquals(first.getHeader("User-Agent"), pass.getHeader("User-Agent"))
            assertEquals(first.getHeader("User-Agent"), verified.getHeader("User-Agent"))
            val nonce = pass.requestUrl!!.queryParameter("nonce")!!
            val hash = MessageDigest.getInstance("SHA-256").digest(("synthetic-seed" + nonce).toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(hash, pass.requestUrl!!.queryParameter("response"))
            assertTrue(hash.startsWith("0"))
        }
    }

    @Test fun validCookieDoesNotTurnAnotherChallengeIntoSuccess() {
        MockWebServer().use { server ->
            server.enqueue(initial()); server.enqueue(accepted()); server.enqueue(initial())
            val report = AnubisProbe().run(server.url("/").toString())
            assertTrue(report, report.endsWith("result=CHALLENGED"))
            assertEquals(3, server.requestCount) // No endless retry loop.
        }
    }

    @Test fun rejectionStopsBeforeVerification() {
        MockWebServer().use { server ->
            server.enqueue(initial())
            server.enqueue(MockResponse().setResponseCode(403).setBody("private server error"))
            val report = AnubisProbe().run(server.url("/").toString())
            assertTrue(report, report.contains("pass=403"))
            assertTrue(report.endsWith("result=PASS_REJECTED"))
            assertFalse(report.contains("private"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun unrecognized200PageIsNotSuccess() {
        MockWebServer().use { server ->
            server.enqueue(initial()); server.enqueue(accepted())
            server.enqueue(MockResponse().setBody("maintenance"))
            assertTrue(AnubisProbe().run(server.url("/").toString()).endsWith("result=UNKNOWN_PAGE"))
        }
    }

    @Test fun unsupportedAlgorithmStopsBeforeSubmission() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(challenge.replace("fast", "other")))
            val report = AnubisProbe().run(server.url("/").toString())
            assertTrue(report, report.contains("stage=parse"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun excessiveDifficultyIsRejected() {
        try {
            AnubisPow.parse(challenge.replace("\"difficulty\":1", "\"difficulty\":9"))
            fail("Expected unsupported difficulty")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun proofBudgetIsEnforced() {
        try {
            AnubisPow.solve(Challenge("id", "seed", 4, ""), budgetMs = 0)
            fail("Expected proof budget limit")
        } catch (_: IllegalStateException) { }
    }

    @Test fun crossOriginRedirectIsNotFollowed() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://other.example/"))
            assertTrue(AnubisProbe().run(server.url("/").toString()).contains("stage=initial"))
            assertEquals(1, server.requestCount)
        }
    }
}
