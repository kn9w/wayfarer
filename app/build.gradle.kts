plugins {
    alias(libs.plugins.android.application)
    // The Compose compiler plugin is still applied by hand: AGP 9's built-in
    // Kotlin replaces kotlin-android and nothing else.
    alias(libs.plugins.kotlin.compose)
}

// See nostr-quartz: jvmTarget follows compileOptions.targetCompatibility now.

android {
    namespace = "app.wayfarer.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.wayfarer"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // versionCode must increase on every upload and is never reused —
        // stores order builds by it. versionName is what people read, and a
        // `-beta.N` suffix is what marks a build as one to try rather than one
        // to rely on.
        versionCode = 1
        versionName = "0.1.0-beta.1"
    }

    buildFeatures {
        compose = true
    }

    /**
     * Signing material comes from the environment, never from this repository.
     *
     * Absent on a normal checkout, which is deliberate: `assembleRelease` then
     * produces an unsigned APK rather than failing, so a contributor — and CI on
     * every push — can prove the release build and R8 both work without holding
     * the upload key.
     */
    val keystore = System.getenv("WAYFARER_KEYSTORE")?.takeIf { it.isNotBlank() }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("WAYFARER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("WAYFARER_KEY_ALIAS")
                keyPassword = System.getenv("WAYFARER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // On, and it is not only about size. Quartz brings a large graph
            // this app uses a corner of — storage, sync and a chess library
            // among it — and shipping the unused remainder is both dead weight
            // and attack surface that R8 can simply remove.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Same shared test doubles the core's own tests use. See core/build.gradle.kts.
    sourceSets.getByName("test").kotlin.srcDir("../core/src/testFixtures/kotlin")

    packaging {
        resources.excludes +=
            setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                // Kotlin's module metadata, dropped rather than shipped. It
                // maps top-level declarations for Kotlin reflection, which this
                // app does not use, and its name comes from whichever module
                // produced it — so one dependency naming itself awkwardly is
                // enough to make an app bundle that bundletool refuses. `core`
                // names itself explicitly for that reason; this covers every
                // jar that does not.
                "META-INF/*.kotlin_module",
            )
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":nostr-quartz"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Pictures, and only from media hosts the user has allowed. The gate is an
    // interceptor on the one client that fetches them — see ImageLoader.
    implementation(libs.okhttp)

    // Only QrScanActivity touches these, and only when the user opens the scanner.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    // Nip55Protocol is pure Kotlin, so its tests run as plain JVM unit tests.
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
