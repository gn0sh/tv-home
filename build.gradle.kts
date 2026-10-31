plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
// android.application is applied only in app/build.gradle.kts (AGP cannot resolve in the sandbox: maven.google.com redirects to blocked dl.google.com). CI resolves it via google().
