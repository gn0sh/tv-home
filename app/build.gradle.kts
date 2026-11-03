import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :app is included by settings.gradle.kts only when an Android SDK is present (CI, or a PC with the SDK). It could not
// be compiled in the sandbox it was written in; CI is its first build (docs/tasks/tv-home-m1.md, T5).
plugins {
    id("com.android.application") // version from the root buildscript classpath (build.gradle.kts)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gnosh.tvhome"
    // compileSdk 35 (build-time only; the workflow installs platforms;android-35); targetSdk stays 34 (architecture section 4).
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gnosh.tvhome"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing comes from the environment only (T6/T7): the workflow decodes the keystore to $RUNNER_TEMP and
    // exports these four variables. Nothing is read from gradle.properties or the command line (#9).
    val keystorePath: String? = System.getenv("TVHOME_KEYSTORE_PATH")
    signingConfigs {
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("TVHOME_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TVHOME_KEY_ALIAS")
                keyPassword = System.getenv("TVHOME_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // CI emulator QA only (q1-debug-not-release): the release build (R8, minify, shrinkResources, the same proguard
        // rules) made debuggable, so `run-as` can plant the M7 Q1/Q2 caches while R8's handling of the cache
        // serializers is what gets tested. Debug-key signed, never the release key. Same applicationId: run-as and the
        // provider authority need it. Built to apk/qa/, never published; the release checks and globs read apk/release/.
        create("qa") {
            initWith(getByName("release"))
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/**")
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // This APK is sideloaded onto one TV and never goes to Google Play, so Play's target-API deadlines do not apply.
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
}

dependencyLocking {
    lockAllConfigurations()
}
