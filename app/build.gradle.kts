plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.phoneagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.phoneagent"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "0.6"
        ndk { abiFilters.add("arm64-v8a") }
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
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
}
