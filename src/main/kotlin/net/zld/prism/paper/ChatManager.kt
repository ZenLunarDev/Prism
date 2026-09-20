package net.zld.prism.paper

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.zld.prism.PrismPlugin
import net.zld.prism.config.db.DatabaseConfigManager
import net.zld.prism.sync.CrossProxySyncManager
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class ChatManager(
    private val plugin: PrismPlugin,
    private val dbManager: DatabaseConfigManager,
    private val syncManager: CrossProxySyncManager,
    private val permissionManager: PermissionManager,
) {
    private val logger = LoggerFactory.getLogger(ChatManager::class.java)
    private val messageHistory = ConcurrentLinkedQueue<ChatMessage>()
    private val mini = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
    private val channelSubscriptions = ConcurrentHashMap<String, MutableSet<UUID>>()
    private val spyMode = ConcurrentHashMap<UUID, Boolean>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { prettyPrint = true }
    private val serializer = GsonComponentSerializer.gson()

    data class ChatMessage(
        val uuid: UUID,
        val username: String,
        val message: String,
        val component: String, // JSON serialized
        val channel: String,
        val timestamp: Long = System.currentTimeMillis(),
        val proxyId: String = "",
    )

    fun sendMessage(player: Player, message: Component) {
        player.sendMessage(message)
    }

    fun sendMessage(player: Player, text: String) {
        player.sendMessage(serializer.deserialize(text))
    }

    fun broadcast(message: Component, permission: String? = null) {
        plugin.proxy.allPlayers.filter { permission == null || it.hasPermission(permission) }
            .forEach { it.sendMessage(message) }

        val jsonMsg = serializer.serialize(message)
        syncManager.broadcastMessage(jsonMsg, permission)
    }

    fun broadcast(text: String, permission: String? = null) {
        broadcast(serializer.deserialize(text), permission)
    }

    fun onPlayerChat(player: Player, message: String): Boolean {
        val chatConfig = plugin.getConfig().chat
        if (!chatConfig.enabled) return false // chat handling disabled — let it pass through untouched

        if (permissionManager.hasPermission(player.uniqueId, "prism.chat.mute") &&
            !permissionManager.hasPermission(player.uniqueId, chatConfig.muteBypassPermission)
        ) {
            player.sendMessage(mini.deserialize(plugin.getConfig().messages.muted))
            return true
        }

        val channel = if (chatConfig.channels.enabled) chatConfig.channels.defaultChannel else "global"
        val formatted = formatMessage(player, message)
        val chatMessage = ChatMessage(
            uuid = player.uniqueId,
            username = player.username,
            message = message,
            component = serializer.serialize(formatted),
            channel = channel
        )

        messageHistory.add(chatMessage)
        while (messageHistory.size > chatConfig.historySize) messageHistory.poll()

        if (chatConfig.sendToConsole) {
            logger.info("[chat] <{}> {}", player.username, message)
        }

        broadcast(formatted)
        return false
    }

    private fun formatMessage(player: Player, message: String): Component {
        val chatConfig = plugin.getConfig().chat
        val prefix = getPrefix(player, chatConfig.defaultPrefix)
        val raw = chatConfig.format
            .replace("{prefix}", prefix)
            .replace("{player}", player.username)
            .replace("{message}", message)
        return mini.deserialize(raw)
    }

    private fun getPrefix(player: Player, default: String = "Player"): String {
        // Check for rank/prefix permissions
        return permissionManager.getPermissions(player.uniqueId).entries
            .filter { it.key.startsWith("prism.prefix.") && it.value }
            .map { it.key.removePrefix("prism.prefix.") }
            .firstOrNull() ?: default
    }

    fun sendPrivateMessage(from: Player, to: Player, message: String) {
        val formatted = Component.text("[PM] ").color(NamedTextColor.LIGHT_PURPLE)
            .append(Component.text("${from.username} -> ${to.username}: ").color(NamedTextColor.AQUA))
            .append(Component.text(message).color(NamedTextColor.WHITE))

        from.sendMessage(formatted)
        to.sendMessage(formatted)

        if (spyMode.values.any { it }) {
            plugin.proxy.allPlayers.filter { spyMode[it.uniqueId] == true }
                .forEach { it.sendMessage(formatted.append(Component.text(" [SPY]").color(NamedTextColor.GRAY))) }
        }
    }

    fun setSpyMode(player: Player, enabled: Boolean) {
        spyMode[player.uniqueId] = enabled
        player.sendMessage(Component.text("Spy mode ${if (enabled) "enabled" else "disabled"}", NamedTextColor.GREEN))
    }

    fun subscribeToChannel(player: Player, channel: String) {
        channelSubscriptions.computeIfAbsent(channel) { mutableSetOf() }.add(player.uniqueId)
    }

    fun unsubscribeFromChannel(player: Player, channel: String) {
        channelSubscriptions[channel]?.remove(player.uniqueId)
    }

    fun sendToChannel(channel: String, message: Component) {
        channelSubscriptions[channel]?.forEach { uuid ->
            plugin.proxy.getPlayer(uuid).ifPresent { it.sendMessage(message) }
        }
    }

    fun getHistory(limit: Int = 50): List<ChatMessage> {
        return messageHistory.reversed().take(limit).toList()
    }

    fun shutdown() {
        scope.cancel()
    }
}