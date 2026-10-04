package dev.dontblameme.selvage.wire

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class MetaTest {
    /** Serves `/meta` with [write] on a local socket, for the length of [block]. */
    private fun <T> serving(
        write: (java.io.OutputStream, CountDownLatch) -> Unit,
        block: (String) -> T,
    ): T {
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/meta") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { write(it, release) }
        }
        server.start()
        try {
            return block("ws://127.0.0.1:${server.address.port}")
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    @Test
    fun `a body that never finishes is bounded by the timeout`() {
        serving({ out, release ->
            out.write("{\"server\":".toByteArray())
            out.flush()
            release.await(30, TimeUnit.SECONDS)
        }) { base ->
            val read = CompletableFuture.supplyAsync { runCatching { Meta.fetch(base, Duration.ofMillis(300)) } }
            val outcome =
                try {
                    read.get(10, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    fail("/meta was still being read 10 s after a 300 ms timeout")
                }
            assertTrue(outcome.exceptionOrNull() is IOException, "$outcome")
        }
    }

    @Test
    fun `a whole body within the bound is read, and a longer one refused`() {
        serving({ out, _ -> out.write("{\"keepalive\":{\"room_grace_ms\":1500}}".toByteArray()) }) { base ->
            assertEquals(1500L, Meta.fetch(base, Duration.ofSeconds(5)).roomGraceMs)
        }
        serving({ out, _ -> out.write(ByteArray(Meta.MAX_BODY_BYTES + 10) { ' '.code.toByte() }) }) { base ->
            val refused = runCatching { Meta.fetch(base, Duration.ofSeconds(5)) }.exceptionOrNull()
            assertTrue(refused is IOException && "longer" in refused.message.orEmpty(), "$refused")
        }
    }
}
