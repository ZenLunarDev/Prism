plugins {
    id("java")
    kotlin("jvm") version "2.4.20"
    kotlin("kapt") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    id("xyz.jpenilla.run-velocity") version "3.0.2"
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Core Velocity API
    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    kapt("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")

    // Configuration (HOCON — needed at runtime, must be shaded)
    implementation("org.spongepowered:configurate-hocon:4.2.0")

    // Metrics & Observability
    implementation("io.micrometer:micrometer-core:1.14.0")
    implementation("io.micrometer:micrometer-registry-prometheus:1.13.6")
    implementation("io.micrometer:micrometer-registry-jmx:1.14.0")
    implementation("io.micrometer:micrometer-registry-influx:1.14.0")

    // Coroutines & Flow
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.9.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.7.2")

    // Redis (Cross-proxy sync, caching, sessions)
    implementation("io.lettuce:lettuce-core:6.3.2.RELEASE")

    // Database (HikariCP + SQL)
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.4.2")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    implementation("org.flywaydb:flyway-core:10.20.1")

    // WebSocket / HTTP API (Ktor)
    implementation("io.ktor:ktor-server-core:3.0.3")
    implementation("io.ktor:ktor-server-cio:3.0.3")
    implementation("io.ktor:ktor-server-netty:3.0.3")
    implementation("io.ktor:ktor-server-websockets:3.0.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    implementation("io.ktor:ktor-server-auth:3.0.3")
    implementation("io.ktor:ktor-server-auth-jwt:3.0.3")

    // Module system & DI
    implementation("com.google.guava:guava:33.3.1-jre")
    implementation("org.reflections:reflections:0.10.2")

    // Brigadier (command tree API — provided by Velocity at runtime, needed at compile time)
    compileOnly("com.mojang:brigadier:1.0.18")

    // Utilities
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    implementation("org.apache.commons:commons-lang3:3.15.0")
    implementation("org.apache.commons:commons-text:1.12.0")

    // Logging (provided by Velocity — compileOnly so we never shade over the proxy's own logging)
    compileOnly("ch.qos.logback:logback-classic:1.5.6")
    compileOnly("net.logstash.logback:logstash-logback-encoder:8.0")

    // Adventure (provided by Velocity)
    compileOnly("net.kyori:adventure-api:4.18.0")
    compileOnly("net.kyori:adventure-text-serializer-gson:4.18.0")
    compileOnly("net.kyori:adventure-text-minimessage:4.18.0")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("io.ktor:ktor-server-test-host:3.0.3")
    // API deps are compileOnly for the plugin but must be on the test classpath
    testImplementation("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    testImplementation("net.kyori:adventure-api:4.18.0")
    testImplementation("net.kyori:adventure-text-serializer-plain:4.18.0")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        archiveClassifier.set("")
        manifest {
            attributes(
                "Implementation-Version" to project.version.toString(),
                "Built-By" to System.getProperty("user.name"),
                "Build-Time" to System.currentTimeMillis().toString()
            )
        }
        mergeServiceFiles()
    }

    runVelocity {
        velocityVersion("3.5.0-SNAPSHOT")
        jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.nio=ALL-UNNAMED")
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}

// Kotlin compiler options
tasks.named("compileKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configure {
    compilerOptions {
        freeCompilerArgs = listOf(
            "-Xjvm-default=all",
            "-Xjsr305=strict",
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
            "-opt-in=io.ktor.util.ExperimentalKtorApi"
        )
    }
}

tasks.named("compileTestKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configure {
    compilerOptions {
        freeCompilerArgs = listOf(
            "-Xjvm-default=all",
            "-Xjsr305=strict",
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
            "-opt-in=io.ktor.util.ExperimentalKtorApi"
        )
    }
}