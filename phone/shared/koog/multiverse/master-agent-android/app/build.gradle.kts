plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "ai.koog.multiverse.android"
    compileSdk = 34
    buildToolsVersion = "33.0.1"

    defaultConfig {
        applicationId = "ai.koog.multiverse.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
        getByName("release") { isMinifyEnabled = false }
    }

    // Reuse the Master Agent + TQUIC transport Kotlin source directly (option c).
    sourceSets["main"].kotlin.srcDirs(
        "../../master-agent/src/jvmMain/kotlin",
        "../../../http-client/http-client-tquic/src/main/kotlin",
    )

    packaging {
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/*.kotlin_module",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/versions/**",
        )
    }
}

// The reused source contains a JVM-only entrypoint (MasterAgentApp.kt with fun main); exclude it.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    sourceSets.getByName("main") {
        kotlin.exclude("**/MasterAgentApp.kt")
    }
}

val koog = "1.1.1"
val ktor = "3.3.3"

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Koog agent framework + LLM clients (Android variants resolved via Gradle metadata)
    implementation("ai.koog:agents-core:$koog")
    implementation("ai.koog:prompt-model:$koog")
    implementation("ai.koog:prompt-llm:$koog")
    implementation("ai.koog:prompt-structure:$koog")
    implementation("ai.koog:prompt-executor-model:$koog")
    implementation("ai.koog:prompt-executor-openai-client:$koog")
    implementation("ai.koog:prompt-markdown:$koog")
    implementation("ai.koog:http-client-core:$koog")
    implementation("ai.koog:http-client-ktor:$koog")

    // Embedded HTTP API server
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("io.ktor:ktor-server-sse:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("io.github.oshai:kotlin-logging:8.0.01")
    implementation("org.slf4j:slf4j-android:1.7.36")
}
