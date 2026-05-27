plugins {
    kotlin("jvm") version "2.3.21"
}

group = "top.colter.dynamic"
version = "0.0.1"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("top.colter.bilibili:bilibili-client:0.0.1")

    implementation("top.colter.dynamic:dynamic-bot-core:0.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Builds an executable fat jar with runtime dependencies."
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.exists() }.map { file ->
            if (file.isDirectory) file else zipTree(file)
        }
    })
}

kotlin {
    jvmToolchain(21)
}
