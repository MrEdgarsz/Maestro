import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CdpClientTest {
    @Test
    fun `each command closes its socket after receiving the matching response`() = runBlocking {
        FakeCdpServer(expectedCloses = 4).use { server ->
            val client = CdpClient("127.0.0.1", server.port)
            try {
                val targets = client.listTargets()
                assertThat(client.evaluate("1", targets[0])).isEqualTo("1")
                assertThat(client.evaluate("2", targets[0])).isEqualTo("2")
                assertThat(client.evaluate("3", targets[1])).isEqualTo("3")
                assertThat(client.evaluate("4", targets[0])).isEqualTo("4")
                assertThat(server.connections.get()).isEqualTo(4)
            } finally {
                client.close()
            }

            assertThat(server.closed.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(server.connections.get()).isEqualTo(4)
        }
    }

    @Test
    fun `a disconnected target is reconnected for the next command`() = runBlocking {
        FakeCdpServer(disconnectAfterFirstCommand = true, expectedCloses = 2).use { server ->
            val client = CdpClient("127.0.0.1", server.port)
            try {
                val target = client.listTargets().first()
                assertThat(client.evaluate("1", target)).isEqualTo("1")
                assertThat(server.commandCompleted.await(2, TimeUnit.SECONDS)).isTrue()
                assertThat(client.evaluate("2", target)).isEqualTo("2")
                assertThat(server.connections.get()).isEqualTo(2)
                assertThat(server.commands.get()).isEqualTo(2)
            } finally {
                client.close()
            }
            assertThat(server.closed.await(2, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `close cancels a command when the target never replies`() = runBlocking {
        FakeCdpServer(neverRespond = true, expectedCloses = 1).use { server ->
            val client = CdpClient("127.0.0.1", server.port)
            val target = client.listTargets().first()
            val request = async(Dispatchers.IO) {
                runCatching { client.evaluate("1", target) }
            }

            assertThat(server.commandReceived.await(2, TimeUnit.SECONDS)).isTrue()
            withTimeout(2_000) {
                withContext(Dispatchers.IO) { client.close() }
            }
            assertThat(request.await().isFailure).isTrue()
            assertThat(server.closed.await(2, TimeUnit.SECONDS)).isTrue()
        }
    }

}

private class FakeCdpServer(
    private val disconnectAfterFirstCommand: Boolean = false,
    private val neverRespond: Boolean = false,
    expectedCloses: Int = 3,
) : AutoCloseable {
    private val listener = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
    private val running = AtomicInteger(1)
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val connectionsClosed = CountDownLatch(expectedCloses)
    val connections = AtomicInteger()
    val commands = AtomicInteger()
    val commandCompleted = CountDownLatch(1)
    val commandReceived = CountDownLatch(1)
    val closed: CountDownLatch get() = connectionsClosed
    val port: Int get() = listener.localPort

    private val acceptor = Thread({
        while (running.get() == 1) {
            try {
                val socket = listener.accept()
                sockets.add(socket)
                Thread { handle(socket) }.apply { isDaemon = true; start() }
            } catch (_: Exception) {
                if (running.get() == 1) throw IllegalStateException("Fake CDP server accept failed")
            }
        }
    }, "fake-cdp-acceptor").apply { isDaemon = true; start() }

    private fun handle(socket: Socket) {
        socket.use { connection ->
            connection.soTimeout = 10_000
            val input = BufferedInputStream(connection.getInputStream())
            val output = BufferedOutputStream(connection.getOutputStream())
            val requestLine = readLine(input) ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
            }

            if (requestLine.startsWith("GET /json ")) {
                val response = """
                    [
                      {"id":"a","title":"a","url":"about:blank","webSocketDebuggerUrl":"ws://127.0.0.1:$port/a"},
                      {"id":"b","title":"b","url":"about:blank","webSocketDebuggerUrl":"ws://127.0.0.1:$port/b"}
                    ]
                """.trimIndent().toByteArray(UTF_8)
                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray(UTF_8))
                output.write(response)
                output.flush()
                return
            }

            val key = headers["sec-websocket-key"] ?: return
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest("${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".toByteArray(UTF_8))
            )
            output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray(UTF_8))
            output.flush()
            connections.incrementAndGet()

            while (true) {
                val first = input.read()
                if (first < 0) {
                    connectionsClosed.countDown()
                    return
                }
                val second = input.read()
                if (second < 0) {
                    connectionsClosed.countDown()
                    return
                }
                val opcode = first and 0x0f
                var length = second and 0x7f
                if (length == 126) length = (input.read() shl 8) or input.read()
                if (length == 127) return
                val mask = if (second and 0x80 != 0) ByteArray(4).also { input.readNBytes(it, 0, 4) } else null
                val payload = ByteArray(length)
                input.readNBytes(payload, 0, length)
                if (mask != null) payload.indices.forEach { payload[it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }

                if (opcode == 8) {
                    output.write(byteArrayOf(0x88.toByte(), 0x00))
                    output.flush()
                    connectionsClosed.countDown()
                    return
                }
                if (opcode != 1) continue
                val request = String(payload, UTF_8)
                val id = Regex("\\\"id\\\"\\s*:\\s*(\\d+)").find(request)?.groupValues?.get(1) ?: return
                val commandNumber = commands.incrementAndGet()
                commandReceived.countDown()
                if (neverRespond) continue
                if (commandNumber == 1) {
                    writeTextFrame(output, "{\"method\":\"Page.loadEventFired\"}")
                    writeTextFrame(output, "{\"id\":${id}0,\"result\":{\"result\":{\"type\":\"number\",\"value\":-1}}}")
                }
                writeTextFrame(output, "{\"id\":$id,\"result\":{\"result\":{\"type\":\"number\",\"value\":$id}}}")
                if (disconnectAfterFirstCommand && commandNumber == 1) {
                    output.write(byteArrayOf(0x88.toByte(), 0x02, 0x03, 0xE8.toByte()))
                    output.flush()
                    commandCompleted.countDown()
                    connectionsClosed.countDown()
                    return
                }
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else String(bytes.toByteArray(), UTF_8)
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.add(value.toByte())
        }
        return String(bytes.toByteArray(), UTF_8)
    }

    private fun writeTextFrame(output: BufferedOutputStream, text: String) {
        val payload = text.toByteArray(UTF_8)
        output.write(0x81)
        when {
            payload.size < 126 -> output.write(payload.size)
            payload.size <= 0xffff -> {
                output.write(126)
                output.write(payload.size ushr 8)
                output.write(payload.size and 0xff)
            }
            else -> error("Fake response too large")
        }
        output.write(payload)
        output.flush()
    }

    override fun close() {
        running.set(0)
        listener.close()
        sockets.forEach { runCatching { it.close() } }
        acceptor.join(1_000)
    }
}
