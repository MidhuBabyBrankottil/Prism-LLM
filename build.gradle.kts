plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.shadow) apply false
}

allprojects {
    group = "io.prism"
    version = "1.0.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
