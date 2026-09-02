plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "app.wayfarer.nostr.quartz"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// A plain Android library rather than a multiplatform module: everything here is
// Android-only, so the multiplatform plugin was declaring a single androidTarget()
// and buying nothing for it but the KMP + com.android.library pairing that breaks
// project sync.
dependencies {
    api(project(":core"))

    // The one and only place Quartz is on the compile classpath.
    // `implementation`, not `api`: no Quartz type may leak out of this
    // module. If this line becomes `api`, the isolation is broken.
    implementation(libs.quartz)
    implementation(libs.kotlinx.coroutines.core)

    // Quartz exposes okhttp only at runtime, but this module names
    // BasicOkHttpWebSocket.Builder and OkHttpClient directly.
    implementation(libs.okhttp)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
