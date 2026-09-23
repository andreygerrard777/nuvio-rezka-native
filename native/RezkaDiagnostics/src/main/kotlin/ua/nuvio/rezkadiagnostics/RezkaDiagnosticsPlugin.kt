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
        // Intentionally expose a short sanitized report through Nuvio's Test diagnostics.
        val report = try {
            AnubisProbe().run(mainUrl + "/")
        } catch (e: LinkageError) {
            linkageReport("init", e)
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            "REZKA_V5 stage=init error=" + e.javaClass.simpleName
        }
        throw IllegalStateException(report)
    }
}

/** Linkage errors contain class/method signatures; keep the UI report bounded. */
internal fun linkageReport(stage: String, error: LinkageError): String {
    val detail = (error.message ?: error.cause?.javaClass?.simpleName ?: "")
        .replace(Regex("[^A-Za-z0-9_.$/;:() -]"), "?")
    return ("REZKA_V5 stage=" + stage + " error=" + error.javaClass.simpleName +
        " detail=" + detail).take(120)
}
