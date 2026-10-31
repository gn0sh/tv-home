plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// No toolchain block: the sandbox has JDK 21 only. Target 17 bytecode instead.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // api: CalendarSource.fetch is a suspend API whose contract (main-safe, cancellable) the app relies on.
    api(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    // Test-only differential oracle (ADR 0004): never on the app's runtime classpath.
    testImplementation(libs.biweekly)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("hostile", "live")
    }
}

// Hostile-input probes (docs/tasks/tv-home-m1.md T2b): a small heap proves the memory bound. Each test class gets a
// fresh JVM, so the P14 perf check measures a first call. Raise the heap only with a measured reason in the PR.
val hostileTest by tasks.registering(Test::class) {
    description = "Hostile ICS probes (tag hostile) in a 96 MiB test JVM."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("hostile")
    }
    maxHeapSize = "96m"
    forkEvery = 1
    shouldRunAfter(tasks.test)
}

// Live check on a public Google feed (no secret); run by the manual CI job only (T2b.6).
val liveTest by tasks.registering(Test::class) {
    description = "Live framing check against a public Google Calendar feed (tag live)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("live")
    }
}

dependencyLocking {
    lockAllConfigurations()
}
