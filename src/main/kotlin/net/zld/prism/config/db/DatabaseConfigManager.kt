package net.zld.prism.config.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.zld.prism.PrismPlugin
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.util.Properties
import javax.sql.DataSource

class DatabaseConfigManager(private val plugin: PrismPlugin) {
    private val logger = LoggerFactory.getLogger(DatabaseConfigManager::class.java)
    private var dataSource: HikariDataSource? = null
    private var flyway: Flyway? = null
    private val configCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun initialize(
        jdbcUrl: String,
        username: String,
        password: String,
        driverClassName: String = "org.postgresql.Driver",
        maxPoolSize: Int = 20,
        minIdle: Int = 5,
        connectionTimeout: Long = 30000,
        idleTimeout: Long = 600000,
        maxLifetime: Long = 1800000,
    ): Boolean {
        if (jdbcUrl.isBlank()) {
            logger.warn("No JDBC URL configured, database config disabled")
            return false
        }

        try {
            val hikariConfig = HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                this.username = username
                this.password = password
                this.driverClassName = driverClassName
                this.maximumPoolSize = maxPoolSize
                this.minimumIdle = minIdle
                this.connectionTimeout = connectionTimeout
                this.idleTimeout = idleTimeout
                this.maxLifetime = maxLifetime
                this.poolName = "Prism-DB-Pool"
                this.isAutoCommit = true
                this.transactionIsolation = "TRANSACTION_READ_COMMITTED"
                dataSourceProperties = Properties().apply {
                    put("reWriteBatchedInserts", "true")
                    put("cachePrepStmts", "true")
                    put("prepStmtCacheSize", "250")
                    put("prepStmtCacheSqlLimit", "2048")
                    put("useServerPrepStmts", "true")
                }
            }

            dataSource = HikariDataSource(hikariConfig)

            flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("db/migration")
                .baselineOnMigrate(true)
                .load()

            flyway?.migrate()

            // Never log the raw JDBC URL — it can embed credentials
            logger.info("Database connected and migrated: {}", sanitizeJdbcUrl(jdbcUrl))
            return true
        } catch (e: Exception) {
            logger.error("Failed to initialize database: {}", e.message, e)
            return false
        }
    }

    /** Strips user/password query parameters from a JDBC URL before logging. */
    private fun sanitizeJdbcUrl(url: String): String =
        url.substringBefore(';')
            .replace(Regex("(?i)(user|password)=[^&;]+"), "$1=***")

    fun getConnection(): Connection? = dataSource?.connection

    fun executeQuery(sql: String, vararg params: Any?): ResultSet? {
        return getConnection()?.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                params.forEachIndexed { index, param ->
                    stmt.setObject(index + 1, param)
                }
                stmt.executeQuery()
            }
        }
    }

    fun executeUpdate(sql: String, vararg params: Any?): Int {
        return getConnection()?.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                params.forEachIndexed { index, param ->
                    stmt.setObject(index + 1, param)
                }
                stmt.executeUpdate()
            }
        } ?: -1
    }

    fun executeTransaction(block: (Connection) -> Unit) {
        getConnection()?.use { conn ->
            conn.autoCommit = false
            try {
                block(conn)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    fun getConfig(key: String): String? {
        return configCache[key] ?: getConnection()?.use { conn ->
            conn.prepareStatement("SELECT value FROM prism_config WHERE key = ?").use { stmt ->
                stmt.setString(1, key)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val value = rs.getString("value")
                        configCache[key] = value
                        value
                    } else null
                }
            }
        }
    }

    fun setConfig(key: String, value: String): Boolean {
        return executeUpdate(
            "INSERT INTO prism_config (key, value, updated_at) VALUES (?, ?, NOW()) " +
            "ON CONFLICT (key) DO UPDATE SET value = ?, updated_at = NOW()",
            key, value, value
        ) > 0
    }

    fun getAllConfig(): Map<String, String> {
        return getConnection()?.use { conn ->
            conn.prepareStatement("SELECT key, value FROM prism_config").use { stmt ->
                stmt.executeQuery().use { rs ->
                    val map = mutableMapOf<String, String>()
                    while (rs.next()) {
                        map[rs.getString("key")] = rs.getString("value")
                    }
                    configCache.putAll(map)
                    map
                }
            }
        } ?: emptyMap()
    }

    fun invalidateCache(key: String? = null) {
        if (key != null) configCache.remove(key) else configCache.clear()
    }

    fun getDataSource(): DataSource? = dataSource

    fun shutdown() {
        dataSource?.close()
        logger.info("Database connection pool closed")
    }

    fun isConnected(): Boolean = dataSource != null && !dataSource!!.isClosed
}