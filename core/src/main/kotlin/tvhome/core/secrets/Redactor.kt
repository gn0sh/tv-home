package tvhome.core.secrets

import java.net.URI
import java.net.URLDecoder

/**
 * Defence in depth for anything that is logged. The main control is that no exception text crosses out of
 * `calendar` (errors are the SourceError enum only); this catches what slips past anyway.
 *
 * Replaces, in a single pass and longest first: each secret, its percent-encoded form, and for URLs the part after
 * the scheme, the path+query, and only the path segments / query values that carry the secret: a `private-` token
 * and its hex, a user segment (anything with `@` or `%40`, raw and decoded), and any segment or value of 16+ chars
 * (long random tokens of other providers). Common short words of the URL ("calendar", "ical", "basic.ics", the
 * host) are not secrets and are left alone. Blank secrets are ignored. The marker contains letters, so a secret
 * that is a substring of the marker (e.g. one letter) cannot be fully hidden; real secrets are far longer.
 */
class Redactor(private val secrets: () -> Collection<String>) {

    fun redact(s: String): String {
        val needles = needles()
        if (needles.isEmpty() || s.isEmpty()) return s
        val regex = Regex(needles.joinToString("|") { Regex.escape(it) })
        return regex.replace(s) { MARKER }
    }

    private fun needles(): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in secrets()) {
            val v = raw.trim()
            if (v.isEmpty()) continue
            out += v
            out += percentEncode(v)
            if (v.startsWith("https://", ignoreCase = true) || v.startsWith("http://", ignoreCase = true)) {
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
        val decoded = try {
            URLDecoder.decode(p, "UTF-8")
        } catch (_: Exception) {
            p
        }
        val token = PRIVATE_TOKEN.find(p)
        if (token != null) {
            out += token.value
            out += token.groupValues[1]
        }
        if ('@' in decoded || p.contains("%40", ignoreCase = true)) {
            out += p
            out += decoded
        }
        if (p.length >= MIN_TOKEN) {
            out += p
            if (decoded.length >= MIN_TOKEN) out += decoded
        }
        return out
    }

    private fun percentEncode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if ((c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) || (c in '0'.code..'9'.code) ||
                c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
            ) sb.append(c.toChar()) else sb.append('%').append("%02X".format(c))
        }
        return sb.toString()
    }

    override fun toString(): String = "Redactor"

    companion object {
        const val MARKER = "[redacted]"
        private const val MIN_TOKEN = 16
        private val PRIVATE_TOKEN = Regex("private-([0-9A-Fa-f]{8,})")
    }
}
