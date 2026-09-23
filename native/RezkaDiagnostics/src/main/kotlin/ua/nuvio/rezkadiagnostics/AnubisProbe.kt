package ua.nuvio.rezkadiagnostics

import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.security.MessageDigest

internal data class Challenge(val id: String, val seed: String, val difficulty: Int, val prefix: String)
internal data class Solution(val hash: String, val nonce: Long, val elapsedMs: Long)

internal object AnubisPow {
    fun parse(html: String): Challenge {
        val doc = Jsoup.parse(html)
        val raw = doc.getElementById("anubis_challenge")?.data() ?: error("missing challenge")
        val root = JsonParser.parseString(raw).asJsonObject
        val rules = root.getAsJsonObject("rules")
        require(rules.get("algorithm").asString in setOf("fast", "slow"))
        val difficulty = rules.get("difficulty").asString
        require(difficulty.matches(Regex("[0-4]")))
        val challenge = root.getAsJsonObject("challenge")
        val id = challenge.get("id").asString
        val seed = challenge.get("randomData").asString
        require(id.length in 1..1024 && seed.length in 1..4096)
        val prefix = doc.getElementById("anubis_base_prefix")?.data()
            ?.let { JsonParser.parseString(it).asString } ?: ""
        require(prefix.isEmpty() || (prefix.startsWith("/") && !prefix.startsWith("//") &&
            !prefix.contains("..") && !prefix.contains('?') && !prefix.contains('#') &&
            !prefix.contains('\\')))
        return Challenge(id, seed, difficulty.toInt(), prefix.trimEnd('/'))
    }

    fun solve(ch: Challenge, budgetMs: Long = 5000): Solution {
        require(ch.difficulty in 0..4)
        val start = System.nanoTime()
        val digest = MessageDigest.getInstance("SHA-256")
        val seed = ch.seed.toByteArray(Charsets.UTF_8)
        for (nonce in 0L until 2_000_000L) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if ((System.nanoTime() - start) / 1_000_000 >= budgetMs) error("proof budget exhausted")
            digest.update(seed)
            val bytes = digest.digest(nonce.toString().toByteArray(Charsets.US_ASCII))
            val zero = (0 until ch.difficulty).all { n ->
                val value = bytes[n / 2].toInt() and 255
                (if (n % 2 == 0) value shr 4 else value and 15) == 0
            }
            if (zero) {
                val hex = "0123456789abcdef"
                val hash = buildString { bytes.forEach { b ->
                    val value = b.toInt() and 255
                    append(hex[value shr 4]); append(hex[value and 15])
                } }
                return Solution(hash, nonce, (System.nanoTime() - start) / 1_000_000)
            }
        }
        error("proof iteration limit")
    }
}

/** One challenge attempt, bounded runtime, no token/URL/body logging. */
internal class AnubisProbe(private val http: NativeHttp = NativeHttp()) {
    private var stage = "initial"
    private val deadline = System.nanoTime() + 40_000_000_000L

    private fun get(url: HttpUrl): Page {
        val remaining = (deadline - System.nanoTime()) / 1_000_000
        check(remaining > 0)
        return http.page(url.toString(), minOf(10000L, remaining))
    }

    private fun sameOrigin(a: HttpUrl, b: HttpUrl) =
        a.scheme == b.scheme && a.host == b.host && a.port == b.port

    private fun pageFollowingRedirects(start: HttpUrl): Page {
        var url = start
        repeat(4) { hop ->
            val page = get(url)
            if (page.status !in setOf(301, 302, 303, 307, 308)) return page
            check(hop < 3)
            val next = url.resolve(page.location ?: error("missing location")) ?: error("bad location")
            check(sameOrigin(start, next))
            url = next
        }
        error("redirect limit")
    }

    private fun gate(page: Page) = page.body.contains("anubis_challenge") ||
        page.body.contains("/.within.website/x/cmd/anubis/")
    private fun site(page: Page) = Jsoup.parse(page.body)
        .select(".b-content__inline_items, .b-content__inline_item, #cdn-player").isNotEmpty()

    fun run(url: String = "https://rezka.ag/"): String {
        try {
            val target = url.toHttpUrl()
            val initial = pageFollowingRedirects(target)
            if (initial.status !in 200..299) return "REZKA_V4 initial=" + initial.status + " result=HTTP_ERROR"
            if (!gate(initial)) return "REZKA_V4 initial=" + initial.status + " gate=0 site=" + site(initial) + " result=NO_CHALLENGE"
            stage = "parse"
            val challenge = AnubisPow.parse(initial.body)
            stage = "solve"
            val solution = AnubisPow.solve(challenge)
            val passUrl = target.newBuilder()
                .encodedPath(challenge.prefix + "/.within.website/x/cmd/anubis/api/pass-challenge")
                .query(null)
                .addQueryParameter("id", challenge.id)
                .addQueryParameter("response", solution.hash)
                .addQueryParameter("nonce", solution.nonce.toString())
                .addQueryParameter("redir", target.toString())
                .addQueryParameter("elapsedTime", solution.elapsedMs.toString())
                .build()
            stage = "pass"
            val passed = get(passUrl)
            val auth = http.cookies.loadForRequest(target).any {
                it.name == "techaro.lol-anubis-auth" && it.value.isNotEmpty()
            }
            if (passed.status !in 200..399) return "REZKA_V4 pass=" + passed.status + " auth=" + auth + " result=PASS_REJECTED"
            stage = "verify"
            // Re-fetch the original page, not an arbitrary server-supplied pass redirect.
            val verified = pageFollowingRedirects(target)
            val stillGate = gate(verified)
            val isSite = site(verified)
            val result = when {
                verified.status !in 200..299 -> "HTTP_ERROR"
                stillGate -> "CHALLENGED"
                isSite -> "OK"
                else -> "UNKNOWN_PAGE"
            }
            return "REZKA_V4 pass=" + passed.status + " auth=" + auth + " verify=" + verified.status +
                " gate=" + stillGate + " site=" + isSite + " result=" + result
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: LinkageError) {
            return linkageReport(stage, e)
        } catch (e: Exception) {
            return "REZKA_V4 stage=" + stage + " error=" + e.javaClass.simpleName
        }
    }
}
