package tvhome.core.io

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads at most [max] bytes. Replaces `InputStream.readNBytes(int)`, which is Java 11 and missing on Android 12
 * (API 31 and 32, this app's minSdk range). Callers pass limit + 1 to detect an oversize input.
 */
internal fun InputStream.readUpTo(max: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(max, 8192))
    val buf = ByteArray(8192)
    var left = max
    while (left > 0) {
        val n = read(buf, 0, minOf(buf.size, left))
        if (n < 0) break
        out.write(buf, 0, n)
        left -= n
    }
    return out.toByteArray()
}
