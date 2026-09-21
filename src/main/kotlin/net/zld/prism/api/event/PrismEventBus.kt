package net.zld.prism.api.event

import net.zld.prism.server.ServerPool
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/**
 * Base type for all PrismMC API events.
 *
 * Prism events model proxy-orchestration concerns that the underlying
 * Velocity API does not cover: pool health, draining, fallbacks, cross-proxy
 * sync and the embedded world.
 */
interface PrismEvent {
    /** Cancellable events can be vetoed by listeners (first cancel wins). */
    interface Cancellable {
        var cancelled: Boolean
    }
}

/** Fired when a pool's health composition changes (a server went online/offline). */
data class PoolHealthChangeEvent(
    val poolName: String,
    val serverName: String,
    val healthy: Boolean,
) : PrismEvent

/** Fired when a pool starts or stops draining. */
data class PoolDrainChangeEvent(
    val poolName: String,
    val draining: Boolean,
) : PrismEvent

/** Fired before a player is routed to a server; listeners may redirect. */
data class PlayerRouteEvent(
    val playerUuid: UUID,
    val playerName: String,
    val targetServer: String,
    val targetPool: String,
    val reason: RouteReason,
) : PrismEvent, PrismEvent.Cancellable {
    override var cancelled: Boolean = false
}

enum class RouteReason { INITIAL_JOIN, COMMAND_SWITCH, FALLBACK, DRAIN_REDIRECT }

/** Fired for chat messages flowing through the proxy (post-format, pre-send). */
data class ProxyChatEvent(
    val playerUuid: UUID,
    val playerName: String,
    val message: String,
    val channel: String?,
) : PrismEvent, PrismEvent.Cancellable {
    override var cancelled: Boolean = false
}

/** Fired when the embedded world starts or stops (server-software mode). */
data class EmbeddedWorldStateEvent(
    val running: Boolean,
    val worldName: String,
) : PrismEvent

typealias Listener<T> = Consumer<T>

/**
 * Central event dispatcher for the PrismMC plugin API.
 *
 * Listeners are keyed by event type; dispatch walks the listener list in
 * registration order. Exceptions in a listener never break dispatch — they
 * are logged and treated as if the listener returned normally (a cancelled
 * flag set before the exception still counts).
 */
class PrismEventBus(private val logger: org.slf4j.Logger) {

    private val listeners = ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<ListenerWrapper<*>>>()

    private class ListenerWrapper<T : PrismEvent>(
        val owner: String?,
        val listener: Listener<T>,
    )

    /** Registers a listener for event type [T]. [owner] names the registering plugin (for cleanup). */
    fun <T : PrismEvent> listen(type: Class<T>, owner: String?, listener: Listener<T>) {
        listeners.computeIfAbsent(type) { CopyOnWriteArrayList() }.add(ListenerWrapper(owner, listener))
    }

    inline fun <reified T : PrismEvent> listen(owner: String?, crossinline listener: (T) -> Unit) =
        listen(T::class.java, owner) { event -> listener(event) }

    /** Removes all listeners registered by [owner]. */
    fun unregisterAll(owner: String) {
        for (list in listeners.values) {
            list.removeIf { it.owner == owner }
        }
    }

    /**
     * Dispatches [event] to all listeners of its runtime type (and supertypes
     * that are registered PrismEvent types). Returns true unless a listener
     * cancelled the event.
     */
    fun <T : PrismEvent> fire(event: T): Boolean {
        var type: Class<*>? = event.javaClass
        while (type != null && PrismEvent::class.java.isAssignableFrom(type)) {
            listeners[type]?.forEach { wrapper ->
                @Suppress("UNCHECKED_CAST")
                val w = wrapper as ListenerWrapper<T>
                try {
                    w.listener.accept(event)
                } catch (e: Exception) {
                    logger.error(
                        "PrismMC API: listener from '{}' threw while handling {}: {}",
                        wrapper.owner ?: "unknown", type.simpleName, e.message, e,
                    )
                }
            }
            type = type.superclass
        }
        val cancellable = event as? PrismEvent.Cancellable
        return cancellable?.cancelled != true
    }

    fun listenerCount(type: Class<*>): Int = listeners[type]?.size ?: 0
}
