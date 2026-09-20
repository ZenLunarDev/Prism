package net.zld.prism.server

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import net.zld.prism.config.PoolDefinition
import net.zld.prism.config.ServerDefinition
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger

class ServerPool(
    private val proxy: ProxyServer,
    private val poolDef: PoolDefinition,
) {
    private val servers = ConcurrentHashMap<String, ServerEntry>()
    private val totalWeight = AtomicInteger(0)

    /** Operational flags — a draining/restarting pool is skipped by selection. */
    @Volatile private var draining = false
    @Volatile private var restarting = false

    init {
        rebuild()
    }

    /** Re-registers all servers from the config; existing health and drain state is preserved. */
    fun rebuild() {
        val previousHealth = servers.entries.associate { it.key to it.value.healthState.snapshot() }
        servers.clear()
        totalWeight.set(0)

        for (def in poolDef.servers) {
            val registered = proxy.getServer(def.name).orElse(null)
            if (registered != null) {
                val entry = ServerEntry(def, registered)
                previousHealth[def.name]?.let { entry.healthState.restore(it) }
                servers[def.name] = entry
                if (entry.isHealthy) {
                    totalWeight.addAndGet(def.weight)
                }
            }
        }
    }

    fun registerServer(serverInfo: ServerInfo): Boolean {
        if (servers.containsKey(serverInfo.name)) return true
        val def = poolDef.servers.find { it.name == serverInfo.name } ?: return false
        val registered = proxy.getServer(serverInfo.name).orElse(null) ?: return false
        val entry = ServerEntry(def, registered)
        servers[serverInfo.name] = entry
        if (entry.isHealthy) {
            totalWeight.addAndGet(def.weight)
        }
        return true
    }

    fun unregisterServer(name: String): Boolean {
        val entry = servers.remove(name) ?: return false
        if (entry.isHealthy) {
            totalWeight.addAndGet(-entry.definition.weight)
        }
        return true
    }

    /**
     * Weighted random selection among healthy servers, excluding any servers named in [exclude].
     * Servers in a draining or restarting pool are never selected.
     * Returns null when no eligible healthy server exists.
     */
    fun selectServer(exclude: Set<String> = emptySet()): RegisteredServer? {
        if (draining || restarting) return null
        val healthyEntries = servers.values
            .filter { it.isHealthy && it.definition.name !in exclude }
            .toList()
        if (healthyEntries.isEmpty()) return null
        if (healthyEntries.size == 1) return healthyEntries[0].registeredServer

        val weightSum = healthyEntries.sumOf { it.definition.weight }
        var roll = ThreadLocalRandom.current().nextInt(weightSum) + 1
        for (entry in healthyEntries) {
            roll -= entry.definition.weight
            if (roll <= 0) {
                return entry.registeredServer
            }
        }
        return healthyEntries.last().registeredServer
    }

    fun getFallbackPoolName(): String? = poolDef.fallbackPool

    fun getHealthyServers(): List<RegisteredServer> = servers.values.filter { it.isHealthy }.map { it.registeredServer }

    fun getAllServers(): List<RegisteredServer> = servers.values.map { it.registeredServer }

    fun getServerDefinition(name: String): ServerDefinition? = poolDef.servers.find { it.name == name }

    fun getHealth(name: String): HealthSnapshot? {
        val entry = servers[name] ?: return null
        return HealthSnapshot(healthy = entry.healthState.isHealthy)
    }

    fun hasServer(name: String): Boolean = servers.containsKey(name)

    /**
     * Record the outcome of a health check. Consecutive failures must reach
     * [failThreshold] before a server is marked unhealthy, and consecutive
     * successes must reach [successThreshold] before it recovers — this
     * hysteresis prevents flapping when a server is intermittently reachable.
     */
    fun recordHealthCheck(name: String, success: Boolean, failThreshold: Int = 2, successThreshold: Int = 3): Boolean {
        val entry = servers[name] ?: return false
        val stateChanged = entry.healthState.record(success, failThreshold, successThreshold)
        if (stateChanged) {
            if (entry.isHealthy) {
                totalWeight.addAndGet(entry.definition.weight)
            } else {
                totalWeight.addAndGet(-entry.definition.weight)
            }
        }
        return stateChanged
    }

    // --- Lifecycle flags (drain / restart orchestration) ---

    fun isDraining(): Boolean = draining

    fun setDraining(value: Boolean) {
        draining = value
    }

    fun isRestarting(): Boolean = restarting

    fun setRestarting(value: Boolean) {
        restarting = value
    }

    fun isEmpty(): Boolean = servers.isEmpty()

    fun size(): Int = servers.size

    fun healthyCount(): Int = servers.values.count { it.isHealthy }

    val name: String get() = poolDef.name

    /**
     * Tracks consecutive check outcomes and the current healthy flag.
     * Thread-safe so the async health checker can call it from any thread.
     */
    /** Immutable view of a server's health, for routing decisions and the API. */
    data class HealthSnapshot(val healthy: Boolean)

    class HealthState(initiallyHealthy: Boolean = true) {
        private var healthy: Boolean = initiallyHealthy
        private var consecutiveSuccesses: Int = 0
        private var consecutiveFailures: Int = 0

        val isHealthy: Boolean get() = healthy

        @Synchronized
        fun record(success: Boolean, failThreshold: Int, successThreshold: Int): Boolean {
            if (success) {
                consecutiveSuccesses++
                consecutiveFailures = 0
                if (!healthy && consecutiveSuccesses >= successThreshold) {
                    healthy = true
                    return true
                }
            } else {
                consecutiveFailures++
                consecutiveSuccesses = 0
                if (healthy && consecutiveFailures >= failThreshold) {
                    healthy = false
                    return true
                }
            }
            return false
        }

        fun snapshot(): Triple<Boolean, Int, Int> = Triple(healthy, consecutiveSuccesses, consecutiveFailures)

        fun restore(state: Triple<Boolean, Int, Int>) {
            healthy = state.first
            consecutiveSuccesses = state.second
            consecutiveFailures = state.third
        }
    }

    private class ServerEntry(
        val definition: ServerDefinition,
        val registeredServer: RegisteredServer,
        val healthState: HealthState = HealthState(),
    ) {
        val isHealthy: Boolean get() = healthState.isHealthy
    }
}
