package net.zld.prism.server

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import net.zld.prism.config.PoolDefinition
import net.zld.prism.config.ServerDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenMock
import java.net.InetSocketAddress

class ServerPoolTest {

    private lateinit var proxy: ProxyServer

    private fun serverInfo(name: String): ServerInfo =
        ServerInfo(name, InetSocketAddress.createUnresolved("127.0.0.1", 20000 + name.hashCode().mod(1000)))

    private fun registered(name: String): RegisteredServer {
        val rs = mock(RegisteredServer::class.java)
        val info = serverInfo(name)
        whenMock(rs.serverInfo).thenReturn(info)
        return rs
    }

    private fun serverDef(name: String, weight: Int = 1) =
        ServerDefinition(name = name, host = "127.0.0.1", port = 20000, weight = weight)

    /** Builds a pool whose servers are all already registered with the proxy mock. */
    private fun poolOf(vararg defs: ServerDefinition, fallbackPool: String? = null): ServerPool {
        defs.forEach { def ->
            val rs = registered(def.name)
            whenMock(proxy.getServer(def.name)).thenReturn(java.util.Optional.of(rs))
        }
        whenMock(proxy.getServer(anyString())).thenAnswer { inv ->
            val name = inv.getArgument<String>(0)
            val def = defs.firstOrNull { it.name == name }
            if (def != null) java.util.Optional.of(registered(name)) else java.util.Optional.empty<RegisteredServer>()
        }
        val poolDef = PoolDefinition(name = "test-pool", servers = defs.toList(), fallbackPool = fallbackPool)
        return ServerPool(proxy, poolDef)
    }

    @BeforeEach
    fun setUp() {
        proxy = mock(ProxyServer::class.java)
    }

    @Nested
    inner class Hysteresis {

        @Test
        fun `single failure does not mark server offline`() {
            val pool = poolOf(serverDef("a"))
            assertFalse(pool.recordHealthCheck("a", success = false, failThreshold = 2, successThreshold = 3))
            assertTrue(pool.getHealth("a")!!.healthy, "server must stay healthy after 1 failure when threshold is 2")
        }

        @Test
        fun `server goes offline only after N consecutive failures`() {
            val pool = poolOf(serverDef("a"))
            val thresholds = 2 to 3

            assertFalse(pool.recordHealthCheck("a", false, 2, 3), "1st failure: no transition")
            assertTrue(pool.recordHealthCheck("a", false, 2, 3), "2nd consecutive failure: must transition")
            assertFalse(pool.getHealth("a")!!.healthy)
        }

        @Test
        fun `intermittent failure resets the failure streak`() {
            val pool = poolOf(serverDef("a"))

            pool.recordHealthCheck("a", false, 2, 3)   // fail 1
            pool.recordHealthCheck("a", true, 2, 3)    // success resets streak
            assertFalse(pool.recordHealthCheck("a", false, 2, 3)) // fail again — streak restarted, still healthy

            assertTrue(pool.getHealth("a")!!.healthy, "flapping server must not drop after alternating results")
        }

        @Test
        fun `offline server recovers only after N consecutive successes`() {
            val pool = poolOf(serverDef("a"))

            // drive it offline
            pool.recordHealthCheck("a", false, 1, 3)
            assertFalse(pool.getHealth("a")!!.healthy)

            // 2 successes are not enough
            pool.recordHealthCheck("a", true, 1, 3)
            pool.recordHealthCheck("a", true, 1, 3)
            assertFalse(pool.getHealth("a")!!.healthy, "must stay offline before success threshold")

            // 3rd consecutive success recovers it
            assertTrue(pool.recordHealthCheck("a", true, 1, 3), "transition expected")
            assertTrue(pool.getHealth("a")!!.healthy)
        }

        @Test
        fun `failure during recovery resets the success streak`() {
            val pool = poolOf(serverDef("a"))
            pool.recordHealthCheck("a", false, 1, 3) // offline

            pool.recordHealthCheck("a", true, 1, 3)
            pool.recordHealthCheck("a", false, 1, 3) // fail mid-recovery
            assertFalse(pool.getHealth("a")!!.healthy)

            // still needs 3 consecutive successes from scratch
            pool.recordHealthCheck("a", true, 1, 3)
            pool.recordHealthCheck("a", true, 1, 3)
            assertFalse(pool.getHealth("a")!!.healthy)
            assertTrue(pool.recordHealthCheck("a", true, 1, 3))
        }

        @Test
        fun `offline server is excluded from healthy selection`() {
            val pool = poolOf(serverDef("a"), serverDef("b"))

            pool.recordHealthCheck("a", false, 1, 3)

            assertEquals("b", pool.getHealthyServers().single().serverInfo.name)
            assertEquals(1, pool.healthyCount())
        }

        @Test
        fun `unknown server name is a no-op`() {
            val pool = poolOf(serverDef("a"))
            assertFalse(pool.recordHealthCheck("nope", false, 1, 3))
        }

        @Test
        fun `health state survives rebuild`() {
            val pool = poolOf(serverDef("a"), serverDef("b"))
            pool.recordHealthCheck("a", false, 1, 3)
            assertFalse(pool.getHealth("a")!!.healthy)

            pool.rebuild() // e.g. config reload

            assertFalse(pool.getHealth("a")!!.healthy, "rebuild must preserve health state")
            assertEquals(1, pool.healthyCount())
        }
    }

