package net.zld.prism.server

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import net.zld.prism.config.PoolDefinition
import net.zld.prism.config.ServerDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenMock
import java.net.InetSocketAddress

/**
 * Tests for lifecycle orchestration pieces: the pure [CrashTracker] and the
 * drain/restart exclusion behaviour of [ServerPool].
 */
class LifecycleTest {

    private lateinit var proxy: ProxyServer

    @BeforeEach
    fun setUp() {
        proxy = mock(ProxyServer::class.java)
    }

    private fun serverDef(name: String, weight: Int = 1) =
        ServerDefinition(name = name, host = "127.0.0.1", port = 20000, weight = weight)

    private fun registeredServerFor(name: String): RegisteredServer {
        val rs = mock(RegisteredServer::class.java)
        whenMock(rs.serverInfo).thenReturn(
            com.velocitypowered.api.proxy.server.ServerInfo(
                name, InetSocketAddress.createUnresolved("127.0.0.1", 20000)
            )
        )
        return rs
    }

    /** Builds a pool whose servers are all already registered with the proxy mock. */
    private fun poolOf(vararg defs: ServerDefinition): ServerPool {
        defs.forEach { def ->
            val rs = mock(RegisteredServer::class.java)
            whenMock(rs.serverInfo).thenReturn(
                com.velocitypowered.api.proxy.server.ServerInfo(
                    def.name, InetSocketAddress.createUnresolved("127.0.0.1", 20000)
                )
            )
            whenMock(proxy.getServer(def.name)).thenReturn(java.util.Optional.of(rs))
        }
        whenMock(proxy.getServer(anyString())).thenAnswer { inv ->
            val name = inv.getArgument<String>(0)
            val def = defs.firstOrNull { it.name == name }
            if (def != null) java.util.Optional.of(registeredServerFor(name)) else java.util.Optional.empty<RegisteredServer>()
        }
        return ServerPool(proxy, PoolDefinition(name = "test-pool", servers = defs.toList(), fallbackPool = null))
    }

    // ------------------------------------------------------------------ CrashTracker

    @Test
    fun `crash tracker does not trigger below threshold`() {
        val tracker = CrashTracker(threshold = 3, windowSeconds = 90)

        assertFalse(tracker.recordFailure(0))
        assertFalse(tracker.recordFailure(1000))
        assertEquals(2, tracker.count())
    }

    @Test
    fun `crash tracker triggers exactly at threshold and re-arms`() {
        val tracker = CrashTracker(threshold = 3, windowSeconds = 90)

        tracker.recordFailure(0)
        tracker.recordFailure(1000)
        assertTrue(tracker.recordFailure(2000))  // 3rd crash within window -> trigger

        // re-armed: needs a full threshold again
        assertFalse(tracker.recordFailure(3000))
        assertFalse(tracker.recordFailure(4000))
        assertTrue(tracker.recordFailure(5000))
    }

    @Test
    fun `crashes outside the window are pruned`() {
        val windowMs = 90_000L
        val tracker = CrashTracker(threshold = 3, windowSeconds = 90)

        tracker.recordFailure(0)
        tracker.recordFailure(1000)
        // long after the first two crashes aged out — should not count
        assertFalse(tracker.recordFailure(0 + windowMs + 5_000))
        assertEquals(1, tracker.count())
    }

    @Test
    fun `old crashes age out mid-sequence`() {
        val windowMs = 90_000L
        val tracker = CrashTracker(threshold = 3, windowSeconds = 90)

        tracker.recordFailure(0)
        tracker.recordFailure(windowMs + 1000)
        assertFalse(tracker.recordFailure(windowMs + 2000))  // only 2 in window
        assertTrue(tracker.recordFailure(windowMs + 3000))   // 3rd in window
    }

    @Test
    fun `clear resets the tracker`() {
        val tracker = CrashTracker(threshold = 2, windowSeconds = 90)

        tracker.recordFailure(0)
        tracker.clear()

        assertFalse(tracker.recordFailure(1000))
        assertEquals(1, tracker.count())
    }

    @Test
    fun `draining pool is never selected`() {
        val pool = poolOf(serverDef("a"), serverDef("b"))
        assertNotNull(pool.selectServer())

        pool.setDraining(true)
        assertNull(pool.selectServer())
        assertTrue(pool.isDraining())

        pool.setDraining(false)
        assertNotNull(pool.selectServer())
    }

    @Test
    fun `restarting pool is never selected`() {
        val pool = poolOf(serverDef("a"))
        assertNotNull(pool.selectServer())

        pool.setRestarting(true)
        assertNull(pool.selectServer())
        assertTrue(pool.isRestarting())

        pool.setRestarting(false)
        assertNotNull(pool.selectServer())
    }

    @Test
    fun `drain state survives rebuild`() {
        val pool = poolOf(serverDef("a"))
        pool.setDraining(true)
        pool.rebuild()
        assertTrue(pool.isDraining(), "drain flag should survive config reloads")
    }
}
