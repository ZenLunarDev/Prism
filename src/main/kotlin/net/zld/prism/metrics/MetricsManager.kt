package net.zld.prism.metrics

import io.micrometer.common.KeyValue
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.distribution.HistogramSnapshot
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics
import net.zld.prism.PrismPlugin
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

class MetricsManager(private val plugin: PrismPlugin) {
    private val logger = LoggerFactory.getLogger(MetricsManager::class.java)
    private val registry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    private val tags = ConcurrentHashMap<String, List<Tag>>()
    private val timers = ConcurrentHashMap<String, Timer>()
    private val counters = ConcurrentHashMap<String, Counter>()
    private val gauges = ConcurrentHashMap<String, Gauge>()
    private val histograms = ConcurrentHashMap<String, io.micrometer.core.instrument.DistributionSummary>()
    private val activePlayers = LongAdder()
    private val totalConnections = LongAdder()
    private val totalDisconnections = LongAdder()
    private val proxyErrors = LongAdder()
    private val serverHealthChecks = LongAdder()
    private val fallbackActivations = LongAdder()

    init {
        registerJvmMetrics()
        registerProxyMetrics()
        logger.info("MetricsManager initialized with Prometheus endpoint")
    }

    private fun registerJvmMetrics() {
        JvmMemoryMetrics().bindTo(registry)
        JvmGcMetrics().bindTo(registry)
        JvmThreadMetrics().bindTo(registry)
        ClassLoaderMetrics().bindTo(registry)
        ProcessorMetrics().bindTo(registry)
        FileDescriptorMetrics().bindTo(registry)
    }

    private fun registerProxyMetrics() {
        Gauge.builder("prism.players.online") { activePlayers.sum().toDouble() }.register(registry)
        Gauge.builder("prism.connections.total") { totalConnections.sum().toDouble() }.register(registry)
        Gauge.builder("prism.disconnections.total") { totalDisconnections.sum().toDouble() }.register(registry)
        Gauge.builder("prism.errors.total") { proxyErrors.sum().toDouble() }.register(registry)
        Gauge.builder("prism.health_checks.total") { serverHealthChecks.sum().toDouble() }.register(registry)
        Gauge.builder("prism.fallback.activations") { fallbackActivations.sum().toDouble() }.register(registry)

        Gauge.builder("prism.pools.count") { plugin.getAllPools().size.toDouble() }.register(registry)
        Gauge.builder("prism.servers.registered") { plugin.proxy.allServers.size.toDouble() }.register(registry)
    }

    fun recordPlayerJoin() {
        activePlayers.increment()
        totalConnections.increment()
    }

    fun recordPlayerQuit() {
        activePlayers.decrement()
        totalDisconnections.increment()
    }

    fun recordProxyError() {
        proxyErrors.increment()
    }

    fun recordHealthCheck(healthy: Boolean) {
        serverHealthChecks.increment()
        counter("prism.health_checks", "healthy" to healthy.toString()).increment()
    }

    fun recordFallbackActivation(fromPool: String, toPool: String) {
        fallbackActivations.increment()
        counter("prism.fallback.activations", "from" to fromPool, "to" to toPool).increment()
    }

    fun recordServerSwitch(player: String, fromPool: String, toPool: String) {
        counter("prism.server.switches", "from" to fromPool, "to" to toPool).increment()
    }

    fun recordCommandExecution(command: String, success: Boolean, durationMs: Long) {
        timer("prism.commands", "command" to command, "success" to success.toString()).record(Duration.ofMillis(durationMs))
    }

    fun recordPoolSelection(pool: String, durationMs: Long) {
        timer("prism.pool.selection", "pool" to pool).record(Duration.ofMillis(durationMs))
    }

    fun recordConnectionAttempt(server: String, success: Boolean, durationMs: Long) {
        timer("prism.connection.attempt", "server" to server, "success" to success.toString()).record(Duration.ofMillis(durationMs))
    }

    fun recordDatabaseQuery(query: String, success: Boolean, durationMs: Long) {
        timer("prism.db.query", "query" to query, "success" to success.toString()).record(Duration.ofMillis(durationMs))
    }

    fun recordSyncEvent(eventType: String, targetProxy: String, durationMs: Long) {
        timer("prism.sync.event", "type" to eventType, "target" to targetProxy).record(Duration.ofMillis(durationMs))
    }

    fun recordWebSocketMessage(messageType: String, sizeBytes: Int) {
        counter("prism.ws.messages", "type" to messageType).increment()
        histogram("prism.ws.message_size", "type" to messageType).record(sizeBytes.toDouble())
    }

    fun recordPoolSize(pool: String, total: Int, healthy: Int) {
        setGauge("prism.pool.servers.total", value = total.toDouble(), "pool" to pool)
        setGauge("prism.pool.servers.healthy", value = healthy.toDouble(), "pool" to pool)
    }

    private fun setGauge(name: String, value: Double, vararg kv: Pair<String, String>) {
        gauge(name, *kv)
        val key = name + kv.joinToString("_") { "${it.first}=${it.second}" }
        gaugeValues[key]?.set(value)
    }

    fun getPrometheusOutput(): String {
        return registry.scrape()
    }

    fun getRegistry(): MeterRegistry = registry

    private fun counter(name: String, vararg kv: Pair<String, String>): Counter {
        val key = name + kv.joinToString("_") { "${it.first}=${it.second}" }
        return counters.computeIfAbsent(key) {
            Counter.builder(name).tags(kv.map { Tag.of(it.first, it.second) }).register(registry)
        }
    }

    private fun timer(name: String, vararg kv: Pair<String, String>): Timer {
        val key = name + kv.joinToString("_") { "${it.first}=${it.second}" }
        return timers.computeIfAbsent(key) {
            Timer.builder(name).tags(kv.map { Tag.of(it.first, it.second) }).register(registry)
        }
    }

    private val gaugeValues = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicReference<Double>>()

    private fun gauge(name: String, vararg kv: Pair<String, String>): Gauge {
        val key = name + kv.joinToString("_") { "${it.first}=${it.second}" }
        return gauges.computeIfAbsent(key) {
            val holder = gaugeValues.computeIfAbsent(key) { java.util.concurrent.atomic.AtomicReference(0.0) }
            Gauge.builder(name) { holder.get() }
                .tags(kv.map { Tag.of(it.first, it.second) })
                .register(registry)
        }
    }

    private fun histogram(name: String, vararg kv: Pair<String, String>): io.micrometer.core.instrument.DistributionSummary {
        val key = name + kv.joinToString("_") { "${it.first}=${it.second}" }
        return histograms.computeIfAbsent(key) {
            io.micrometer.core.instrument.DistributionSummary.builder(name).tags(kv.map { Tag.of(it.first, it.second) }).register(registry)
        }
    }

    fun shutdown() {
        registry.close()
        logger.info("MetricsManager shutdown")
    }
}