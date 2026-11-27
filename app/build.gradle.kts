import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val modelSettings = listOf("LUMI_LLM_BASE_URL", "LUMI_LLM_MODEL", "LUMI_LLM_API_KEY")
val gatewaySettings = listOf("LUMI_GATEWAY_URL", "LUMI_GATEWAY_MODEL")
val localSettings = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.reader()?.use { load(it) }
}

fun buildSetting(key: String): String {
    val raw = System.getenv(key)?.takeIf { it.isNotBlank() } ?: localSettings.getProperty(key).orEmpty()
    val clean = raw.trim().takeUnless { s -> s.any(Char::isISOControl) }.orEmpty()
    return "\"" + clean.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

val releaseStoreFilePath = System.getenv("LUMI_SIGNING_STORE_FILE")
    ?: localSettings.getProperty("LUMI_SIGNING_STORE_FILE").orEmpty()
val releaseStorePassword = System.getenv("LUMI_SIGNING_STORE_PASSWORD")
    ?: localSettings.getProperty("LUMI_SIGNING_STORE_PASSWORD").orEmpty()
val releaseKeyAlias = System.getenv("LUMI_SIGNING_KEY_ALIAS")
    ?: localSettings.getProperty("LUMI_SIGNING_KEY_ALIAS").orEmpty()
val releaseKeyPassword = System.getenv("LUMI_SIGNING_KEY_PASSWORD")
    ?: localSettings.getProperty("LUMI_SIGNING_KEY_PASSWORD").orEmpty()
val hasReleaseSigning = releaseStoreFilePath.isNotBlank() &&
    file(releaseStoreFilePath).isFile &&
    releaseStorePassword.isNotBlank() &&
    releaseKeyAlias.isNotBlank() &&
    releaseKeyPassword.isNotBlank()

android {
    namespace = "com.lumi.camera"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.lumi.camera"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.1.2"
        modelSettings.forEach { buildConfigField("String", it, "\"\"") }
        gatewaySettings.forEach { buildConfigField("String", it, buildSetting(it)) }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFilePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        named("debug") {
            modelSettings.forEach { key ->
                buildConfigField("String", key, buildSetting(key))
            }
        }
        named("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Produce lean per-ABI APKs (~45-55 MB instead of ~133 MB universal APK) for direct/beta distribution,
    // while keeping Android App Bundle (.aab) splits cleanly enabled when running bundle* tasks.
    val isBuildingBundle = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
    splits {
        abi {
            isEnable = !isBuildingBundle
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    bundle {
        abi { enableSplit = true }
        density { enableSplit = true }
        language { enableSplit = true }
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    val cameraX = "1.5.2"
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("androidx.camera:camera-extensions:$cameraX")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:object-detection:17.0.2")
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
}
