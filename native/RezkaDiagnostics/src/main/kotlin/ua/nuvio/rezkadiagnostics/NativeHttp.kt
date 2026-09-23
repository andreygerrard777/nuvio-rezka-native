package ua.nuvio.rezkadiagnostics

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
