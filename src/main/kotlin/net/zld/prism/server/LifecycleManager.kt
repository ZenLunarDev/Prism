package net.zld.prism.server

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.scheduler.ScheduledTask
import net.kyori.adventure.text.minimessage.MiniMessage
import net.zld.prism.PrismPlugin
import net.zld.prism.config.LifecycleSettingsDefinition
import net.zld.prism.sync.CrossProxySyncManager
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Lifecycle orchestration for backend pools (Phase 4+):
 *
 *  - **Drain**: `/prism drain <pool>` stops new selections for the pool, moves
 *    every connected player along the fallback chain and force-moves stragglers
 *    after [LifecycleSettingsDefinition.drainTimeoutSeconds]. `/prism undrain <pool>`
 *    returns the pool to rotation.
 *
 *  - **Auto-restart**: every OFFLINE transition recorded by the health checker
 *    is fed into [CrashTracker]. When [LifecycleSettingsDefinition.crashThreshold]
 *    crashes happen within [LifecycleSettingsDefinition.restartCooldownSeconds],
 *    the pool is marked restarting (excluded from routing), the configured
 *    [LifecycleSettingsDefinition.restartCommand] is executed after
 *    [LifecycleSettingsDefinition.crashRestartDelaySeconds] (empty = external
 *    supervisor only) and the pool is watched for recovery within
 *    [LifecycleSettingsDefinition.healthWaitTimeoutSeconds].
 */
