import io.gitlab.arturbosch.detekt.Detekt

plugins {
    // Declared once here so every module shares one copy of each plugin.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

apply(from = "gradle/changelog.gradle.kts")

// Formatting and static analysis run once from the root over every module's
// sources, so a new module is covered without extra setup.
val moduleSourceDirs = listOf("protocol/src", "client/src", "bridge/src", "android/src")

spotless {
    val ktlintVersion = libs.versions.ktlint.get()
    val editorConfig = rootProject.file(".editorconfig")
    kotlin {
        target(moduleSourceDirs.map { "$it/**/*.kt" })
        ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts", "gradle/*.gradle.kts")
        ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("config/detekt/detekt.yml")
    source.setFrom(moduleSourceDirs)
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = libs.versions.jvmTarget.get()
    reports {
        html.required.set(false)
        xml.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}
