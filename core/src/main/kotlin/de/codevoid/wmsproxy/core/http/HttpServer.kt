package de.codevoid.wmsproxy.core.http

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ServerSocketFactory

/**
 * A minimal blocking HTTP/1.1 server.
 *
 * It exists because the alternatives did not fit: Ktor's CIO engine has no server-side
 * TLS at all, and Netty or Jetty would be heavy on Android for two GET routes. Hand
 * rolling is viable here precisely because the surface is tiny — one client, on
 * loopback, issuing GETs with no body.
 *
 * The payoff is that this is plain JVM code, so unlike an Android-hosted server it can
 * be started against a real socket in a unit test and verified in CI, which is the only
 * fast feedback this project has.
 *
 * TLS is supplied by passing an SSL-backed [ServerSocketFactory]; nothing else changes.
 */
class HttpServer(
    private val host: String,
    private val port: Int,
    private val socketFactory: ServerSocketFactory = ServerSocketFactory.getDefault(),
    private val handler: (HttpRequest) -> HttpResponse,
) {

    private var serverSocket: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(WORKER_THREADS, namedThreads())
    @Volatile private var running = false

    /** The port actually bound, which differs from [port] when 0 was requested. */
    val boundPort: Int get() = serverSocket?.localPort ?: -1

    fun start() {
        if (running) return
        val socket = socketFactory.createServerSocket(port, BACKLOG, InetAddress.getByName(host))
        serverSocket = socket
        running = true
        Thread({ acceptLoop(socket) }, "http-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        // Closing the socket is what unblocks accept(); the loop treats the resulting
        // exception as a shutdown rather than an error.
        runCatching { serverSocket?.close() }
        serverSocket = null
        workers.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running) continue else break
            }
            try {
                workers.execute { serve(client) }
            } catch (e: Exception) {
                // Pool rejected the task (shutting down, or saturated): close rather
                // than leak the connection.
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            // A client that opens a connection and then stalls must not hold a worker
            // thread for ever.
            client.soTimeout = SOCKET_TIMEOUT_MS
            client.tcpNoDelay = true

            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())

            val request = HttpParser.parse(input)
            val response = when {
                request == null -> HttpResponse.badRequest("Malformed request")
                request.method != "GET" ->
                    HttpResponse.text(405, "Method Not Allowed", "Only GET is supported")
                else -> runCatching { handler(request) }.getOrElse {
                    HttpResponse.text(500, "Internal Server Error", "Handler failed")
                }
            }
            response.writeTo(output)
        } catch (e: SocketException) {
            // Client went away mid-response; nothing useful to do.
        } catch (e: Exception) {
            // Never let one bad connection take the server down.
        } finally {
            runCatching { client.close() }
        }
    }

    private fun namedThreads(): ThreadFactory {
        val counter = AtomicInteger()
        return ThreadFactory { runnable ->
            Thread(runnable, "http-worker-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
    }

    private companion object {
        /**
         * Tiles are fetched a handful at a time by a single client, and each worker
         * blocks on one upstream request. Enough for parallel fetches, small enough
         * that a stuck upstream cannot spawn threads without bound.
         */
        const val WORKER_THREADS = 8
        const val BACKLOG = 32
        val SOCKET_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30).toInt()
    }
}
