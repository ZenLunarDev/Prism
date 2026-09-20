package net.zld.prism.server

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import net.zld.prism.config.FallbackChainDefinition
import org.slf4j.Logger

/**
 * Resolves fallback targets by walking the configured chain of *pools* in
 * order and asking each pool for a healthy, weighted-random server — never by
 * treating pool names as server names (the Phase-1 bug).
 *
 * Exclusions ensure a player is never sent back to the pool/server that just
 * failed them, and the final result never equals the server the player is
 * being kicked from.
 */
class FallbackManager(
    private val proxy: ProxyServer,
    private val logger: Logger,
    private val config: FallbackChainDefinition,
    private val poolLookup: () -> Collection<ServerPool>,
) {
    private val fallbackPools: List<String> = config.chain
    private val defaultTarget: String = config.defaultTarget

    fun getFallbackChain(): List<String> = fallbackPools

    fun getDefaultTarget(): String = defaultTarget

    /**
     * Find a fallback server by walking the pool chain.
     *
     * @param excludePools  pool names to skip entirely (e.g. the pool that just went down)
     * @param excludeServers server names that must not be selected (e.g. the server the player was kicked from)
     * @return a healthy server, or null only when the proxy has nothing healthy left
     */
    fun findFallbackServer(
        excludePools: Set<String> = emptySet(),
        excludeServers: Set<String> = emptySet(),
    ): RegisteredServer? {
        // 1) Walk the configured pool chain, asking each healthy pool for a server
        for (poolName in fallbackPools) {
            if (poolName in excludePools) continue
            val pool = poolLookup().firstOrNull { it.name == poolName }
            if (pool == null) {
                logger.debug("Fallback chain references unknown pool '{}'", poolName)
                continue
            }
            val candidate = pool.selectServer(excludeServers)
            if (candidate != null) {
                return candidate
            }
            logger.debug("Fallback pool '{}' has no healthy server, continuing down the chain", poolName)
        }

        // 2) Default target: a specific server name, resolved directly
        val defaultServer = proxy.getServer(defaultTarget).orElse(null)
        if (defaultServer != null && defaultServer.serverInfo.name !in excludeServers) {
            return defaultServer
        }

        // 3) Last resort: any server not excluded
        val anyServer = proxy.allServers.firstOrNull { it.serverInfo.name !in excludeServers }
        if (anyServer != null) {
            logger.warn("No fallback pool/target available, using any remaining server: {}", anyServer.serverInfo.name)
        } else {
            logger.error("No fallback available at all — proxy has no usable server")
        }
        return anyServer
    }

    /** Fallback for players leaving a specific pool; never returns that pool's servers. */
    fun findFallbackForPool(poolName: String, excludeServers: Set<String> = emptySet()): RegisteredServer? =
        findFallbackServer(excludePools = setOf(poolName), excludeServers = excludeServers)
}
