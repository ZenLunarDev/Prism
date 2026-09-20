package net.zld.prism.server

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.scheduler.ScheduledTask
import net.zld.prism.config.HealthCheckDefinition
import net.zld.prism.sync.CrossProxySyncManager
import org.slf4j.Logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Periodically pings every registered server and feeds the results back into
 * the owning pool so routing decisions reflect real reachability.
 *
 * Key behaviours (Phase 2):
 *  - real ping timeout via orTimeout — a hung backend can't stall the round
 *  - results are pushed into the pool (markHealthy path) — health state changes
 *  - hysteresis: N consecutive failures before unhealthy, N consecutive
 *    successes before recovering (configured via [HealthCheckDefinition])
 *  - all servers are pinged concurrently, not sequentially
 *  - every result is recorded as a metric and health transitions are
 *    broadcast to other proxies over the Redis sync channel
 */
class HealthChecker(
    private val proxy: ProxyServer,
    private val logger: Logger,
    private val config: HealthCheckDefinition,
    pluginOwner: Any,
    private val poolLookup: () -> Collection<ServerPool>,
    private val syncManager: CrossProxySyncManager? = null,
    private val metricsRecorder: ((serverName: String, healthy: Boolean) -> Unit)? = null,
    private val onServerOffline: ((serverName: String) -> Unit)? = null,
    private val onServerOnline: ((serverName: String) -> Unit)? = null,
) {
    private val task: ScheduledTask? = if (config.enabled) {
        proxy.scheduler.buildTask(pluginOwner, Runnable { checkAllServers() })
            .delay(config.intervalSeconds.toLong(), TimeUnit.SECONDS)
            .repeat(config.intervalSeconds.toLong(), TimeUnit.SECONDS)
            .schedule()
    } else null

    private val running = AtomicBoolean(false)

    /** Latest ping-derived info per server, exposed to the API layer. */
    private val serverStatus = ConcurrentHashMap<String, ServerStatus>()

    fun checkAllServers() {
        if (!running.compareAndSet(false, true)) {
            logger.debug("Health check already running, skipping")
            return
        }

        try {
            val servers = proxy.allServers.toList()
            if (servers.isEmpty()) return

            logger.debug("Health check round: pinging {} servers (interval={}s, timeout={}ms)",
                servers.size, config.intervalSeconds, config.timeoutMillis)

            val futures = servers.map { server -> checkServerAsync(server) }
            // Wait for the whole round (bounded by the per-ping timeout) without
            // blocking the event loop for longer than one interval.
            try {
                CompletableFuture.allOf(*futures.toTypedArray())
                    .get(timeoutMillis() * 2L + 1000L, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                logger.debug("Health check round interrupted: {}", e.message)
            }
        } finally {
            running.set(false)
        }
    }

    private fun timeoutMillis(): Long = config.timeoutMillis.coerceAtLeast(500).toLong()

    private fun checkServerAsync(server: RegisteredServer): CompletableFuture<Boolean> {
        val name = server.serverInfo.name

        return server.ping()
            .orTimeout(timeoutMillis(), TimeUnit.MILLISECONDS)
            .handle { result, throwable ->
                val success = throwable == null && result != null
                if (!success) {
                    // Per-ping failures are debug — the pool's hysteresis decides when a
                    // transition happens, and transitions are logged at WARN/INFO in applyResult
                    logger.debug("Health check failed for server '{}': {}", name, throwable?.message ?: "no response")
                } else {
                    serverStatus[name] = ServerStatus(
                        motd = result.descriptionComponent?.let { net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it) },
                        gameVersion = result.version.toString(),
                    )
                }

                applyResult(name, success)
                success
            }
    }

    /** Push the ping outcome into the owning pool and the wider systems. */
    private fun applyResult(serverName: String, success: Boolean) {
        // Metrics for every check
        metricsRecorder?.invoke(serverName, success)

        // Find the pool that owns this server and let its hysteresis decide
        val pool = poolLookup().firstOrNull { it.hasServer(serverName) }
        if (pool != null) {
            val changed = pool.recordHealthCheck(serverName, success, config.failThreshold, config.successThreshold)
            if (changed) {
                val healthy = pool.getHealth(serverName)?.healthy ?: success
                if (healthy) {
                    logger.info("Server '{}' is back ONLINE ({} consecutive successes)", serverName, config.successThreshold)
                    onServerOnline?.invoke(serverName)
                } else {
                    logger.warn("Server '{}' marked OFFLINE after {} consecutive failures", serverName, config.failThreshold)
                    onServerOffline?.invoke(serverName)
                }
                // Broadcast health transitions so other proxies stop routing there
                syncManager?.publishServerHealthChanged(serverName, healthy)
            }
        }
    }

    /** Snapshot of the last successful ping for API exposure. */
    data class ServerStatus(
        val motd: String?,
        val gameVersion: String?,
    )

    fun getStatus(serverName: String): ServerStatus? = serverStatus[serverName]

    fun shutdown() {
        task?.cancel()
    }
}
