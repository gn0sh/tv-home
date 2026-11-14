package com.gnosh.tvhome

import android.content.Context
import org.json.JSONObject
import tvhome.core.feeds.Pick
import tvhome.core.feeds.Picks
import java.io.File

/**
 * qa build only (CI emulator QA, never published). The emulator is offline for the hero checks, so it can never fetch
 * Claude's pick; emulator-qa.sh plants `no_backup/qa-pick.json` with run-as: a v1 pick file (see [Picks]) with one
 * more field, `"team"`, the team key it is for. It goes through [Picks.parse] like a fetched file (same caps, same
 * character rules) and is shown for that team's game, so the pick stub and the full ticket can be checked.
 */
internal object PickQaHook {
    fun planted(context: Context): Map<String, Pick> = runCatching {
        val f = File(context.noBackupFilesDir, "qa-pick.json")
        if (!f.isFile || f.length() > Picks.MAX_BODY) return emptyMap()
        val raw = f.readText()
        val pick = Picks.parse(raw) ?: return emptyMap()
        mapOf(JSONObject(raw).getString("team") to pick)
    }.getOrDefault(emptyMap())
}
