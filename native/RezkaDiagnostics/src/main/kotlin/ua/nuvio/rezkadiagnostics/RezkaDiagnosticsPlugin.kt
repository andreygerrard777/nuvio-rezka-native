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
        registerMainAPI(RezkaHttpProbe())
    }
}

// Nuvio's Plugin Test captures search exceptions in its diagnostic report (120 chars).
// One registered provider: Nuvio caches and tests the first MainAPI only.
class RezkaHttpProbe : MainAPI() {
    override var name = "Rezka HTTP test"
    override var mainUrl = "https://rezka.ag"
    override var lang = "uk"
    override val hasMainPage = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun search(query: String): List<SearchResponse> {
        // Fresh isolated session for each test. One passive GET; no challenge solving yet.
        val report = try {
            val r = NativeHttp().probe(mainUrl + "/")
            "REZKA_HTTP v2 status=${r.status} anubis=${r.challenge} setCookie=${r.hasSetCookie} manualRedirect=true"
        } catch (e: Exception) {
            // Exception messages can contain URLs/tokens; report only the exception class.
            "REZKA_HTTP_ERROR v2 ${e.javaClass.simpleName}"
        }
        throw IllegalStateException(report)
    }
}
