plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":analytics"))
    implementation(project(":storage"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
