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
    jvm {
        compilerOptions {
            // Named, rather than derived from the Gradle path. The default is
            // built from the project path, which contains a colon — and the
            // `.kotlin_module` file it names goes into the APK and the app
            // bundle. A zip tolerates a colon in an entry name; bundletool
            // rejects it outright, so `assembleRelease` passed and
            // `bundleRelease` failed with "Entry name contains invalid
            // characters", which is a tag-time discovery for a build that had
            // been green for weeks.
            moduleName.set("wayfarer-core")
        }
    }

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
                // Left as the helper here, unlike the Android modules: `core`
                // applies the Kotlin multiplatform plugin, which is what
                // supplies the version and picks the framework variant. The
                // modules AGP compiles have no such plugin any more and so name
                // the artifact in full.
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
