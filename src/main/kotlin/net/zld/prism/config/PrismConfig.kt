package net.zld.prism.config

import com.velocitypowered.api.proxy.server.ServerInfo as VelocityServerInfo
import org.spongepowered.configurate.CommentedConfigurationNode
import org.spongepowered.configurate.ConfigurationOptions
import org.spongepowered.configurate.hocon.HoconConfigurationLoader
import org.spongepowered.configurate.objectmapping.meta.Setting
import java.io.File
import java.net.InetSocketAddress

data class ServerDefinition(
    @Setting("name") val name: String,
    @Setting("host") val host: String,
    @Setting("port") val port: Int = 25565,
    @Setting("weight") val weight: Int = 1,
    @Setting("fallback") val fallback: Boolean = false,
    @Setting("motd") val motd: String? = null,
) {
    fun toServerInfo(): VelocityServerInfo {
        val address = InetSocketAddress(host, port)
        return VelocityServerInfo(name, address)
    }
}

data class PoolDefinition(
    @Setting("name") val name: String,
    @Setting("servers") val servers: List<ServerDefinition>,
    @Setting("fallback") val fallbackPool: String? = null,
)

data class FallbackChainDefinition(
    @Setting("chain") val chain: List<String>,
    @Setting("default") val defaultTarget: String,
)

data class HealthCheckDefinition(
    @Setting("enabled") val enabled: Boolean = true,
    @Setting("interval") val intervalSeconds: Int = 15,
    @Setting("timeout") val timeoutMillis: Int = 5000,
    @Setting("fail-threshold") val failThreshold: Int = 2,
    @Setting("success-threshold") val successThreshold: Int = 3,
)

data class DatabaseDefinition(
    @Setting("jdbc-url") val jdbcUrl: String = "",
    @Setting("username") val username: String = "",
    @Setting("password") val password: String = "",
    @Setting("driver") val driver: String = "org.postgresql.Driver",
    @Setting("max-pool-size") val maxPoolSize: Int = 20,
    @Setting("min-idle") val minIdle: Int = 5,
)

data class RedisDefinition(
    @Setting("url") val url: String = "",
    @Setting("password") val password: String = "",
    @Setting("database") val database: Int = 0,
    @Setting("pool-size") val poolSize: Int = 20,
)

data class ApiDefinition(
    @Setting("host") val host: String = "0.0.0.0",
    @Setting("port") val port: Int = 8080,
    @Setting("enabled") val enabled: Boolean = true,
    @Setting("jwt-secret") val jwtSecret: String = "",
    @Setting("cors-enabled") val corsEnabled: Boolean = true,
    @Setting("rate-limit-enabled") val rateLimitEnabled: Boolean = true,
    @Setting("rate-limit-per-minute") val rateLimitPerMinute: Int = 120,
)

data class LoggingDefinition(
    @Setting("level") val level: String = "INFO",
    @Setting("json-format") val jsonFormat: Boolean = false,
    @Setting("loki-url") val lokiUrl: String = "",
)

