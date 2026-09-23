package ua.nuvio.rezkadiagnostics

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.jsoup.Jsoup

internal data class MovieHit(val title: String, val url: String, val year: Int?)
internal data class MovieDub(
    val id: String, val translator: String, val label: String,
    val camrip: String = "0", val ads: String = "0", val director: String = "0"
)
internal data class MoviePage(val title: String, val url: String, val dubs: List<MovieDub>, val favs: String)
internal data class MovieStream(val url: String, val quality: Int, val label: String)

/** Pure parsing is kept separate from the host's CloudStream API. */
internal object RezkaMovies {
    fun sameOrigin(a: HttpUrl, b: HttpUrl) =
        a.scheme == b.scheme && a.host == b.host && a.port == b.port

    fun search(html: String, origin: String): List<MovieHit> {
        val base = origin.toHttpUrl()
        return Jsoup.parse(html, origin).select("li a[href]").mapNotNull { a ->
            val local = a.selectFirst(".enty")?.text()?.trim() ?: return@mapNotNull null
            val url = base.resolve(a.attr("href")) ?: return@mapNotNull null
            if (!sameOrigin(base, url) || !url.encodedPath.endsWith(".html") ||
                url.encodedPath.contains("/series/")) return@mapNotNull null
            val extra = a.ownText().trim().removePrefix("(").substringBeforeLast(")")
            val yearMatch = Regex("""(?:^|,\s*)((?:19|20)\d{2})\b""").find(extra)
            val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
            val original = (if (yearMatch != null) extra.substring(0, yearMatch.range.first) else "")
                .replace(Regex("""(,\s*[а-яёіїєґ\s-]+)+$""", RegexOption.IGNORE_CASE), "").trim()
            MovieHit(original.ifBlank { local }, url.toString(), year)
        }.distinctBy { it.url }.take(30)
    }

    fun page(html: String, url: String): MoviePage {
        val doc = Jsoup.parse(html, url)
        check(!html.contains("initCDNSeriesEvents")) { "REZKA_V6 stage=page result=SERIES_UNSUPPORTED" }
        val boot = Regex("""initCDNMoviesEvents\(\s*(\d+)\s*,\s*(\d+)\s*,\s*([^,]*),\s*([^,]*),\s*([^,]*),""")
            .find(html)
        val pageId = boot?.groupValues?.get(1)
            ?: Regex("""/(\d+)-[^/]*\.html""").find(url)?.groupValues?.get(1).orEmpty()
        fun flag(s: String) = if (s.trim() == "1") "1" else "0"
        val dubs = doc.select(".b-translator__item, .b-translator__items").mapNotNull { e ->
            val id = e.attr("data-id").ifBlank { pageId }
            val translator = e.attr("data-translator_id")
            if (!id.matches(Regex("""\d+""")) || !translator.matches(Regex("""\d+"""))) return@mapNotNull null
            val language = e.selectFirst("img[title]")?.attr("title").orEmpty()
            val title = e.attr("title").ifBlank { e.text() }.ifBlank { "Rezka" }
            MovieDub(id, translator, "$title $language".trim(),
                flag(e.attr("data-camrip")), flag(e.attr("data-ads")), flag(e.attr("data-director")))
        }.distinctBy { it.translator + ":" + it.director }.toMutableList()
        if (dubs.isEmpty() && boot != null) {
            val g = boot.groupValues
            dubs.add(MovieDub(g[1], g[2], "Rezka", flag(g[3]), flag(g[4]), flag(g[5])))
        }
        val sorted = dubs.sortedByDescending {
            it.label.contains("укра", true) || it.label.contains("ukrain", true)
        }
        return MoviePage(doc.selectFirst("h1")?.text().orEmpty().ifBlank { "Rezka" },
            url, sorted, doc.selectFirst("#ctrl_favs")?.attr("value").orEmpty())
    }

