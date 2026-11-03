package tvhome.core

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The app's minSdk is 31 with no core library desugaring, and :core is a plain JVM module, so Android lint's NewApi
 * check never sees it: a Java 9+ API that Android added only in API 33 or 34 compiles and passes every JVM test, then
 * throws NoSuchMethodError on an Android 12 or 13 TV (api-34-only-call). This scans :core and :app sources for the
 * calls we know are missing on API 31. Add to [banned] when another one is found; the message says what to use.
 */
class AndroidApiGuardTest {
    private val banned = mapOf(
        "LocalDate.ofInstant(" to "API 34: use instant.atZone(zone).toLocalDate()",
        "LocalTime.ofInstant(" to "API 34: use instant.atZone(zone).toLocalTime()",
        ".datesUntil(" to "API 34: loop with plusDays",
        "Part()" to "Duration.to*Part() is API 34: compute from toMillis()",
        ".readNBytes(" to "API 33: use tvhome.core.io.BoundedRead",
        ".readAllBytes(" to "API 33: use tvhome.core.io.BoundedRead",
        ".transferTo(" to "API 33: copy with a buffer",
        "requireNonNullElse" to "API 33: use ?:",
        ".orElseThrow()" to "API 33: use .get()",
        "Stream.toList()" to "API 34: use collect(Collectors.toList())",
    )

    @Test fun noCallsMissingOnApi31() {
        val roots = listOf(File("src/main"), File("../app/src/main")).filter { it.isDirectory }
        assertTrue(roots.isNotEmpty(), "sources not found from ${File(".").absolutePath}")
        val hits = roots.flatMap { root -> root.walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.toList() }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    val code = line.substringBefore("//").trim()
                    if (code.startsWith("*") || code.startsWith("/*")) return@mapIndexedNotNull null
                    banned.entries.firstOrNull { code.contains(it.key) }?.let { "${f.path}:${i + 1}: ${it.key} (${it.value})" }
                }
            }
        assertTrue(hits.isEmpty(), hits.joinToString("\n"))
    }
}
