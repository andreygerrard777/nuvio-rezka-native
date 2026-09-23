package ua.nuvio.rezkadiagnostics

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class RezkaMoviesTest {
    private fun searchPage(base: String) = """
        <li><a href="$base/films/action/123-terminator-1984.html"><span class="enty">Терминатор</span> (The Terminator, 1984)<span class="rating">8</span></a></li>
        <li><a href="$base/films/action/124-terminator-1991.html"><span class="enty">Терминатор 2</span> (Terminator 2, 1991)</a></li>
        <li><a href="https://other.example/films/9-wrong.html"><span class="enty">Wrong</span> (1984)</a></li>
        <li><a href="$base/series/10-series.html"><span class="enty">Series</span> (2020)</a></li>
    """.trimIndent()

    private val movie = """
        <h1>Терминатор</h1><input value="test-favs" id="ctrl_favs">
        <li class="b-translator__item" data-translator_id="7" title="Other">Other</li>
        <a data-translator_id="8" class="b-translator__items" title="Studio"><img title="Український"></a>
        <script>sof.tv.initCDNMoviesEvents(123, 7, 0, 0, 0, 'rezka.ag');</script>
    """.trimIndent()

    @Test fun searchKeepsOriginalTitleAndReleaseYearAndRejectsForeignResults() {
        val hits = RezkaMovies.search(searchPage("https://rezka.ag"), "https://rezka.ag")
        assertEquals(2, hits.size)
        assertEquals("The Terminator", hits[0].title)
        assertEquals(1984, hits[0].year)
        assertEquals(1991, hits[1].year)
        val blade = """<li><a href="/films/1-blade.html"><span class="enty">Бегущий</span> (Blade Runner 2049, 2017)</a></li>"""
        assertEquals(2017, RezkaMovies.search(blade, "https://rezka.ag").single().year)
    }

    @Test fun pageSupportsUnorderedAttributesAndPrefersUkrainianDub() {
        val parsed = RezkaMovies.page(movie, "https://rezka.ag/films/123-film.html")
        assertEquals("test-favs", parsed.favs)
        assertEquals("8", parsed.dubs.first().translator)
        assertEquals("123", parsed.dubs.first().id)
        assertEquals(2, parsed.dubs.size)
    }

    @Test fun singleDubFallsBackToPlayerBootstrap() {
        val parsed = RezkaMovies.page(
            "<script>sof.tv.initCDNMoviesEvents(123, 9, 1, 0, 1, 'rezka.ag');</script>",
            "https://rezka.ag/films/123-film.html")
        assertEquals(MovieDub("123", "9", "Rezka", "1", "0", "1"), parsed.dubs.single())
    }

    @Test fun extractsQualitiesAndRejectsNonHttpUrls() {
        val links = RezkaMovies.streams(
            """[720p]https://cdn.example/a.m3u8 or https://cdn.example/backup.m3u8,[<b>1080p</b>]https://cdn.example/b.mp4,[360p]javascript:bad""",
            "Dub")
        assertEquals(listOf(720, 1080), links.map { it.quality })
        assertEquals("https://cdn.example/a.m3u8", links.first().url)
        assertEquals("Dub 1080p", links.last().label)
    }

    @Test fun completeMovieFlowRetainsAnubisCookieForSearchPageAndFormPost() {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            val challenge = """<script id="anubis_challenge" type="application/json">{"rules":{"algorithm":"fast","difficulty":1},"challenge":{"id":"test-id","randomData":"test-seed"}}</script>"""
            server.enqueue(MockResponse().setBody(challenge)) // initial POST gets gate
            server.enqueue(MockResponse().setBody(challenge)
                .addHeader("Set-Cookie", "techaro.lol-anubis-cookie-verification=test-id; Path=/"))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/")
                .addHeader("Set-Cookie", "techaro.lol-anubis-auth=test-auth; Path=/; HttpOnly"))
            server.enqueue(MockResponse().setBody("""<div class="b-content__inline_items">Catalog</div>"""))
            server.enqueue(MockResponse().setBody(searchPage(base)))
            server.enqueue(MockResponse().setBody(movie))
            server.enqueue(MockResponse().setBody("""{"success":true,"url":"[720p]https://cdn.example/movie.m3u8"}"""))

            val session = RezkaMovieSession(base)
            val hit = session.search("The Terminator").first()
            val links = session.streams(hit.url)
            assertEquals(1984, hit.year)
            assertEquals(1, links.size)
            val requests = (1..7).map { server.takeRequest(1, TimeUnit.SECONDS)!! }
            assertEquals("POST", requests[0].method)
            assertEquals("q=The Terminator", java.net.URLDecoder.decode(requests[0].body.readUtf8(), "UTF-8"))
            assertTrue(requests[2].getHeader("Cookie")!!.contains("cookie-verification=test-id"))
            for (i in 3..6) assertTrue(requests[i].getHeader("Cookie")!!.contains("anubis-auth=test-auth"))
            val form = requests[6].body.readUtf8()
            assertTrue(form.contains("translator_id=8"))
            assertTrue(form.contains("action=get_movie"))
            assertTrue(form.contains("favs=test-favs"))
            assertEquals("XMLHttpRequest", requests[6].getHeader("X-Requested-With"))
            assertEquals(base, requests[6].getHeader("Origin"))
            assertEquals(requests[0].getHeader("User-Agent"), requests[6].getHeader("User-Agent"))
        }
    }

    @Test fun rejectsForeignMovieUrlBeforeSendingRequest() {
        MockWebServer().use { server ->
            try {
                RezkaMovieSession(server.url("/").toString().trimEnd('/'))
                    .movie("https://other.example/films/1.html")
                fail("Expected origin check")
            } catch (_: IllegalArgumentException) { }
            assertEquals(0, server.requestCount)
        }
    }
}
