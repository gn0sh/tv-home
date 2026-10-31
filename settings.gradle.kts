pluginManagement {
    repositories {
        mavenCentral()
        maven("https://maven.google.com") {
            // dl.google.com (google()) is unreachable from the sandbox; maven.google.com serves the same artifacts.
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://maven.google.com") {
            // dl.google.com (google()) is unreachable from the sandbox; maven.google.com serves the same artifacts.
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

rootProject.name = "tv-home"

include(":core")

// :app needs an Android SDK. Include it only when one is present, so `:core:test` works in the sandbox.
val hasAndroidSdk: Boolean = run {
    val env = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    val lp = file("local.properties")
    val fromProps = lp.exists() && lp.readLines().any { it.trim().startsWith("sdk.dir=") }
    !env.isNullOrBlank() || fromProps
}
if (hasAndroidSdk && file("app/build.gradle.kts").exists()) {
    include(":app")
}
