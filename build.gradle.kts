// No kotlin-android here. AGP 9 compiles Kotlin itself — applying the separate
// plugin alongside it is a hard error, not a redundancy. The multiplatform
// plugin stays: built-in Kotlin replaces kotlin-android only, and `core` is a
// KMP module that applies no Android plugin at all.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
