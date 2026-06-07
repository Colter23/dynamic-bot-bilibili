plugins {
    kotlin("jvm") version "2.3.21"
}

apply(from = "gradle/dynamic-plugin-fatjar.gradle.kts")

group = "top.colter.dynamic"
version = "0.0.1"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    val coreVersion = "0.0.6"
    val kotlinLoggingVersion = "7.0.0"

    implementation("top.colter.bilibili:bilibili-client:0.0.1")

    compileOnly("top.colter.dynamic:dynamic-bot-core:$coreVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    compileOnly("io.github.oshai:kotlin-logging-jvm:$kotlinLoggingVersion")

    testImplementation(kotlin("test"))
    testImplementation("top.colter.dynamic:dynamic-bot-core:$coreVersion")
    testImplementation("io.github.oshai:kotlin-logging-jvm:$kotlinLoggingVersion")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
