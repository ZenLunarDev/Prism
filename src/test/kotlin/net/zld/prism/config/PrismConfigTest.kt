package net.zld.prism.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PrismConfigTest {

    @TempDir
    lateinit var tempDir: File

    private fun newFile(name: String): File = File(tempDir, name)

    private fun sampleConfig() = PrismConfig(
        pools = listOf(
            PoolDefinition(
                name = "lobby",
                servers = listOf(
                    ServerDefinition("lobby-1", "127.0.0.1", 25565, weight = 10, fallback = true, motd = "Welcome!"),
                ),
                fallbackPool = "fallback",
            ),
            PoolDefinition(
                name = "survival",
                servers = listOf(
                    ServerDefinition("survival-1", "10.0.0.1", 25566, weight = 5),
                    ServerDefinition("survival-2", "10.0.0.2", 25567, weight = 3),
                ),
                fallbackPool = "lobby",
            ),
        ),
        fallback = FallbackChainDefinition(chain = listOf("lobby", "fallback"), defaultTarget = "lobby"),
        healthCheck = HealthCheckDefinition(
            enabled = true, intervalSeconds = 20, timeoutMillis = 3000,
            failThreshold = 4, successThreshold = 5,
        ),
        database = DatabaseDefinition(jdbcUrl = "jdbc:postgresql://db:5432/prism", username = "u", password = "p"),
        redis = RedisDefinition(url = "redis://redis:6379", password = "rp", database = 2, poolSize = 8),
        api = ApiDefinition(host = "127.0.0.1", port = 9090, enabled = false, jwtSecret = "s3cret", corsEnabled = false),
        logging = LoggingDefinition(level = "DEBUG", jsonFormat = true, lokiUrl = "http://loki:3100"),
    )

    @Test
    fun `save then load round-trips all fields`() {
        val original = sampleConfig()
        val file = newFile("roundtrip.conf")

        PrismConfig.save(original, file)
        assertTrue(file.exists(), "save() must create the file")

        val loaded = PrismConfig.load(file)

        // Pools
        assertEquals(original.pools.size, loaded.pools.size)
        for ((i, expected) in original.pools.withIndex()) {
            val actual = loaded.pools[i]
            assertEquals(expected.name, actual.name)
            assertEquals(expected.fallbackPool, actual.fallbackPool)
            assertEquals(expected.servers.size, actual.servers.size)
            for ((j, expectedServer) in expected.servers.withIndex()) {
                val actualServer = actual.servers[j]
                assertEquals(expectedServer.name, actualServer.name)
                assertEquals(expectedServer.host, actualServer.host)
                assertEquals(expectedServer.port, actualServer.port)
                assertEquals(expectedServer.weight, actualServer.weight)
                assertEquals(expectedServer.fallback, actualServer.fallback)
                assertEquals(expectedServer.motd, actualServer.motd)
            }
        }

        // Fallback chain
        assertEquals(original.fallback.chain, loaded.fallback.chain)
        assertEquals(original.fallback.defaultTarget, loaded.fallback.defaultTarget)

        // Health check
        assertEquals(original.healthCheck, loaded.healthCheck)

        // Database / Redis
        assertEquals(original.database, loaded.database)
        assertEquals(original.redis, loaded.redis)

        // API / Logging
        assertEquals(original.api, loaded.api)
        assertEquals(original.logging, loaded.logging)
    }

    @Test
    fun `save is idempotent - save, load, save, load yields the same config`() {
        val original = sampleConfig()
        val file1 = newFile("idem1.conf")
        val file2 = newFile("idem2.conf")

        PrismConfig.save(original, file1)
        val onceLoaded = PrismConfig.load(file1)
        PrismConfig.save(onceLoaded, file2)
        val twiceLoaded = PrismConfig.load(file2)

        assertEquals(onceLoaded, twiceLoaded)
        assertEquals(original.pools.map { it.name }, twiceLoaded.pools.map { it.name })
        assertEquals(original.pools.map { it.servers.map(ServerDefinition::name) }, twiceLoaded.pools.map { it.servers.map(ServerDefinition::name) })
    }

    @Test
    fun `defaults are applied when the file only contains pools`() {
        val file = newFile("minimal.conf")
        file.writeText(
            """
            pools = [
                {
                    name = "only"
                    servers = [ { name = "only-1", host = "127.0.0.1", port = 25565 } ]
                }
            ]
            """.trimIndent()
        )

        val config = PrismConfig.load(file)

        assertEquals(1, config.pools.size)
        assertEquals("only-1", config.pools[0].servers.single().name)
        assertEquals(1, config.pools[0].servers.single().weight) // default weight

        // Everything else falls back to defaults
        assertEquals(HealthCheckDefinition(), config.healthCheck)
        assertEquals("", config.fallback.defaultTarget) // empty default = no default target set
        assertTrue(config.api.enabled)
        assertEquals("INFO", config.logging.level)
    }

    @Test
    fun `parses the bundled default prism conf resource`() {
        // The resource shipped with the plugin must always be parseable by load()
        val resource = javaClass.classLoader.getResourceAsStream("prism.conf")
        if (resource == null) {
            // resource not on the test classpath (not packaged) — skip gracefully
            return
        }
        val file = newFile("bundled.conf")
        resource.use { input -> file.outputStream().use { input.copyTo(it) } }

        val config = PrismConfig.load(file)

        assertTrue(config.pools.isNotEmpty(), "bundled config must contain pools")
        assertTrue(config.healthCheck.enabled)
        assertEquals(15, config.healthCheck.intervalSeconds, "bundled default interval should be 15s")
        assertEquals(2, config.healthCheck.failThreshold)
        assertEquals(3, config.healthCheck.successThreshold)
        // Every pool in the bundled config must declare at least one server
        config.pools.forEach { pool ->
            assertTrue(pool.servers.isNotEmpty(), "pool '${pool.name}' must not be empty")
        }
    }

    @Test
    fun `weight defaults come through on partial server entries`() {
        val file = newFile("partial.conf")
        file.writeText(
            """
            pools = [
                {
                    name = "p"
                    servers = [
                        { name = "w", host = "h", port = 1, weight = 7 }
                        { name = "noweight", host = "h", port = 2 }
                    ]
                }
            ]
            """.trimIndent()
        )

        val config = PrismConfig.load(file)
        val servers = config.pools.single().servers

        assertEquals(7, servers[0].weight)
        assertEquals(1, servers[1].weight)
    }
}
