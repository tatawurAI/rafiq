import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The bridge server that runs on your server. Built as a plain JVM application,
// so `installDist` gives a start script and `run` starts it locally.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
    }
}

application {
    mainClass.set("ai.tatawur.rafiq.bridge.MainKt")
    applicationName = "rafiq-bridge"
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    // CIO is Ktor's pure-Kotlin engine: fewer dependencies and a smaller footprint than Netty,
    // which suits one long-running process on a small server.
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.status.pages)
    // Plain JDBC on one connection: the event log is one writer and a few readers, so an ORM adds nothing.
    implementation(libs.sqlite.jdbc)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The version reported by GET /health. It is written into a resource so the
// running bridge and the tests read the same value.
tasks.processResources {
    val version = providers.gradleProperty("rafiq.version")
    inputs.property("version", version)
    filesMatching("**/version.properties") {
        expand("version" to version.get())
    }
}

tasks.test {
    useJUnitPlatform()
}
