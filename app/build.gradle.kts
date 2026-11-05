plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.slogan.tv.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.slogan.tv"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
    implementation(project(":player"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
}
