package ua.nuvio.rezkadiagnostics

import okhttp3.FormBody
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Cookie scope and expiration are enforced; values are never included in diagnostics. */
internal class SessionCookies : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        this.cookies.removeAll { it.expiresAt <= now }
        cookies.forEach { incoming ->
            this.cookies.removeAll {
                it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path
            }
            if (incoming.expiresAt > now) this.cookies.add(incoming)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }.sortedByDescending { it.path.length }
    }
}

internal data class ProbeResult(val status: Int, val challenge: Boolean, val hasSetCookie: Boolean)

internal data class Page(val status: Int, val body: String, val location: String?)

internal class NativeHttp {
    val cookies = SessionCookies()
    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookies)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    fun page(url: String, timeoutMs: Long): Page = request(url, timeoutMs)

    fun request(
        url: String, timeoutMs: Long, fields: Map<String, String>? = null,
        referer: String = "https://rezka.ag/"
    ): Page {
        val builder = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "ru-RU,ru;q=0.9,uk;q=0.8,en;q=0.7")
            .header("Referer", referer)
        if (fields != null) {
            val form = FormBody.Builder()
            fields.forEach { (key, value) -> form.add(key, value) }
            builder.post(form.build())
                .header("Origin", referer.trimEnd('/'))
                .header("X-Requested-With", "XMLHttpRequest")
        }
        return client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
            .newCall(builder.build()).execute().use { response ->
                val body = response.peekBody(1024L * 1024 + 1)
                check(body.contentLength() <= 1024L * 1024) { "REZKA_V6 stage=http result=BODY_TOO_LARGE" }
                Page(response.code, body.string(), response.header("Location"))
            }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
    }

    fun probe(url: String): ProbeResult {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36")
            .header("Accept-Language", "ru-RU,ru;q=0.9,uk;q=0.8,en;q=0.7")
            .build()
        return client.newCall(request).execute().use { response ->
            // Bounded read, no HTML/challenge/cookie values in logs or test output.
            val preview = response.peekBody(128L * 1024).string()
            ProbeResult(response.code, preview.contains("anubis_challenge"),
                response.headers.values("Set-Cookie").isNotEmpty())
        }
    }
}
