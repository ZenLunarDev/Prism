package net.zld.prism

import com.google.inject.Inject
import com.google.inject.Injector
import com.velocitypowered.api.command.CommandManager
import com.velocitypowered.api.command.CommandMeta
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import net.zld.prism.api.WebSocketApiManager
import net.zld.prism.command.GListCommand
import net.zld.prism.command.ServerCommand
import net.zld.prism.config.PrismConfig
import net.zld.prism.config.db.DatabaseConfigManager
import net.zld.prism.metrics.MetricsManager
import net.zld.prism.module.ModuleManager
import net.zld.prism.paper.ChatManager
import net.zld.prism.paper.CommandAPIManager
import net.zld.prism.paper.PermissionManager
import net.zld.prism.paper.PlayerManager
import net.zld.prism.server.FallbackManager
import net.zld.prism.server.HealthChecker
import net.zld.prism.server.LifecycleManager
import net.zld.prism.server.ServerPool
import net.zld.prism.sync.CrossProxySyncManager
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

@Plugin(
    id = "prism",
    name = "Prism",
    version = "1.0-SNAPSHOT",
    description = "Prism - Modern Velocity Proxy Orchestration Platform",
    authors = ["ZenLunarDev"]
)
class PrismPlugin @Inject constructor(
    private val logger: Logger,
    val proxy: ProxyServer,
    @DataDirectory dataDirectoryPath: Path,
    private val injector: Injector,
) {

    // Velocity injects a Path for @DataDirectory; expose it as File for the rest of the codebase
    val dataDirectory: File = dataDirectoryPath.toFile()

    val startTime: Long = System.currentTimeMillis()

    // Core systems
    private var config: PrismConfig? = null
    private val pools = mutableMapOf<String, ServerPool>()
    private lateinit var healthChecker: HealthChecker
    private lateinit var fallbackManager: FallbackManager
    private lateinit var lifecycleManager: LifecycleManager

    // PaperMC-style modules
    private lateinit var metricsManager: MetricsManager
    private lateinit var syncManager: CrossProxySyncManager
    private lateinit var wsApiManager: WebSocketApiManager
    private lateinit var dbManager: DatabaseConfigManager
    private lateinit var playerManager: PlayerManager
    private lateinit var permissionManager: PermissionManager
    private lateinit var chatManager: ChatManager
    private lateinit var commandAPIManager: CommandAPIManager
    private lateinit var moduleManager: ModuleManager
    private var embeddedWorldServer: net.zld.prism.world.EmbeddedWorldServer? = null

    // Legacy command
    private lateinit var serverCommand: ServerCommand
    private lateinit var glistCommand: GListCommand

    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        logger.info("Prism Proxy Core starting...")

        // Load configuration first
        loadConfig()

        // Initialize core systems (metrics/sync first so the health checker can use them)
        metricsManager = MetricsManager(this)

        syncManager = CrossProxySyncManager(this)
        val syncEnabled = syncManager.initialize(config!!.redis?.url ?: "")

        fallbackManager = FallbackManager(proxy, logger, config!!.fallback) { pools.values }

        // Embedded world (server-software mode) — must start BEFORE pools are
        // built so its listener can be registered as a regular server definition
        if (config!!.embeddedWorld.enabled) {
            logger.info("Starting embedded world server (Minestom)...")
            embeddedWorldServer = net.zld.prism.world.EmbeddedWorldServer(config!!.embeddedWorld, logger)
            embeddedWorldServer!!.start()
        }

        // Pools are built after fallback/health so their constructors see fully initialized state
        initializePools()

        // Lifecycle orchestration (drain / auto-restart) — needs pools to exist
        lifecycleManager = LifecycleManager(
            this, logger, config!!.lifecycle, this,
            poolLookup = { pools.values },
            syncManager = syncManager,
        )
        healthChecker = HealthChecker(
            proxy, logger, config!!.healthCheck, this,
            poolLookup = { pools.values },
            syncManager = syncManager,
            metricsRecorder = { _, healthy -> metricsManager.recordHealthCheck(healthy) },
            onServerOffline = { lifecycleManager.recordServerDown(it) },
            onServerOnline = { lifecycleManager.recordServerUp(it) },
        )

        // Initialize database
        dbManager = DatabaseConfigManager(this)
        val dbEnabled = dbManager.initialize(
            jdbcUrl = config!!.database?.jdbcUrl ?: "",
            username = config!!.database?.username ?: "",
            password = config!!.database?.password ?: "",
            driverClassName = config!!.database?.driver ?: "org.postgresql.Driver"
        )

        // Initialize cross-proxy sync

        // Initialize PaperMC-style modules
        playerManager = PlayerManager(this, dbManager, syncManager)
        permissionManager = PermissionManager(this, dbManager, syncManager)
        chatManager = ChatManager(this, dbManager, syncManager, permissionManager)
        commandAPIManager = CommandAPIManager(this, proxy.commandManager, syncManager)

        // Initialize WebSocket API
        wsApiManager = WebSocketApiManager(this, metricsManager, syncManager)

        // Initialize Module system
        moduleManager = ModuleManager(this, injector)

        // Register commands
        serverCommand = ServerCommand(this)
        glistCommand = GListCommand(this)
        registerCommands()

        // Start WebSocket API (Ktor embedded server lives inside the manager)
        wsApiManager.start()

        // Load modules
        moduleManager.initialize()

        // Log startup info
        logger.info("Prism Proxy Core successfully enabled!")
        logger.info("  Pools: {}", pools.size)
        logger.info("  Servers registered: {}", proxy.allServers.size)
        logger.info("  Metrics: enabled (Prometheus)")
        logger.info("  Cross-proxy sync: {}", if (syncEnabled) "connected" else "disabled")
        logger.info("  Database: {}", if (dbEnabled) "connected" else "disabled")
        logger.info("  WebSocket API: enabled")
        logger.info("  Modules loaded: {}", moduleManager.getLoadedModules().size)
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        logger.info("Prism Proxy Core shutting down...")

        embeddedWorldServer?.stop()
        lifecycleManager.shutdown()
        healthChecker.shutdown()
        wsApiManager.shutdown()
        syncManager.shutdown()
        dbManager.shutdown()
        moduleManager.shutdown()
        playerManager.shutdown()
        chatManager.shutdown()
        metricsManager.shutdown()

        logger.info("Prism Proxy Core disabled!")
    }

    @Subscribe
    fun onPlayerChooseInitialServer(event: PlayerChooseInitialServerEvent) {
        val player = event.player
        val initialServer = selectInitialServer(player)
        initialServer?.let { event.setInitialServer(it) }
    }

    @Subscribe
    fun onServerPreConnect(event: ServerPreConnectEvent) {
        val targetServer = event.originalServer
        val pool = pools.values.find { it.hasServer(targetServer.serverInfo.name) } ?: return

        // Draining pools never accept new joins — reroute if a fallback exists
        if (pool.isDraining()) {
            val alternative = fallbackManager.findFallbackForPool(
                pool.name,
                excludeServers = setOf(targetServer.serverInfo.name)
            )
            if (alternative != null) {
                logger.info(
                    "Routing {} away from draining pool '{}' -> '{}'",
                    event.player.username, pool.name, alternative.serverInfo.name
                )
                event.result = ServerPreConnectEvent.ServerResult.allowed(alternative)
            } else {
                logger.warn(
                    "Denying connection for {} — pool '{}' is draining and no fallback exists",
                    event.player.username, pool.name
                )
                event.result = ServerPreConnectEvent.ServerResult.denied()
            }
            return
        }

        // Never send players to a server that health checks marked offline
        val health = pool.getHealth(targetServer.serverInfo.name)
        if (health?.healthy == false) {
            val alternative = pool.selectServer(exclude = setOf(targetServer.serverInfo.name))
                ?: fallbackManager.findFallbackForPool(pool.name)
            if (alternative != null) {
                logger.info(
                    "Routing {} away from offline server '{}' -> '{}'",
                    event.player.username, targetServer.serverInfo.name, alternative.serverInfo.name
                )
                event.result = ServerPreConnectEvent.ServerResult.allowed(alternative)
            } else {
                logger.warn(
                    "Denying connection for {} — target '{}' is offline and no fallback exists",
                    event.player.username, targetServer.serverInfo.name
                )
                event.result = ServerPreConnectEvent.ServerResult.denied()
            }
        }
    }

    @Subscribe
    fun onServerConnected(event: ServerConnectedEvent) {
        val player = event.player
        val server = event.server

        logger.debug("Player {} connected to server {}", player.username, server.serverInfo.name)

        // Update metrics
        metricsManager.recordPlayerJoin()

        // Update player manager
        playerManager.onPlayerJoin(player)
    }

    @Subscribe
    fun onKickedFromServer(event: KickedFromServerEvent) {
        val player = event.player
        val originalServer = event.server
        val originalName = originalServer.serverInfo.name
        val poolName = getPoolNameForServer(originalServer) ?: "unknown"

        val kickReason = event.serverKickReason.orElse(null)
        logger.info("Player {} kicked from {}: {}", player.username, originalName,
            kickReason?.let { net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it) } ?: "no reason given")

        // Walk the pool chain, skipping the failed pool and the failed server
        val redirect = fallbackManager.findFallbackForPool(poolName, excludeServers = setOf(originalName))

        if (redirect != null && redirect.serverInfo.name != originalName) {
            logger.info("Redirecting {} to fallback server {}", player.username, redirect.serverInfo.name)
            metricsManager.recordFallbackActivation(poolName, redirect.serverInfo.name)
            event.result = KickedFromServerEvent.RedirectPlayer.create(
                redirect,
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(getConfig().messages.fallbackRedirect.replace("<server>", redirect.serverInfo.name))
            )
        } else {
            // Nothing healthy to move the player to — kick with a clear message so the
            // connection is never left dangling (stability bar: never drop players silently)
            logger.warn("No fallback available for {} — disconnecting with message", player.username)
            metricsManager.recordFallbackActivation(poolName, "none")
            event.result = KickedFromServerEvent.DisconnectPlayer.create(
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(getConfig().messages.noFallbackKick)
            )
        }
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        val player = event.player
        playerManager.onPlayerQuit(player.uniqueId)
        metricsManager.recordPlayerQuit()
    }

    @Subscribe
    fun onPlayerChat(event: com.velocitypowered.api.event.player.PlayerChatEvent) {
        val player = event.player

        // Delegate to chat manager
        chatManager.onPlayerChat(player, event.message)
    }

    private fun loadConfig() {
        val configFile = File(dataDirectory, "prism.conf")
        if (!configFile.exists()) {
            dataDirectory.mkdirs()
            val inputStream = PrismPlugin::class.java.classLoader.getResourceAsStream("prism.conf")
            if (inputStream != null) {
                Files.copy(inputStream, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                logger.info("Created default config at {}", configFile.absolutePath)
            } else {
                logger.error("Default config resource not found!")
            }
        }

        val report = PrismConfig.loadWithReport(configFile)
        for (migration in report.migrationsApplied) {
            logger.info("Config migration: {}", migration)
        }
        for (warning in report.warnings) {
            logger.warn("Config warning: {}", warning)
        }
        if (!report.valid) {
            for (error in report.errors) {
                logger.error("Config error: {}", error)
            }
            throw IllegalStateException(
                "Invalid prism.conf (${report.errors.size} error(s)) — fix the errors above and retry"
            )
        }

        config = report.config
        logger.info("Loaded Prism configuration v{} with {} pools", config!!.configVersion, config!!.pools.size)
    }

    private fun initializePools() {
        pools.clear()
        for (poolDef in effectivePoolDefs()) {
            // Register with the proxy FIRST — ServerPool's constructor rebuilds its
            // membership from proxy.getServer(), which would otherwise find nothing
            registerPoolServers(poolDef)
            pools[poolDef.name] = ServerPool(proxy, poolDef)
        }
    }

    /**
     * Config pools plus the embedded world listener (when running), injected
     * into its target pool so it routes/falls back like any other server.
     */
    private fun effectivePoolDefs(): List<net.zld.prism.config.PoolDefinition> {
        val ew = embeddedWorldServer?.takeIf { it.isRunning } ?: return config!!.pools
        val def = net.zld.prism.config.ServerDefinition(
            name = ew.getServerName(),
            host = "127.0.0.1",
            port = ew.getPort(),
            weight = 1,
            fallback = false,
            motd = "Prism built-in world",
        )
        val defs = config!!.pools.toMutableList()
        val idx = defs.indexOfFirst { it.name == ew.getPoolName() }
        if (idx >= 0) {
            val pool = defs[idx]
            if (pool.servers.none { it.name == def.name }) {
                defs[idx] = pool.copy(servers = pool.servers + def)
            }
        } else {
            defs.add(net.zld.prism.config.PoolDefinition(name = ew.getPoolName(), servers = listOf(def)))
        }
        return defs
    }

    private fun registerPoolServers(poolDef: net.zld.prism.config.PoolDefinition) {
        for (serverDef in poolDef.servers) {
            val serverInfo = serverDef.toServerInfo()
            if (proxy.getServer(serverInfo.name).isEmpty) {
                proxy.registerServer(serverInfo)
                logger.info("Registered server {} at {}:{}", serverInfo.name, serverDef.host, serverDef.port)
            }
        }
    }

    private fun selectInitialServer(player: com.velocitypowered.api.proxy.Player): RegisteredServer? {
        val lobbyPool = pools["lobby"]
        return lobbyPool?.selectServer() ?: fallbackManager.findFallbackServer()
    }

    private fun registerCommands() {
        val commandsConfig = getConfig().commands

        // Legacy /server command (toggleable via commands.server-command)
        if (commandsConfig.serverCommandEnabled) {
            val commandMeta = proxy.commandManager.metaBuilder("server").plugin(this).build()
            proxy.commandManager.register(commandMeta, serverCommand)
        }

        // /glist — players per pool (toggleable via commands.glist-command)
        if (commandsConfig.glistCommandEnabled) {
            val glistMeta = proxy.commandManager.metaBuilder("glist").plugin(this).build()
            proxy.commandManager.register(glistMeta, glistCommand)
        }

        // Register admin commands via CommandAPIManager — aliases come from commands.admin-aliases
        commandAPIManager.registerCommand("prism", commandsConfig.adminAliases, "prism.admin") {
            then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal<com.velocitypowered.api.command.CommandSource>("reload").executes { ctx ->
                safeReload(ctx.source)
                1
            })
            then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal<com.velocitypowered.api.command.CommandSource>("status").executes { ctx ->
                sendStatus(ctx.source)
                1
            })
            then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal<com.velocitypowered.api.command.CommandSource>("modules").executes { ctx ->
                ctx.source.sendMessage(net.kyori.adventure.text.Component.text("Loaded modules: ${moduleManager.getLoadedModules().joinToString(", ") { it.name }}", net.kyori.adventure.text.format.NamedTextColor.AQUA))
                1
            })
            then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal<com.velocitypowered.api.command.CommandSource>("metrics").executes { ctx ->
                ctx.source.sendMessage(net.kyori.adventure.text.Component.text("Metrics endpoint: /api/v1/metrics", net.kyori.adventure.text.format.NamedTextColor.AQUA))
                1
            })
            then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal<com.velocitypowered.api.command.CommandSource>("sync").executes { ctx ->
                val status = if (syncManager.isConnected()) "Connected" else "Disconnected"
                ctx.source.sendMessage(net.kyori.adventure.text.Component.text("Sync: $status (proxyId: ${syncManager.proxyIdStr})", net.kyori.adventure.text.format.NamedTextColor.AQUA))
                1
            })
            then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal<com.velocitypowered.api.command.CommandSource>("drain")
                .then(poolArgument(
                    suggest = { pools.values.filter { !it.isDraining() }.map { it.name } },
                    executes = { source, poolName ->
                        when (val moved = lifecycleManager.drainPool(poolName)) {
                            -1 -> source.sendMessage(net.kyori.adventure.text.Component.text("Unknown pool: $poolName", net.kyori.adventure.text.format.NamedTextColor.RED))
                            -2 -> source.sendMessage(net.kyori.adventure.text.Component.text("Pool '$poolName' is already draining.", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                            else -> source.sendMessage(net.kyori.adventure.text.Component.text("Pool '$poolName' is draining — moved $moved player(s); new joins are routed elsewhere.", net.kyori.adventure.text.format.NamedTextColor.GREEN))
                        }
                    }
                )))
            then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal<com.velocitypowered.api.command.CommandSource>("undrain")
                .then(poolArgument(
                    suggest = { pools.values.filter { it.isDraining() }.map { it.name } },
                    executes = { source, poolName ->
                        if (lifecycleManager.undrainPool(poolName)) {
                            source.sendMessage(net.kyori.adventure.text.Component.text("Pool '$poolName' is back in rotation.", net.kyori.adventure.text.format.NamedTextColor.GREEN))
                        } else {
                            source.sendMessage(net.kyori.adventure.text.Component.text("Pool '$poolName' is not draining (or unknown).", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                        }
                    }
                )))
            then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal<com.velocitypowered.api.command.CommandSource>("restart")
                .then(poolArgument(
                    suggest = { pools.values.map { it.name } },
                    executes = { source, poolName ->
                        if (lifecycleManager.restartPool(poolName)) {
                            val cmd = getConfig().lifecycle.restartCommand
                            source.sendMessage(net.kyori.adventure.text.Component.text(
                                if (cmd.isBlank()) "Pool '$poolName' marked restarting — no restart-command configured, waiting for external supervisor."
                                else "Pool '$poolName' marked restarting — executing restart command in ${getConfig().lifecycle.crashRestartDelaySeconds}s.",
                                net.kyori.adventure.text.format.NamedTextColor.YELLOW
                            ))
                        } else {
                            source.sendMessage(net.kyori.adventure.text.Component.text("Unknown pool: $poolName", net.kyori.adventure.text.format.NamedTextColor.RED))
                        }
                    }
                )))
        }

        logger.info("Registered commands")
    }

    /** Brigadier argument for a pool name with tab-complete from [suggest]. */
    private fun poolArgument(
        suggest: () -> List<String>,
        executes: (com.velocitypowered.api.command.CommandSource, String) -> Unit,
    ): com.mojang.brigadier.builder.RequiredArgumentBuilder<com.velocitypowered.api.command.CommandSource, String> =
        com.mojang.brigadier.builder.RequiredArgumentBuilder
            .argument<com.velocitypowered.api.command.CommandSource, String>(
                "pool", com.mojang.brigadier.arguments.StringArgumentType.word()
            )
            .suggests { _, builder ->
                suggest().forEach { builder.suggest(it) }
                builder.buildFuture()
            }
            .executes { ctx ->
                val poolName = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "pool")
                executes(ctx.source, poolName)
                1
            }

    /**
     * Reload config without dropping players:
     *  - old health checker task is cancelled before a new one is scheduled
     *  - pools are rebuilt from the new config; per-server health state is
     *    preserved for servers that still exist (ServerPool.rebuild)
     *  - the config version is bumped and broadcast so other proxies reload too
     */
    private fun safeReload(source: com.velocitypowered.api.command.CommandSource) {
        try {
            loadConfig()

            // Replace the fallback chain first (routing must stay consistent during reload)
            fallbackManager = FallbackManager(proxy, logger, config!!.fallback) { pools.values }

            // Stop the old health checker before creating a new one (no double scheduling)
            healthChecker.shutdown()
            healthChecker = HealthChecker(
                proxy, logger, config!!.healthCheck, this,
                poolLookup = { pools.values },
                syncManager = syncManager,
                metricsRecorder = { _, healthy -> metricsManager.recordHealthCheck(healthy) },
            )

            // Rebuild pools against the new config — ServerPool.rebuild keeps health state
            pools.clear()
            for (poolDef in config!!.pools) {
                registerPoolServers(poolDef)
                pools[poolDef.name] = ServerPool(proxy, poolDef)
            }

            // Tell other proxies the config changed
            syncManager.publishConfigUpdated()

            source.sendMessage(net.kyori.adventure.text.Component.text(
                "Prism config reloaded: ${config!!.pools.size} pools, ${proxy.allServers.size} servers registered.",
                net.kyori.adventure.text.format.NamedTextColor.GREEN
            ))
        } catch (e: Exception) {
            logger.error("Config reload failed — keeping previous config: {}", e.message, e)
            source.sendMessage(net.kyori.adventure.text.Component.text(
                "Reload failed: ${e.message}. Previous config kept.",
                net.kyori.adventure.text.format.NamedTextColor.RED
            ))
        }
    }

    private fun sendStatus(source: com.velocitypowered.api.command.CommandSource) {
        val gray = net.kyori.adventure.text.format.NamedTextColor.GRAY
        val aqua = net.kyori.adventure.text.format.NamedTextColor.AQUA
        val green = net.kyori.adventure.text.format.NamedTextColor.GREEN
        val red = net.kyori.adventure.text.format.NamedTextColor.RED

        val uptimeMin = (System.currentTimeMillis() - startTime) / 60000
        source.sendMessage(net.kyori.adventure.text.Component.text("=== Prism Status ===", aqua))
        source.sendMessage(net.kyori.adventure.text.Component.text(
            "Uptime: ${uptimeMin}m | Players: ${proxy.playerCount} | Proxies synced: ${if (syncManager.isConnected()) "connected" else "offline"}", gray
        ))

        for (pool in pools.values) {
            val servers = pool.getAllServers().joinToString(", ") { rs ->
                val healthy = pool.getHealth(rs.serverInfo.name)?.healthy ?: true
                val marker = if (healthy) "+" else "x"
                "[$marker]${rs.serverInfo.name}(${rs.playersConnected.size})"
            }
            val healthyCount = pool.healthyCount()
            val poolColor = when {
                healthyCount == pool.size() && pool.size() > 0 -> green
                healthyCount == 0 -> red
                else -> net.kyori.adventure.text.format.NamedTextColor.YELLOW
            }
            source.sendMessage(
                net.kyori.adventure.text.Component.text("  ${pool.name} ", poolColor)
                    .append(net.kyori.adventure.text.Component.text("($healthyCount/${pool.size()}) ", gray))
                    .append(net.kyori.adventure.text.Component.text(servers.ifEmpty { "(no servers)" }, aqua))
            )
        }
        source.sendMessage(net.kyori.adventure.text.Component.text(
            "Fallback chain: ${fallbackManager.getFallbackChain().joinToString(" -> ")} (default: ${fallbackManager.getDefaultTarget()})", gray
        ))
    }

    // Public API
    fun getPool(name: String): ServerPool? = pools[name]
    fun getAllPools(): Collection<ServerPool> = pools.values
    fun getConfig(): PrismConfig = config!!
    fun getVersion(): String = "1.0-SNAPSHOT"
    fun getFallbackManager(): FallbackManager = fallbackManager
    fun getHealthChecker(): HealthChecker = healthChecker
    fun getWsApiManager(): WebSocketApiManager = wsApiManager
    fun getMetricsManager(): MetricsManager = metricsManager
    fun getSyncManager(): CrossProxySyncManager = syncManager
    fun getWebSocketApiManager(): WebSocketApiManager = wsApiManager
    fun getDatabaseManager(): DatabaseConfigManager = dbManager
    fun getPlayerManager(): PlayerManager = playerManager
    fun getPermissionManager(): PermissionManager = permissionManager
    fun getChatManager(): ChatManager = chatManager
    fun getCommandAPIManager(): CommandAPIManager = commandAPIManager
    fun getModuleManager(): ModuleManager = moduleManager

    fun getPoolNameForServer(server: RegisteredServer): String? {
        return pools.entries.find { (_, pool) -> pool.getAllServers().contains(server) }?.key
    }

    fun getPoolNameForServer(serverName: String): String? {
        return pools.entries.find { (_, pool) -> pool.getAllServers().any { it.serverInfo.name == serverName } }?.key
    }
}