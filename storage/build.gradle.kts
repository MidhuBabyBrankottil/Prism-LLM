plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":analytics"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sqlite.jdbc)
    implementation(libs.hikaricp)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
