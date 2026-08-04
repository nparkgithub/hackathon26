import ai.koog.gradle.publish.maven.Publishing.publishToMaven

val isBeta by extra(true)

plugins {
    id("ai.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":http-client:http-client-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.oshai.kotlin.logging)

    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.kotlinx.coroutines.test)
}

publishToMaven()
