group = "${rootProject.group}.multiverse"
version = rootProject.version

val isBeta by extra(true)

plugins {
    id("ai.kotlin.multiplatform.server")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)

    sourceSets {
        jvmMain {
            dependencies {
                // Koog agent framework + LLM clients
                implementation(project(":agents:agents-core"))
                implementation(project(":prompt:prompt-executor:prompt-executor-model"))
                implementation(project(":prompt:prompt-executor:prompt-executor-llms-all"))
                implementation(
                    project(":prompt:prompt-executor:prompt-executor-clients:prompt-executor-openai-client")
                )
                implementation(project(":prompt:prompt-structure"))
                implementation(project(":prompt:prompt-markdown"))

                // Multiverse TQUIC transport (remote route; JNI scaffold)
                implementation(project(":http-client:http-client-tquic"))
                implementation(project(":http-client:http-client-core"))
                // Default Ktor HTTP transport for the local OpenAI-compatible route
                implementation(project(":http-client:http-client-ktor"))

                // Embedded HTTP API server
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.sse)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)

                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.oshai.kotlin.logging)
                runtimeOnly(libs.logback.classic)
            }
        }

        jvmTest {
            dependencies {
                implementation(kotlin("test-junit5"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.server.test.host)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.client.content.negotiation)
            }
        }
    }
}

// Run the Master Agent app: ./gradlew :multiverse:master-agent:runMasterAgent
tasks.register<JavaExec>("runMasterAgent") {
    group = "application"
    description = "Starts the Koog Master Agent HTTP server"
    mainClass.set("ai.koog.multiverse.MasterAgentAppKt")
    classpath = kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles +
        kotlin.jvm().compilations.getByName("main").output.allOutputs
    doFirst {
        standardInput = System.`in`
        standardOutput = System.out
    }
}
