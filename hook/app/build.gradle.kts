plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.chx.claudeprivacy"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.chx.claudeprivacy"
        minSdk = 32
        targetSdk = 35
        versionCode = 5
        versionName = "0.1.5"
    }

    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("androidx.webkit:webkit:1.18.0")
}
