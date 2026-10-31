package tvhome.core.secrets

import java.net.URI

/**
 * Defence in depth for anything that is logged. The main control is that no exception text crosses out of
 * `calendar` (errors are the SourceError enum only); this catches what slips past anyway.
 *
 * Replaces each secret, its percent-encoded form, and (for URLs) the part after the scheme and the path+query,
 * in a single pass, longest first (URL path segments and query values of 8+ chars count as secrets too). Blank secrets are ignored. The marker contains letters, so a secret that is
 * a substring of the marker (e.g. one letter) cannot be fully hidden; real secrets are far longer.
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
                    // the token usually is one path segment or query value: hide those on their own too
                    val parts = (u.rawPath.orEmpty().split('/')) +
                        (u.rawQuery?.split('&', '=').orEmpty())
                    for (p in parts) if (p.length >= 8) out += p
                } catch (_: Exception) {
                    // not a parseable URI: the raw forms above still apply
                }
            }
        }
        return out.filter { it.isNotEmpty() }.sortedByDescending { it.length }
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
    }
}
