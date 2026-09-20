package net.zld.prism.paper

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import net.zld.prism.PrismPlugin
import net.zld.prism.config.db.DatabaseConfigManager
import net.zld.prism.sync.CrossProxySyncManager
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PlayerManager(
    private val plugin: PrismPlugin,
    private val dbManager: DatabaseConfigManager,
    private val syncManager: CrossProxySyncManager,
) {
    private val logger = LoggerFactory.getLogger(PlayerManager::class.java)
    private val playerDataCache = ConcurrentHashMap<UUID, PlayerData>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val lastSave = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

    data class PlayerData(
        val uuid: UUID,
        var username: String,
        var firstJoin: Instant = Instant.now(),
        var lastSeen: Instant = Instant.now(),
        var totalPlaytime: Long = 0,
        var currentServer: String? = null,
        var currentPool: String? = null,
        var metadata: Map<String, String> = emptyMap(),
        var online: Boolean = false,
    ) {
        fun updateLastSeen() {
            lastSeen = Instant.now()
            online = true
        }

        fun markOffline() {
            online = false
            totalPlaytime += Duration.between(lastSeen, Instant.now()).toMillis()
        }
    }

    fun getPlayer(uuid: UUID): PlayerData? = playerDataCache[uuid]

    fun getPlayerByName(name: String): PlayerData? = playerDataCache.values.find { it.username.equals(name, ignoreCase = true) }

    fun getAllPlayers(): List<PlayerData> = playerDataCache.values.toList()

    fun getOnlinePlayers(): List<PlayerData> = playerDataCache.values.filter { it.online }.toList()

    fun onPlayerJoin(player: Player): PlayerData {
        val serverName: String? = player.currentServer.map { it.serverInfo.name }.orElse(null)
        val poolName: String? = serverName?.let { plugin.getPoolNameForServer(it) }
        val data = playerDataCache.computeIfAbsent(player.uniqueId) {
            PlayerData(
                uuid = player.uniqueId,
                username = player.username,
                currentServer = serverName,
                currentPool = poolName
            )
        }.apply {
            username = player.username
            updateLastSeen()
            currentServer = serverName
            currentPool = poolName
        }

        scope.launch {
            syncManager.publishPlayerJoined(
                """{"uuid":"${data.uuid}","username":"${data.username}","server":"${serverName ?: "none"}"}"""
            )
        }
        scheduleSave()
        return data
    }

    fun onPlayerQuit(uuid: UUID) {
        playerDataCache[uuid]?.apply {
            markOffline()
            currentServer = null
            currentPool = null
            scope.launch { syncManager.publishPlayerQuit(uuid.toString()) }
        }
        scheduleSave()
    }

    fun onPlayerSwitchServer(uuid: UUID, fromPool: String?, toPool: String?, serverName: String?) {
        playerDataCache[uuid]?.apply {
            currentServer = serverName
            currentPool = toPool
            scope.launch { fromPool?.let { syncManager.publishPlayerSwitched(uuid.toString(), it, toPool!!) } }
        }
        scheduleSave()
    }

    fun updateMetadata(uuid: UUID, key: String, value: String) {
        playerDataCache[uuid]?.metadata = playerDataCache[uuid]!!.metadata + (key to value)
    }

    private fun scheduleSave() {
        if (System.currentTimeMillis() - lastSave.get() > 30000) {
            saveScheduler.schedule({ saveAll() }, 30, TimeUnit.SECONDS)
            lastSave.set(System.currentTimeMillis())
        }
    }

    private fun saveAll() {
        scope.launch {
            val toSave = playerDataCache.values.filter { it.lastSeen.isAfter(Instant.now().minusSeconds(60)) }
            toSave.forEach { savePlayer(it) }
        }
    }

    private fun savePlayer(data: PlayerData) {
        try {
            dbManager.executeUpdate("""
                INSERT INTO prism_players (uuid, username, first_join, last_seen, total_playtime, current_server, current_pool, metadata, online)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (uuid) DO UPDATE SET
                    username = EXCLUDED.username,
                    last_seen = EXCLUDED.last_seen,
                    total_playtime = EXCLUDED.total_playtime,
                    current_server = EXCLUDED.current_server,
                    current_pool = EXCLUDED.current_pool,
                    metadata = EXCLUDED.metadata,
                    online = EXCLUDED.online
            """,
                data.uuid, data.username, data.firstJoin, data.lastSeen, data.totalPlaytime,
                data.currentServer, data.currentPool,
                kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.serializer<Map<String, String>>(),
                    data.metadata
                ),
                data.online
            )
        } catch (e: Exception) {
            logger.warn("Failed to save player {}: {}", data.uuid, e.message)
        }
    }

    fun loadPlayer(uuid: UUID): PlayerData? {
        return dbManager.getConnection()?.use { conn ->
            conn.prepareStatement("SELECT * FROM prism_players WHERE uuid = ?").use { stmt ->
                stmt.setObject(1, uuid)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val data = PlayerData(
                            uuid = rs.getObject("uuid", UUID::class.java),
                            username = rs.getString("username"),
                            firstJoin = rs.getTimestamp("first_join").toInstant(),
                            lastSeen = rs.getTimestamp("last_seen").toInstant(),
                            totalPlaytime = rs.getLong("total_playtime"),
                            currentServer = rs.getString("current_server"),
                            currentPool = rs.getString("current_pool"),
                            metadata = kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(rs.getString("metadata")),
                            online = rs.getBoolean("online")
                        )
                        playerDataCache[data.uuid] = data
                        data
                    } else null
                }
            }
        }
    }

    fun shutdown() {
        saveAll()
        scope.cancel()
        saveScheduler.shutdown()
        saveScheduler.awaitTermination(10, TimeUnit.SECONDS)
    }
}