data class PrismConfig(
    val pools: List<PoolDefinition> = emptyList(),
    val fallback: FallbackChainDefinition = FallbackChainDefinition(emptyList(), "lobby"),
    val healthCheck: HealthCheckDefinition = HealthCheckDefinition(),
    val database: DatabaseDefinition = DatabaseDefinition(),
    val redis: RedisDefinition = RedisDefinition(),
    val api: ApiDefinition = ApiDefinition(),
    val logging: LoggingDefinition = LoggingDefinition(),
    val chat: ChatSettingsDefinition = ChatSettingsDefinition(),
    val embeddedWorld: EmbeddedWorldSettingsDefinition = EmbeddedWorldSettingsDefinition(),
    val players: PlayersSettingsDefinition = PlayersSettingsDefinition(),
    val commands: CommandsSettingsDefinition = CommandsSettingsDefinition(),
    val lifecycle: LifecycleSettingsDefinition = LifecycleSettingsDefinition(),
    val sync: SyncSettingsDefinition = SyncSettingsDefinition(),
    val metrics: MetricsSettingsDefinition = MetricsSettingsDefinition(),
    val advanced: AdvancedSettingsDefinition = AdvancedSettingsDefinition(),
    val messages: MessagesSettingsDefinition = MessagesSettingsDefinition(),
    val configVersion: Int = CURRENT_VERSION,
) {
    /** Result of a validated load — config plus human-readable issues. */
    data class LoadReport(
        val config: PrismConfig,
        val errors: List<String>,
        val warnings: List<String>,
        val migrationsApplied: List<String>,
    ) {
        val valid: Boolean get() = errors.isEmpty()
    }

    companion object {
        /** Bump when a config migration is added; see [migrate]. */
        const val CURRENT_VERSION = 4

        private val SECRET_KEYS = setOf("password", "jwt-secret")

        /** Loads without validation — kept for backwards compatibility. */
        fun load(file: File): PrismConfig {
            val loader = HoconConfigurationLoader.builder()
                .path(file.toPath())
                .build()
            val node = loader.load(ConfigurationOptions.defaults())
            return parseConfig(node)
        }

        /** Loads with migration + validation; never throws for validation issues. */
        fun loadWithReport(file: File): LoadReport {
            val loader = HoconConfigurationLoader.builder()
                .path(file.toPath())
                .build()
            val node = loader.load(ConfigurationOptions.defaults())

            val migrations = mutableListOf<String>()
            val reported = node.node("config-version").getInt(1)
            if (reported < CURRENT_VERSION) {
                migrate(node, reported, migrations)
                // Persist migrated keys so the on-disk file matches the running config
                try {
                    node.node("config-version").raw(CURRENT_VERSION)
                    loader.save(node)
                    migrations.add("config-version updated to $CURRENT_VERSION")
                } catch (_: Exception) {
                    // read-only fs etc. — migration still applies in memory
                }
            }

            val config = parseConfig(node)
            val (errors, warnings) = validate(config)
            return LoadReport(config, errors, warnings, migrations)
        }

        /**
         * In-place migration of a config node from [from] version to CURRENT_VERSION.
         * Add a branch per historical version bump.
         */
        private fun migrate(node: org.spongepowered.configurate.ConfigurationNode, from: Int, out: MutableList<String>) {
            if (from < 4) {
                // v3 -> v4: embedded world server section was introduced
                val ew = node.node("embedded-world")
                if (ew.node("enabled").virtual()) {
                    ew.node("enabled").raw(false)
                    out.add("added embedded-world.enabled = false")
                }
                if (ew.node("port").virtual()) {
                    ew.node("port").raw(25580)
                    out.add("added embedded-world.port = 25580")
                }
            }
            if (from < 2) {
                // v1 -> v2: health-check thresholds were introduced
                val hc = node.node("health-check")
                if (hc.node("fail-threshold").virtual()) {
                    hc.node("fail-threshold").raw(2)
                    out.add("added health-check.fail-threshold = 2")
                }
                if (hc.node("success-threshold").virtual()) {
                    hc.node("success-threshold").raw(3)
                    out.add("added health-check.success-threshold = 3")
                }
            }
            if (from < 3) {
                // v2 -> v3: Purpur-style granular sections introduced.
                // Every new key has an in-code default, so nothing needs filling in —
                // record it so operators can see what the migration did.
                out.add("added Purpur-style sections (chat, players, commands, lifecycle, sync, metrics, advanced, messages) — defaults applied")
            }
            // future: if (from < 4) { ... }
        }

        /** @return (errors, warnings) — errors make the config invalid. */
        fun validate(config: PrismConfig): Pair<List<String>, List<String>> {
            val errors = mutableListOf<String>()
            val warnings = mutableListOf<String>()

            // --- Pools ---
            if (config.pools.isEmpty()) {
                warnings.add("no pools defined — no backend servers will be registered")
            }
            val serverOwners = mutableMapOf<String, String>() // server name -> pool name
            for (pool in config.pools) {
                if (pool.name.isBlank()) {
                    errors.add("a pool has an empty name")
                    continue
                }
                if (pool.servers.isEmpty()) {
                    errors.add("pool '${pool.name}' has no servers")
                }
                for (server in pool.servers) {
                    if (server.weight <= 0) {
                        errors.add("server '${server.name}' in pool '${pool.name}' has weight ${server.weight} — weight must be > 0")
                    }
                    if (server.port !in 1..65535) {
                        errors.add("server '${server.name}' has invalid port ${server.port}")
                    }
                    val previous = serverOwners.put(server.name, pool.name)
                    if (previous != null) {
                        errors.add("server '${server.name}' is declared in both pool '$previous' and pool '${pool.name}' — names must be unique across pools")
                    }
                }
            }

            // --- Fallback chain must reference real pools ---
            val poolNames = config.pools.map { it.name }.toSet()
            for (chainPool in config.fallback.chain) {
                if (chainPool !in poolNames) {
                    warnings.add("fallback chain references pool '$chainPool' which is not defined in pools")
                }
            }
            val knownServers = config.pools.flatMap { it.servers }.map { it.name }.toSet()
            if (config.fallback.defaultTarget !in knownServers) {
                errors.add("fallback default target '${config.fallback.defaultTarget}' is not a known server")
            }

            // --- Health check sanity ---
            if (config.healthCheck.intervalSeconds <= 0) {
                errors.add("health-check.interval must be > 0")
            }
            if (config.healthCheck.timeoutMillis < 500) {
                errors.add("health-check.timeout must be >= 500 ms")
            }
            if (config.healthCheck.failThreshold < 1 || config.healthCheck.successThreshold < 1) {
                errors.add("health-check thresholds must be >= 1")
            }

            // --- Purpur-style section sanity ---
            if (config.chat.historySize < 0) {
                errors.add("chat.history-size must be >= 0")
            }
            if (config.commands.commandCooldownSeconds < 0) {
                errors.add("commands.cooldown-seconds must be >= 0")
            }
            if (config.sync.eventQueueSize < 1) {
                errors.add("sync.event-queue-size must be >= 1")
            }
            if (config.sync.connectTimeoutSeconds < 1) {
                errors.add("sync.connect-timeout-seconds must be >= 1")
            }
            if (config.sync.publishTimeoutSeconds < 1) {
                errors.add("sync.publish-timeout-seconds must be >= 1")
            }
            if (config.metrics.pushGatewayUrl.isNotBlank() && config.metrics.pushIntervalSeconds < 5) {
                errors.add("metrics.push-interval-seconds must be >= 5 when a push gateway is configured")
            }
            if (config.advanced.healthPingConcurrency < 0) {
                errors.add("advanced.health-ping-concurrency must be >= 0 (0 = unlimited)")
            }
            if (config.advanced.connectionTimeoutMillis < 100) {
                errors.add("advanced.connection-timeout-millis must be >= 100")
            }
            if (config.api.rateLimitEnabled && config.api.rateLimitPerMinute < 1) {
                errors.add("api.rate-limit-per-minute must be >= 1 when rate limiting is enabled")
            }
            if (config.commands.blockedCommands.any { it.isBlank() }) {
                warnings.add("commands.blocked-commands contains blank entries — they will be ignored")
            }

            // --- Secrets ---
            if (config.api.enabled) {
                if (config.api.jwtSecret.isBlank()) {
                    warnings.add("api.jwt-secret is empty — protected API routes will reject all requests")
                } else if (config.api.jwtSecret.startsWith("changeme")) {
                    warnings.add("api.jwt-secret looks like a default value — set a real secret before production")
                }
            }

            return errors to warnings
        }

        private fun parseConfig(node: CommentedConfigurationNode): PrismConfig {
            val pools = mutableListOf<PoolDefinition>()
            val poolsNode = node.node("pools")
            if (!poolsNode.virtual()) {
                // pools may be a HOCON array (childrenList) or object (childrenMap)
                val poolNodes: List<org.spongepowered.configurate.ConfigurationNode> =
                    if (poolsNode.isList) poolsNode.childrenList() else poolsNode.childrenMap().values.toList()
                for (poolNode in poolNodes) {
                    val name = poolNode.node("name").getString() ?: continue
                    val servers = mutableListOf<ServerDefinition>()
                    val serversNode = poolNode.node("servers")
                    if (!serversNode.virtual()) {
                        val serverNodes: List<org.spongepowered.configurate.ConfigurationNode> =
                            if (serversNode.isList) serversNode.childrenList() else serversNode.childrenMap().values.toList()
                        for (serverNode in serverNodes) {
                            val serverName = serverNode.node("name").getString() ?: continue
                            val host = serverNode.node("host").getString() ?: continue
                            val port = serverNode.node("port").getInt(25565)
                            val weight = serverNode.node("weight").getInt(1)
                            val fallback = serverNode.node("fallback").getBoolean(false)
                            val motd = serverNode.node("motd").getString()
                            servers.add(ServerDefinition(serverName, host, port, weight, fallback, motd))
                        }
                    }
                    val fallbackPool = poolNode.node("fallback").getString()
                    pools.add(PoolDefinition(name, servers, fallbackPool))
                }
            }

            val fallbackNode = node.node("fallback")
            val fallbackChain = mutableListOf<String>()
            val chainNode = fallbackNode.node("chain")
            if (!chainNode.virtual()) {
                val chainItems: List<org.spongepowered.configurate.ConfigurationNode> =
                    if (chainNode.isList) chainNode.childrenList() else chainNode.childrenMap().values.toList()
                for (itemNode in chainItems) {
                    val item = itemNode.getString()
                    if (item != null) fallbackChain.add(item)
                }
            }
            val defaultTarget = fallbackNode.node("default").getString("lobby")
            val fallback = FallbackChainDefinition(fallbackChain, defaultTarget)

            val healthCheckNode = node.node("health-check")
            val healthCheck = HealthCheckDefinition(
                healthCheckNode.node("enabled").getBoolean(true),
                healthCheckNode.node("interval").getInt(15),
                healthCheckNode.node("timeout").getInt(5000),
                healthCheckNode.node("fail-threshold").getInt(2),
                healthCheckNode.node("success-threshold").getInt(3),
            )

            val databaseNode = node.node("database")
            val database = DatabaseDefinition(
                databaseNode.node("jdbc-url").getString(""),
                databaseNode.node("username").getString(""),
                databaseNode.node("password").getString(""),
                databaseNode.node("driver").getString("org.postgresql.Driver"),
                databaseNode.node("max-pool-size").getInt(20),
                databaseNode.node("min-idle").getInt(5),
            )

            val redisNode = node.node("redis")
            val redis = RedisDefinition(
                redisNode.node("url").getString(""),
                redisNode.node("password").getString(""),
                redisNode.node("database").getInt(0),
                redisNode.node("pool-size").getInt(20),
            )

            val apiNode = node.node("api")
            val api = ApiDefinition(
                apiNode.node("host").getString("0.0.0.0"),
                apiNode.node("port").getInt(8080),
                apiNode.node("enabled").getBoolean(true),
                apiNode.node("jwt-secret").getString(""),
                apiNode.node("cors-enabled").getBoolean(true),
            )

            val loggingNode = node.node("logging")
            val logging = LoggingDefinition(
                loggingNode.node("level").getString("INFO"),
                loggingNode.node("json-format").getBoolean(false),
                loggingNode.node("loki-url").getString(""),
            )

            // --- Purpur-style sections (all keys optional — defaults come from the data classes) ---
            val chatNode = node.node("chat")
            val channelsNode = chatNode.node("channels")

            val embeddedWorldNode = node.node("embedded-world")
            val embeddedWorldGenerationNode = embeddedWorldNode.node("generation")
            val embeddedWorld = EmbeddedWorldSettingsDefinition(
                embeddedWorldNode.node("enabled").getBoolean(false),
                embeddedWorldNode.node("server-name").getString("prism-world"),
                embeddedWorldNode.node("host").getString("127.0.0.1"),
                embeddedWorldNode.node("port").getInt(25580),
                embeddedWorldNode.node("brand-name").getString("Prism"),
                embeddedWorldNode.node("pool").getString("lobby"),
                embeddedWorldNode.node("spawn-x").getDouble(0.5),
                embeddedWorldNode.node("spawn-y").getDouble(42.0),
                embeddedWorldNode.node("spawn-z").getDouble(0.5),
                embeddedWorldNode.node("forwarding-secret-file").getString("forwarding.secret"),
                EmbeddedWorldGenerationDefinition(
                    embeddedWorldGenerationNode.node("type").getString("flat"),
                    embeddedWorldGenerationNode.node("height").getInt(40),
                ),
            )

            val playersNode = node.node("players")
            val players = PlayersSettingsDefinition(
                playersNode.node("auto-register").getBoolean(true),
                playersNode.node("save-interval-seconds").getInt(300),
                playersNode.node("cache-expiry-minutes").getInt(60),
                playersNode.node("playtime-tracking").getBoolean(true),
                playersNode.node("announce-joins").getBoolean(false),
                playersNode.node("announce-quits").getBoolean(false),
                playersNode.node("first-join-message").getString(),
                playersNode.node("default-locale").getString("en_us"),
            )

            val commandsNode = node.node("commands")
            val commands = CommandsSettingsDefinition(
                commandsNode.node("server-command").getBoolean(true),
                commandsNode.node("glist-command").getBoolean(true),
                stringList(commandsNode.node("admin-aliases"), listOf("p", "proxyadmin", "proxy")),
                commandsNode.node("permission-on-deny-message").getString("You don't have permission to use this command."),
                commandsNode.node("cooldown-seconds").getInt(0),
                stringList(commandsNode.node("blocked-commands"), emptyList()),
                commandsNode.node("blocked-bypass-permission").getString("prism.command.bypass-blocked"),
            )

            val lifecycleNode = node.node("lifecycle")
            val lifecycle = LifecycleSettingsDefinition(
                lifecycleNode.node("drain-timeout-seconds").getInt(60),
                lifecycleNode.node("drain-kick-message").getString("This server is going down for maintenance — you were moved to a fallback server."),
                lifecycleNode.node("restart-cooldown-seconds").getInt(90),
                lifecycleNode.node("auto-restart").getBoolean(false),
                lifecycleNode.node("crash-threshold").getInt(3),
                lifecycleNode.node("crash-restart-delay-seconds").getInt(10),
                lifecycleNode.node("health-wait-timeout-seconds").getInt(300),
                lifecycleNode.node("restart-command").getString(""),
            )

            val syncNode = node.node("sync")
            val sync = SyncSettingsDefinition(
                syncNode.node("enabled").getBoolean(true),
                syncNode.node("announce-health").getBoolean(true),
                syncNode.node("announce-player-switches").getBoolean(true),
                syncNode.node("announce-lifecycle").getBoolean(true),
                syncNode.node("event-queue-size").getInt(1000),
                syncNode.node("connect-timeout-seconds").getInt(5),
                syncNode.node("publish-timeout-seconds").getInt(2),
                syncNode.node("player-data-ttl-minutes").getInt(1440),
            )

            val metricsNode = node.node("metrics")
            val metrics = MetricsSettingsDefinition(
                metricsNode.node("enabled").getBoolean(true),
                metricsNode.node("bind-jvm-metrics").getBoolean(true),
                metricsNode.node("bind-pool-metrics").getBoolean(true),
                metricsNode.node("histogram-max-age-seconds").getInt(600),
                metricsNode.node("push-gateway-url").getString(""),
                metricsNode.node("push-interval-seconds").getInt(60),
            )

            val advancedNode = node.node("advanced")
            val advanced = AdvancedSettingsDefinition(
                advancedNode.node("connection-timeout-millis").getInt(3000),
                advancedNode.node("health-ping-concurrency").getInt(0),
                advancedNode.node("log-health-debug").getBoolean(false),
                advancedNode.node("cache-expiry-minutes").getInt(30),
                advancedNode.node("bungee-plugin-message-channel").getBoolean(true),
                advancedNode.node("allow-spectator-ping").getBoolean(false),
                advancedNode.node("disable-proxy-idle-kick").getBoolean(true),
            )

            val messagesNode = node.node("messages")
            val messages = MessagesSettingsDefinition(
                messagesNode.node("prefix").getString("<gray>[<aqua>Prism</aqua>]</gray> "),
                messagesNode.node("fallback-redirect").getString("<gray>The server you were on is unavailable — moved you to <aqua><server></aqua>.</gray>"),
                messagesNode.node("no-fallback-kick").getString("<red>You were disconnected because the server you were playing on went down, and no fallback server is currently available. Please try again shortly.</red>"),
                messagesNode.node("server-offline").getString("<red>Server <server> is currently offline.</red>"),
                messagesNode.node("no-healthy-servers").getString("<red>No healthy servers in pool <pool> right now.</red>"),
                messagesNode.node("already-connected").getString("<yellow>You are already connected to <server>!</yellow>"),
                messagesNode.node("already-in-pool").getString("<yellow>You are already in pool <pool>!</yellow>"),
                messagesNode.node("connect-success").getString("<green>Connected to <target> → <server></green>"),
                messagesNode.node("connect-failed").getString("<red>Failed to connect to <target>.</red>"),
                messagesNode.node("muted").getString("<red>You are muted!</red>"),
                messagesNode.node("reload-success").getString("<green>Prism config reloaded: <pools> pools, <servers> servers registered.</green>"),
                messagesNode.node("reload-failed").getString("<red>Reload failed: <error>. Previous config kept.</red>"),
            )

            val chatSettings = ChatSettingsDefinition(
                chatNode.node("enabled").getBoolean(true),
                chatNode.node("format").getString("[{prefix}] <{player}> {message}"),
                chatNode.node("default-prefix").getString("Player"),
                chatNode.node("send-to-console").getBoolean(true),
                chatNode.node("history-size").getInt(1000),
                chatNode.node("ignore-mute-permission").getString("prism.chat.bypass-mute"),
                ChatChannelsDefinition(
                    channelsNode.node("enabled").getBoolean(false),
                    channelsNode.node("default-channel").getString("global"),
                    channelsNode.node("cross-proxy").getBoolean(true),
                    stringList(channelsNode.node("list"), listOf("global", "staff")),
                ),
            )

            val configVersion = node.node("config-version").getInt(CURRENT_VERSION)
            return PrismConfig(
                pools = pools,
                fallback = fallback,
                healthCheck = healthCheck,
                database = database,
                redis = redis,
                api = api,
                logging = logging,
                chat = chatSettings,
                embeddedWorld = embeddedWorld,
                players = players,
                commands = commands,
                lifecycle = lifecycle,
                sync = sync,
                metrics = metrics,
                advanced = advanced,
                messages = messages,
                configVersion = configVersion,
            )
        }

        /** Reads a list of strings, falling back to [default] when the node is absent. */
        private fun stringList(node: org.spongepowered.configurate.ConfigurationNode, default: List<String>): List<String> {
            if (node.virtual()) return default
            val items = node.childrenList().mapNotNull { it.getString() }
            return items.ifEmpty { default }
        }

        fun save(config: PrismConfig, file: File) {
            val loader = HoconConfigurationLoader.builder()
                .path(file.toPath())
                .build()
            val node = CommentedConfigurationNode.root(ConfigurationOptions.defaults())
            node.node("config-version").raw(CURRENT_VERSION)

            val poolsNode = node.node("pools")
            for ((index, pool) in config.pools.withIndex()) {
                val poolNode = poolsNode.node(index)
                poolNode.node("name").raw(pool.name)
                poolNode.node("fallback").raw(pool.fallbackPool)
                val serversNode = poolNode.node("servers")
                for ((sIndex, server) in pool.servers.withIndex()) {
                    val serverNode = serversNode.node(sIndex)
                    serverNode.node("name").raw(server.name)
                    serverNode.node("host").raw(server.host)
                    serverNode.node("port").raw(server.port)
                    serverNode.node("weight").raw(server.weight)
                    serverNode.node("fallback").raw(server.fallback)
                    server.motd?.let { serverNode.node("motd").raw(it) }
                }
            }

            val fallbackNode = node.node("fallback")
            for ((index, chain) in config.fallback.chain.withIndex()) {
                fallbackNode.node("chain").node(index).raw(chain)
            }
            fallbackNode.node("default").raw(config.fallback.defaultTarget)

            val healthCheckNode = node.node("health-check")
            healthCheckNode.node("enabled").raw(config.healthCheck.enabled)
            healthCheckNode.node("interval").raw(config.healthCheck.intervalSeconds)
            healthCheckNode.node("timeout").raw(config.healthCheck.timeoutMillis)
            healthCheckNode.node("fail-threshold").raw(config.healthCheck.failThreshold)
            healthCheckNode.node("success-threshold").raw(config.healthCheck.successThreshold)

            val databaseNode = node.node("database")
            databaseNode.node("jdbc-url").raw(config.database.jdbcUrl)
            databaseNode.node("username").raw(config.database.username)
            databaseNode.node("password").raw(config.database.password)
            databaseNode.node("driver").raw(config.database.driver)
            databaseNode.node("max-pool-size").raw(config.database.maxPoolSize)
            databaseNode.node("min-idle").raw(config.database.minIdle)

            val redisNode = node.node("redis")
            redisNode.node("url").raw(config.redis.url)
            redisNode.node("password").raw(config.redis.password)
            redisNode.node("database").raw(config.redis.database)
            redisNode.node("pool-size").raw(config.redis.poolSize)

            val apiNode = node.node("api")
            apiNode.node("host").raw(config.api.host)
            apiNode.node("port").raw(config.api.port)
            apiNode.node("enabled").raw(config.api.enabled)
            apiNode.node("jwt-secret").raw(config.api.jwtSecret)
            apiNode.node("cors-enabled").raw(config.api.corsEnabled)
            apiNode.node("rate-limit-enabled").raw(config.api.rateLimitEnabled)
            apiNode.node("rate-limit-per-minute").raw(config.api.rateLimitPerMinute)

            val loggingNode = node.node("logging")
            loggingNode.node("level").raw(config.logging.level)
            loggingNode.node("json-format").raw(config.logging.jsonFormat)
            loggingNode.node("loki-url").raw(config.logging.lokiUrl)

            val chatNode = node.node("chat")
            chatNode.node("enabled").raw(config.chat.enabled)
            chatNode.node("format").raw(config.chat.format)
            chatNode.node("default-prefix").raw(config.chat.defaultPrefix)
            chatNode.node("send-to-console").raw(config.chat.sendToConsole)
            chatNode.node("history-size").raw(config.chat.historySize)
            chatNode.node("ignore-mute-permission").raw(config.chat.muteBypassPermission)
            val channelsNode = chatNode.node("channels")
            channelsNode.node("enabled").raw(config.chat.channels.enabled)
            channelsNode.node("default-channel").raw(config.chat.channels.defaultChannel)
            channelsNode.node("cross-proxy").raw(config.chat.channels.crossProxy)
            for ((index, channel) in config.chat.channels.list.withIndex()) {
                channelsNode.node("list").node(index).raw(channel)
            }

            val embeddedWorldNode = node.node("embedded-world")
            embeddedWorldNode.node("enabled").raw(config.embeddedWorld.enabled)
            embeddedWorldNode.node("server-name").raw(config.embeddedWorld.serverName)
            embeddedWorldNode.node("host").raw(config.embeddedWorld.host)
            embeddedWorldNode.node("port").raw(config.embeddedWorld.port)
            embeddedWorldNode.node("brand-name").raw(config.embeddedWorld.brandName)
            embeddedWorldNode.node("pool").raw(config.embeddedWorld.pool)
            embeddedWorldNode.node("spawn-x").raw(config.embeddedWorld.spawnX)
            embeddedWorldNode.node("spawn-y").raw(config.embeddedWorld.spawnY)
            embeddedWorldNode.node("spawn-z").raw(config.embeddedWorld.spawnZ)
            embeddedWorldNode.node("forwarding-secret-file").raw(config.embeddedWorld.forwardingSecretFile)
            embeddedWorldNode.node("generation").node("type").raw(config.embeddedWorld.generation.type)
            embeddedWorldNode.node("generation").node("height").raw(config.embeddedWorld.generation.height)

            val playersNode = node.node("players")
            playersNode.node("auto-register").raw(config.players.autoRegister)
            playersNode.node("save-interval-seconds").raw(config.players.saveIntervalSeconds)
            playersNode.node("cache-expiry-minutes").raw(config.players.cacheExpiryMinutes)
            playersNode.node("playtime-tracking").raw(config.players.playtimeTracking)
            playersNode.node("announce-joins").raw(config.players.announceJoins)
            playersNode.node("announce-quits").raw(config.players.announceQuits)
            config.players.firstJoinMessage?.let { playersNode.node("first-join-message").raw(it) }
            playersNode.node("default-locale").raw(config.players.defaultLocale)

            val commandsNode = node.node("commands")
            commandsNode.node("server-command").raw(config.commands.serverCommandEnabled)
            commandsNode.node("glist-command").raw(config.commands.glistCommandEnabled)
            for ((index, alias) in config.commands.adminAliases.withIndex()) {
                commandsNode.node("admin-aliases").node(index).raw(alias)
            }
            commandsNode.node("permission-on-deny-message").raw(config.commands.permissionDenyMessage)
            commandsNode.node("cooldown-seconds").raw(config.commands.commandCooldownSeconds)
            for ((index, blocked) in config.commands.blockedCommands.withIndex()) {
                commandsNode.node("blocked-commands").node(index).raw(blocked)
            }
            commandsNode.node("blocked-bypass-permission").raw(config.commands.blockedBypassPermission)

            val lifecycleNode = node.node("lifecycle")
            lifecycleNode.node("drain-timeout-seconds").raw(config.lifecycle.drainTimeoutSeconds)
            lifecycleNode.node("drain-kick-message").raw(config.lifecycle.drainKickMessage)
            lifecycleNode.node("restart-cooldown-seconds").raw(config.lifecycle.restartCooldownSeconds)
            lifecycleNode.node("auto-restart").raw(config.lifecycle.autoRestart)
            lifecycleNode.node("crash-threshold").raw(config.lifecycle.crashThreshold)
            lifecycleNode.node("crash-restart-delay-seconds").raw(config.lifecycle.crashRestartDelaySeconds)
            lifecycleNode.node("health-wait-timeout-seconds").raw(config.lifecycle.healthWaitTimeoutSeconds)
            lifecycleNode.node("restart-command").raw(config.lifecycle.restartCommand)

            val syncNode = node.node("sync")
            syncNode.node("enabled").raw(config.sync.enabled)
            syncNode.node("announce-health").raw(config.sync.announceHealth)
            syncNode.node("announce-player-switches").raw(config.sync.announcePlayerSwitches)
            syncNode.node("announce-lifecycle").raw(config.sync.announceLifecycle)
            syncNode.node("event-queue-size").raw(config.sync.eventQueueSize)
            syncNode.node("connect-timeout-seconds").raw(config.sync.connectTimeoutSeconds)
            syncNode.node("publish-timeout-seconds").raw(config.sync.publishTimeoutSeconds)
            syncNode.node("player-data-ttl-minutes").raw(config.sync.playerDataTtlMinutes)

            val metricsNode = node.node("metrics")
            metricsNode.node("enabled").raw(config.metrics.enabled)
            metricsNode.node("bind-jvm-metrics").raw(config.metrics.bindJvmMetrics)
            metricsNode.node("bind-pool-metrics").raw(config.metrics.bindPoolMetrics)
            metricsNode.node("histogram-max-age-seconds").raw(config.metrics.histogramMaxAgeSeconds)
            metricsNode.node("push-gateway-url").raw(config.metrics.pushGatewayUrl)
            metricsNode.node("push-interval-seconds").raw(config.metrics.pushIntervalSeconds)

            val advancedNode = node.node("advanced")
            advancedNode.node("connection-timeout-millis").raw(config.advanced.connectionTimeoutMillis)
            advancedNode.node("health-ping-concurrency").raw(config.advanced.healthPingConcurrency)
            advancedNode.node("log-health-debug").raw(config.advanced.logHealthDebug)
            advancedNode.node("cache-expiry-minutes").raw(config.advanced.cacheExpiryMinutes)
            advancedNode.node("bungee-plugin-message-channel").raw(config.advanced.bungeePluginMessageChannel)
            advancedNode.node("allow-spectator-ping").raw(config.advanced.allowSpectatorPing)
            advancedNode.node("disable-proxy-idle-kick").raw(config.advanced.disableProxyIdleKick)

            val messagesNode = node.node("messages")
            messagesNode.node("prefix").raw(config.messages.prefix)
            messagesNode.node("fallback-redirect").raw(config.messages.fallbackRedirect)
            messagesNode.node("no-fallback-kick").raw(config.messages.noFallbackKick)
            messagesNode.node("server-offline").raw(config.messages.serverOffline)
            messagesNode.node("no-healthy-servers").raw(config.messages.noHealthyServers)
            messagesNode.node("already-connected").raw(config.messages.alreadyConnected)
            messagesNode.node("already-in-pool").raw(config.messages.alreadyInPool)
            messagesNode.node("connect-success").raw(config.messages.connectSuccess)
            messagesNode.node("connect-failed").raw(config.messages.connectFailed)
            messagesNode.node("muted").raw(config.messages.muted)
            messagesNode.node("reload-success").raw(config.messages.reloadSuccess)
            messagesNode.node("reload-failed").raw(config.messages.reloadFailed)

            loader.save(node)
        }
    }
}