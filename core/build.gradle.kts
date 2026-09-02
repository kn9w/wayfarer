plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)

    // No Android target, and so no Android Gradle plugin. This module has no
    // Android source — only commonMain — so an androidTarget() built an AAR with
    // nothing Android in it, and applying com.android.library alongside the
    // multiplatform plugin is the combination AGP is dropping.
    //
    // :app consumes this anyway: an androidJvm consumer resolves a jvm producer,
    // which is the same rule that lets an Android app depend on any plain
    // Kotlin/JVM library. Adding iosArm64()/iosSimulatorArm64() here is still all
    // that is needed to take the core to iOS.
    jvm()

    sourceSets {
        commonMain.dependencies {
            // The core deliberately has exactly one dependency beyond stdlib.
            // No Quartz, no serialization, no networking, no Android, no UI.
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest {
            // Test doubles shared with the app module's unit tests. Kept out of
            // commonMain so they never ship, and in their own directory rather
            // than under commonTest/kotlin so the app can add the same directory
            // to its own test source set — a KMP module cannot conveniently
            // publish a test artifact for the app to depend on instead.
            kotlin.srcDir("src/testFixtures/kotlin")

            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
