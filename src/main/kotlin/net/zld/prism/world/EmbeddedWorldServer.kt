package net.zld.prism.world

import net.minestom.server.MinecraftServer
import net.minestom.server.extras.velocity.VelocityProxy
import net.minestom.server.coordinate.Pos
import net.minestom.server.event.GlobalEventHandler
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.InstanceManager
import net.minestom.server.instance.block.Block
import net.zld.prism.config.EmbeddedWorldSettingsDefinition
import org.slf4j.Logger
import java.util.concurrent.atomic.AtomicInteger

/**
 * Prism's built-in world server, powered by Minestom.
 *
 * When enabled, Prism hosts its own playable world in-process — no external
 * backend needed. The listener is registered with the proxy as a regular
 * server definition (see Plugin.initializePools), so it participates in
 * pools, fallback chains, health checks and routing like any other server.
 */
class EmbeddedWorldServer(
    private val settings: EmbeddedWorldSettingsDefinition,
    private val logger: Logger,
) {
    private var minecraftServer: MinecraftServer? = null
    private var instance: InstanceContainer? = null
    private val onlinePlayers = AtomicInteger(0)

    @Volatile
    var isRunning: Boolean = false
        private set

    /** Starts Minestom and binds its listener. Idempotent — safe to call twice. */
    @Synchronized
    fun start() {
        if (isRunning) return

        // Minestom needs its own brand and compression settings
        System.setProperty("minestom.brand-name", settings.brandName)

        // Velocity modern forwarding: when the secret file exists, the built-in
        // world expects forwarded logins from the proxy (this is the normal
        // setup since the world sits behind Prism itself). Without it, the
        // world accepts direct connections (offline mode).
        val secret = readForwardingSecret()
        if (secret != null) {
            // The world sits behind Prism itself — expect Velocity modern
            // forwarded logins signed with the same secret as velocity.toml
            VelocityProxy.enable(secret)
            logger.info("[embedded-world] Velocity modern forwarding enabled")
        } else {
            logger.info("[embedded-world] no forwarding secret found — accepting direct connections")
        }
        minecraftServer = MinecraftServer.init()

        val instanceManager: InstanceManager = MinecraftServer.getInstanceManager()
        val container = instanceManager.createInstanceContainer()
        container.setGenerator { unit ->
            // Flat grass world — a simple, dependable spawn area
            unit.modifier().fillHeight(0, settings.generation.height, Block.GRASS_BLOCK)
        }
        instance = container

        val handler: GlobalEventHandler = MinecraftServer.getGlobalEventHandler()
        handler.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
            val player = event.player
            event.spawningInstance = container
            player.respawnPoint = Pos(settings.spawnX, settings.spawnY, settings.spawnZ)
            onlinePlayers.incrementAndGet()
            logger.info("[embedded-world] {} joined Prism's built-in world", player.username)
        }
        handler.addListener(PlayerDisconnectEvent::class.java) { event ->
            onlinePlayers.decrementAndGet()
            logger.info("[embedded-world] {} left Prism's built-in world", event.player.username)
        }
        // Allow block interaction out of the box (Minestom cancels nothing by
        // default, but explicit handlers make behaviour predictable)
        handler.addListener(PlayerBlockBreakEvent::class.java) { /* allow */ }
        handler.addListener(PlayerBlockPlaceEvent::class.java) { /* allow */ }

        minecraftServer!!.start(settings.host, settings.port)
        isRunning = true
        logger.info(
            "[embedded-world] Prism built-in world listening on {}:{} (flat world, height {})",
            settings.host, settings.port, settings.generation.height,
        )
    }

    private fun readForwardingSecret(): String? {
        val file = java.io.File(settings.forwardingSecretFile)
        if (!file.isFile) return null
        val content = file.readText().trim()
        return content.ifEmpty { null }
    }

    /** Stops Minestom cleanly. Safe to call even when never started. */
    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        try {
            MinecraftServer.stopCleanly()
        } catch (e: Exception) {
            logger.warn("[embedded-world] error during shutdown", e)
        }
        minecraftServer = null
        instance = null
        logger.info("[embedded-world] stopped")
    }

    fun getOnlinePlayers(): Int = onlinePlayers.get()

    fun getServerName(): String = settings.serverName

    fun getPoolName(): String = settings.pool

    fun getPort(): Int = settings.port
}
