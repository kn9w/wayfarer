plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    jvmToolchain(17)

    androidTarget()

    sourceSets {
        androidMain.dependencies {
            api(project(":core"))

            // The one and only place Quartz is on the compile classpath.
            // `implementation`, not `api`: no Quartz type may leak out of this
            // module. If this line becomes `api`, the isolation is broken.
            implementation(libs.quartz)
            implementation(libs.kotlinx.coroutines.core)

            // Quartz exposes okhttp only at runtime, but this module names
            // BasicOkHttpWebSocket.Builder and OkHttpClient directly.
            implementation(libs.okhttp)
        }

        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "app.wayfarer.nostr.quartz"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
}
