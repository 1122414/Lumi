import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val modelSettings = listOf("LUMI_LLM_BASE_URL", "LUMI_LLM_MODEL", "LUMI_LLM_API_KEY")
val localSettings = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.reader()?.use { load(it) }
}

android {
    namespace = "com.lumi.camera"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.lumi.camera"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        modelSettings.forEach { buildConfigField("String", it, "\"\"") }
    }
    buildTypes.named("debug") {
        modelSettings.forEach { key ->
            val value = localSettings.getProperty(key).orEmpty().trim().takeUnless { s -> s.any(Char::isISOControl) }.orEmpty()
            buildConfigField("String", key, "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        }
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
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
}
