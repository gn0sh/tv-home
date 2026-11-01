package tvhome.core.secrets

import java.net.URI
import java.net.URLDecoder

/**
 * Defence in depth for anything that is logged. The main control is that no exception text crosses out of
 * `calendar` (errors are the SourceError enum only); this catches what slips past anyway.
 *
 * Matching ignores case (hex digits, `%2F` vs `%2f`, `HTTPS://`). Replaces, in a single pass and longest first: each
 * secret, its percent-encoded form (also encoded twice), and for URLs (any `scheme://`, so `webcal://` too) the part
 * after the scheme, the path+query, and only the path segments / query values that carry the secret: a `private-` token
 * and its hex, a user segment (anything with `@` or `%40`, raw and decoded), and any segment or value of 16+ chars
 * (long random tokens of other providers), each also percent-encoded once and twice and decoded up to three times
 * (a user segment logged as `fake%2540example.test`). Common short words of the URL ("calendar", "ical", "basic.ics", the
 * host) are not secrets and are left alone. Blank secrets are ignored. The marker contains letters, so a secret
 * that is a substring of the marker (e.g. one letter) cannot be fully hidden; real secrets are far longer.
 */
class Redactor(private val secrets: () -> Collection<String>) {

    fun redact(s: String): String {
        val needles = needles()
        if (needles.isEmpty() || s.isEmpty()) return s
        val regex = Regex(needles.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        return regex.replace(s) { MARKER }
    }

    private fun needles(): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in secrets()) {
            val v = raw.trim()
            if (v.isEmpty()) continue
            out += v
            out += percentEncode(v)
            out += percentEncode(percentEncode(v))
            if (SCHEME.containsMatchIn(v)) {
                out += v.substringAfter("//")
                try {
                    val u = URI(v)
                    val pq = (u.rawPath.orEmpty()) + (u.rawQuery?.let { "?$it" } ?: "")
                    if (pq.length >= 8) out += pq
                    val parts = u.rawPath.orEmpty().split('/') +
                        u.rawQuery?.split('&')?.map { it.substringAfter('=') }.orEmpty()
                    for (p in parts) out += secretParts(p)
                } catch (_: Exception) {
                    // not a parseable URI: the raw forms above still apply
                }
            }
        }
        return out.filter { it.isNotEmpty() }.sortedByDescending { it.length }
    }

    /** The secret-bearing forms of one path segment or query value; empty for ordinary words. */
    private fun secretParts(p: String): List<String> {
        val out = ArrayList<String>()
        // p, then p decoded once, twice, three times (a segment can arrive already encoded one or two times).
        val levels = ArrayList<String>(4)
        levels += p
        repeat(3) {
            val next = decode(levels.last())
            if (next != levels.last()) levels += next
        }
        for (l in levels) {
            val token = PRIVATE_TOKEN.find(l)
            if (token != null) {
                out += token.value
                out += token.groupValues[1]
            }
        }
        if (levels.any { '@' in it }) out += levels
        if (levels.any { it.length >= MIN_TOKEN }) out += levels.filter { it.length >= MIN_TOKEN }
        // every kept form also as it would be logged after one or two more rounds of encoding
        for (x in out.toList()) {
            val once = percentEncode(x)
            out += once
            out += percentEncode(once)
            // encoders that also escape - . _ ~ (so a dot arrives as %2E, or %252E after a second round)
            val strict = percentEncode(x, keepMarks = false)
            out += strict
            out += percentEncode(strict)
            out += percentEncode(strict, keepMarks = false)
        }
        return out
    }

    private fun decode(p: String): String = try {
        URLDecoder.decode(p, "UTF-8")
    } catch (_: Exception) {
        p
    }

    private fun percentEncode(s: String, keepMarks: Boolean = true): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if ((c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) || (c in '0'.code..'9'.code) ||
                (keepMarks && (c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code))
            ) sb.append(c.toChar()) else sb.append('%').append("%02X".format(c))
        }
        return sb.toString()
    }

    override fun toString(): String = "Redactor"

    companion object {
        const val MARKER = "[redacted]"
        private const val MIN_TOKEN = 16
        private val PRIVATE_TOKEN = Regex("private-([0-9A-Fa-f]{8,})", RegexOption.IGNORE_CASE)
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    }
}
