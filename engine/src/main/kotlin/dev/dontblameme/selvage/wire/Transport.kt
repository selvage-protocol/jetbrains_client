package dev.dontblameme.selvage.wire

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/** What a socket reports. Called from the transport's threads, one call at a time per socket. */
interface SocketListener {
    fun onText(text: String)

    fun onBinary(bytes: ByteArray)

    /** The socket is gone, by a close frame (its code) or a drop (1006). Called once. */
    fun onClose(
        code: Int,
        reason: String,
    )
}

/** An open socket the session writes to. */
interface WireSocket {
    fun sendText(text: String)

    fun sendBinary(bytes: ByteArray)

    fun close(
        code: Int = 1000,
        reason: String = "",
    )
}

/** Opens sockets; a test injects one it controls. */
fun interface Transport {
    /** Completes with an open socket, or exceptionally when the upgrade fails or outlasts [timeout]. */
    fun open(
        url: String,
        timeout: Duration,
        listener: SocketListener,
    ): CompletableFuture<WireSocket>
}

/**
 * `java.net.http.WebSocket`: fragments are assembled up to [Wire.MAX_INBOUND_MESSAGE_BYTES] and a
 * longer message drops the socket (§2.1); sends are queued one after the other, as the JDK
 * requires; Pings are answered by the JDK.
 */
class JdkTransport(
    private val client: HttpClient = HttpClient.newHttpClient(),
    private val sendTimeout: Duration = Duration.ofSeconds(10),
) : Transport {
    override fun open(
        url: String,
        timeout: Duration,
        listener: SocketListener,
    ): CompletableFuture<WireSocket> {
        val receiver = Receiver(listener)
        val result = CompletableFuture<WireSocket>()
        client
            .newWebSocketBuilder()
            .connectTimeout(timeout)
            .buildAsync(URI(url), receiver)
            .whenComplete { socket, error ->
                when {
                    error != null -> result.completeExceptionally(error)

                    // An upgrade that lands after the deadline is nobody's socket.
                    !result.complete(Sender(socket, sendTimeout, receiver)) -> socket.abort()
                }
            }
        return result.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    private class Receiver(
        val listener: SocketListener,
    ) : WebSocket.Listener {
        private val text = StringBuilder()
        private val binary = ByteArrayOutputStream()
        private var closed = false

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(
            webSocket: WebSocket,
            data: CharSequence,
            last: Boolean,
        ): CompletionStage<*>? {
            text.append(data)
            if (text.length * 3L > Wire.MAX_INBOUND_MESSAGE_BYTES &&
                text.toString().toByteArray(Charsets.UTF_8).size > Wire.MAX_INBOUND_MESSAGE_BYTES
            ) {
                return tooLong(webSocket)
            }
            if (last) {
                val message = text.toString()
                text.setLength(0)
                listener.onText(message)
            }
            webSocket.request(1)
            return null
        }

        override fun onBinary(
            webSocket: WebSocket,
            data: ByteBuffer,
            last: Boolean,
        ): CompletionStage<*>? {
            if (binary.size() + data.remaining() > Wire.MAX_INBOUND_MESSAGE_BYTES) return tooLong(webSocket)
            val chunk = ByteArray(data.remaining())
            data.get(chunk)
            binary.write(chunk)
            if (last) {
                val message = binary.toByteArray()
                binary.reset()
                listener.onBinary(message)
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(
            webSocket: WebSocket,
            statusCode: Int,
            reason: String,
        ): CompletionStage<*>? {
            closed(statusCode, reason)
            return null
        }

        override fun onError(
            webSocket: WebSocket,
            error: Throwable,
        ) {
            closed(1006, error.message ?: error.toString())
        }

        fun closed(
            code: Int,
            reason: String,
        ) {
            synchronized(this) {
                if (closed) return
                closed = true
            }
            listener.onClose(code, reason)
        }

        private fun tooLong(webSocket: WebSocket): CompletionStage<*>? {
            text.setLength(0)
            binary.reset()
            webSocket.abort()
            closed(1009, "a message over ${Wire.MAX_INBOUND_MESSAGE_BYTES} bytes")
            return null
        }
    }

    private class Sender(
        private val socket: WebSocket,
        private val timeout: Duration,
        private val receiver: Receiver,
    ) : WireSocket {
        private var tail: CompletableFuture<*> = CompletableFuture.completedFuture(null)

        private fun enqueue(send: () -> CompletableFuture<WebSocket>) {
            synchronized(this) {
                tail =
                    tail
                        .handle { _, _ -> }
                        .thenCompose { send() }
                        .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                        .whenComplete { _, error ->
                            if (error != null) {
                                socket.abort()
                                receiver.closed(1006, "a send failed: ${error.message}")
                            }
                        }
            }
        }

        override fun sendText(text: String) = enqueue { socket.sendText(text, true) }

        override fun sendBinary(bytes: ByteArray) = enqueue { socket.sendBinary(ByteBuffer.wrap(bytes), true) }

        override fun close(
            code: Int,
            reason: String,
        ) {
            enqueue { socket.sendClose(code, reason) }
            // The close handshake is bounded too: whatever the server does, the socket is gone.
            CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS).execute { socket.abort() }
            receiver.closed(code, reason)
        }
    }
}
