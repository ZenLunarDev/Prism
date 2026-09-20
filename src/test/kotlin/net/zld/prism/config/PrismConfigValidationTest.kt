package net.zld.prism.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PrismConfigValidationTest {

    @TempDir
    lateinit var tempDir: File

    private fun fileWith(content: String): File {
        val f = File(tempDir, "test-${System.nanoTime()}.conf")
        f.writeText(content.trimIndent())
        return f
    }

    private fun load(content: String): PrismConfig.LoadReport =
        PrismConfig.loadWithReport(fileWith(content))

    private fun validConfigBody(): String = """
        pools = [
            {
                name = "lobby"
                servers = [ { name = "lobby-1", host = "127.0.0.1", port = 25565, weight = 5 } ]
            }
        ]
        fallback = { chain = ["lobby"], default = "lobby-1" }
    """.trimIndent()

    // ---------- Validation: valid configs ----------

    @Test
    fun `a well-formed config is valid with no errors`() {
        val report = load(validConfigBody())

        assertTrue(report.valid, "errors were: ${report.errors}")
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun `missing default server is an error`() {
        val report = load(
            """
            pools = [
                { name = "lobby", servers = [ { name = "lobby-1", host = "h", port = 1 } ] }
            ]
            fallback = { chain = ["lobby"], default = "does-not-exist" }
            """.trimIndent()
        )

        assertFalse(report.valid)
        assertTrue(report.errors.any { it.contains("default target") && it.contains("does-not-exist") })
    }

    @Test
    fun `chain referencing unknown pool is a warning not an error`() {
        val report = load(
            """
            pools = [
                { name = "lobby", servers = [ { name = "lobby-1", host = "h", port = 1 } ] }
            ]
            fallback = { chain = ["lobby", "ghost-pool"], default = "lobby-1" }
            """.trimIndent()
        )

        assertTrue(report.valid)
        assertTrue(report.warnings.any { it.contains("ghost-pool") })
    }

    @Test
    fun `empty pool is an error`() {
        val report = load(
            """
            pools = [
                { name = "empty", servers = [] }
                { name = "lobby", servers = [ { name = "lobby-1", host = "h", port = 1 } ] }
            ]
            fallback = { chain = ["lobby"], default = "lobby-1" }
            """.trimIndent()
        )

        assertFalse(report.valid)
        assertTrue(report.errors.any { it.contains("pool 'empty' has no servers") })
    }

    @Test
    fun `weight zero or negative is an error`() {
        val report = load(
            """
            pools = [
                { name = "p", servers = [ { name = "bad", host = "h", port = 1, weight = 0 } ] }
            ]
            fallback = { chain = [], default = "bad" }
            """.trimIndent()
        )

        assertFalse(report.valid)
        assertTrue(report.errors.any { it.contains("weight") && it.contains("bad") })
    }

    @Test
    fun `duplicate server name across pools is an error`() {
        val report = load(
            """
            pools = [
                { name = "a", servers = [ { name = "dup", host = "h", port = 1 } ] }
                { name = "b", servers = [ { name = "dup", host = "h", port = 2 } ] }
            ]
            fallback = { chain = [], default = "dup" }
            """.trimIndent()
        )

        assertFalse(report.valid)
        assertTrue(report.errors.any { it.contains("'dup'") && it.contains("both pool") })
    }

    @Test
    fun `invalid port is an error`() {
        val report = load(
            """
            pools = [
                { name = "p", servers = [ { name = "s", host = "h", port = 70000 } ] }
            ]
            fallback = { chain = [], default = "s" }
            """.trimIndent()
        )

        assertFalse(report.valid)
        assertTrue(report.errors.any { it.contains("invalid port") })
    }

    @Test
    fun `invalid health check values are errors`() {
        val body = validConfigBody()
        val report = load(
            """
            $body
            health-check = { enabled = true, interval = 0, timeout = 100 }
            """.trimIndent()
        )

        assertFalse(report.valid)
        assertTrue(report.errors.any { it.contains("interval") })
        assertTrue(report.errors.any { it.contains("timeout") })
    }

    // ---------- Secrets ----------

    @Test
    fun `default jwt secret produces a warning when api enabled`() {
        val report = load(
            """
            ${validConfigBody()}
            api = { enabled = true, jwt-secret = "changeme-generatesecurekey" }
            """.trimIndent()
        )

        assertTrue(report.valid)
        assertTrue(report.warnings.any { it.contains("jwt-secret") && it.contains("default") })
    }

    @Test
    fun `empty jwt secret produces a warning when api enabled`() {
        val report = load(
            """
            ${validConfigBody()}
            api = { enabled = true, jwt-secret = "" }
            """.trimIndent()
        )

        assertTrue(report.warnings.any { it.contains("jwt-secret") })
    }

    @Test
    fun `default jwt secret is fine when api is disabled`() {
        val report = load(
            """
            ${validConfigBody()}
            api = { enabled = false, jwt-secret = "changeme-generatesecurekey" }
            """.trimIndent()
        )

        assertTrue(report.valid)
        assertTrue(report.warnings.none { it.contains("jwt-secret") })
    }

    // ---------- Migration ----------

    @Test
    fun `v1 config without thresholds is migrated to current version`() {
        // v1: no config-version key (defaults to 1), no health-check thresholds
        val file = fileWith(
            """
            pools = [
                { name = "lobby", servers = [ { name = "lobby-1", host = "h", port = 1 } ] }
            ]
            fallback = { chain = ["lobby"], default = "lobby-1" }
            health-check = { enabled = true, interval = 30, timeout = 5000 }
            """.trimIndent()
        )

        val report = PrismConfig.loadWithReport(file)

        assertTrue(report.migrationsApplied.isNotEmpty(), "expected migrations to run")
        assertTrue(report.migrationsApplied.any { it.contains("fail-threshold") })
        assertTrue(report.migrationsApplied.any { it.contains("success-threshold") })

        // Migrated values present in the parsed config
        assertEquals(2, report.config.healthCheck.failThreshold)
        assertEquals(3, report.config.healthCheck.successThreshold)
        // And the version was bumped on disk
        assertEquals(PrismConfig.CURRENT_VERSION, report.config.configVersion)
        val onDisk = file.readText()
        assertTrue(onDisk.contains("config-version"), "migrated file should persist the new config-version")
    }

    @Test
    fun `current version config runs no migrations`() {
        val body = validConfigBody()
        val file = fileWith(
            """
            config-version = ${PrismConfig.CURRENT_VERSION}
            $body
            """.trimIndent()
        )

        val report = PrismConfig.loadWithReport(file)

        assertTrue(report.migrationsApplied.isEmpty())
        assertTrue(report.config.configVersion == PrismConfig.CURRENT_VERSION)
    }

    @Test
    fun `migration failure on read-only file still yields migrated in-memory config`() {
        // Simulate an unwritable file by pointing save at a directory path
        val file = fileWith(
            """
            pools = [
                { name = "lobby", servers = [ { name = "lobby-1", host = "h", port = 1 } ] }
            ]
            fallback = { chain = ["lobby"], default = "lobby-1" }
            health-check = { enabled = true, interval = 30, timeout = 5000 }
            """.trimIndent()
        )
        file.setWritable(false)
        try {
            val report = PrismConfig.loadWithReport(file)
            // migration applied in memory even if persistence failed
            assertEquals(2, report.config.healthCheck.failThreshold)
            assertTrue(report.migrationsApplied.any { it.contains("fail-threshold") })
        } finally {
            file.setWritable(true)
        }
    }

    // ---------- save() writes the version ----------

    @Test
    fun `save writes current config version`() {
        val file = File(tempDir, "saved.conf")
        PrismConfig.save(PrismConfig(), file)
        // Round-trip: the saved file must carry the current version when parsed back
        val reloaded = PrismConfig.loadWithReport(file)
        assertEquals(PrismConfig.CURRENT_VERSION, reloaded.config.configVersion)
        assertTrue(reloaded.migrationsApplied.isEmpty())
    }

    // ---------- Error messages never contain secrets ----------

    @Test
    fun `no secret values appear in warnings or errors`() {
        val secret = "super-secret-value-42"
        val report = load(
            """
            ${validConfigBody()}
            api = { enabled = true, jwt-secret = "$secret" }
            database = { jdbc-url = "jdbc:postgresql://db:5432/prism", username = "u", password = "db-pass-99" }
            redis = { url = "redis://:redis-pass-77@localhost:6379" }
            """.trimIndent()
        )

        val allMessages = (report.errors + report.warnings + report.migrationsApplied).joinToString("\n")
        assertFalse(allMessages.contains(secret), "jwt secret leaked into messages")
        assertFalse(allMessages.contains("db-pass-99"), "db password leaked into messages")
        assertFalse(allMessages.contains("redis-pass-77"), "redis password leaked into messages")
    }
}
