import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

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

// ---- Self-contained bridge download (release) -------------------------------------------------------------
// `bridgeArchive` builds build/distributions/rafiq-bridge-<version>-<os>-<arch>.tar.gz: the installed bridge
// plus a trimmed Java runtime made with jlink, so a machine with no JDK can run it. jlink makes a runtime for
// the machine it runs on, so build each platform's archive on that platform. The release workflow does.
// Only the Java 21 toolchain's jlink is used. The Gradle JVM may be a different version.

// The modules the bridge needs at run time. Start from `jdeps --print-module-deps` over the installed
// bridge's lib folder, then add what it cannot see: java.net.http and java.logging (push and logback),
// java.xml (logback's configuration), jdk.crypto.ec (P-256 keys for push) and java.naming (SQLite and JNDI
// lookups in logging). A missing module shows up as NoClassDefFoundError, so the release build starts the
// image and checks /health before it uploads anything.
val bridgeImageModules =
    listOf(
        "java.base",
        "java.desktop",
        "java.instrument",
        "java.logging",
        "java.management",
        "java.naming",
        "java.net.http",
        "java.sql",
        "java.xml",
        "jdk.crypto.ec",
        "jdk.net",
        "jdk.unsupported",
    )

abstract class JlinkRuntime : DefaultTask() {
    @get:Input
    abstract val javaHome: Property<String>

    @get:Input
    abstract val modules: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun link() {
        val out = outputDir.get().asFile
        // jlink refuses to write into an existing folder.
        out.deleteRecursively()
        val jlink = File(javaHome.get(), "bin/jlink")
        exec.exec {
            commandLine(
                jlink.path,
                "--add-modules",
                modules.get().joinToString(","),
                "--strip-debug",
                "--no-header-files",
                "--no-man-pages",
                "--compress",
                "zip-6",
                "--output",
                out.path,
            )
        }
    }
}

// "macos-arm64", "linux-x64" and so on. Override with -Prafiq.platform=... only for a dry run.
val bridgePlatform =
    providers.gradleProperty("rafiq.platform").orElse(
        providers.provider {
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()
            val osName =
                if (os.contains("mac")) {
                    "macos"
                } else if (os.contains("linux")) {
                    "linux"
                } else {
                    os.filter { it.isLetter() }
                }
            val archName =
                if (arch == "aarch64" ||
                    arch == "arm64"
                ) {
                    "arm64"
                } else if (arch == "amd64" || arch == "x86_64") {
                    "x64"
                } else {
                    arch
                }
            "$osName-$archName"
        },
    )

val rafiqVersion = providers.gradleProperty("rafiq.version")

val jlinkRuntime =
    tasks.register<JlinkRuntime>("jlinkRuntime") {
        description = "Builds a trimmed Java runtime for this machine with jlink."
        group = "distribution"
        javaHome.set(
            javaToolchains
                .launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
                .map { it.metadata.installationPath.asFile.path },
        )
        modules.set(bridgeImageModules)
        outputDir.set(layout.buildDirectory.dir("jlink/runtime"))
    }

// The image: bin/rafiq-bridge is a small wrapper that points JAVA_HOME at runtime/ and runs the Gradle start
// script, which is shipped as bin/rafiq-bridge-jvm. lib/ and hooks/ come from installDist unchanged.
val bridgeImage =
    tasks.register<Sync>("bridgeImage") {
        description = "Assembles the self-contained bridge folder."
        group = "distribution"
        val name = bridgePlatform.map { "rafiq-bridge-${rafiqVersion.get()}-$it" }
        into(layout.buildDirectory.dir("image"))
        into(name) {
            from(tasks.installDist.map { it.destinationDir }) {
                exclude("bin/**")
            }
            from(tasks.installDist.map { it.destinationDir.resolve("bin/rafiq-bridge") }) {
                into("bin")
                rename { "rafiq-bridge-jvm" }
                filePermissions { unix("rwxr-xr-x") }
            }
            from(rootProject.file("packaging/rafiq-bridge")) {
                into("bin")
                filePermissions { unix("rwxr-xr-x") }
            }
            from(jlinkRuntime) { into("runtime") }
            from(rootProject.file("LICENSE"))
            from(rootProject.file("NOTICE"))
        }
    }

tasks.register<Tar>("bridgeArchive") {
    description = "Packs the self-contained bridge as a .tar.gz."
    group = "distribution"
    archiveFileName.set(bridgePlatform.map { "rafiq-bridge-${rafiqVersion.get()}-$it.tar.gz" })
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    compression = Compression.GZIP
    from(bridgeImage.map { it.destinationDir }) {
        // Keep the executable bit on the start scripts, the hook and the runtime's programs.
        eachFile {
            if (!isDirectory && file.canExecute()) permissions { unix("rwxr-xr-x") }
        }
    }
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}
