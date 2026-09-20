package net.zld.prism.module

import com.google.inject.Injector
import kotlin.reflect.KClass
import net.zld.prism.PrismPlugin
import net.zld.prism.paper.ChatManager
import net.zld.prism.paper.CommandAPIManager
import net.zld.prism.paper.PermissionManager
import net.zld.prism.paper.PlayerManager
import org.reflections.Reflections
import org.reflections.scanners.Scanners
import org.reflections.util.ClasspathHelper
import org.reflections.util.ConfigurationBuilder
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.jar.JarFile

interface PrismModule {
    val name: String
    val version: String
    val description: String
    val authors: List<String>
    val dependencies: List<String> get() = emptyList()
    val softDependencies: List<String> get() = emptyList()

    fun onEnable(plugin: PrismPlugin, context: ModuleContext)
    fun onDisable()
    fun onReload()
}

interface ModuleContext {
    val plugin: PrismPlugin
    val injector: Injector
    val config: ModuleConfig
    fun getModule(name: String): PrismModule?
    fun <T : PrismModule> getModule(clazz: KClass<T>): T?
    fun registerCommand(name: String, aliases: List<String>, permission: String?, builder: com.mojang.brigadier.builder.LiteralArgumentBuilder<com.velocitypowered.api.command.CommandSource>.() -> Unit)
    fun registerEventListener(listener: Any)
    fun getScheduler(): kotlinx.coroutines.CoroutineScope
    fun getDataFolder(): File
}

data class ModuleConfig(
    val enabled: Boolean = true,
    val config: Map<String, Any> = emptyMap(),
)

