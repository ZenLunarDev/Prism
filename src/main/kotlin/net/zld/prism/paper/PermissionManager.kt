package net.zld.prism.paper

import net.zld.prism.PrismPlugin
import net.zld.prism.config.db.DatabaseConfigManager
import net.zld.prism.sync.CrossProxySyncManager
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PermissionManager(
    private val plugin: PrismPlugin,
    private val dbManager: DatabaseConfigManager,
    private val syncManager: CrossProxySyncManager,
) {
    private val logger = LoggerFactory.getLogger(PermissionManager::class.java)
    private val permissionCache = ConcurrentHashMap<UUID, Map<String, Boolean>>()
    private val groupPermissions = ConcurrentHashMap<String, MutableMap<String, Boolean>>()
    private val playerGroups = ConcurrentHashMap<UUID, Set<String>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val defaultGroup = "default"

    data class PermissionEntry(
        val permission: String,
        val value: Boolean,
        val context: Map<String, Any> = emptyMap()
    )

    fun hasPermission(uuid: UUID, permission: String): Boolean {
        return getPermissions(uuid).getOrDefault(permission, false)
    }

    fun hasPermission(uuid: UUID, permission: String, context: Map<String, Any>): Boolean {
        val perms = getPermissions(uuid)
        return perms.getOrDefault(permission, false)
    }

    fun getPermissions(uuid: UUID): Map<String, Boolean> {
        return permissionCache.computeIfAbsent(uuid) { loadPermissions(uuid) }
    }

    private fun loadPermissions(uuid: UUID): Map<String, Boolean> {
        val map = mutableMapOf<String, Boolean>()

        // Default group permissions
        groupPermissions[defaultGroup]?.forEach { (perm, value) ->
            map[perm] = value
        }

        // Player groups
        playerGroups[uuid]?.forEach { group ->
            groupPermissions[group]?.forEach { (perm, value) ->
                map[perm] = value
            }
        }

        // Player-specific permissions
        dbManager.getConnection()?.use { conn ->
            conn.prepareStatement("SELECT permission, value FROM prism_permissions WHERE uuid = ?").use { stmt ->
                stmt.setObject(1, uuid)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        map[rs.getString("permission")] = rs.getBoolean("value")
                    }
                }
            }
        }

        return map
    }

    fun setPermission(uuid: UUID, permission: String, value: Boolean): Boolean {
        val success = dbManager.executeUpdate(
            "INSERT INTO prism_permissions (uuid, permission, value) VALUES (?, ?, ?) " +
            "ON CONFLICT (uuid, permission) DO UPDATE SET value = ?",
            uuid, permission, value, value
        ) > 0

        if (success) {
            invalidateCache(uuid)
        }
        return success
    }

    fun removePermission(uuid: UUID, permission: String): Boolean {
        val success = dbManager.executeUpdate(
            "DELETE FROM prism_permissions WHERE uuid = ? AND permission = ?",
            uuid, permission
        ) > 0

        if (success) {
            invalidateCache(uuid)
        }
        return success
    }

    fun getPlayerGroups(uuid: UUID): Set<String> {
        return playerGroups.computeIfAbsent(uuid) {
            dbManager.getConnection()?.use { conn ->
                conn.prepareStatement("SELECT group_name FROM prism_player_groups WHERE uuid = ?").use { stmt ->
                    stmt.setObject(1, uuid)
                    stmt.executeQuery().use { rs ->
                        val groups = mutableSetOf<String>()
                        while (rs.next()) groups.add(rs.getString("group_name"))
                        groups
                    }
                }
            } ?: emptySet()
        }
    }

    fun addPlayerToGroup(uuid: UUID, group: String): Boolean {
        // Implementation depends on prism_player_groups table
        invalidateCache(uuid)
        return true
    }

    fun removePlayerFromGroup(uuid: UUID, group: String): Boolean {
        invalidateCache(uuid)
        return true
    }

    fun setGroupPermission(group: String, permission: String, value: Boolean) {
        groupPermissions.computeIfAbsent(group) { mutableMapOf() }[permission] = value
        invalidateAllCaches()
    }

    fun invalidateCache(uuid: UUID) {
        permissionCache.remove(uuid)
    }

    fun invalidateAllCaches() {
        permissionCache.clear()
    }

    fun reloadAll() {
        invalidateAllCaches()
        loadGroupPermissions()
    }

    private fun loadGroupPermissions() {
        // Load from database or config
    }

    fun shutdown() {
        scope.cancel()
    }
}