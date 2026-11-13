plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    namespace = "com.swordfish.lemuroid.netplay"

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // The core of this module (protocol, session, TCP transport) is plain Kotlin on purpose: it only depends on the
    // JDK, so it can be unit tested on the JVM. Only the Bluetooth/Wi-Fi helpers touch the Android framework.
    testImplementation("junit:junit:4.13.2")
}
