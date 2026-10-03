package dev.dontblameme.selvage.wire

import dev.dontblameme.selvage.canonical.CanonicalJson
import dev.dontblameme.selvage.canonical.JsonValue
import dev.dontblameme.selvage.canonical.MalformedJson
import dev.dontblameme.selvage.peer.Keepalive
import dev.dontblameme.selvage.sealed.Urls
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** `GET /meta` (§2): advisory, so a body that cannot be read decides nothing. */
data class Meta(
    val server: String?,
    val wireVersions: List<String>,
    val capabilities: List<String>,
    val keepalive: Keepalive?,
    val roomGraceMs: Long?,
) {
    companion object {
        const val MAX_BODY_BYTES = 64 * 1024

        fun parse(body: String): Meta? {
            val obj =
                try {
                    CanonicalJson.parse(body) as? JsonValue.Obj
                } catch (e: MalformedJson) {
                    null
                } ?: return null
            val keepalive = obj.obj("keepalive")
            val clocks =
                keepalive?.let {
                    val defaults = Keepalive()
                    Keepalive(
                        it.count("ping_interval_ms") ?: defaults.pingIntervalMs,
                        it.count("awareness_renew_ms") ?: defaults.awarenessRenewMs,
                        it.count("awareness_expire_ms") ?: defaults.awarenessExpireMs,
                    )
                }
            return Meta(
                obj.string("server"),
                strings(obj.arr("wire_versions")),
                strings(obj.arr("capabilities")),
                clocks,
                keepalive?.count("room_grace_ms"),
            )
        }

        /** Reads `/meta` under [base], bounded by [timeout]; throws [IOException] when it cannot. */
        fun fetch(
            base: String,
            timeout: Duration,
            client: HttpClient = HttpClient.newBuilder().connectTimeout(timeout).build(),
        ): Meta {
            val session = Urls.sessionBase(base) ?: throw IOException("not a session address: $base")
            val request =
                HttpRequest
                    .newBuilder(URI(Urls.metaUrl(session)))
                    .timeout(timeout)
                    .GET()
                    .build()
            val response =
                try {
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("interrupted while reading /meta", e)
                }
            val body = response.body().use { it.readNBytes(MAX_BODY_BYTES + 1) }
            if (response.statusCode() != 200) throw IOException("/meta answered ${response.statusCode()}")
            if (body.size > MAX_BODY_BYTES) throw IOException("/meta is longer than $MAX_BODY_BYTES bytes")
            return parse(String(body, Charsets.UTF_8)) ?: throw IOException("/meta is not one JSON object")
        }

        private fun strings(arr: JsonValue.Arr?): List<String> =
            arr?.items?.mapNotNull { (it as? JsonValue.Str)?.value } ?: emptyList()
    }
}

/** §9.1's bounded reconnect: the numbers are policy, the bound is not. */
data class ReconnectPolicy(
    val enabled: Boolean = true,
    val initialDelayMs: Long = 500,
    val maxDelayMs: Long = 10_000,
    val maxAttempts: Int = 5,
) {
    fun delay(attempt: Int): Long = minOf(initialDelayMs.coerceAtLeast(1) shl minOf(attempt, 30), maxDelayMs)

    /** How many attempts it takes before the waits in front of them add up to [graceMs], capped. */
    fun attemptsForGrace(graceMs: Long): Int {
        var waited = 0L
        var attempts = 0
        while (waited < graceMs && attempts < MAX_GRACE_ATTEMPTS) {
            waited += minOf(delay(attempts), maxDelayMs.coerceAtLeast(1))
            attempts += 1
        }
        return attempts
    }

    companion object {
        const val MAX_GRACE_ATTEMPTS = 360
    }
}
