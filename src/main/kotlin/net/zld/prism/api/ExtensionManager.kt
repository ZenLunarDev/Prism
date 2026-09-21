package net.zld.prism.api

import net.zld.prism.PrismPlugin
import net.zld.prism.api.event.EmbeddedWorldStateEvent
import net.zld.prism.api.event.PoolDrainChangeEvent
import net.zld.prism.api.event.PoolHealthChangeEvent
import net.zld.prism.api.event.PrismEvent
import net.zld.prism.api.event.PrismEventBus
import net.zld.prism.api.event.ProxyChatEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

/**
 * Loads and manages [PrismExtension] jars from `extensions/<id>/`.
 *
 * Each extension gets its own [URLClassLoader] (child of Prism's classloader)
 * so extensions are isolated from each other but can use Prism's shaded
 * libraries. Unloading closes the classloader and removes its event listeners.
 */
class ExtensionManager(
    private val plugin: PluginServices,
) {

    /** Minimal view of the running server that extensions infra needs. */
    interface PluginServices {
        val dataDirectory: File
        fun getVersion(): String
        fun getAllPools(): Collection<net.zld.prism.server.ServerPool>
        fun getPool(name: String): net.zld.prism.server.ServerPool?
    }

    private val logger: Logger = LoggerFactory.getLogger("prism.extensions")
    val eventBus = PrismEventBus(logger)

    private val loaded = LinkedHashMap<String, LoadedExtension>()
    private val extensionsDir get() = File(plugin.dataDirectory, "extensions")

    class LoadedExtension(
        val extension: PrismExtension,
        val classLoader: URLClassLoader,
        val file: File,
    )

    fun loadAll() {
        extensionsDir.mkdirs()
        val jars = extensionsDir.listFiles { f -> f.isFile && f.extension == "jar" } ?: return
        for (jar in jars) loadJar(jar)
        logger.info("PrismMC extensions loaded: {}", loaded.size)
    }

    private fun loadJar(jar: File) {
        try {
            val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), this::class.java.classLoader)
            val manifest = JarFile(jar).use { it.manifest }
            val mainClass = manifest?.mainAttributes?.getValue("Prism-Extension")
            if (mainClass.isNullOrBlank()) {
                logger.warn("Extension {}: manifest is missing 'Prism-Extension' attribute — skipped", jar.name)
                loader.close()
                return
            }
            val clazz = loader.loadClass(mainClass.trim())
            val instance = clazz.getDeclaredConstructor().newInstance() as PrismExtension
            val api = buildApi(instance)
            instance.onEnable(api)
            loaded[instance.id] = LoadedExtension(instance, loader, jar)
            logger.info("Enabled extension: {} v{}", instance.id, instance.version)
        } catch (e: Exception) {
            logger.error("Failed to load extension from {}: {}", jar.name, e.message, e)
        }
    }

    /** Disables and unloads a single extension by id (also removes its listeners). */
    fun unload(id: String): Boolean {
        val ext = loaded.remove(id) ?: return false
        try {
            ext.extension.onDisable()
        } catch (e: Exception) {
            logger.error("Extension {} threw during onDisable: {}", id, e.message, e)
        }
        eventBus.unregisterAll(id)
        try {
            ext.classLoader.close()
        } catch (e: Exception) {
            logger.warn("Could not close classloader for {}: {}", id, e.message)
        }
        logger.info("Unloaded extension: {}", id)
        return true
    }

    fun unloadAll() {
        for (id in loaded.keys.toList()) unload(id)
    }

    fun loadedIds(): List<String> = loaded.keys.toList()

    private fun buildApi(instance: PrismExtension): PrismApi = object : PrismApi {
        private val pluginLogger = LoggerFactory.getLogger("prism.ext.${instance.id}")

        override fun version(): String = plugin.getVersion()
        override fun events(): PrismEventBus = eventBus
        override fun pools(): PoolView = PoolViewImpl(plugin)
        override fun dataFolder(): File {
            val dir = File(extensionsDir, instance.id)
            dir.mkdirs()
            return dir
        }
        override fun logger(): Logger = pluginLogger
    }
}

/** Read-only pool view backed by the real ServerPool registry. */
internal class PoolViewImpl(private val plugin: ExtensionManager.PluginServices) : PoolView {
    override fun poolNames(): List<String> = plugin.getAllPools().map { it.name }.sorted()

    override fun serversIn(pool: String): List<String> =
        plugin.getPool(pool)?.getAllServers()?.map { it.serverInfo.name } ?: emptyList()

    override fun isPoolHealthy(pool: String): Boolean {
        val p = plugin.getPool(pool) ?: return false
        return p.getAllServers().all { s -> p.getHealth(s.serverInfo.name)?.healthy == true }
    }

    override fun isPoolDraining(pool: String): Boolean = plugin.getPool(pool)?.isDraining() ?: false

    override fun poolOf(serverName: String): String? =
        plugin.getAllPools().firstOrNull { it.hasServer(serverName) }?.name
}
