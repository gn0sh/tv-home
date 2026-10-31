// CI uses plain google(). Locally (sandbox) dl.google.com is unreachable (403, and Gradle fails instead of
// skipping), so use the maven.google.com mirror restricted to Google groups.
// (pluginManagement must be the first block, so the repo setup is repeated in both blocks.)
pluginManagement {
    repositories {
        mavenCentral()
        if (System.getenv("CI") == "true") {
            google()
        } else {
            maven("https://maven.google.com") {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google\\..*")
                    includeGroupByRegex("androidx.*")
                }
            }
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        if (System.getenv("CI") == "true") {
            google()
        } else {
            maven("https://maven.google.com") {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google\\..*")
                    includeGroupByRegex("androidx.*")
                }
            }
        }
    }
}

rootProject.name = "tv-home"

include(":core")

// :app needs an Android SDK. Include it only when one is present, so `:core:test` works in the sandbox.
val onCi: Boolean = System.getenv("CI") == "true"

fun sdkDirExists(): Boolean {
    val env = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (!env.isNullOrBlank() && file(env).isDirectory) return true
    val lp = file("local.properties")
    if (!lp.exists()) return false
    val dir = lp.readLines().firstOrNull { it.trim().startsWith("sdk.dir=") }
        ?.substringAfter("=")?.trim()?.replace("\\\\", "\\")
    return !dir.isNullOrBlank() && file(dir).isDirectory
}

if (file("app/build.gradle.kts").exists()) {
    if (sdkDirExists()) {
        include(":app")
    } else if (onCi) {
        throw GradleException("CI: app/build.gradle.kts exists but no Android SDK was found (ANDROID_HOME, ANDROID_SDK_ROOT or local.properties sdk.dir)")
    }
}
