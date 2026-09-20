package net.zld.prism.command

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.zld.prism.PrismPlugin

/**
 * /glist [pool]
 *
 * Lists players grouped by pool — the proxy-wide equivalent of BungeeCord's
 * /glist. Without arguments it shows every pool with its servers and players;
 * with a pool (or server) name it shows just that pool's players, including
 * players connected directly to a specific server inside it.
 */
class GListCommand(private val plugin: PrismPlugin) : SimpleCommand {

    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        val args = invocation.arguments()

        if (args.isNotEmpty()) {
            val query = args[0]
            val pool = plugin.getAllPools().firstOrNull { it.name.equals(query, ignoreCase = true) }
            if (pool == null) {
                // Also accept a direct server name for convenience
                val server = plugin.proxy.getServer(query).orElse(null)
                if (server == null) {
                    source.sendMessage(Component.text("Unknown pool or server: $query", NamedTextColor.RED))
                    return
                }
                val players = server.playersConnected
                source.sendMessage(header(server.serverInfo.name, players.size))
                players.forEach { source.sendMessage(playerLine(it.username, it.currentServer.map { c -> c.serverInfo.name }.orElse("?"))) }
                return
            }

            source.sendMessage(header("pool '${pool.name}'", pool.getAllServers().sumOf { it.playersConnected.size }))
            for (rs in pool.getAllServers()) {
                val name = rs.serverInfo.name
                val players = rs.playersConnected
                source.sendMessage(
                    Component.text("  $name ", NamedTextColor.AQUA)
                        .append(Component.text("(${players.size})", NamedTextColor.GRAY))
                )
                players.forEach { source.sendMessage(playerLine(it.username, name)) }
            }
            return
        }

        // Overview: all pools, then any servers not in a pool
        var total = 0
        source.sendMessage(Component.text("=== Players per pool ===", NamedTextColor.AQUA))
        for (pool in plugin.getAllPools()) {
            val servers = pool.getAllServers()
            val poolPlayers = servers.sumOf { it.playersConnected.size }
            total += poolPlayers
            val color = when {
                poolPlayers > 0 -> NamedTextColor.GREEN
                else -> NamedTextColor.GRAY
            }
            source.sendMessage(
                Component.text("${pool.name} ", color)
                    .append(Component.text("($poolPlayers)", NamedTextColor.GRAY))
                    .append(Component.text(" — ${servers.joinToString(", ") { it.serverInfo.name }}", NamedTextColor.DARK_GRAY))
            )
        }

        // Servers not managed by any pool (e.g. registered by velocity.toml)
        val orphans = plugin.proxy.allServers.filter { plugin.getPoolNameForServer(it) == null }
        val orphanPlayers = orphans.sumOf { it.playersConnected.size }
        if (orphans.isNotEmpty()) {
            total += orphanPlayers
            source.sendMessage(
                Component.text("(unassigned) ", NamedTextColor.YELLOW)
                    .append(Component.text("($orphanPlayers)", NamedTextColor.GRAY))
                    .append(Component.text(" — ${orphans.joinToString(", ") { it.serverInfo.name }}", NamedTextColor.DARK_GRAY))
            )
        }

        source.sendMessage(Component.text("Total players online: $total", NamedTextColor.AQUA))
    }

    private fun header(what: String, count: Int): Component =
        Component.text("=== Players in $what ($count) ===", NamedTextColor.AQUA)

    private fun playerLine(username: String, server: String): Component =
        Component.text("  • $username ", NamedTextColor.WHITE)
            .append(Component.text("($server)", NamedTextColor.GRAY))

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
        val args = invocation.arguments()
        if (args.size > 1) return emptyList()
        val partial = args.getOrElse(0) { "" }.lowercase()
        return buildList {
            plugin.getAllPools().forEach { if (it.name.lowercase().startsWith(partial)) add(it.name) }
            plugin.proxy.allServers.forEach {
                if (it.serverInfo.name.lowercase().startsWith(partial)) add(it.serverInfo.name)
            }
        }
    }

    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean =
        invocation.source().hasPermission("prism.command.glist")
}
