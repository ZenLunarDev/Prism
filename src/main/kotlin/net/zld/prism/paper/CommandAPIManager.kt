package net.zld.prism.paper

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandManager
import com.velocitypowered.api.command.CommandMeta
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.zld.prism.PrismPlugin
import net.zld.prism.sync.CrossProxySyncManager
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

class CommandAPIManager(
    private val plugin: PrismPlugin,
    private val commandManager: CommandManager,
    private val syncManager: CrossProxySyncManager,
) {
    private val logger = LoggerFactory.getLogger(CommandAPIManager::class.java)
    private val dispatcher = CommandDispatcher<CommandSource>()
    private val serializer = GsonComponentSerializer.gson()
    private val commands = mutableMapOf<String, CommandRegistration>()

    data class CommandRegistration(
        val name: String,
        val aliases: List<String>,
        val permission: String?,
        val builder: LiteralArgumentBuilder<CommandSource>,
    )

    fun registerCommand(
        name: String,
        aliases: List<String> = emptyList(),
        permission: String? = null,
        builder: LiteralArgumentBuilder<CommandSource>.() -> Unit
    ) {
        val root = LiteralArgumentBuilder.literal<CommandSource>(name)
        root.requires { src -> permission == null || src.hasPermission(permission) }
        builder(root)
        root.build()

        val allAliases = listOf(name) + aliases
        val registration = CommandRegistration(name, aliases, permission, root)
        commands[name] = registration

        val meta = commandManager.metaBuilder(name).aliases(*aliases.toTypedArray()).plugin(plugin).build()
        commandManager.register(meta, BrigadierCommand(root))
        logger.info("Registered command: {} (aliases: {})", name, aliases.joinToString(", "))
    }

    fun registerPlayerCommand(
        name: String,
        aliases: List<String> = emptyList(),
        permission: String? = "prism.command.$name",
        builder: RequiredArgumentBuilder<CommandSource, Player>.() -> Unit
    ) {
        registerCommand(name, aliases, permission) {
            requires { it is Player }
        }
    }

    fun unregisterCommand(name: String) {
        commands.remove(name)?.aliases?.forEach { commandManager.unregister(it) }
        commandManager.unregister(name)
    }

    fun getCommand(name: String): CommandRegistration? = commands[name]

    fun getAllCommands(): List<CommandRegistration> = commands.values.toList()

    fun executeAsync(source: CommandSource, commandLine: String): CompletableFuture<Boolean> {
        return commandManager.executeAsync(source, commandLine)
    }

    // Helper builders
    fun literal(name: String): LiteralArgumentBuilder<CommandSource> = LiteralArgumentBuilder.literal(name)
    fun <T> argument(name: String, type: com.mojang.brigadier.arguments.ArgumentType<T>): RequiredArgumentBuilder<CommandSource, T> =
        RequiredArgumentBuilder.argument(name, type)

    // Common exceptions
    companion object {
        // Brigadier's SimpleCommandExceptionType expects a raw Message
        private fun msg(text: String): com.mojang.brigadier.Message = com.mojang.brigadier.LiteralMessage(text)

        val NO_PERMISSION = SimpleCommandExceptionType(msg("You don't have permission!"))
        val PLAYER_ONLY = SimpleCommandExceptionType(msg("Only players can use this command!"))
        val SERVER_NOT_FOUND = SimpleCommandExceptionType(msg("Server not found!"))
        val PLAYER_NOT_FOUND = SimpleCommandExceptionType(msg("Player not found!"))
    }
}