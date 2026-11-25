import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Android app. The Android Gradle plugin compiles Kotlin itself, so only the
// Compose compiler plugin is applied alongside it.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Generates the Room database code for the data layer.
    alias(libs.plugins.ksp)
}

android {
    namespace = "ai.tatawur.rafiq"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        // termlib ships native code for four ABIs. Rafiq runs on arm64 phones only.
        ndk { abiFilters += listOf("arm64-v8a") }
        applicationId = "ai.tatawur.rafiq"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        versionCode = 1
        versionName = providers.gradleProperty("rafiq.version").get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }

    buildFeatures {
        compose = true
    }

    // The release APK is built unsigned and signed afterwards by the release workflow, so no signing key is
    // ever in the Gradle process. RAFIQ_VERSION_CODE lets the release raise versionCode above 1.
    providers.environmentVariable("RAFIQ_VERSION_CODE").orNull?.let { code ->
        defaultConfig.versionCode = code.toInt()
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
    }
}

dependencies {
    implementation(project(":client"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)

    // The data layer: event cache, network, token storage and app lifecycle.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.datastore.preferences)
    implementation(libs.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)

    // Screens, navigation and chat rendering.
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.markdown.renderer.m3)

    // Fold posture for the half-folded layout.
    implementation(libs.window)

    // The Terminal view's emulator.
    implementation(libs.termlib)

    // Push through a UnifiedPush distributor such as ntfy (ADR 0010).
    implementation(libs.unifiedpush.connector)
    // Scanning the pairing QR code: CameraX for the camera, ZXing core to decode. No Google Play services.
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)

    // JVM unit tests for the screens' state holders.
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