class LifecycleManager(
    private val plugin: PrismPlugin,
    private val logger: Logger,
    private val config: LifecycleSettingsDefinition,
    private val pluginOwner: Any,
    private val poolLookup: () -> Collection<ServerPool>,
    private val syncManager: CrossProxySyncManager? = null,
) {
    private val mini = MiniMessage.miniMessage()

    private val crashTrackers = ConcurrentHashMap<String, CrashTracker>()
    private val lastRestartAt = ConcurrentHashMap<String, Long>()
    private val pendingTasks = ConcurrentHashMap<String, ScheduledTask>()

    // ------------------------------------------------------------------ drain

    /**
     * Marks the pool draining and moves all current players to the fallback
     * chain. Players who cannot be moved are retried once when the drain
     * timeout expires. @return number of players moved immediately.
     */
    fun drainPool(poolName: String): Int {
        val pool = poolLookup().firstOrNull { it.name.equals(poolName, ignoreCase = true) } ?: return -1
        if (pool.isDraining()) return -2

        pool.setDraining(true)
        logger.warn("Pool '{}' is now DRAINING — new connections will be routed elsewhere", poolName)
        runCatching { plugin.getWsApiManager().broadcastAll("pool_draining", poolName) }

        val moved = movePlayersOutOfPool(pool, force = false)

        // Stragglers that could not be moved get one more chance after the timeout
        val timeoutTask = plugin.proxy.scheduler
            .buildTask(pluginOwner, Runnable { finishDrain(pool) })
            .delay(config.drainTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .schedule()
        pendingTasks["drain:${pool.name}"] = timeoutTask

        return moved
    }

    /** Returns the pool to rotation. */
    fun undrainPool(poolName: String): Boolean {
        val pool = poolLookup().firstOrNull { it.name.equals(poolName, ignoreCase = true) } ?: return false
        if (!pool.isDraining()) return false

        pool.setDraining(false)
        pendingTasks.remove("drain:${pool.name}")?.cancel()
        logger.info("Pool '{}' is back in rotation", pool.name)
        runCatching { plugin.getWsApiManager().broadcastAll("pool_undrained", pool.name) }
        return true
    }

    fun isDraining(poolName: String): Boolean =
        poolLookup().firstOrNull { it.name.equals(poolName, ignoreCase = true) }?.isDraining() == true

    fun isDrainingServer(serverName: String): Boolean =
        poolLookup().firstOrNull { it.hasServer(serverName) }?.isDraining() == true

    /** Players still stuck in a drained pool when the timeout hits are force-moved. */
    private fun finishDrain(pool: ServerPool) {
        pendingTasks.remove("drain:${pool.name}")
        if (!pool.isDraining()) return
        val remaining = movePlayersOutOfPool(pool, force = true)
        if (remaining > 0) {
            logger.warn(
                "Drain timeout for pool '{}' — {} player(s) could not be moved and were disconnected",
                pool.name, remaining
            )
        }
    }

    /** @return number of players still remaining after the move attempt. */
    private fun movePlayersOutOfPool(pool: ServerPool, force: Boolean): Int {
        val drainMessage = mini.deserialize(
            config.drainKickMessage.replace("<pool>", pool.name)
        )
        var remaining = 0
        for (player in plugin.proxy.allPlayers.toList()) {
            val current = player.currentServer.orElse(null)?.server ?: continue
            if (!pool.hasServer(current.serverInfo.name)) continue

            val fallback = plugin.getFallbackManager()
                .findFallbackForPool(pool.name, excludeServers = setOf(current.serverInfo.name))
            if (fallback != null && fallback.serverInfo.name != current.serverInfo.name) {
                movePlayer(player, fallback, drainMessage)
            } else if (force) {
                player.disconnect(drainMessage)
                remaining++
            } else {
                remaining++
            }
        }
        return remaining
    }

    private fun movePlayer(player: Player, target: RegisteredServer, message: net.kyori.adventure.text.Component) {
        player.sendMessage(message)
        player.createConnectionRequest(target).fireAndForget()
    }

    // ------------------------------------------------------------------ auto-restart

    /**
     * Called by the health checker when a server transitions to OFFLINE.
     * Feeds the crash tracker and triggers auto-restart when configured.
     */
    fun recordServerDown(serverName: String) {
        val lifecycle = plugin.getConfig().lifecycle
        val pool = poolLookup().firstOrNull { it.hasServer(serverName) } ?: return

        val tracker = crashTrackers.computeIfAbsent(serverName) {
            CrashTracker(lifecycle.crashThreshold, lifecycle.restartCooldownSeconds.toLong())
        }
        val shouldRestart = tracker.recordFailure(System.currentTimeMillis())

        if (shouldRestart && lifecycle.autoRestart) {
            maybeRestartPool(pool, lifecycle)
        } else if (shouldRestart) {
            logger.warn(
                "Pool '{}' hit the crash threshold ({} crashes in {}s) — auto-restart is disabled (lifecycle.auto-restart = false)",
                pool.name, lifecycle.crashThreshold, lifecycle.restartCooldownSeconds
            )
        }
    }

    /** Called by the health checker when a server comes back ONLINE. */
    fun recordServerUp(serverName: String) {
        crashTrackers.remove(serverName)
        val pool = poolLookup().firstOrNull { it.hasServer(serverName) } ?: return
        if (pool.isRestarting()) {
            pool.setRestarting(false)
            logger.info("Pool '{}' recovered and is back in rotation", pool.name)
            runCatching { plugin.getWsApiManager().broadcastAll("pool_recovered", pool.name) }
        }
    }

    /** Manual restart trigger (/prism restart <pool>) — works regardless of auto-restart. */
    fun restartPool(poolName: String): Boolean {
        val pool = poolLookup().firstOrNull { it.name.equals(poolName, ignoreCase = true) } ?: return false
        maybeRestartPool(pool, plugin.getConfig().lifecycle)
        return true
    }

    private fun maybeRestartPool(pool: ServerPool, lifecycle: LifecycleSettingsDefinition) {
        val now = System.currentTimeMillis()
        val cooldownMs = lifecycle.restartCooldownSeconds * 1000L
        val last = lastRestartAt[pool.name] ?: 0L
        if (now - last < cooldownMs) {
            logger.debug("Restart cooldown active for pool '{}' — skipping", pool.name)
            return
        }
        lastRestartAt[pool.name] = now

        pool.setRestarting(true)
        logger.warn(
            "Pool '{}' entered RESTARTING state — excluded from routing for up to {}s",
            pool.name, lifecycle.healthWaitTimeoutSeconds
        )
        runCatching { plugin.getWsApiManager().broadcastAll("pool_restarting", pool.name) }

        pendingTasks["restart:${pool.name}"]?.cancel()
        pendingTasks["restart:${pool.name}"] = plugin.proxy.scheduler
            .buildTask(pluginOwner, Runnable { executeRestart(pool, lifecycle) })
            .delay(lifecycle.crashRestartDelaySeconds.toLong(), TimeUnit.SECONDS)
            .schedule()
    }

    private fun executeRestart(pool: ServerPool, lifecycle: LifecycleSettingsDefinition) {
        pendingTasks.remove("restart:${pool.name}")
        val command = lifecycle.restartCommand.trim()
        if (command.isEmpty()) {
            logger.info(
                "No lifecycle.restart-command configured for pool '{}' — waiting up to {}s for an external supervisor to bring it back",
                pool.name, lifecycle.healthWaitTimeoutSeconds
            )
            return
        }

        logger.info("Executing restart command for pool '{}': {}", pool.name, command)
        try {
            val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
            val process = if (isWindows) {
                ProcessBuilder("cmd", "/c", command).start()
            } else {
                ProcessBuilder("sh", "-c", command).start()
            }
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                logger.info("Restart command for pool '{}' completed successfully", pool.name)
            } else {
                logger.error("Restart command for pool '{}' exited with code {}", pool.name, exitCode)
            }
        } catch (e: Exception) {
            logger.error("Failed to execute restart command for pool '{}': {}", pool.name, e.message)
        }
    }

    fun shutdown() {
        pendingTasks.values.forEach { it.cancel() }
        pendingTasks.clear()
    }
}

/**
 * Rolling crash detector. Counts failures within a sliding window; returns
 * true exactly once when the threshold is reached, then re-arms. Pure and
 * time-injectable so it is fully unit-testable.
 */
class CrashTracker(
    private val threshold: Int,
    private val windowSeconds: Long,
) {
    private val failures = ArrayDeque<Long>()

    /** Records a failure at [atMillis]; @return true when the threshold is reached. */
    @Synchronized
    fun recordFailure(atMillis: Long): Boolean {
        failures.addLast(atMillis)
        prune(atMillis)
        if (failures.size >= threshold) {
            failures.clear()
            return true
        }
        return false
    }

    @Synchronized
    fun clear() = failures.clear()

    @Synchronized
    fun count(): Int {
        // Prune relative to the newest recorded failure (not wall clock) so
        // tests can inject timestamps freely.
        failures.lastOrNull()?.let { prune(it) }
        return failures.size
    }

    private fun prune(now: Long) {
        val cutoff = now - windowSeconds * 1000
        while (failures.isNotEmpty() && failures.first() < cutoff) {
            failures.removeFirst()
        }
    }
}
