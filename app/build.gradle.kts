plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "uk.co.cgunner.meetingcoach"
    compileSdk = 35

    defaultConfig {
        applicationId = "uk.co.cgunner.meetingcoach"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
}
