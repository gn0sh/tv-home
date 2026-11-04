package com.gnosh.tvhome

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * qa build only (CI emulator QA, never published). The emulator has no recognizer and no API key, so emulator-qa.sh
 * plants `no_backup/qa-voice.json` with run-as: {"transcript": "...", "reply": "<raw reply JSON>", "route":
 * "direct"|"assistant"}. The overlay then skips the microphone and the API and shows that reply. "direct" opens the
 * old way (startActivity from the session) so QA can show the platform blocking it.
 */
internal object VoiceQaHook {
    fun planted(context: Context): QaPlanted? = runCatching {
        val f = File(context.noBackupFilesDir, "qa-voice.json")
        if (!f.isFile || f.length() > 8_192) return null
        val o = JSONObject(f.readText())
        QaPlanted(o.getString("transcript"), o.getString("reply"), o.optString("route") == "direct")
    }.getOrNull()
}
