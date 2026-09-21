package net.zld.prism.standalone

import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.anvil.AnvilLoader
import net.minestom.server.instance.block.Block
import net.zld.prism.api.ExtensionManager
import net.zld.prism.config.PrismConfig
import net.zld.prism.world.EmbeddedWorldCommands
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Scanner

/**
 * PrismMC standalone launcher — `java -jar prism.jar` boots a complete,
 * self-contained Minecraft server: no proxy, no Velocity, no external backend.
 *
 * Configuration comes from prism.conf in the working directory (Purpur-style
 * deep sections). Worlds persist to disk via Anvil. Extensions from
 * extensions/ are loaded exactly like in proxy mode.
 */
object PrismStandalone {

    private val logger = LoggerFactory.getLogger("prism")

    private const val BANNER = """

         ██▓███   ██▀███   ██▓  ██████  ███▄ ▄███▓    ███▄ ▄███▓ ▄████▄  
        ▓██░  ██▒▓██ ▒ ██▒▓██▒▒██    ▒ ▓██▒▀█▀ ██▒   ▓██▒▀█▀ ██▒▒██▀ ▀█  
        ▓██░ ██▓▒▓██ ░▄█ ▒▒██▒░ ▓██▄   ▓██    ▓██░   ▓██    ▓██░▒▓█    ▄ 
        ▒██▄█▓▒ ▒▒██▀▀█▄  ░██░  ▒   ██▒▒██    ▒██    ▒██    ▒██ ▒▓▓▄ ▄██▒
        ▒██▒ ░  ░░██▓ ▒██▒░██░▒██████▒▒▒██▒   ░██▒   ▒██▒   ░██▒▒ ▓███▀ ░
        ▒▓▒░ ░  ░░ ▒▓ ░▒▓░░▓  ▒ ▒▓▒ ▒ ░░ ▒░   ░  ░   ░ ▒░   ░  ░░ ░▒ ▒  ░
        ░▒ ░       ░▒ ░ ▒░ ▒ ░░ ░▒  ░ ░░  ░      ░   ░  ░      ░  ░  ▒   
        ░░         ░░   ░  ▒ ░░  ░  ░  ░      ░      ░      ░   ░        
                    ░      ░        ░         ░             ░   ░ ░      
                                                                ░        
"""

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run(args)
        } catch (t: Throwable) {
            // Last-resort reporting that survives even a broken logger setup
            System.err.println("PrismMC failed to start:")
            t.printStackTrace()
        }
    }

    private fun run(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
        val start = System.currentTimeMillis()
        println(BANNER)

        // Accept the EULA by default on first boot? No — Paper-compatible
        // behaviour: refuse to start without eula.txt=eula-accepted.
        val eulaFile = Paths.get("eula.txt")
        if (!Files.exists(eulaFile) || !readFile(eulaFile).contains("eula=true")) {
            Files.writeString(eulaFile, "# Accepted by running the server once? Set eula=true\neula=false\n")
            logger.error("You must accept the Minecraft EULA: set 'eula=true' in eula.txt")
            logger.error("https://aka.ms/MinecraftEULA")
            return
        }

        // Load (or create) the Purpur-style config — standalone defaults are
        // pre-wired for the built-in world (embedded-world.enabled = true)
        val configFile = Paths.get("prism.conf").toFile()
        if (!configFile.exists()) {
            val defaults = PrismConfig(
                fallback = net.zld.prism.config.FallbackChainDefinition(emptyList(), "prism-world"),
                embeddedWorld = net.zld.prism.config.EmbeddedWorldSettingsDefinition(enabled = true),
            )
            PrismConfig.save(defaults, configFile)
            logger.info("Wrote default prism.conf (standalone profile) — tune it and restart")
        }
        val report = PrismConfig.loadWithReport(configFile)
        if (!report.valid) {
            report.errors.forEach { logger.error("Config error: {}", it) }
            return
        }
        report.warnings.forEach { logger.warn("Config warning: {}", it) }
        report.migrationsApplied.forEach { logger.info("Config migration: {}", it) }
        val config = report.config
        val ew = config.embeddedWorld

        // Extensions (same loader as proxy mode) — standalone has no pools
        val extensionManager = ExtensionManager(object : net.zld.prism.api.ExtensionManager.PluginServices {
            override val dataDirectory = java.io.File(".")
            override fun getVersion() = "standalone"
            override fun getAllPools(): Collection<net.zld.prism.server.ServerPool> = emptyList()
            override fun getPool(name: String): net.zld.prism.server.ServerPool? = null
        })
        extensionManager.loadAll()

        // Boot Minestom
        System.setProperty("minestom.brand-name", ew.brandName)
        val server = MinecraftServer.init()

        val worldDir = Paths.get(ew.worldFolder)
        val instance: InstanceContainer = MinecraftServer.getInstanceManager()
            .createInstanceContainer(AnvilLoader(worldDir))
        instance.setGenerator { unit ->
            unit.modifier().fillHeight(0, ew.generation.height, Block.GRASS_BLOCK)
        }

        MinecraftServer.getGlobalEventHandler().addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
            event.spawningInstance = instance
            event.player.respawnPoint = Pos(ew.spawnX, ew.spawnY, ew.spawnZ)
            logger.info("{} joined the game", event.player.username)
        }

        EmbeddedWorldCommands.registerAll()

        val listenHost = if (ew.host == "127.0.0.1") "0.0.0.0" else ew.host
        server.start(listenHost, ew.port)
        val bootSeconds = (System.currentTimeMillis() - start) / 1000.0
        logger.info("PrismMC {} started in {}s — listening on {}:{}", config.configVersion, bootSeconds, listenHost, ew.port)
        logger.info("Type 'stop' to save and shut down.")

        // Console loop — commands go straight to Minestom's command manager
        val scanner = Scanner(System.`in`)
        while (true) {
            if (!scanner.hasNextLine()) { Thread.sleep(200); continue }
            val line = scanner.nextLine().trim()
            if (line.isEmpty()) continue
            if (line.equals("stop", ignoreCase = true) || line.equals("exit", ignoreCase = true)) {
                logger.info("Stopping — saving world...")
                runCatching { instance.saveChunksToStorage().get() }
                extensionManager.unloadAll()
                MinecraftServer.stopCleanly()
                logger.info("World saved. PrismMC stopped.")
                return
            }
            val result = MinecraftServer.getCommandManager().executeServerCommand(line)
            if (result.type == net.minestom.server.command.builder.CommandResult.Type.UNKNOWN) {
                logger.warn("Unknown command: {}", line)
            }
        }
    }

    private fun readFile(path: java.nio.file.Path): String =
        try { Files.readString(path) } catch (_: Exception) { "" }
}
