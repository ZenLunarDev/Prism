package net.zld.prism.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for the Purpur-style granular config sections:
 * chat / players / commands / lifecycle / sync / metrics / advanced / messages.
 */
class PrismConfigSectionsTest {

    @TempDir
    lateinit var tempDir: File

    private fun fileWith(content: String): File {
        val f = File(tempDir, "test-${System.nanoTime()}.conf")
        f.writeText(content)
        return f
    }

    // ------------------------------------------------------------------ defaults

    @Test
    fun `all purpur sections have sensible defaults`() {
        val config = PrismConfig()

        assertEquals(true, config.chat.enabled)
        assertEquals("[{prefix}] <{player}> {message}", config.chat.format)
        assertEquals(1000, config.chat.historySize)
        assertEquals(false, config.chat.channels.enabled)

        assertEquals(true, config.players.autoRegister)
        assertEquals(300, config.players.saveIntervalSeconds)
        assertEquals("en_us", config.players.defaultLocale)

        assertEquals(listOf("p", "proxyadmin", "proxy"), config.commands.adminAliases)
        assertEquals(0, config.commands.commandCooldownSeconds)

        assertEquals(60, config.lifecycle.drainTimeoutSeconds)
        assertEquals(false, config.lifecycle.autoRestart)

        assertEquals(true, config.sync.enabled)
        assertEquals(1000, config.sync.eventQueueSize)

        assertEquals(true, config.metrics.enabled)
        assertEquals(60, config.metrics.pushIntervalSeconds)

        assertEquals(3000, config.advanced.connectionTimeoutMillis)
        assertEquals(0, config.advanced.healthPingConcurrency)

        assertTrue(config.messages.prefix.contains("Prism"))
        assertTrue(config.messages.fallbackRedirect.contains("<server>"))
    }

    // ------------------------------------------------------------------ parse

    @Test
    fun `partial sections are filled with defaults`() {
        val file = fileWith(
            """
            config-version = 3
            chat = {
                enabled = false
                history-size = 50
            }
            commands = {
                admin-aliases = ["adm"]
                cooldown-seconds = 5
            }
            """.trimIndent()
        )

        val config = PrismConfig.load(file)

        // overridden values
        assertEquals(false, config.chat.enabled)
        assertEquals(50, config.chat.historySize)
        assertEquals(listOf("adm"), config.commands.adminAliases)
        assertEquals(5, config.commands.commandCooldownSeconds)
        // untouched keys fall back to defaults
        assertEquals("[{prefix}] <{player}> {message}", config.chat.format)
        assertEquals("prism.command.bypass-blocked", config.commands.blockedBypassPermission)
        assertEquals(300, config.players.saveIntervalSeconds)
        assertEquals(true, config.sync.enabled)
    }

    @Test
    fun `list keys round-trip through parse`() {
        val file = fileWith(
            """
            config-version = 3
            chat = {
                channels = {
                    enabled = true
                    list = ["global", "staff", "vip"]
                }
            }
            commands = {
                blocked-commands = ["plugins", "version"]
            }
            """.trimIndent()
        )

        val config = PrismConfig.load(file)

        assertEquals(true, config.chat.channels.enabled)
        assertEquals(listOf("global", "staff", "vip"), config.chat.channels.list)
        assertEquals(listOf("plugins", "version"), config.commands.blockedCommands)
    }

    // ------------------------------------------------------------------ round-trip

