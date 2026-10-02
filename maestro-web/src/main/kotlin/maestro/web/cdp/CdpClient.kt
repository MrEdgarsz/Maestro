import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

/**
 * Descriptor for a CDP target (an open tab/page).
 */
@Serializable
data class CdpTarget(
    val id: String,
    val title: String,
    val url: String,
    val type: String = "",
    val webSocketDebuggerUrl: String? = null,
)

// A live page replies in milliseconds, but some targets never reply at all: Page.captureScreenshot
// against a non-visible target simply goes quiet. Without a bound that stalls the whole run.
private const val CDP_RESPONSE_TIMEOUT_MS = 30_000L

/**
 * A simple client for Chrome DevTools Protocol (CDP).
 *
 * Connects via HTTP to list targets and via WebSocket
 * to evaluate JS expressions with full JSON serialization.
 */
class CdpClient(
    private val host: String = "localhost",
    private val port: Int = 9222
) : AutoCloseable {
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
        install(WebSockets)
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val idCounter = AtomicInteger(1)
    private val closed = AtomicBoolean(false)
    private val activeSessions = ConcurrentHashMap.newKeySet<DefaultClientWebSocketSession>()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeSessions.toList().forEach { it.cancel() }
        httpClient.close()
    }

    /**
     * Fetches the list of open CDP targets (tabs/pages).
     */
    suspend fun listTargets(): List<CdpTarget> {
        val endpoint = "http://$host:$port/json"
        val response = httpClient.get(endpoint).bodyAsText()

        return json.decodeFromString(response)
    }

    /**
     * Evaluates a JS expression on the given target, serializing the result via JSON.stringify.
     *
     * @param expression JS code to evaluate.
     * @param target The CDP target descriptor.
     * @return A JSON string of the evaluated result.
     */
    suspend fun evaluate(expression: String, target: CdpTarget): String {
        val wsUrl = target.webSocketDebuggerUrl ?: error("Target ${target.id} has no WebSocket debugger URL")

        // The idea here is that we return JSON object as a String. That makes it much easier to handle
        // as passing objects between JS and outside world would require many round-trips to query the values
        // from the browser.
        val wrapped = """
            (async () => {
                try { return JSON.stringify(await ($expression)) ?? "null"; }
                catch(e) { return JSON.stringify({ __cdpError: e.toString() }); }
            })()
        """.trimIndent()

        val exprJson = Json.encodeToString(JsonPrimitive(wrapped))
        val messageId = idCounter.getAndIncrement()
        val payload = """
            {
                "id":$messageId,
                "method":"Runtime.evaluate",
                "params":{"expression":$exprJson,"awaitPromise":true}
            }
        """.trimIndent()

        val text = sendCommand(wsUrl, messageId, payload)
        val root = json.parseToJsonElement(text).jsonObject
        val resultObj = root["result"]?.jsonObject
            ?.get("result")?.jsonObject
            ?: error("Invalid CDP response: $text")
        val raw: String = resultObj["value"]?.jsonPrimitive?.content ?: ""
        if (raw.isEmpty()) return ""
        val parsed = json.parseToJsonElement(raw)
        if (parsed is JsonObject && parsed.jsonObject.containsKey("__cdpError")) {
            val err = parsed.jsonObject["__cdpError"]?.jsonPrimitive?.content
            error("JS error: $err")
        }
        return raw
    }

    suspend fun captureScreenshot(target: CdpTarget): ByteArray {
        val messageId = idCounter.getAndIncrement()

        // Request the screenshot
        val payload = """
            {
                "id": $messageId,
                "method": "Page.captureScreenshot",
                "params": {
                    "format": "png",
                    "quality": 100
                }
            }
        """.trimIndent()

        // Open WS, send & await
        val wsUrl = target.webSocketDebuggerUrl ?: error("Target ${target.id} has no WebSocket debugger URL")

        val text = sendCommand(wsUrl, messageId, payload)
        val data = Json.parseToJsonElement(text)
            .jsonObject["result"]!!.jsonObject["data"]!!.jsonPrimitive.content
        return Base64.getDecoder().decode(data)
    }

    suspend fun openUrl(url: String, target: CdpTarget) {
        // Send a CDP command to open a new tab with the specified URL
        val messageId = idCounter.getAndIncrement()
        val payload = """
            {
                "id": $messageId,
                "method": "Page.navigate",
                "params": {
                    "url": "$url"
                }
            }
        """.trimIndent()

        val wsUrl = target.webSocketDebuggerUrl ?: error("Target ${target.id} has no WebSocket debugger URL")
        sendCommand(wsUrl, messageId, payload)
    }

    suspend fun clearDataForOrigin(origin: String, storageTypes: String, target: CdpTarget) {
        val messageId = idCounter.getAndIncrement()
        val originJson = Json.encodeToString(JsonPrimitive(origin))
        val storageTypesJson = Json.encodeToString(JsonPrimitive(storageTypes))
        val payload = """
            {
                "id": $messageId,
                "method": "Storage.clearDataForOrigin",
                "params": {
                    "origin": $originJson,
                    "storageTypes": $storageTypesJson
                }
            }
        """.trimIndent()

        val wsUrl = target.webSocketDebuggerUrl ?: error("Target ${target.id} has no WebSocket debugger URL")
        val text = sendCommand(wsUrl, messageId, payload)
        val root = json.parseToJsonElement(text).jsonObject
        if (root["error"] != null) {
            error("CDP error: ${root["error"]}")
        }
    }

    /** Each command owns a WebSocket that closes on success, failure, or cancellation. */
    private suspend fun sendCommand(wsUrl: String, messageId: Int, payload: String): String {
        check(!closed.get()) { "CDP client is closed" }
        val session = httpClient.webSocketSession { url(wsUrl) }
        activeSessions.add(session)
        if (closed.get()) {
            activeSessions.remove(session)
            session.cancel()
            error("CDP client is closed")
        }
        return try {
            session.send(Frame.Text(payload))
            session.waitForMessage(messageId)
        } finally {
            activeSessions.remove(session)
            session.cancel()
        }
    }

    private suspend fun DefaultClientWebSocketSession.waitForMessage(messageId: Int): String {
        val text = withTimeoutOrNull(CDP_RESPONSE_TIMEOUT_MS) {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    val responseId = runCatching {
                        json.parseToJsonElement(text).jsonObject["id"]?.jsonPrimitive?.content?.toInt()
                    }.getOrNull()
                    if (responseId == messageId) {
                        return@withTimeoutOrNull text
                    }
                }
            }
            null
        }

        return text ?: error("No message with id $messageId received within ${CDP_RESPONSE_TIMEOUT_MS}ms")
    }

}

suspend fun main() {
    val client = CdpClient("localhost", 9222)
    val targets = client.listTargets()
    println("Available pages: $targets")

    val page = targets.first()
    val json = client.evaluate("1+1", page)
    println("Result: $json")

    val screenshot = client.captureScreenshot(page)
    println("Screenshot captured, size: ${screenshot.size} bytes")

    // Save screenshot to file or process as needed
    File("local/screenshot.png").writeBytes(screenshot)
}
