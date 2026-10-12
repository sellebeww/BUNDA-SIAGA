import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "id.bundasiaga.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "id.bundasiaga.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    // Kunci rilis/unggah dari keystore.properties (jangan pernah di-commit). Tanpa file ini,
    // build rilis tidak ditandatangani; kunci debug tidak pernah dipakai untuk rilis.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    flavorDimensions += "dist"
    productFlavors {
        // Dipasang oleh dinkes (MDM/adb): mengirim dan menerima SMS sendiri, peran gateway tersedia.
        create("pilot") {
            dimension = "dist"
            isDefault = true
            buildConfigField("boolean", "SMS_DIRECT", "true")
        }
        // Build Google Play tanpa izin SMS: teks diserahkan ke aplikasi SMS.
        create("play") {
            dimension = "dist"
            buildConfigField("boolean", "SMS_DIRECT", "false")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        // Izin SMS adalah inti produk; kebijakan Play dibahas di dokumen.
        // Bahasa Indonesia tidak punya bentuk jamak pada teks ini ("3 minggu", "1 minggu").
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "PluralsCandidate")
        abortOnError = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":rules"))
    implementation(project(":codec"))
    implementation(project(":outbox"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
