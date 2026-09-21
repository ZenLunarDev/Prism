package net.zld.prism.api

import net.zld.prism.api.event.PoolHealthChangeEvent
import net.zld.prism.api.event.PlayerRouteEvent
import net.zld.prism.api.event.PrismEvent
import net.zld.prism.api.event.PrismEventBus
import net.zld.prism.api.event.RouteReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.UUID

class PrismEventBusTest {

    private val bus = PrismEventBus(LoggerFactory.getLogger("test"))

    @Test
    fun `listeners receive events in registration order`() {
        val order = mutableListOf<String>()
        bus.listen<PoolHealthChangeEvent>("a") { order.add("a") }
        bus.listen<PoolHealthChangeEvent>("b") { order.add("b") }

        bus.fire(PoolHealthChangeEvent("lobby", "lobby-1", true))

        assertEquals(listOf("a", "b"), order)
    }

    @Test
    fun `cancellable event can be vetoed`() {
        bus.listen<PlayerRouteEvent>("veto") { it.cancelled = true }
        val ok = bus.fire(PlayerRouteEvent(UUID.randomUUID(), "Bob", "lobby-1", "lobby", RouteReason.COMMAND_SWITCH))
        assertFalse(ok)
    }

    @Test
    fun `cancellable event passes when nobody cancels`() {
        bus.listen<PlayerRouteEvent>("observer") { /* just observe */ }
        val ok = bus.fire(PlayerRouteEvent(UUID.randomUUID(), "Bob", "lobby-1", "lobby", RouteReason.INITIAL_JOIN))
        assertTrue(ok)
    }

    @Test
    fun `listener exceptions do not break dispatch`() {
        val seen = mutableListOf<String>()
        bus.listen<PoolHealthChangeEvent>("boom") { throw IllegalStateException("boom") }
        bus.listen<PoolHealthChangeEvent>("after") { seen.add("after") }

        val ok = bus.fire(PoolHealthChangeEvent("lobby", "lobby-1", false))

        assertEquals(listOf("after"), seen)
        assertTrue(ok)
    }

    @Test
    fun `unregisterAll removes only that owner`() {
        bus.listen<PoolHealthChangeEvent>("a") { }
        bus.listen<PoolHealthChangeEvent>("b") { }
        assertEquals(2, bus.listenerCount(PoolHealthChangeEvent::class.java))

        bus.unregisterAll("a")
        assertEquals(1, bus.listenerCount(PoolHealthChangeEvent::class.java))
    }

    @Test
    fun `different event types do not cross dispatch`() {
        var healthHits = 0
        var drainHits = 0
        bus.listen<PoolHealthChangeEvent>("h") { healthHits++ }
        bus.listen<net.zld.prism.api.event.PoolDrainChangeEvent>("d") { drainHits++ }

        bus.fire(net.zld.prism.api.event.PoolDrainChangeEvent("lobby", true))

        assertEquals(0, healthHits)
        assertEquals(1, drainHits)
    }

}