    @Test
    fun `purpur sections survive save and load`() {
        val config = PrismConfig(
            chat = ChatSettingsDefinition(
                enabled = false,
                format = "<green>{player}</green>: {message}",
                defaultPrefix = "Member",
                sendToConsole = false,
                historySize = 42,
                muteBypassPermission = "custom.bypass",
                channels = ChatChannelsDefinition(
                    enabled = true,
                    defaultChannel = "vip",
                    crossProxy = false,
                    list = listOf("vip"),
                ),
            ),
            players = PlayersSettingsDefinition(
                autoRegister = false,
                saveIntervalSeconds = 120,
                cacheExpiryMinutes = 15,
                playtimeTracking = false,
                announceJoins = true,
                announceQuits = true,
                firstJoinMessage = "<gold>hi <player></gold>",
                defaultLocale = "th_th",
            ),
            commands = CommandsSettingsDefinition(
                serverCommandEnabled = false,
                glistCommandEnabled = false,
                adminAliases = listOf("adm"),
                permissionDenyMessage = "denied!",
                commandCooldownSeconds = 10,
                blockedCommands = listOf("plugins"),
                blockedBypassPermission = "custom.bypass.cmd",
            ),
            lifecycle = LifecycleSettingsDefinition(
                drainTimeoutSeconds = 30,
                drainKickMessage = "draining",
                restartCooldownSeconds = 45,
                autoRestart = true,
                crashThreshold = 5,
                crashRestartDelaySeconds = 20,
                healthWaitTimeoutSeconds = 100,
            ),
            sync = SyncSettingsDefinition(
                enabled = false,
                announceHealth = false,
                announcePlayerSwitches = false,
                announceLifecycle = false,
                eventQueueSize = 500,
                connectTimeoutSeconds = 3,
                publishTimeoutSeconds = 1,
                playerDataTtlMinutes = 60,
            ),
            metrics = MetricsSettingsDefinition(
                enabled = false,
                bindJvmMetrics = false,
                bindPoolMetrics = false,
                histogramMaxAgeSeconds = 120,
                pushGatewayUrl = "http://localhost:9091",
                pushIntervalSeconds = 30,
            ),
            advanced = AdvancedSettingsDefinition(
                connectionTimeoutMillis = 1500,
                healthPingConcurrency = 4,
                logHealthDebug = true,
                cacheExpiryMinutes = 10,
                bungeePluginMessageChannel = false,
                allowSpectatorPing = true,
                disableProxyIdleKick = false,
            ),
            messages = MessagesSettingsDefinition(
                prefix = "<red>[X]</red> ",
                fallbackRedirect = "moved to <server>",
                noFallbackKick = "bye",
                serverOffline = "offline <server>",
                noHealthyServers = "no pool <pool>",
                alreadyConnected = "already <server>",
                alreadyInPool = "already pool <pool>",
                connectSuccess = "went to <server>",
                connectFailed = "failed <target>",
                muted = "muted",
                reloadSuccess = "ok <pools>/<servers>",
                reloadFailed = "bad <error>",
            ),
        )

        val file = File(tempDir, "roundtrip.conf")
        PrismConfig.save(config, file)
        val loaded = PrismConfig.load(file)

        // chat
        assertEquals(config.chat, loaded.chat)
        // players
        assertEquals(config.players, loaded.players)
        // commands
        assertEquals(config.commands, loaded.commands)
        // lifecycle
        assertEquals(config.lifecycle, loaded.lifecycle)
        // sync
        assertEquals(config.sync, loaded.sync)
        // metrics
        assertEquals(config.metrics, loaded.metrics)
        // advanced
        assertEquals(config.advanced, loaded.advanced)
        // messages
        assertEquals(config.messages, loaded.messages)
    }

    // ------------------------------------------------------------------ validation

    @Test
    fun `validation catches invalid purpur section values`() {
        val config = PrismConfig(
            sync = SyncSettingsDefinition(eventQueueSize = 0),
            metrics = MetricsSettingsDefinition(pushGatewayUrl = "http://pg:9091", pushIntervalSeconds = 2),
            advanced = AdvancedSettingsDefinition(healthPingConcurrency = -1),
            api = ApiDefinition(rateLimitEnabled = true, rateLimitPerMinute = 0),
        )

        val (errors, _) = PrismConfig.validate(config)

        assertTrue(errors.any { it.contains("event-queue-size") })
        assertTrue(errors.any { it.contains("push-interval-seconds") })
        assertTrue(errors.any { it.contains("health-ping-concurrency") })
        assertTrue(errors.any { it.contains("rate-limit-per-minute") })
    }

    @Test
    fun `valid purpur sections produce no section errors`() {
        val (errors, _) = PrismConfig.validate(PrismConfig())
        assertFalse(errors.any { it.contains("chat.") || it.contains("sync.") || it.contains("advanced.") })
    }

    // ------------------------------------------------------------------ bundled resource

    @Test
    fun `bundled default prism conf parses with purpur sections`() {
        val stream = javaClass.classLoader.getResourceAsStream("prism.conf")
            ?: error("prism.conf resource missing from test classpath")
        val file = File(tempDir, "bundled.conf")
        stream.use { file.writeBytes(it.readBytes()) }

        val report = PrismConfig.loadWithReport(file)

        assertTrue(report.valid, "bundled config should be valid: ${report.errors}")
        assertEquals(3, report.config.configVersion)
        assertEquals(true, report.config.chat.enabled)
        assertEquals(true, report.config.api.rateLimitEnabled)
        assertEquals(120, report.config.api.rateLimitPerMinute)
        assertEquals(listOf("p", "proxyadmin", "proxy"), report.config.commands.adminAliases)
        assertEquals(true, report.config.sync.announceHealth)
        assertTrue(report.config.messages.fallbackRedirect.contains("<server>"))
    }

    // ------------------------------------------------------------------ migration

    @Test
    fun `v2 config migrates to v3 without losing values`() {
        val file = fileWith(
            """
            config-version = 2
            chat = {
                history-size = 77
            }
            """.trimIndent()
        )

        val report = PrismConfig.loadWithReport(file)

        assertEquals(3, report.config.configVersion)
        assertEquals(77, report.config.chat.historySize)
        assertTrue(report.migrationsApplied.isNotEmpty(), "migration should be recorded")
        // on-disk file is upgraded too
        assertEquals(3, PrismConfig.load(file).configVersion)
    }
}
