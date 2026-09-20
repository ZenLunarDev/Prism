package net.zld.prism.command

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.zld.prism.PrismPlugin
import net.zld.prism.server.ServerPool

/**
 * /server <pool|server>
 *
 * Accepts either a pool name (weighted selection among healthy servers) or a
 * direct server name (rejected when health checks marked it offline — with a
 * suggested alternative instead of a doomed connection attempt).
 *
 * All user-facing messages are Adventure components; the fallback redirect is
 * left to the KickedFromServerEvent handler, so this command does not duplicate it.
 */
class ServerCommand(private val plugin: PrismPlugin) : SimpleCommand {

    private val cooldowns = java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long>()

    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        val args = invocation.arguments()

        if (args.isEmpty()) {
            sendUsage(source)
            return
        }
        if (source !is Player) {
            source.sendMessage(Component.text("Only players can use this command!", NamedTextColor.RED))
            return
        }

        val query = args[0]
        val player = source
        val currentServer = player.currentServer.orElse(null)

        val messages = plugin.getConfig().messages
        val mini = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()

        // Per-player cooldown on /server (commands.cooldown-seconds, 0 = disabled)
        val commandsConfig = plugin.getConfig().commands
        if (commandsConfig.commandCooldownSeconds > 0) {
            val now = System.currentTimeMillis()
            val last = cooldowns[player.uniqueId] ?: 0L
            if (now - last < commandsConfig.commandCooldownSeconds * 1000L) {
                val remaining = ((commandsConfig.commandCooldownSeconds * 1000L - (now - last)) / 1000L).toInt() + 1
                player.sendMessage(Component.text("Please wait ${remaining}s before using /server again.", NamedTextColor.YELLOW))
                return
            }
            cooldowns[player.uniqueId] = now
        }

        // --- Pool target: weighted selection among healthy servers ---
        val pool = plugin.getAllPools().firstOrNull { it.name.equals(query, ignoreCase = true) }
        if (pool != null) {
            val currentPool: String? = currentServer?.server?.let { plugin.getPoolNameForServer(it) }
            if (currentPool == pool.name) {
                source.sendMessage(mini.deserialize(messages.alreadyInPool.replace("<pool>", pool.name)))
                return
            }

            val target = pool.selectServer(exclude = setOfNotNull(currentServer?.serverInfo?.name))
            if (target == null) {
                source.sendMessage(
                    mini.deserialize(messages.noHealthyServers.replace("<pool>", pool.name))
                        .append(Component.newline())
                        .append(Component.text("Tip: try /server <server> or another pool.", NamedTextColor.GRAY))
                )
                return
            }
            connect(player, target, "pool '${pool.name}'")
            return
        }

        // --- Direct server target ---
        val registered = plugin.proxy.getServer(query).orElse(null)
        if (registered == null) {
            source.sendMessage(Component.text("Unknown pool or server: $query", NamedTextColor.RED))
            sendUsage(source)
            return
        }

        if (currentServer != null && currentServer.serverInfo.name == registered.serverInfo.name) {
            source.sendMessage(mini.deserialize(messages.alreadyConnected.replace("<server>", registered.serverInfo.name)))
            return
        }

        // Health-aware: never attempt a server that health checks marked offline
        val poolName = plugin.getPoolNameForServer(registered)
        val owningPool = poolName?.let { plugin.getPool(it) }
        val health = owningPool?.getHealth(registered.serverInfo.name)
        if (health?.healthy == false) {
            val alternative = owningPool.selectServer(exclude = setOf(registered.serverInfo.name))
            if (alternative != null) {
                source.sendMessage(
                    mini.deserialize(messages.serverOffline.replace("<server>", registered.serverInfo.name))
                        .append(Component.newline())
                        .append(Component.text("Sending you to '${alternative.serverInfo.name}' instead...", NamedTextColor.GRAY))
                )
                connect(player, alternative, "fallback of '${registered.serverInfo.name}'")
            } else {
                source.sendMessage(
                    Component.text("Server '${registered.serverInfo.name}' is offline and no alternative is healthy right now.", NamedTextColor.RED)
                )
            }
            return
        }

        connect(player, registered, "server '${registered.serverInfo.name}'")
    }

    private fun connect(player: Player, target: com.velocitypowered.api.proxy.server.RegisteredServer, description: String) {
        val messages = plugin.getConfig().messages
        val mini = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
        player.createConnectionRequest(target).connect().thenAccept { result ->
            if (result.isSuccessful) {
                player.sendMessage(
                    mini.deserialize(
                        messages.connectSuccess
                            .replace("<target>", description)
                            .replace("<server>", target.serverInfo.name)
                    )
                )
            } else {
                val reason = result.reasonComponent.orElse(null)
                player.sendMessage(
                    mini.deserialize(messages.connectFailed.replace("<target>", description))
                        .append(
                            if (reason != null)
                                Component.newline().append(Component.text("Reason: ", NamedTextColor.GRAY))
                                    .append(reason)
                            else Component.empty()
                        )
                )
            }
        }
    }

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
        val args = invocation.arguments()
        if (args.size > 1) return emptyList()
        val partial = args.getOrElse(0) { "" }.lowercase()

        val suggestions = mutableListOf<String>()
        for (pool in plugin.getAllPools()) {
            if (pool.name.lowercase().startsWith(partial)) suggestions.add(pool.name)
        }
        for (server in plugin.proxy.allServers) {
            val name = server.serverInfo.name
            if (name.lowercase().startsWith(partial)) suggestions.add(name)
        }
        return suggestions
    }

    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean =
        invocation.source().hasPermission("prism.command.server")

    private fun sendUsage(source: CommandSource) {
        val pools = plugin.getAllPools().joinToString(", ") { pool ->
            "${pool.name} (${pool.healthyCount()}/${pool.size()} healthy)"
        }
        source.sendMessage(Component.text("Usage: /server <pool|server>", NamedTextColor.YELLOW))
        source.sendMessage(Component.text("Pools: $pools", NamedTextColor.GRAY))
    }
}
