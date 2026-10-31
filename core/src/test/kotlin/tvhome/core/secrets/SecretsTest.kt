package tvhome.core.secrets

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.FakeSecrets
import java.net.URLEncoder
import kotlin.random.Random

class SecretsTest {
    private val url = FakeSecrets.CALENDAR_URL

    @Test fun inMemoryStoreRoundTrips() {
        val s = InMemorySecretStore()
        assertNull(s.get(SecretKey.CALENDAR_URL))
        s.put(SecretKey.CALENDAR_URL, url)
        assertEquals(url, s.get(SecretKey.CALENDAR_URL))
        s.clear(SecretKey.CALENDAR_URL)
        assertNull(s.get(SecretKey.CALENDAR_URL))
        s.put(SecretKey.ANTHROPIC_API_KEY, FakeSecrets.API_KEY)
        assertFalse(s.toString().contains(FakeSecrets.API_KEY))
    }

    @Test fun validCalendarUrlAcceptedAndTrimmed() {
        val r = ProvisionValidator.validateCalendarUrl("  $url\t")
        assertTrue(r is ProvisionResult.Valid)
        assertEquals(url, (r as ProvisionResult.Valid).value)
        assertFalse(r.toString().contains(FakeSecrets.CALENDAR_TOKEN))
    }

    @Test fun badCalendarUrlsRejectedWithoutEchoingTheValue() {
        val tok = FakeSecrets.CALENDAR_TOKEN
        val bad = listOf(
            "",
            "   ",
            url.replace("https://", "http://"),
            url.replace("calendar.google.com", "evil.example.test"),
            "https://user:pw@calendar.google.com/calendar/ical/x/$tok/basic.ics",
            url + "\r",                       // trailing CR
            url + "\n",                       // trailing newline ($ must not skip it)
            url + " ",                   // trailing NBSP
            " $url",                     // leading NBSP
            url.replace("/basic.ics", "/basi\rc.ics"),   // CR inside
            url + "?x=1",
            url.replace("private-" + "f".repeat(32), "private-" + "f".repeat(31)),
            url.replace("private-" + "f".repeat(32), "private-" + "F".repeat(32)),
        )
        for (b in bad) {
            val r = ProvisionValidator.validateCalendarUrl(b)
            assertTrue(r is ProvisionResult.Invalid, "should reject (len ${b.length})")
            // the whole result, as text, must not contain the secret or the input
            val text = r.toString()
            assertFalse(text.contains(tok))
            assertFalse(text.contains(b) && b.isNotBlank())
        }
        assertEquals(ProvisionResult.Invalid(ProvisionError.EMPTY), ProvisionValidator.validateCalendarUrl(" \t"))
        assertEquals(ProvisionResult.Invalid(ProvisionError.BAD_FORMAT), ProvisionValidator.validateCalendarUrl(url + " "))
    }

    @Test fun apiKeyValidation() {
        assertTrue(ProvisionValidator.validateApiKey(FakeSecrets.API_KEY) is ProvisionResult.Valid)
        assertTrue(ProvisionValidator.validate(SecretKey.ANTHROPIC_API_KEY, " ${FakeSecrets.API_KEY} ") is ProvisionResult.Valid)
        for (b in listOf("", "sk-ant-short", "sk-xxx" + "f".repeat(40), FakeSecrets.API_KEY + "\r", FakeSecrets.API_KEY + " ",
            FakeSecrets.API_KEY + " x", "sk-ant-" + "f".repeat(20) + "'; rm")) {
            val r = ProvisionValidator.validateApiKey(b)
            assertTrue(r is ProvisionResult.Invalid)
            assertFalse(r.toString().contains(FakeSecrets.API_KEY))
        }
    }

    @Test fun redactorHidesRawEncodedAndUrlForms() {
        val r = Redactor { listOf(url) }
        val text = "GET $url failed; host path ${url.substringAfter("//")} enc ${URLEncoder.encode(url, "UTF-8")}" +
            " pq ${url.substringAfter("calendar.google.com")}"
        val out = r.redact(text)
        assertFalse(out.contains(FakeSecrets.CALENDAR_TOKEN), out)
        assertTrue(out.contains(Redactor.MARKER))
        assertEquals("plain text", r.redact("plain text"))
        assertEquals("x", Redactor { listOf("", "  ") }.redact("x"))
    }

    /** redactor-over-redacts: only the token, its hex and the user segment are needles, not the URL's common words. */
    @Test fun redactorLeavesOrdinaryWordsOfTheUrlAlone() {
        val url = FakeSecrets.CALENDAR_URL
        val r = Redactor { listOf(url) }
        val plain = "calendar sync: ical feed basic.ics from calendar.google.com, 3 events"
        assertEquals(plain, r.redact(plain))
        val hex = FakeSecrets.CALENDAR_TOKEN.removePrefix("private-")
        val user = java.net.URLDecoder.decode(FakeSecrets.CALENDAR_PATH_USER, "UTF-8")
        for (leak in listOf(FakeSecrets.CALENDAR_TOKEN, hex, FakeSecrets.CALENDAR_PATH_USER, user)) {
            val out = r.redact("x $leak y")
            assertEquals("x ${Redactor.MARKER} y", out, leak)
        }
        // a generic provider's long random path token is still caught on its own
        val other = "https://cal.example.test/feeds/Zx81kQ0pL2mN7vB4cX9a/basic.ics"
        assertEquals("token ${Redactor.MARKER} seen", Redactor { listOf(other) }.redact("token Zx81kQ0pL2mN7vB4cX9a seen"))
        assertEquals("feeds basic.ics", Redactor { listOf(other) }.redact("feeds basic.ics"))
    }

    @Test fun redactorReadsSecretsLazily() {
        val store = InMemorySecretStore()
        val r = Redactor { listOfNotNull(store.get(SecretKey.ANTHROPIC_API_KEY)) }
        assertTrue(r.redact(FakeSecrets.API_KEY).contains(FakeSecrets.API_KEY)) // nothing stored yet
        store.put(SecretKey.ANTHROPIC_API_KEY, FakeSecrets.API_KEY)
        assertFalse(r.redact("key=${FakeSecrets.API_KEY}!").contains(FakeSecrets.API_KEY))
    }

    @Test fun seededPropertyRedactedStringsNeverContainTheSecret() {
        val rnd = Random(20261031)
        val alphabet = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '_', '.', '%', '/', '+', '=')
        repeat(300) {
            // No "private-" / "sk-ant-" prefixes here: keeps build logs free of lookalikes (see FakeSecrets).
            val secret = "tok" + (1..rnd.nextInt(8, 80)).map { alphabet[rnd.nextInt(alphabet.size)] }.joinToString("")
            val asUrl = rnd.nextBoolean()
            val value = if (asUrl) "https://host.example.test/a/$secret/basic.ics" else secret
            val r = Redactor { listOf(value) }
            val noise = (1..rnd.nextInt(0, 30)).map { alphabet[rnd.nextInt(alphabet.size)] }.joinToString("")
            val forms = listOf(value, URLEncoder.encode(value, "UTF-8"), "Exception: $noise$value$noise", "$noise${value}${value}")
            for (f in forms) assertFalse(r.redact(f).contains(secret), "leak for seed iteration: ${r.redact(f)}")
        }
    }
}
