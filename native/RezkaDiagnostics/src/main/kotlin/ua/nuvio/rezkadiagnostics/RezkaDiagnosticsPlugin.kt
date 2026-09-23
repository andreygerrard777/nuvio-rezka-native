package ua.nuvio.rezkadiagnostics

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@CloudstreamPlugin
class RezkaDiagnosticsPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(RezkaHttpProbe())
    }
}

/** Keep the registered class stable so existing installations can update. */
class RezkaHttpProbe : MainAPI() {
    override var name = "Rezka Native Movies"
    override var mainUrl = "https://rezka.ag"
    override var lang = "uk"
    override val hasMainPage = false
    override val supportedTypes = setOf(TvType.Movie)
    private val session by lazy { RezkaMovieSession(mainUrl) }

    private suspend fun <T> guarded(stage: String, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: LinkageError) {
            throw IllegalStateException(linkageReport(stage, e))
        } catch (e: Exception) {
            val report = e.message?.takeIf { it.startsWith("REZKA_V6 ") }
                ?: ("REZKA_V6 stage=" + stage + " error=" + e.javaClass.simpleName)
            throw IllegalStateException(report.take(120))
        }
    }

    override suspend fun search(query: String): List<SearchResponse> = guarded("search") {
        session.search(query).map { hit ->
            newMovieSearchResponse(hit.title, hit.url, TvType.Movie) { year = hit.year }
        }
    }

    override suspend fun load(url: String): LoadResponse = guarded("load") {
        val movie = session.movie(url)
        newMovieLoadResponse(movie.title, movie.url, TvType.Movie, movie.url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = guarded("links") {
        val links = session.streams(data)
        for (stream in links) {
            callback(newExtractorLink(
                source = name, name = "Rezka - " + stream.label, url = stream.url,
                type = if (stream.url.substringBefore('?').endsWith(".m3u8", true))
                    ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                quality = stream.quality
                referer = mainUrl + "/"
                headers = mapOf("Origin" to mainUrl, "User-Agent" to NativeHttp.USER_AGENT)
            })
        }
        links.isNotEmpty()
    }
}

/** Linkage errors contain class/method signatures; keep the UI report bounded. */
internal fun linkageReport(stage: String, error: LinkageError): String {
    val detail = (error.message ?: error.cause?.javaClass?.simpleName ?: "")
        .replace(Regex("[^A-Za-z0-9_.$/;:() -]"), "?")
    return ("REZKA_V6 stage=" + stage + " error=" + error.javaClass.simpleName +
        " detail=" + detail).take(120)
}
