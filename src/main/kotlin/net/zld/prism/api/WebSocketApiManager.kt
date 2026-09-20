package net.zld.prism.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.zld.prism.PrismPlugin
import net.zld.prism.metrics.MetricsManager
import net.zld.prism.sync.CrossProxySyncManager
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WebSocketApiManager(
    private val plugin: PrismPlugin,
    private val metricsManager: MetricsManager,
    private val syncManager: CrossProxySyncManager,
) {
    private val logger = LoggerFactory.getLogger(WebSocketApiManager::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private val sessions = ConcurrentHashMap<String, WsSession>()
    private val sessionCounter = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rateLimiter = ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<Long>>()

    @Volatile private var server: EmbeddedServer<*, *>? = null
    @Volatile private var jwtSecret: String = ""

    // --- Lifecycle ---

    fun start() {
        val apiConfig = plugin.getConfig().api
        if (!apiConfig.enabled) {
            logger.info("WebSocket API disabled in config")
            return
        }

        jwtSecret = apiConfig.jwtSecret
        if (!hasValidSecret()) {
            logger.warn("API jwt-secret is not set (or a default) — protected routes will reject all requests")
        }

        val app: Application.() -> Unit = {
            install(ContentNegotiation) { json(json) }
            install(WebSockets)

            routing {
                get("/health") {
                    call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
                }

                get("/api/v1/metrics") {
                    if (isRateLimited(call)) {
                        call.respondText("""{"error":"rate_limited"}""", ContentType.Application.Json, HttpStatusCode.TooManyRequests)
                    } else if (!call.isAuthorized()) {
                        call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                    } else {
                        call.respondText(metricsManager.getPrometheusOutput(), ContentType.Text.Plain)
                    }
                }

                get("/api/v1/proxy/info") { respondAuthorizedJson { getProxyInfo() } }
                get("/api/v1/pools") { respondAuthorizedJson { getPoolsInfo() } }
                get("/api/v1/servers") { respondAuthorizedJson { getServersInfo() } }
                get("/api/v1/players") { respondAuthorizedJson { getPlayersInfo() } }
                get("/api/v1/sync/status") { respondAuthorizedJson { getSyncStatus() } }
                get("/api/v1/state") { respondAuthorizedJson { getFullState() } }

                post("/api/v1/broadcast") { respondAuthorizedJson { handleBroadcast() } }
                post("/api/v1/player/kick") { respondAuthorizedJson { handleKick() } }
                post("/api/v1/player/move") { respondAuthorizedJson { handleMove() } }

                webSocket("/ws") {
                    val sessionId = "ws-${sessionCounter.incrementAndGet()}"
                    val session = WsSession(sessionId, this)
                    sessions[sessionId] = session
                    logger.info("WebSocket connected: {} (total: {})", sessionId, sessions.size)

                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) handleWsMessage(session, frame.readText())
                        }
                    } catch (e: Exception) {
                        logger.warn("WebSocket error for {}: {}", sessionId, e.message)
                    } finally {
                        sessions.remove(sessionId)
                        logger.info("WebSocket disconnected: {} (total: {})", sessionId, sessions.size)
                    }
                }
            }
        }

        val host = apiConfig.host.ifBlank { "0.0.0.0" }
        server = embeddedServer(io.ktor.server.cio.CIO, port = apiConfig.port, host = host, module = app)
            .apply { start(wait = false) }

        logger.info("WebSocket API listening on {}:{} (metrics: GET /api/v1/metrics)", host, apiConfig.port)
    }

    private fun hasValidSecret(): Boolean =
        jwtSecret.isNotBlank() && !jwtSecret.startsWith("changeme")

    // --- Rate limiting (per remote address, sliding 60-second window) ---

    private fun isRateLimited(call: io.ktor.server.application.ApplicationCall): Boolean {
        val apiConfig = plugin.getConfig().api
        if (!apiConfig.rateLimitEnabled) return false
        val key = call.request.origin.remoteHost.ifBlank { "unknown" }
        val now = System.currentTimeMillis()
        val windowStart = now - 60_000L
        val hits = rateLimiter.computeIfAbsent(key) { java.util.concurrent.CopyOnWriteArrayList() }
        hits.removeIf { it < windowStart }
        if (hits.size >= apiConfig.rateLimitPerMinute) return true
        hits.add(now)
        if (rateLimiter.size > 10_000) rateLimiter.clear() // opportunistic cleanup
        return false
    }

    // --- Auth (HS256 JWT verified manually — no extra dependency) ---

    private fun io.ktor.server.request.ApplicationRequest.bearerToken(): String? {
        val header = headers["Authorization"] ?: return null
        return if (header.startsWith("Bearer ", ignoreCase = true)) header.substring(7).trim() else null
    }

    private fun verifyJwt(token: String): Boolean {
        if (!hasValidSecret()) return false
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return false
            val (headerB64, payloadB64, sigB64) = parts
            val header = json.parseToJsonElement(Base64.getUrlDecoder().decode(headerB64).decodeToString())
            val alg = (header as? JsonObject)?.get("alg")?.let { (it as? JsonPrimitive)?.content }
            if (alg != "HS256") return false

            val expectedSig = hmacSha256("$headerB64.$payloadB64", jwtSecret)
            val givenSig = Base64.getUrlDecoder().decode(sigB64)
            if (!java.security.MessageDigest.isEqual(expectedSig, givenSig)) return false

            // exp check (seconds since epoch), optional
            val payload = json.parseToJsonElement(Base64.getUrlDecoder().decode(payloadB64).decodeToString())
            val exp = (payload as? JsonObject)?.get("exp")?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() }
            exp == null || exp > System.currentTimeMillis() / 1000
        } catch (e: Exception) {
            false
        }
    }

    private fun hmacSha256(data: String, secret: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray())
    }

    private suspend fun io.ktor.server.application.ApplicationCall.isAuthorized(): Boolean {
        val token = request.bearerToken() ?: return false
        return verifyJwt(token)
    }

    private suspend fun RoutingContext.respondAuthorizedJson(body: suspend () -> Any?) {
        if (isRateLimited(call)) {
            call.respondText("""{"error":"rate_limited"}""", ContentType.Application.Json, HttpStatusCode.TooManyRequests)
            return
        }
        if (!call.isAuthorized()) {
            call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
        } else {
            call.respondText(json.encodeToString(JsonElement.serializer(), toJsonElement(body())), ContentType.Application.Json)
        }
    }

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJsonElement(v) })
        is Iterable<*> -> kotlinx.serialization.json.JsonArray(value.map { toJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }

    // --- HTTP data ---

    private fun getProxyInfo() = mapOf(
        "name" to "Prism Proxy",
        "version" to plugin.getVersion(),
        "uptime_seconds" to (System.currentTimeMillis() - plugin.startTime) / 1000,
        "proxy_id" to syncManager.proxyIdStr,
        "players_online" to plugin.proxy.playerCount,
        "servers_registered" to plugin.proxy.allServers.size,
        "sync_connected" to syncManager.isConnected(),
    )

    private fun getPoolsInfo() = plugin.getAllPools().map { pool ->
        mapOf(
            "name" to pool.name,
            "total_servers" to pool.size(),
            "healthy_servers" to pool.healthyCount(),
            "fallback_pool" to pool.getFallbackPoolName(),
        )
    }

    private fun getServersInfo() = plugin.proxy.allServers.map { server ->
        val health = plugin.getHealthChecker().getStatus(server.serverInfo.name)
        mapOf(
            "name" to server.serverInfo.name,
            "address" to server.serverInfo.address.toString(),
            "players" to server.playersConnected.size,
            "pool" to (plugin.getPoolNameForServer(server) ?: "unknown"),
            "motd" to health?.motd,
            "game_version" to health?.gameVersion,
        )
    }

    private fun getPlayersInfo() = plugin.proxy.allPlayers.map { player ->
        val conn = player.currentServer.orElse(null)
        mapOf(
            "uuid" to player.uniqueId.toString(),
            "username" to player.username,
            "server" to (conn?.serverInfo?.name ?: "none"),
            "pool" to (conn?.server?.let { plugin.getPoolNameForServer(it) } ?: "none"),
            "ping_ms" to player.ping,
        )
    }

    private fun getSyncStatus() = mapOf(
        "connected" to syncManager.isConnected(),
        "proxy_id" to syncManager.proxyIdStr,
        "config_version" to syncManager.getConfigVersion(),
    )

    private fun getFullState() = mapOf(
        "proxy" to getProxyInfo(),
        "pools" to getPoolsInfo(),
        "servers" to getServersInfo(),
        "players" to getPlayersInfo(),
        "sync" to getSyncStatus(),
        "timestamp" to Instant.now().toString(),
    )

    private suspend fun RoutingContext.handleBroadcast(): Map<String, Any?> {
        val body = call.receiveText()
        return try {
            val req = json.decodeFromString<BroadcastRequest>(body)
            syncManager.broadcastMessage(req.message, req.permission)
            mapOf("success" to true)
        } catch (e: Exception) {
            mapOf("success" to false, "error" to (e.message ?: "invalid body"))
        }
    }

    private suspend fun RoutingContext.handleKick(): Map<String, Any?> {
        val body = call.receiveText()
        return try {
            val req = json.decodeFromString<KickRequest>(body)
            val player = plugin.proxy.getPlayer(java.util.UUID.fromString(req.playerId)).orElse(null)
            if (player == null) {
                mapOf("success" to false, "error" to "player not found")
            } else {
                player.disconnect(net.kyori.adventure.text.Component.text(req.reason))
                mapOf("success" to true)
            }
        } catch (e: Exception) {
            mapOf("success" to false, "error" to (e.message ?: "invalid body"))
        }
    }

    private suspend fun RoutingContext.handleMove(): Map<String, Any?> {
        val body = call.receiveText()
        return try {
            val req = json.decodeFromString<MoveRequest>(body)
            val player = plugin.proxy.getPlayer(java.util.UUID.fromString(req.playerId)).orElse(null)
            val target = plugin.proxy.getServer(req.server).orElse(null)
            if (player == null || target == null) {
                mapOf("success" to false, "error" to "player or server not found")
            } else {
                val result = player.createConnectionRequest(target).connect().get(10, TimeUnit.SECONDS)
                mapOf(
                    "success" to result.isSuccessful,
                    "error" to if (!result.isSuccessful) "connection failed" else null
                )
            }
        } catch (e: Exception) {
            mapOf("success" to false, "error" to (e.message ?: "invalid body"))
        }
    }

    // --- WebSocket ---

    private suspend fun handleWsMessage(session: WsSession, text: String) {
        try {
            val request = json.decodeFromString<ApiRequest>(text)
            when (request.action) {
                "subscribe" -> {
                    session.subscribe(request.topics)
                    session.send(ApiResponse("subscribed", session.subscriptions.joinToString(",")))
                }
                "unsubscribe" -> {
                    session.unsubscribe(request.topics)
                    session.send(ApiResponse("unsubscribed", ""))
                }
                "ping" -> session.send(ApiResponse("pong", Instant.now().toString()))
                "get_state" -> session.send(ApiResponse("state", json.encodeToString(JsonElement.serializer(), toJsonElement(getFullState()))))
                else -> session.send(ApiResponse("error", "Unknown action: ${request.action}"))
            }
        } catch (e: Exception) {
            session.send(ApiResponse("error", e.message ?: "bad request"))
        }
    }

    fun broadcastToTopic(topic: String, event: String, data: String) {
        val payload = json.encodeToString(ApiEvent(event, topic, data))
        sessions.values.forEach { session -> scope.launch { session.sendRaw(payload) } }
    }

    fun broadcastAll(event: String, data: String) = broadcastToTopic("*", event, data)

    fun shutdown() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        try {
            server?.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)
        } catch (e: Exception) {
            logger.debug("Error stopping API server: {}", e.message)
        }
        server = null
        scope.cancel()
        logger.info("WebSocket API stopped")
    }
}

