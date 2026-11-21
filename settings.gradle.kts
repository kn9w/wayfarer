pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // androidx.* and com.android.* live only here. Quartz pulls androidx.collection,
        // androidx.sqlite and androidx.compose.runtime:runtime-annotation transitively,
        // so this repository is required even for the non-UI modules.
        google()
        mavenCentral()
    }
}

rootProject.name = "wayfarer"

include(":core")
include(":nostr-quartz")
include(":app")
