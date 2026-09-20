package net.zld.prism.sync

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.resource.ClientResources
import io.lettuce.core.resource.DefaultClientResources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.zld.prism.PrismPlugin
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Serializable
sealed class SyncEvent {
    abstract val proxyId: String
    abstract val timestamp: Long

    @Serializable
    data class PlayerJoined(val playerData: String, override val proxyId: String, override val timestamp: Long) : SyncEvent()

    @Serializable
    data class PlayerQuit(val playerId: String, override val proxyId: String, override val timestamp: Long) : SyncEvent()

    @Serializable
    data class PlayerSwitched(val playerId: String, val fromPool: String, val toPool: String, override val proxyId: String, override val timestamp: Long) : SyncEvent()

    @Serializable
    data class ServerHealthChanged(val serverName: String, val healthy: Boolean, override val proxyId: String, override val timestamp: Long) : SyncEvent()

    @Serializable
    data class ConfigUpdated(val configVersion: Long, override val proxyId: String, override val timestamp: Long) : SyncEvent()

    @Serializable
    data class BroadcastMessage(val message: String, val permission: String? = null, override val proxyId: String, override val timestamp: Long) : SyncEvent()

    @Serializable
    data class ProxyStatus(val status: String, override val proxyId: String, override val timestamp: Long) : SyncEvent()

    @Serializable
    data class KickPlayer(val playerId: String, val reason: String, override val proxyId: String, override val timestamp: Long) : SyncEvent()
}

class CrossProxySyncManager(private val plugin: PrismPlugin) {
    private val logger = LoggerFactory.getLogger(CrossProxySyncManager::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }

    private val clientResources: ClientResources = DefaultClientResources.builder().build()
    @Volatile private var redisClient: RedisClient? = null
    @Volatile private var pubSubConnection: StatefulRedisConnection<String, String>? = null

    private val subscribers = ConcurrentHashMap<String, MutableList<(SyncEvent) -> Unit>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isConnected = AtomicBoolean(false)
    private val proxyId = UUID.randomUUID().toString().substring(0, 8)
    private val configVersion = AtomicLong(0)
    private val eventChannel = Channel<SyncEvent>(1000)
    private val processorJob = AtomicReference<Job?>(null)

    val proxyIdStr: String get() = proxyId

    fun initialize(redisUrl: String): Boolean {
        val syncSettings = plugin.getConfig().sync
        if (!syncSettings.enabled) {
            logger.info("Cross-proxy sync disabled by config (sync.enabled = false)")
            return false
        }
        if (redisUrl.isBlank()) {
            logger.warn("No Redis URL configured, cross-proxy sync disabled")
            return false
        }
        try {
            val uri = RedisURI.create(redisUrl)
            val client = RedisClient.create(clientResources, uri)
            val pubSub = client.connectPubSub() // blocking connect, returns StatefulRedisPubSubConnection

            this.redisClient = client
            this.pubSubConnection = pubSub

            val api = pubSub.async()
            api.subscribe(
                "prism:events",
                "prism:events:player",
                "prism:events:server",
                "prism:events:config",
                "prism:broadcast",
                "prism:proxy:$proxyId",
            ).get(syncSettings.connectTimeoutSeconds.toLong(), TimeUnit.SECONDS)

            pubSub.addListener(object : RedisPubSubAdapter<String, String>() {
                override fun message(channel: String, message: String) {
                    this@CrossProxySyncManager.handleMessage(channel, message)
                }
            })

            isConnected.set(true)
            startEventProcessor()
            publishProxyStatus("ONLINE")

            // Strip credentials from the URL before logging (redis://:pass@host:6379)
            val sanitized = redisUrl.replace(Regex("(?<=://)([^@/:]*:)?([^@/]*)@"), "***@")
            logger.info("Cross-proxy sync connected to Redis: {} (proxyId: {})", sanitized, proxyId)
            return true
        } catch (e: Exception) {
            logger.error("Failed to initialize cross-proxy sync: {}", e.message, e)
            shutdown()
            return false
        }
    }

