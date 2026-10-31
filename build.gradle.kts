plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
// android.application is declared (apply false) in the :app task: AGP cannot resolve in the sandbox (maven.google.com redirects to blocked dl.google.com).
