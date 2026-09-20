package net.zld.prism.config

import org.spongepowered.configurate.objectmapping.meta.Setting

// ---------------------------------------------------------------------------
// Purpur-style granular config sections for Prism.
// Every subsystem gets its own deep section with sensible defaults, so server
// owners can tune Prism the same way they tune Paper/Purpur.
// ---------------------------------------------------------------------------

/** Global defaults applied to every pool (per-pool values override these). */
data class DefaultsDefinition(
    @Setting("weight") val weight: Int = 1,
    @Setting("fallback") val fallback: Boolean = false,
    @Setting("motd") val motd: String? = null,
)

/** settings.section.* — chat handling on the proxy. */
data class ChatSettingsDefinition(
    @Setting("enabled") val enabled: Boolean = true,
    @Setting("format") val format: String = "[{prefix}] <{player}> {message}",
    @Setting("default-prefix") val defaultPrefix: String = "Player",
    @Setting("send-to-console") val sendToConsole: Boolean = true,
    @Setting("history-size") val historySize: Int = 1000,
    @Setting("ignore-mute-permission") val muteBypassPermission: String = "prism.chat.bypass-mute",
    @Setting("channels") val channels: ChatChannelsDefinition = ChatChannelsDefinition(),
)

data class ChatChannelsDefinition(
    @Setting("enabled") val enabled: Boolean = false,
    @Setting("default-channel") val defaultChannel: String = "global",
    @Setting("cross-proxy") val crossProxy: Boolean = true,
    @Setting("list") val list: List<String> = listOf("global", "staff"),
)

/** settings.section.* — player behaviour on the proxy. */
data class PlayersSettingsDefinition(
    @Setting("auto-register") val autoRegister: Boolean = true,
    @Setting("save-interval-seconds") val saveIntervalSeconds: Int = 300,
    @Setting("cache-expiry-minutes") val cacheExpiryMinutes: Int = 60,
    @Setting("playtime-tracking") val playtimeTracking: Boolean = true,
    @Setting("announce-joins") val announceJoins: Boolean = false,
    @Setting("announce-quits") val announceQuits: Boolean = false,
    @Setting("first-join-message") val firstJoinMessage: String? = null,
    @Setting("default-locale") val defaultLocale: String = "en_us",
)

/** settings.section.* — command registration and UX. */
data class CommandsSettingsDefinition(
    @Setting("server-command") val serverCommandEnabled: Boolean = true,
    @Setting("glist-command") val glistCommandEnabled: Boolean = true,
    @Setting("admin-aliases") val adminAliases: List<String> = listOf("p", "proxyadmin", "proxy"),
    @Setting("permission-on-deny-message") val permissionDenyMessage: String = "You don't have permission to use this command.",
    @Setting("cooldown-seconds") val commandCooldownSeconds: Int = 0,
    @Setting("blocked-commands") val blockedCommands: List<String> = emptyList(),
    @Setting("blocked-bypass-permission") val blockedBypassPermission: String = "prism.command.bypass-blocked",
)

/** settings.section.* — backend server lifecycle (drain / restart / crash). */
data class LifecycleSettingsDefinition(
    @Setting("drain-timeout-seconds") val drainTimeoutSeconds: Int = 60,
    @Setting("drain-kick-message") val drainKickMessage: String = "This server is going down for maintenance — you were moved to a fallback server.",
    @Setting("restart-cooldown-seconds") val restartCooldownSeconds: Int = 90,
    @Setting("auto-restart") val autoRestart: Boolean = false,
    @Setting("crash-threshold") val crashThreshold: Int = 3,
    @Setting("crash-restart-delay-seconds") val crashRestartDelaySeconds: Int = 10,
    @Setting("health-wait-timeout-seconds") val healthWaitTimeoutSeconds: Int = 300,
    @Setting("restart-command") val restartCommand: String = "",
)

/** settings.section.* — cross-proxy sync over Redis. */
data class SyncSettingsDefinition(
    @Setting("enabled") val enabled: Boolean = true,
    @Setting("announce-health") val announceHealth: Boolean = true,
    @Setting("announce-player-switches") val announcePlayerSwitches: Boolean = true,
    @Setting("announce-lifecycle") val announceLifecycle: Boolean = true,
    @Setting("event-queue-size") val eventQueueSize: Int = 1000,
    @Setting("connect-timeout-seconds") val connectTimeoutSeconds: Int = 5,
    @Setting("publish-timeout-seconds") val publishTimeoutSeconds: Int = 2,
    @Setting("player-data-ttl-minutes") val playerDataTtlMinutes: Int = 1440,
)

/** settings.section.* — metrics exposure. */
data class MetricsSettingsDefinition(
    @Setting("enabled") val enabled: Boolean = true,
    @Setting("bind-jvm-metrics") val bindJvmMetrics: Boolean = true,
    @Setting("bind-pool-metrics") val bindPoolMetrics: Boolean = true,
    @Setting("histogram-max-age-seconds") val histogramMaxAgeSeconds: Int = 600,
    @Setting("push-gateway-url") val pushGatewayUrl: String = "",
    @Setting("push-interval-seconds") val pushIntervalSeconds: Int = 60,
)

/** settings.section.* — low-level engine toggles. */
data class AdvancedSettingsDefinition(
    @Setting("connection-timeout-millis") val connectionTimeoutMillis: Int = 3000,
    @Setting("health-ping-concurrency") val healthPingConcurrency: Int = 0,
    @Setting("log-health-debug") val logHealthDebug: Boolean = false,
    @Setting("cache-expiry-minutes") val cacheExpiryMinutes: Int = 30,
    @Setting("bungee-plugin-message-channel") val bungeePluginMessageChannel: Boolean = true,
    @Setting("allow-spectator-ping") val allowSpectatorPing: Boolean = false,
    @Setting("disable-proxy-idle-kick") val disableProxyIdleKick: Boolean = true,
)

/** messages.* — every player-facing string, MiniMessage-formattable. */
data class MessagesSettingsDefinition(
    @Setting("prefix") val prefix: String = "<gray>[<aqua>Prism</aqua>]</gray> ",
    @Setting("fallback-redirect") val fallbackRedirect: String = "<gray>The server you were on is unavailable — moved you to <aqua><server></aqua>.</gray>",
    @Setting("no-fallback-kick") val noFallbackKick: String = "<red>You were disconnected because the server you were playing on went down, and no fallback server is currently available. Please try again shortly.</red>",
    @Setting("server-offline") val serverOffline: String = "<red>Server <server> is currently offline.</red>",
    @Setting("no-healthy-servers") val noHealthyServers: String = "<red>No healthy servers in pool <pool> right now.</red>",
    @Setting("already-connected") val alreadyConnected: String = "<yellow>You are already connected to <server>!</yellow>",
    @Setting("already-in-pool") val alreadyInPool: String = "<yellow>You are already in pool <pool>!</yellow>",
    @Setting("connect-success") val connectSuccess: String = "<green>Connected to <target> → <server></green>",
    @Setting("connect-failed") val connectFailed: String = "<red>Failed to connect to <target>.</red>",
    @Setting("muted") val muted: String = "<red>You are muted!</red>",
    @Setting("reload-success") val reloadSuccess: String = "<green>Prism config reloaded: <pools> pools, <servers> servers registered.</green>",
    @Setting("reload-failed") val reloadFailed: String = "<red>Reload failed: <error>. Previous config kept.</red>",
)