class WsSession(
    val id: String,
    private val webSocket: io.ktor.server.websocket.DefaultWebSocketServerSession,
) {
    val subscriptions = mutableSetOf<String>()

    suspend fun send(response: ApiResponse) {
        webSocket.send(Frame.Text("${response.event}|${response.data}"))
    }

    suspend fun sendRaw(text: String) {
        webSocket.send(Frame.Text(text))
    }

    fun subscribe(topics: List<String>) {
        subscriptions.addAll(topics)
    }

    fun unsubscribe(topics: List<String>) {
        subscriptions.removeAll(topics.toSet())
    }

    fun isSubscribed(topic: String): Boolean = topic in subscriptions || "*" in subscriptions

    fun close() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                webSocket.close(CloseReason(CloseReason.Codes.NORMAL, "Server shutdown"))
            } catch (_: Exception) {}
        }
    }
}

@Serializable
data class ApiRequest(
    val action: String,
    val topics: List<String> = emptyList(),
    val token: String? = null,
)

@Serializable
data class ApiResponse(val event: String, val data: String)

@Serializable
data class ApiEvent(val event: String, val topic: String, val data: String)

@Serializable
data class BroadcastRequest(val message: String, val permission: String? = null)

@Serializable
data class KickRequest(val playerId: String, val reason: String = "Kicked by Prism")

@Serializable
data class MoveRequest(val playerId: String, val server: String)