    private fun handleMessage(channel: String, message: String) {
        try {
            val event = json.decodeFromString<SyncEvent>(message)
            if (event.proxyId == proxyId) return // ignore our own events

            val channelForEvent = channelNameFor(event)
            val handlers = (subscribers[channelForEvent] ?: emptyList()) + subscribers["prism:events"].orEmpty()
            handlers.forEach { handler ->
                try {
                    handler(event)
                } catch (e: Exception) {
                    logger.warn("Sync event handler failed: {}", e.message)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse sync message from {}: {}", channel, e.message)
        }
    }

    private fun startEventProcessor() {
        val newJob = scope.launch {
            for (event in eventChannel) {
                publishEvent(event)
            }
        }
        processorJob.set(newJob)
    }

    private fun channelNameFor(event: SyncEvent): String = when (event) {
        is SyncEvent.PlayerJoined, is SyncEvent.PlayerQuit,
        is SyncEvent.PlayerSwitched, is SyncEvent.KickPlayer -> "prism:events:player"
        is SyncEvent.ServerHealthChanged -> "prism:events:server"
        is SyncEvent.ConfigUpdated -> "prism:events:config"
        is SyncEvent.BroadcastMessage -> "prism:broadcast"
        else -> "prism:events"
    }

    private fun publishEvent(event: SyncEvent) {
        if (!isConnected.get() || !plugin.getConfig().sync.enabled) return
        try {
            val payload = json.encodeToString<SyncEvent>(event)
            val channel = channelNameFor(event)
            pubSubConnection?.async()?.publish(channel, payload)
                ?.get(plugin.getConfig().sync.publishTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.warn("Failed to publish event: {}", e.message)
        }
    }

    // --- Public publish API (fire-and-forget into the channel) ---

    fun publishPlayerJoined(playerDataJson: String) {
        eventChannel.trySend(SyncEvent.PlayerJoined(playerDataJson, proxyId, System.currentTimeMillis()))
    }

    fun publishPlayerQuit(playerId: String) {
        eventChannel.trySend(SyncEvent.PlayerQuit(playerId, proxyId, System.currentTimeMillis()))
    }

    fun publishPlayerSwitched(playerId: String, fromPool: String, toPool: String) {
        if (!plugin.getConfig().sync.announcePlayerSwitches) return
        eventChannel.trySend(SyncEvent.PlayerSwitched(playerId, fromPool, toPool, proxyId, System.currentTimeMillis()))
    }

    fun publishServerHealthChanged(serverName: String, healthy: Boolean) {
        if (!plugin.getConfig().sync.announceHealth) return
        eventChannel.trySend(SyncEvent.ServerHealthChanged(serverName, healthy, proxyId, System.currentTimeMillis()))
    }

    fun publishConfigUpdated() {
        val version = configVersion.incrementAndGet()
        eventChannel.trySend(SyncEvent.ConfigUpdated(version, proxyId, System.currentTimeMillis()))
    }

    fun broadcastMessage(message: String, permission: String?) {
        eventChannel.trySend(SyncEvent.BroadcastMessage(message, permission, proxyId, System.currentTimeMillis()))
    }

    fun publishKickPlayer(playerId: String, reason: String) {
        eventChannel.trySend(SyncEvent.KickPlayer(playerId, reason, proxyId, System.currentTimeMillis()))
    }

    private fun publishProxyStatus(status: String) {
        if (!isConnected.get() || !plugin.getConfig().sync.announceLifecycle) return
        try {
            val payload = json.encodeToString<SyncEvent>(SyncEvent.ProxyStatus(status, proxyId, System.currentTimeMillis()))
            pubSubConnection?.async()?.publish("prism:events", payload)
                ?.get(plugin.getConfig().sync.publishTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.warn("Failed to publish proxy status: {}", e.message)
        }
    }

    fun subscribe(channel: String, handler: (SyncEvent) -> Unit) {
        subscribers.computeIfAbsent(channel) { mutableListOf() }.add(handler)
    }

    fun unsubscribe(channel: String, handler: (SyncEvent) -> Unit) {
        subscribers[channel]?.remove(handler)
    }

    fun getConfigVersion(): Long = configVersion.get()

    fun isConnected(): Boolean = isConnected.get()

    fun shutdown() {
        isConnected.set(false)
        try {
            publishProxyStatus("OFFLINE")
        } catch (_: Exception) {}
        processorJob.get()?.cancel()
        eventChannel.close()
        scope.cancel()
        try {
            pubSubConnection?.close()
            redisClient?.shutdown()
            clientResources.shutdown(0, 2, TimeUnit.SECONDS).get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.debug("Error during redis shutdown: {}", e.message)
        }
        logger.info("Cross-proxy sync shutdown")
    }
}