    @Nested
    inner class WeightedSelection {

        @Test
        fun `single healthy server is always selected`() {
            val pool = poolOf(serverDef("a", weight = 10))
            assertEquals("a", pool.selectServer()?.serverInfo?.name)
        }

        @Test
        fun `selection is biased toward higher weight`() {
            val heavy = registered("heavy")
            val light = registered("light")
            whenMock(heavy.serverInfo).thenReturn(serverInfo("heavy"))
            whenMock(light.serverInfo).thenReturn(serverInfo("light"))

            val pool = poolOf(serverDef("heavy", weight = 9), serverDef("light", weight = 1))

            // With weights 9:1, "heavy" must dominate over many draws
            var heavyCount = 0
            repeat(1000) {
                val picked = pool.selectServer()!!.serverInfo.name
                if (picked == "heavy") heavyCount++
            }
            assertTrue(heavyCount > 800, "expected 'heavy' >> 800/1000 draws with 9:1 weight, got $heavyCount")
        }

        @Test
        fun `unhealthy servers are never selected`() {
            val pool = poolOf(serverDef("a"), serverDef("b"))
            pool.recordHealthCheck("a", false, 1, 3)

            repeat(50) {
                assertEquals("b", pool.selectServer()?.serverInfo?.name)
            }
        }

        @Test
        fun `excluded server is never selected`() {
            val pool = poolOf(serverDef("a"), serverDef("b"))
            repeat(50) {
                val picked = pool.selectServer(exclude = setOf("a"))!!.serverInfo.name
                assertEquals("b", picked)
            }
        }

        @Test
        fun `returns null when everything is excluded`() {
            val pool = poolOf(serverDef("a"), serverDef("b"))
            assertNull(pool.selectServer(exclude = setOf("a", "b")))
        }

        @Test
        fun `returns null when no healthy server remains`() {
            val pool = poolOf(serverDef("a"))
            pool.recordHealthCheck("a", false, 1, 3)
            assertNull(pool.selectServer())
        }

        @Test
        fun `exclusion falls back to remaining healthy servers`() {
            val pool = poolOf(serverDef("a"), serverDef("b"), serverDef("c"))
            pool.recordHealthCheck("a", false, 1, 3)

            // excluding the only other healthy server must not select the offline one
            repeat(30) {
                val picked = pool.selectServer(exclude = setOf("b"))!!.serverInfo.name
                assertEquals("c", picked)
            }
        }
    }

    @Nested
    inner class WeightAccounting {

        @Test
        fun `weights are restored when a server recovers`() {
            val pool = poolOf(serverDef("a", weight = 5), serverDef("b", weight = 1))
            assertEquals(2, pool.healthyCount())

            pool.recordHealthCheck("a", false, 1, 3)
            assertEquals(1, pool.healthyCount())

            pool.recordHealthCheck("a", true, 1, 3)
            pool.recordHealthCheck("a", true, 1, 3)
            pool.recordHealthCheck("a", true, 1, 3)
            assertEquals(2, pool.healthyCount())
        }

        @Test
        fun `unregister removes server from selection`() {
            val pool = poolOf(serverDef("a"), serverDef("b"))
            assertTrue(pool.unregisterServer("a"))
            assertFalse(pool.hasServer("a"))
            assertEquals(1, pool.size())

            repeat(20) {
                assertEquals("b", pool.selectServer()?.serverInfo?.name)
            }
        }

        @Test
        fun `unregister of unknown server returns false`() {
            val pool = poolOf(serverDef("a"))
            assertFalse(pool.unregisterServer("ghost"))
        }

        @Test
        fun `hasServer and getHealth reflect membership`() {
            val pool = poolOf(serverDef("a"))
            assertTrue(pool.hasServer("a"))
            assertNotNull(pool.getHealth("a"))
            assertNull(pool.getHealth("ghost"))
        }
    }
}
