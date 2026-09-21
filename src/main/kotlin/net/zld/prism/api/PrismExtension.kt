package net.zld.prism.api

import net.zld.prism.api.event.PrismEventBus
import java.io.File

/**
 * Entry-point interface for PrismMC extensions (third-party plugins).
 *
 * An extension is a jar placed in `plugins/prism/extensions/` with a manifest
 * attribute `Prism-Extension: <fully.qualified.ClassName>`. The class must
 * implement this interface and provide a no-arg constructor.
 *
 * Extensions receive a [PrismApi] facade that exposes PrismMC's orchestration
 * systems and its own data folder. Unlike the older module system, extensions
 * are loaded with isolated classloaders, cannot see each other's classes, and
 * are cleanly unloaded on shutdown/reload.
 */
interface PrismExtension {
    /** Unique id (lowercase, no spaces) — used for data folders and listener cleanup. */
    val id: String

    val name: String get() = id
    val version: String get() = "1.0"

    /** Called once after the jar is loaded, before any events flow. */
    fun onEnable(api: PrismApi)

    /** Called on shutdown or extension reload. */
    fun onDisable() {}
}

/**
 * Facade handed to every extension. Deliberately narrow: extensions interact
 * with PrismMC through this and the event bus, not through internals.
 */
interface PrismApi {
    /** PrismMC version string. */
    fun version(): String

    /** Event bus for listening to (and firing) PrismMC events. */
    fun events(): PrismEventBus

    /** Pool orchestration view. */
    fun pools(): PoolView

    /** Per-extension data folder: extensions/<id>/ */
    fun dataFolder(): File

    /** Logs through PrismMC's logger, prefixed with the extension id. */
    fun logger(): org.slf4j.Logger
}

/** Read-only view over the pools, safe for extensions. */
interface PoolView {
    /** Names of all configured pools. */
    fun poolNames(): List<String>

    /** Names of servers in a pool. */
    fun serversIn(pool: String): List<String>

    /** Is every server in the pool healthy? */
    fun isPoolHealthy(pool: String): Boolean

    /** Is the pool currently draining? */
    fun isPoolDraining(pool: String): Boolean

    /** Which pool contains this server (null if none). */
    fun poolOf(serverName: String): String?
}
