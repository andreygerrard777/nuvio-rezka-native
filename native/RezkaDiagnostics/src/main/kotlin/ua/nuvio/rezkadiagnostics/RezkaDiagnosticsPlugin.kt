package ua.nuvio.rezkadiagnostics

import android.content.Context
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RezkaDiagnosticsPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(RuntimeProbe())
        registerMainAPI(RezkaHttpProbe())
    }
}

// Nuvio's Plugin Test captures search exceptions in its diagnostic report (120 chars).
// These providers deliberately stop at search; no invented titles or playable URLs.
class RuntimeProbe : MainAPI() {
    override var name = "Rezka 1 - Runtime test"
    override var mainUrl = "https://rezka.ag"
    override var lang = "uk"
    override val hasMainPage = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun search(query: String): List<SearchResponse> {
        throw IllegalStateException("REZKA_RUNTIME_OK v1: native plugin loaded; search executed; no streams expected")
    }
}

class RezkaHttpProbe : MainAPI() {
    override var name = "Rezka 2 - HTTP test"
    override var mainUrl = "https://rezka.ag"
    override var lang = "uk"
    override val hasMainPage = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun search(query: String): List<SearchResponse> {
        // Fresh isolated session for each test. One passive GET; no challenge solving yet.
        val report = try {
            val r = NativeHttp().probe(mainUrl + "/")
            "REZKA_HTTP v1 status=${r.status} anubis=${r.challenge} setCookie=${r.hasSetCookie} manualRedirect=true"
        } catch (e: Exception) {
            // Exception messages can contain URLs/tokens; report only the exception class.
            "REZKA_HTTP_ERROR v1 ${e.javaClass.simpleName}"
        }
        throw IllegalStateException(report)
    }
}
