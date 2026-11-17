plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cosmosa27.cosmosassistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cosmosa27.cosmosassistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
}
