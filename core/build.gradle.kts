plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    jvmToolchain(17)

    androidTarget()

    // Present so the pure logic in commonMain (relay gate, outbox routing, feed
    // merging) can be unit-tested on a plain JVM without an emulator. Adding
    // iosArm64()/iosSimulatorArm64() here is all that is needed to take the core
    // to iOS: Quartz publishes those targets too, and this module has no
    // platform-specific code.
    jvm()

    sourceSets {
        commonMain.dependencies {
            // The core deliberately has exactly one dependency beyond stdlib.
            // No Quartz, no serialization, no networking, no Android, no UI.
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "app.wayfarer.core"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
}