class ModuleManager(
    private val plugin: PrismPlugin,
    private val injector: Injector,
) {
    private val logger = LoggerFactory.getLogger(ModuleManager::class.java)
    private val modules = ConcurrentHashMap<String, ModuleInstance>()
    private val loadOrder = CopyOnWriteArrayList<String>()
    private val moduleDir = File(plugin.dataDirectory, "modules")
    private val classLoaders = ConcurrentHashMap<String, URLClassLoader>()

    data class ModuleInstance(
        val module: PrismModule,
        val classLoader: ClassLoader,
        val file: File,
        var enabled: Boolean = true,
    )

    fun initialize() {
        moduleDir.mkdirs()
        loadModules()
        enableModules()
        logger.info("ModuleManager initialized with {} modules", modules.size)
    }

    private fun loadModules() {
        val reflections = Reflections(ConfigurationBuilder()
            .setUrls(ClasspathHelper.forPackage("net.zld.prism.modules"))
            .setScanners(Scanners.SubTypes)
        )

        val moduleClasses = reflections.getSubTypesOf(PrismModule::class.java)
            .filter { !it.isInterface && !Modifier.isAbstract(it.modifiers) }

        moduleClasses.forEach { clazz ->
            try {
                val module = clazz.getDeclaredConstructor().newInstance() as PrismModule
                val classLoader = this::class.java.classLoader
                val instance = ModuleInstance(module, classLoader, File(""), true)
                modules[module.name] = instance
                logger.debug("Found built-in module: {}", module.name)
            } catch (e: Exception) {
                logger.warn("Failed to load built-in module {}: {}", clazz.name, e.message)
            }
        }

        // Load external modules from JARs
        moduleDir.listFiles()?.filter { it.extension == "jar" }?.forEach { jar ->
            loadJarModule(jar)
        }
    }

    private fun loadJarModule(jar: File) {
        try {
            val urls = arrayOf(jar.toURI().toURL())
            val classLoader = URLClassLoader(urls, this::class.java.classLoader)
            val jarFile = JarFile(jar)
            val manifest = jarFile.manifest
            val mainClass = manifest?.mainAttributes?.getValue("Module-Class")
                ?: manifest?.mainAttributes?.getValue("Main-Class")

            if (mainClass == null) {
                logger.warn("No Module-Class in manifest for {}", jar.name)
                return
            }

            val clazz = classLoader.loadClass(mainClass)
            val module = clazz.getDeclaredConstructor().newInstance() as PrismModule

            val instance = ModuleInstance(module, classLoader, jar, true)
            classLoaders[module.name] = classLoader
            modules[module.name] = instance
            logger.info("Loaded external module: {} v{} from {}", module.name, module.version, jar.name)
        } catch (e: Exception) {
            logger.error("Failed to load module from {}: {}", jar.name, e.message, e)
        }
    }

    private fun enableModules() {
        val sorted = topologicalSort()
        sorted.forEach { name ->
            val instance = modules[name] ?: return@forEach
            if (!instance.enabled) return@forEach

            try {
                val context = ModuleContextImpl(plugin, injector)
                instance.module.onEnable(plugin, context)
                loadOrder.add(name)
                logger.info("Enabled module: {} v{}", name, instance.module.version)
            } catch (e: Exception) {
                logger.error("Failed to enable module {}: {}", name, e.message, e)
                instance.enabled = false
            }
        }
    }

    private fun topologicalSort(): List<String> {
        val visited = mutableSetOf<String>()
        val temp = mutableSetOf<String>()
        val result = mutableListOf<String>()

        fun visit(name: String) {
            if (name in visited) return
            if (name in temp) {
                logger.warn("Circular dependency detected involving {}", name)
                return
            }
            temp.add(name)

            val module = modules[name]?.module ?: return
            module.dependencies.forEach { dep ->
                if (modules.containsKey(dep)) visit(dep)
            }
            module.softDependencies.forEach { dep ->
                if (modules.containsKey(dep)) visit(dep)
            }

            temp.remove(name)
            visited.add(name)
            result.add(name)
        }

        modules.keys.forEach { visit(it) }
        return result
    }

    fun reloadModule(name: String) {
        val instance = modules[name] ?: return
        try {
            instance.module.onDisable()
            val context = ModuleContextImpl(plugin, injector)
            instance.module.onEnable(plugin, context)
            logger.info("Reloaded module: {}", name)
        } catch (e: Exception) {
            logger.error("Failed to reload module {}: {}", name, e.message, e)
        }
    }

    fun reloadAll() {
        modules.values.forEach { it.module.onDisable() }
        enableModules()
    }

    fun getModule(name: String): PrismModule? = modules[name]?.module

    fun <T : PrismModule> getModule(clazz: KClass<T>): T? {
        return modules.values.firstOrNull { clazz.isInstance(it.module) }?.module as T?
    }
    fun getLoadedModules(): List<PrismModule> = modules.values.map { it.module }.toList()

    fun shutdown() {
        loadOrder.reversed().forEach { name ->
            modules[name]?.module?.onDisable()
        }
        classLoaders.values.forEach { it.close() }
        logger.info("ModuleManager shutdown")
    }

    inner class ModuleContextImpl(override val plugin: PrismPlugin, override val injector: Injector) : ModuleContext {
        override val config: ModuleConfig = ModuleConfig()

        override fun getModule(name: String): PrismModule? = modules[name]?.module

        override fun <T : PrismModule> getModule(clazz: KClass<T>): T? = this@ModuleManager.getModule(clazz)

        override fun registerCommand(name: String, aliases: List<String>, permission: String?, builder: com.mojang.brigadier.builder.LiteralArgumentBuilder<com.velocitypowered.api.command.CommandSource>.() -> Unit) {
            val apiManager = injector.getInstance(CommandAPIManager::class.java)
            apiManager.registerCommand(name, aliases, permission, builder)
        }

        override fun registerEventListener(listener: Any) {
            plugin.proxy.eventManager.register(plugin, listener)
        }

        override fun getScheduler(): kotlinx.coroutines.CoroutineScope {
            return kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        }

        override fun getDataFolder(): File = plugin.dataDirectory
    }
}