    fun streams(raw: String, dub: String): List<MovieStream> =
        Regex("""\[([^\]]*)\]([^\[]+?)(?=,\[|$)""").findAll(raw).mapNotNull { match ->
            val qualityLabel = Jsoup.parse(match.groupValues[1]).text()
            val quality = if (qualityLabel.contains("4K", true)) 2160
                else Regex("""\d{3,4}""").find(qualityLabel)?.value?.toIntOrNull() ?: 0
            val url = match.groupValues[2].split(" or ").mapNotNull { it.trim().toHttpUrlOrNull() }
                .firstOrNull { it.username.isEmpty() && it.password.isEmpty() } ?: return@mapNotNull null
            MovieStream(url.toString(), quality, "$dub $qualityLabel".trim())
        }.distinctBy { it.url }.toList()
}

/** A single cookie jar is reused for search, page, challenge and stream requests. */
internal class RezkaMovieSession(
    private val origin: String = "https://rezka.ag",
    private val http: NativeHttp = NativeHttp()
) {
    private val base = origin.toHttpUrl()
    private fun request(url: String, fields: Map<String, String>? = null): String {
        val target = url.toHttpUrl()
        require(RezkaMovies.sameOrigin(base, target))
        fun fetch(): Page {
            var current = target
            repeat(4) {
                val page = http.request(current.toString(), 8000, fields, origin + "/")
                if (page.status !in setOf(301, 302, 303, 307, 308)) return page
                check(fields == null) { "REZKA_V6 stage=http result=POST_REDIRECT" }
                current = current.resolve(page.location ?: error("REZKA_V6 stage=http result=NO_LOCATION"))
                    ?: error("REZKA_V6 stage=http result=BAD_LOCATION")
                check(RezkaMovies.sameOrigin(base, current)) { "REZKA_V6 stage=http result=CROSS_ORIGIN" }
            }
            error("REZKA_V6 stage=http result=REDIRECT_LIMIT")
        }
        var page = fetch()
        if (page.body.contains("anubis_challenge")) {
            val report = AnubisProbe(http).run(origin + "/")
            check(report.endsWith("result=OK") || report.endsWith("result=NO_CHALLENGE")) {
                report.replace("REZKA_V5", "REZKA_V6")
            }
            page = fetch()
        }
        check(page.status in 200..299) { "REZKA_V6 stage=http status=" + page.status }
        check(!page.body.contains("anubis_challenge")) { "REZKA_V6 stage=http result=CHALLENGED" }
        return page.body
    }

    @Synchronized fun search(query: String): List<MovieHit> {
        if (query.isBlank()) return emptyList()
        val queries = linkedSetOf(query.take(150))
        // Local aliases for the first user-selected title; never substitute an unrelated movie.
        if (Regex("""(?i)^(the\s+)?(terminator|термінатор|терминатор)(\s*\(?1984\)?)?$""")
                .matches(query.trim())) {
            queries.add("The Terminator")
            queries.add("Терминатор")
        }
        for (q in queries) {
            val hits = RezkaMovies.search(request(origin + "/engine/ajax/search.php", mapOf("q" to q)), origin)
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    @Synchronized fun movie(url: String): MoviePage = RezkaMovies.page(request(url), url)

    @Synchronized fun streams(url: String): List<MovieStream> {
        val movie = movie(url)
        check(movie.dubs.isNotEmpty()) { "REZKA_V6 stage=page result=NO_DUBS" }
        // First playable dub only for this prototype; Ukrainian entries sort first.
        for (dub in movie.dubs.take(3)) {
            val raw = request(origin + "/ajax/get_cdn_series/", mapOf(
                "id" to dub.id, "translator_id" to dub.translator, "is_camrip" to dub.camrip,
                "is_ads" to dub.ads, "is_director" to dub.director, "favs" to movie.favs,
                "action" to "get_movie"))
            val response = JSONObject(raw)
            if (!response.optBoolean("success")) continue
            val links = RezkaMovies.streams(response.optString("url"), dub.label)
            if (links.isNotEmpty()) return links
        }
        error("REZKA_V6 stage=streams result=NO_PLAYABLE_URLS")
    }
}
