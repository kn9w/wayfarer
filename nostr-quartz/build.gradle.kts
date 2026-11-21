plugins {
    alias(libs.plugins.android.library)
}

// No `kotlin { jvmToolchain(17) }`. Under AGP 9's built-in Kotlin the Kotlin
// `jvmTarget` defaults to `android.compileOptions.targetCompatibility`, set to
// 17 below — so the toolchain block was both unsupported here and saying the
// same thing twice.

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

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Desktop natives for secp256k1, for the unit tests only. These tests do
    // real signing and real verification rather than faking a signature — which
    // is the point of them, and is why they need a secp256k1 that loads on the
    // build machine rather than the Android one Quartz depends on.
    testRuntimeOnly(libs.secp256k1.jni.jvm)
}
