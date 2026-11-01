package tvhome.core.calendar

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import tvhome.core.agenda.agendaFocus
import tvhome.core.agenda.agendaWindow
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Live check of the framing assumptions ADR 0004 rests on, against Google's public US-holiday feed (no secret).
 * Tag `live`: excluded from `test` and `hostileTest`; run by `:core:liveTest` in the manual CI job (T2b.6). It
 * asserts a `Complete` outcome and prints what it saw: line ends, VERSION, CALSCALE, the component set.
 */
@Tag("live")
class GoogleFramingLiveTest {
    private val url = "https://calendar.google.com/calendar/ical/en.usa%23holiday%40group.v.calendar.google.com/public/basic.ics"

    @Test fun publicGoogleFeedParsesComplete() {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
        val res = client.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        assertTrue(res.statusCode() == 200, "HTTP ${res.statusCode()}")
        val body = res.body()
        assertTrue(body.size in 1..IcsReader.MAX_LINE * 1280, "size ${body.size}")

        var crlf = 0
        var bareCr = 0
        var bareLf = 0
        for (i in body.indices) {
            when (body[i]) {
                13.toByte() -> if (i + 1 < body.size && body[i + 1] == 10.toByte()) crlf++ else bareCr++
                10.toByte() -> if (i == 0 || body[i - 1] != 13.toByte()) bareLf++
            }
        }
        val text = body.toString(Charsets.UTF_8)
        val lines = text.split("\r\n")
        val version = lines.firstOrNull { it.startsWith("VERSION") }
        val calscale = lines.firstOrNull { it.startsWith("CALSCALE") }
        val components = lines.filter { it.startsWith("BEGIN:") }.map { it.substringAfter(':') }.toSortedSet()
        val properties = lines.filter { it.isNotEmpty() && it[0] != ' ' && it[0] != '\t' }
            .map { it.substringBefore(':').substringBefore(';') }.toSortedSet()
        println("live: ${body.size} B, CRLF=$crlf bareCR=$bareCr bareLF=$bareLf, $version, $calscale, components=$components")
        println("live: property names=$properties")

        val ny = ZoneId.of("America/New_York")
        val window = agendaWindow(Instant.now(), ny)
        when (val o = IcsParser.parse(body, window, ny, agendaFocus(window, ny))) {
            is ParseOutcome.Complete -> println("live: $o")
            else -> fail("expected Complete, got $o")
        }
        assertTrue(bareCr == 0 && bareLf == 0 && version == "VERSION:2.0", "framing assumptions changed")
    }
}
