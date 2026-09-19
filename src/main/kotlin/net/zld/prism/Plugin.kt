package net.zld.prism

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Plugin
import org.slf4j.Logger

@Plugin(
    id = "prism",
    name = "Prism",
    version = "1.0-SNAPSHOT",
    description = "Custom core plugin for Prism",
    authors = ["ZenLunarDev"]
)
class PrismPlugin @Inject constructor(val logger: Logger) {

    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        logger.info("Prism Proxy Core successfully enabled!")
    }
}