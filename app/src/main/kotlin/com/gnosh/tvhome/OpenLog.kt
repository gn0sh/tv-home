package com.gnosh.tvhome

import android.content.Context
import java.io.File

/**
 * open-failure-silent: a short record of the voice overlay's Open attempts, in app-private no-backup storage
 * (`no_backup/open-log.txt`, the last 20 lines). Each line is the time, the step, the route (deep_link, search, home)
 * and the result; never the title, the link or anything Claude said. The app writes nothing to the platform log (R8 strips it
 * and AppManifestTest forbids it), so this is what QA and a support check read (run-as, or the PC tools).
 */
internal object OpenLog {
    private const val MAX_LINES = 20

    @Synchronized
    fun record(context: Context, step: String, route: String, result: String) {
        runCatching {
            val f = File(context.noBackupFilesDir, "open-log.txt")
            val old = if (f.isFile && f.length() < 16_384) f.readLines() else emptyList()
            val line = "${System.currentTimeMillis() / 1000} $step route=$route $result".filter { it.isLetterOrDigit() || it in " =_:.-" }
            f.writeText((old + line).takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
        }
    }
}
