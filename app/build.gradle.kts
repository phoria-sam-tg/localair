import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing comes from the gitignored local.properties (release.*),
// so the keystore and its password never enter the repo. Without those keys
// a release build is produced unsigned.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.localair.airplay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.localair.airplay"
        minSdk = 28
        targetSdk = 34
        versionCode = 5
        versionName = "1.1.0"
    }

    signingConfigs {
        localProps.getProperty("release.storeFile")?.let { store ->
            create("release") {
                storeFile = file(store)
                storePassword = localProps.getProperty("release.storePassword")
                keyAlias = localProps.getProperty("release.keyAlias")
                keyPassword = localProps.getProperty("release.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Lets a debug build install alongside a signed release instead
            // of fighting it over the same applicationId/signature.
            applicationIdSuffix = ".debug"
            // ...and advertise as a different receiver, so phones on the same
            // network never pick the test build by mistake.
            buildConfigField("boolean", "DEV_RECEIVER", "true")
        }
        release {
            buildConfigField("boolean", "DEV_RECEIVER", "false")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true; buildConfig = true }
}

dependencies {
    implementation(project(":airplay"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
}
