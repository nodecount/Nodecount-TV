import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nodecount.tvlauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nodecount.tvlauncher"
        minSdk = 28
        targetSdk = 34
        versionCode = 4
        versionName = "1.4"
    }

    // Signature release : lue dans launcher/keystore.properties (non versionné)
    val ksFile = rootProject.file("keystore.properties")
    if (ksFile.exists()) {
        val ks = Properties().apply { ksFile.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = file(ks.getProperty("storeFile"))
                storePassword = ks.getProperty("storePassword")
                keyAlias = ks.getProperty("keyAlias")
                keyPassword = ks.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    // Client ADB : le nettoyage des applis préinstallées se connecte à l'ADB de la TV elle-même (localhost)
    implementation("dev.mobile:dadb:1.2.10")
}
