plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "local.smartdisplay"
    compileSdk = 34

    defaultConfig {
        applicationId = "local.smartdisplay"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // assets を https://appassets.androidplatform.net/ として安全に配信するために使用
    implementation("androidx.webkit:webkit:1.11.0")
}
