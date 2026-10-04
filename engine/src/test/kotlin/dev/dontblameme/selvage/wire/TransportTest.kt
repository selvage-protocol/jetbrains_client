package dev.dontblameme.selvage.wire

import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail

class TransportTest {
    /** A socket each of whose sends takes the next of [durations] from the moment it starts. */
    private class SlowSocket(
        private val durations: List<Long>,
    ) : WebSocket {
        val started = AtomicInteger()
        val finished = AtomicInteger()
        val aborted = AtomicBoolean()

        private fun send(): CompletableFuture<WebSocket> {
            val delay = durations[started.getAndIncrement()]
            val done = CompletableFuture<WebSocket>()
            CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute {
                finished.incrementAndGet()
                done.complete(this)
            }
            return done
        }

        override fun sendText(
            data: CharSequence,
            last: Boolean,
        ) = send()

        override fun sendBinary(
            data: ByteBuffer,
            last: Boolean,
        ) = send()

        override fun sendPing(message: ByteBuffer) = send()

        override fun sendPong(message: ByteBuffer) = send()

        override fun sendClose(
            statusCode: Int,
            reason: String,
        ) = send()

        override fun request(n: Long) = Unit

        override fun getSubprotocol() = ""

        override fun isOutputClosed() = false

        override fun isInputClosed() = false

        override fun abort() {
            aborted.set(true)
        }
    }

    private fun receiver(closes: MutableList<Int>) =
        JdkTransport.Receiver(
            object : SocketListener {
                override fun onText(text: String) = Unit

                override fun onBinary(bytes: ByteArray) = Unit

                override fun onClose(
                    code: Int,
                    reason: String,
                ) {
                    closes.add(code)
                }
            },
        )

    @Test
    fun `a send's timeout runs from its start, not from its place in the queue`() {
        val closes = CopyOnWriteArrayList<Int>()
        val receiver = receiver(closes)
        // Each send is well inside the second, and the two together are not.
        val socket = SlowSocket(listOf(700, 600))
        val sender = JdkTransport.Sender(socket, Duration.ofMillis(1_000), receiver)
        sender.sendBinary(byteArrayOf(1))
        sender.sendBinary(byteArrayOf(2))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (socket.finished.get() < 2 && closes.isEmpty()) {
            if (System.nanoTime() > deadline) fail("sends finished: ${socket.finished.get()}")
            LockSupport.parkNanos(1_000_000)
        }
        // A timeout would close after the second send started and before it finished.
        assertEquals(emptyList(), closes)
        assertFalse(socket.aborted.get())
        assertEquals(2, socket.finished.get())
    }

    @Test
    fun `a send that outlasts the timeout still fails the socket`() {
        val closes = CopyOnWriteArrayList<Int>()
        val socket = SlowSocket(listOf(5_000))
        JdkTransport.Sender(socket, Duration.ofMillis(300), receiver(closes)).sendBinary(byteArrayOf(1))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (closes.isEmpty()) {
            if (System.nanoTime() > deadline) fail("no close 4 s after a 300 ms send timeout")
            LockSupport.parkNanos(1_000_000)
        }
        assertEquals(listOf(1006), closes)
        assertEquals(true, socket.aborted.get())
    }
}
