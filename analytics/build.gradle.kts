plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
