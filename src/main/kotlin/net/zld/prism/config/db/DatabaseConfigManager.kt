package net.zld.prism.config.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.zld.prism.PrismPlugin
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.sql.ResultSet
import java.util.Properties
import javax.sql.DataSource

class DatabaseConfigManager(private val plugin: PrismPlugin) {
    private val logger = LoggerFactory.getLogger(DatabaseConfigManager::class.java)
    private var dataSource: HikariDataSource? = null
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

            // Flyway 10's scanner is unreliable inside shaded jars (flyway#3811),
            // so migrations run through a simple JDBC runner instead: extract the
            // bundled SQL files and apply each one exactly once, tracked in a
            // schema history table.
            val migrationsDir = prepareMigrationDirectory()
            runMigrations(dataSource!!, migrationsDir)

            // Never log the raw JDBC URL — it can embed credentials
            logger.info("Database connected and migrated: {}", sanitizeJdbcUrl(jdbcUrl))
            return true
        } catch (e: Exception) {
            logger.error("Failed to initialize database: {}", e.message, e)
            return false
        }
    }

    /**
     * Applies every bundled migration exactly once. Files must be named
     * V<version>__<description>.sql and are executed in version order.
     * Each file runs as a single transaction and is recorded in prism_schema_history.
     */
    private fun runMigrations(dataSource: HikariDataSource, migrationsDir: File) {
        val files = migrationsDir.listFiles { f -> f.name.matches(Regex("V\\d+__.*\\.sql")) }
            ?.sortedBy { it.name.substringBefore('_').removePrefix("V").toInt() }
            ?: emptyList()
        logger.debug("Migration dir: {} — {} candidate file(s)", migrationsDir.absolutePath, files.size)

        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """CREATE TABLE IF NOT EXISTS prism_schema_history (
                        version INT PRIMARY KEY,
                        description VARCHAR(255) NOT NULL,
                        applied_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                    )"""
                )
            }

            val applied = mutableSetOf<Int>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT version FROM prism_schema_history").use { rs ->
                    while (rs.next()) applied.add(rs.getInt(1))
                }
            }

            for (file in files) {
                val version = file.name.substringBefore('_').removePrefix("V").toInt()
                if (version in applied) continue

                val autoCommit = conn.autoCommit
                conn.autoCommit = false
                try {
                    conn.createStatement().use { stmt ->
                        stmt.execute(file.readText())
                    }
                    conn.prepareStatement("INSERT INTO prism_schema_history (version, description) VALUES (?, ?)").use { ins ->
                        ins.setInt(1, version)
                        ins.setString(2, file.name.substringAfter("__").removeSuffix(".sql"))
                        ins.executeUpdate()
                    }
                    conn.commit()
                    logger.info("Applied migration V{}: {}", version, file.name)
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.autoCommit = autoCommit
                }
            }
        }
    }

    /**
     * Copies bundled SQL migrations from the plugin jar into <dataDirectory>/migrations
     * so they can be applied from a stable filesystem location.
     */
    private fun prepareMigrationDirectory(): File {
        val migrationsDir = File(plugin.dataDirectory, "migrations")
        migrationsDir.mkdirs()

        val classLoader = javaClass.classLoader
        val urls = classLoader.getResources("db/migration")
        while (urls.hasMoreElements()) {
            val url = urls.nextElement()
            if (url.protocol != "jar") continue
            val jarPath = java.net.URLDecoder.decode(
                url.path.removePrefix("file:").substringBefore("!"), Charsets.UTF_8.name()
            )
            try {
                java.util.jar.JarFile(jarPath).use { jar ->
                    for (entry in jar.entries().asSequence()) {
                        if (entry.isDirectory || !entry.name.startsWith("db/migration/") || !entry.name.endsWith(".sql")) continue
                        val target = File(migrationsDir, entry.name.substringAfterLast('/'))
                        if (!target.exists() || target.length() != entry.size) {
                            // read straight from the JarFile — a leading-slash
                            // getResourceAsStream returns null in plugin classloaders
                            jar.getInputStream(entry).use { input ->
                                target.outputStream().use { input.copyTo(it) }
                            }
                            logger.info("Extracted migration {} to {}", target.name, migrationsDir.name)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("Could not extract bundled migrations: {}", e.message)
            }
        }
        return migrationsDir
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