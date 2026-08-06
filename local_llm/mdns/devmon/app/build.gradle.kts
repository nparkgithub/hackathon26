import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.devmon"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.devmon"
        // Koog's supported Android OpenAI transport requires API 35.
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val koogVersion = "1.0.0-preview7"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("ai.koog:prompt-executor-openai-client:$koogVersion")
    implementation("ai.koog:http-client-ktor:$koogVersion")
    // Koog delegates HTTP to Ktor; Android needs a concrete engine at runtime.
    implementation("io.ktor:ktor-client-okhttp:3.3.3")

    testImplementation("junit:junit:4.13.2")
    // Android's built-in org.json is a stub that throws off-device; this pulls in a real impl for JVM unit tests.
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
