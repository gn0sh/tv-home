buildscript {
    if (System.getenv("TVHOME_BUILD_APP") == "true") {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools.build:gradle:${libs.versions.agp.get()}")
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
// AGP must sit on the same (root) classloader as the Kotlin Gradle plugin, or kotlin.android cannot see AGP classes
// ("Could not generate a decorated class for type KotlinAndroidTarget > com/android/build/gradle/api/BaseVariant").
// It is added only with TVHOME_BUILD_APP=true, so the core and live jobs and the sandbox never resolve AGP.
// app/build.gradle.kts then applies com.android.application by id, without a version.
