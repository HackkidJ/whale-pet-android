import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.dsdaughter.pet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dsdaughter.pet"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField(
            "String",
            "DEEPSEEK_API_KEY",
            "\"${localProps.getProperty("deepseek.apiKey", "")}\"",
        )
        buildConfigField(
            "String",
            "VOLC_API_KEY",
            "\"${localProps.getProperty("volc.apiKey", "")}\"",
        )
        buildConfigField(
            "String",
            "VOLC_RESOURCE_ID",
            "\"${localProps.getProperty("volc.resourceId", "seed-tts-2.0")}\"",
        )
        buildConfigField(
            "String",
            "VOLC_VOICE_TYPE",
            "\"${localProps.getProperty("volc.voiceType", "BV700_streaming")}\"",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
