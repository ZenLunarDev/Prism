package net.zld.prism.world

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.CommandSender
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.CommandContext
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import java.util.Locale

/**
 * In-game commands for Prism's built-in world, handled by Minestom:
 * /gamemode, /tp, /setblock. Registered on the Minestom command manager so
 * they only exist while the embedded world is running.
 */
object EmbeddedWorldCommands {

    private val gamemodeArg = ArgumentType.Enum("mode", GameMode::class.java)
    private val xArg = ArgumentType.Double("x")
    private val yArg = ArgumentType.Double("y")
    private val zArg = ArgumentType.Double("z")
    private val targetArg = ArgumentType.Word("target")
    private val blockArg = ArgumentType.String("block")

    fun registerAll() {
        val manager = net.minestom.server.MinecraftServer.getCommandManager()
        manager.register(gamemodeCommand())
        manager.register(teleportCommand())
        manager.register(setBlockCommand())
    }

    // ------------------------------------------------------------------ /gamemode

    private fun gamemodeCommand(): Command {
        val cmd = Command("gamemode")
        cmd.addSyntax({ sender, ctx -> setGamemode(sender, ctx.get(gamemodeArg)) }, gamemodeArg)
        return cmd
    }

    private fun setGamemode(sender: CommandSender, mode: GameMode) {
        val player = sender.asPlayer() ?: return
        if (player.setGameMode(mode)) {
            player.sendMessage(
                Component.text("Gamemode set to ", NamedTextColor.GREEN)
                    .append(Component.text(mode.name.lowercase(Locale.ROOT), NamedTextColor.YELLOW))
            )
        } else {
            player.sendMessage(Component.text("Failed to set gamemode", NamedTextColor.RED))
        }
    }

    // ------------------------------------------------------------------ /tp

    private fun teleportCommand(): Command {
        val cmd = Command("tp")
        // /tp <x> <y> <z>
        cmd.addSyntax({ sender, ctx ->
            val player = sender.asPlayer() ?: return@addSyntax
            val x = ctx.get(xArg); val y = ctx.get(yArg); val z = ctx.get(zArg)
            teleport(player, Pos(x, y, z))
        }, xArg, yArg, zArg)
        // /tp <player>
        cmd.addSyntax({ sender, ctx ->
            val player = sender.asPlayer() ?: return@addSyntax
            val name = ctx.get(targetArg)
            val target = findPlayer(name)
            if (target != null && target !== player) {
                teleport(player, target.position)
                player.sendMessage(
                    Component.text("Teleported to ", NamedTextColor.GREEN)
                        .append(Component.text(target.username, NamedTextColor.YELLOW))
                )
            } else {
                player.sendMessage(
                    Component.text("Player ", NamedTextColor.RED)
                        .append(Component.text(name, NamedTextColor.YELLOW))
                        .append(Component.text(" not found", NamedTextColor.RED))
                )
            }
        }, targetArg)
        return cmd
    }

    private fun teleport(player: Player, pos: Pos) {
        player.teleport(pos).thenAccept {
            player.sendMessage(
                Component.text("Teleported to ", NamedTextColor.GREEN)
                    .append(Component.text("%.1f, %.1f, %.1f".format(pos.x, pos.y, pos.z), NamedTextColor.YELLOW))
            )
        }
    }

    // ------------------------------------------------------------------ /setblock

    private fun setBlockCommand(): Command {
        val cmd = Command("setblock")
        cmd.addSyntax({ sender, ctx ->
            val player = sender.asPlayer() ?: return@addSyntax
            val inst: Instance = player.instance ?: return@addSyntax
            val x = ctx.get(xArg).toInt()
            val y = ctx.get(yArg).toInt()
            val z = ctx.get(zArg).toInt()
            val raw = ctx.get(blockArg).removePrefix("minecraft:")
            val block = Block.fromKey(raw) ?: run {
                player.sendMessage(
                    Component.text("Unknown block: ", NamedTextColor.RED)
                        .append(Component.text(raw, NamedTextColor.YELLOW))
                )
                return@addSyntax
            }
            inst.setBlock(x, y, z, block)
            player.sendMessage(
                Component.text("Placed ", NamedTextColor.GREEN)
                    .append(Component.text(raw, NamedTextColor.YELLOW))
                    .append(Component.text(" at ", NamedTextColor.GREEN))
                    .append(Component.text("$x, $y, $z", NamedTextColor.YELLOW))
            )
        }, xArg, yArg, zArg, blockArg)
        return cmd
    }

    // ------------------------------------------------------------------ helpers

    private fun CommandSender.asPlayer(): Player? {
        if (this is Player) return this
        sendMessage(Component.text("Only players can use this command", NamedTextColor.RED))
        return null
    }

    private fun findPlayer(name: String): Player? {
        return net.minestom.server.MinecraftServer.getConnectionManager()
            .onlinePlayers
            .firstOrNull { it.username.equals(name, ignoreCase = true) }
    }
}
