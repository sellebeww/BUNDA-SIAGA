plugins {
    alias(libs.plugins.kotlin.jvm)

}

kotlin { jvmToolchain(17) }

dependencies {

    testImplementation(project(":rules"))

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

tasks.test {
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = true }
}
