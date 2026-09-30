plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("id.bundasiaga.server.MainKt")
}

dependencies {
    implementation(project(":rules"))
    implementation(project(":codec"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.logback)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

tasks.test {
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// Alat tanda tangan luring untuk pembaruan aturan: ./gradlew -q :server:rulesTool --args="..." (lihat docs/RULES_UPDATE.md)
tasks.register<JavaExec>("rulesTool") {
    group = "bunda siaga"
    description = "Membuat kunci, memeriksa, menandatangani, dan memverifikasi paket pembaruan aturan"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("id.bundasiaga.server.RulesTool")
    workingDir = rootProject.projectDir
}
