// CI uses google() restricted to Google's groups (security finding supply-chain-tofu-google). Locally (sandbox) dl.google.com is unreachable (403, and Gradle fails instead of
// skipping), so use the maven.google.com mirror restricted to Google groups.
// (pluginManagement must be the first block, so the repo setup is repeated in both blocks.)
pluginManagement {
    repositories {
        mavenCentral()
        if (System.getenv("CI") == "true") {
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google\\..*")
                    includeGroupByRegex("androidx.*")
                }
            }
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
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google\\..*")
                    includeGroupByRegex("androidx.*")
                }
            }
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

// :app is included only on explicit opt-in: TVHOME_BUILD_APP=true, set only in the app job of
// .github/workflows/android.yml. Runners and dev PCs often have an Android SDK (ubuntu-latest sets ANDROID_HOME), and
// configuring :app resolves AGP and Compose, which the core and live jobs and the sandbox must never do.
val onCi: Boolean = System.getenv("CI") == "true"
val buildApp: Boolean = System.getenv("TVHOME_BUILD_APP") == "true"

fun sdkDirExists(): Boolean {
    val env = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (!env.isNullOrBlank() && file(env).isDirectory) return true
    val lp = file("local.properties")
    if (!lp.exists()) return false
    val dir = lp.readLines().firstOrNull { it.trim().startsWith("sdk.dir=") }
        ?.substringAfter("=")?.trim()?.replace("\\\\", "\\")
    return !dir.isNullOrBlank() && file(dir).isDirectory
}

if (buildApp && file("app/build.gradle.kts").exists()) {
    if (sdkDirExists()) {
        include(":app")
    } else if (onCi) {
        throw GradleException("CI: TVHOME_BUILD_APP=true and app/build.gradle.kts exists but no Android SDK was found (ANDROID_HOME, ANDROID_SDK_ROOT or local.properties sdk.dir)")
    }
}
