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
    implementation(libs.ktor.server.body.limit)
    // Plain JDBC on one connection: the event log is one writer and a few readers, so an ORM adds nothing.
    implementation(libs.sqlite.jdbc)
    // The ACP Kotlin SDK: JSON-RPC over the agent process's stdio, and ACP's typed messages.
    implementation(libs.acp)
    runtimeOnly(libs.logback.classic)
    // Draws the pairing QR code in the terminal.
    implementation(libs.qrcodegen)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.kotlin.test)
    // Virtual time for the reader's waits: the approval deadline, the ordering window and the reply check.
    testImplementation(libs.kotlinx.coroutines.test)
    // Decodes the printed pairing QR code, to check it holds what the app will read.
    testImplementation(libs.zxing.core)
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
    // The hook program is tested by running it, so a change to it must rerun the tests.
    inputs.dir("hooks")
}

// The hook program ships next to the start script, at `hooks/rafiq-hook.py` in the installed bridge. Claude Code
// runs it directly, so it must stay executable.
distributions {
    main {
        contents {
            from("hooks") {
                into("hooks")
                filePermissions { unix("rwxr-xr-x") }
            }
        }
    }
}

// Rewrites the event streams the app's tests replay, from the ACP fixtures, through the real adapter
// and event log. Not part of `test`: run it when the mapping changes and review the diff. See
// client/src/test/resources/events/recorded/README.md.
tasks.register<Test>("recordEventFixtures") {
    description = "Records client/src/test/resources/events/recorded/ from the ACP fixtures."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("ai.tatawur.rafiq.bridge.RecordEventFixtures") }
    systemProperty("rafiq.recordEventFixtures", "true")
    systemProperty(
        "rafiq.recordedEventsDir",
        rootProject.layout.projectDirectory
            .dir("client/src/test/resources/events/recorded")
            .asFile.path,
    )
    outputs.upToDateWhen { false }
}
