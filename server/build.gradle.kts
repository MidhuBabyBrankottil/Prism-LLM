plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}

application {
    mainClass.set("io.prism.server.ApplicationKt")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":analytics"))
    implementation(project(":storage"))
    implementation(project(":proxy"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("prism-llm-server")
    archiveClassifier.set("all")
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()
}
