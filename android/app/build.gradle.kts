import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun quoteForBuildConfig(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) {
            file.inputStream().use { stream -> load(stream) }
        }
    }

fun readConfigValue(
    key: String,
    defaultValue: String = ""
): String {
    val fromGradleProperty = providers.gradleProperty(key).orNull
    if (!fromGradleProperty.isNullOrBlank()) {
        return fromGradleProperty
    }

    val fromEnvironment = providers.environmentVariable(key).orNull
    if (!fromEnvironment.isNullOrBlank()) {
        return fromEnvironment
    }

    val fromLocalProperties = localProperties.getProperty(key)
    if (!fromLocalProperties.isNullOrBlank()) {
        return fromLocalProperties
    }

    return defaultValue
}

val openAiApiKey = readConfigValue("OPENAI_API_KEY")
val birdnetAnalyzerUrl = readConfigValue("BIRDNET_ANALYZER_URL", "")

android {
    namespace = "com.openaiexperiments.birdingbuddy"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.openaiexperiments.birdingbuddy"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "OPENAI_API_KEY", quoteForBuildConfig(openAiApiKey))
        buildConfigField("String", "BIRDNET_ANALYZER_URL", quoteForBuildConfig(birdnetAnalyzerUrl))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
