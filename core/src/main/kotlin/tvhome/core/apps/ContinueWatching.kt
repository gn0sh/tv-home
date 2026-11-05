package tvhome.core.apps

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tvhome.core.assistant.AskApp
import tvhome.core.assistant.AskConfig
import tvhome.core.assistant.AskReply
import tvhome.core.assistant.CountStore
import tvhome.core.assistant.OpenTarget

/**
 * continue-tile: the home row's "Continue" tile. Android keeps other apps' Continue watching rows for the system
 * launcher only (ACCESS_ALL_EPG_DATA, signature|privileged; probe-watch-next), so the tile shows Brian's last Ask Claude
 * Open pick (title and app, re-opened the same way), else the streaming app he used last (UsageStats, if allowed), else
 * nothing. Titles are personal data: kept app-private, never logged, hidden from toString.
 */
sealed interface ContinueTile {
    /** The last Ask Claude pick still installed. */
    class Pick(val target: OpenTarget) : ContinueTile {
        override fun toString() = "Pick(${target.app.packageName}, title=${target.title.length} chars)"
    }

    /** No pick: the streaming app used last. */
    data class App(val packageName: String) : ContinueTile
}

/** The last few successful Opens, newest first, in a small app-private file. */
class ContinuePicks(private val store: CountStore, private val clock: () -> Long) {
    /** Newest first, at most [KEEP], and trimmed until the file fits [MAX_BYTES] (continue-picks-cap). */
    @Synchronized
    fun record(target: OpenTarget) {
        var next = (listOf(Entry(target, clock())) + read().filterNot { same(it.target, target) }).take(KEEP)
        var text = encode(next)
        while (next.isNotEmpty() && text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            next = next.dropLast(1)
            text = encode(next)
        }
        if (next.isNotEmpty()) runCatching { store.write(text) }
    }

    /** Newest first; anything unreadable, invalid or older than [MAX_AGE_MS] is dropped (never an error). */
    @Synchronized
    fun read(): List<Entry> = runCatching { store.read() }.getOrNull()?.let { decode(it, clock()) }.orEmpty()

    class Entry(val target: OpenTarget, val atMillis: Long) {
        override fun toString() = "Entry(${target.app.packageName})"
    }

    companion object {
        const val KEEP = 3
        /** The file's size cap in UTF-8 bytes; record() trims to it and a larger file is ignored. */
        const val MAX_BYTES = 4_096
        /** picks-no-expiry: a pick older than this is dropped. */
        const val MAX_AGE_MS = 30L * 24 * 3600 * 1000

        private val json = Json { ignoreUnknownKeys = true }

        private fun same(a: OpenTarget, b: OpenTarget) = a.app.packageName == b.app.packageName && a.title == b.title

        fun encode(entries: List<Entry>): String = buildJsonArray {
            entries.forEach { e ->
                add(buildJsonObject {
                    put("t", e.target.title)
                    put("p", e.target.app.packageName)
                    put("l", e.target.app.label)
                    e.target.deepLink?.let { put("d", it) }
                    put("at", e.atMillis)
                })
            }
        }.toString()

        fun decode(text: String, now: Long): List<Entry> {
            if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return emptyList()
            val arr = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return emptyList()
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val title = str("t")?.let { clean(it, AskConfig.MAX_TITLE_CHARS) }?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val pkg = str("p")?.takeIf { PKG.matches(it) } ?: return@mapNotNull null
                val label = str("l")?.let { clean(it, AskConfig.MAX_LABEL_CHARS) }?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val link = str("d")?.let { AskReply.validDeepLink(it) ?: return@mapNotNull null }
                val at = (o["at"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null
                if (now - at > MAX_AGE_MS || at > now + 60_000) return@mapNotNull null
                Entry(OpenTarget(AskApp(label, pkg), title, link), at)
            }.take(KEEP)
        }

        private val PKG = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")
        private fun clean(s: String, max: Int) = s.filter { !it.isISOControl() }.trim().take(max)
    }
}

/** One app's last use, from UsageStats. */
data class RecentUse(val packageName: String, val lastUsedMillis: Long)

object ContinueChoice {
    /** Not streaming apps, even though they have a TV launcher entry. */
    val NOT_STREAMING = setOf(
        "com.android.tv.settings",
        "com.google.android.tv.settings",
        "com.android.vending",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.google.android.katniss",
    )

    /** Without usage data, a pick older than this is not shown (continue-stale-pick). */
    const val PICK_FRESH_MS = 7L * 24 * 3600 * 1000

    /**
     * The tile. Only apps in the launcher list and not hidden by Brian ([hidden], continue-hidden-app) count. The newest
     * such pick wins if it is newer than the last use of any such streaming app (with no usage data: if it is under
     * [PICK_FRESH_MS] old); else that last-used app; else no tile. [recent] is null when UsageStats is not allowed.
     */
    fun choose(
        picks: List<ContinuePicks.Entry>,
        recent: List<RecentUse>?,
        launcherPkgs: Set<String>,
        ownPackage: String,
        hidden: Set<String> = emptySet(),
        now: Long = Long.MAX_VALUE,
    ): ContinueTile? {
        fun ok(pkg: String) = pkg in launcherPkgs && pkg !in hidden && pkg != ownPackage
        val pick = picks.firstOrNull { ok(it.target.app.packageName) }
        val last = recent.orEmpty().asSequence()
            .filter { it.lastUsedMillis > 0 && ok(it.packageName) && it.packageName !in NOT_STREAMING }
            .maxByOrNull { it.lastUsedMillis }
        if (pick != null) {
            val fresh = if (last != null) pick.atMillis >= last.lastUsedMillis else now - pick.atMillis <= PICK_FRESH_MS
            if (fresh) return ContinueTile.Pick(pick.target)
        }
        return last?.let { ContinueTile.App(it.packageName) }
    }

    /** The tile's spoken description. [hideTitle]: Quiet mode (M7-F6) is on, so a pick names only its app. */
    fun description(tile: ContinueTile, hideTitle: Boolean = false, appLabel: (String) -> String?): String = when (tile) {
        is ContinueTile.Pick -> if (hideTitle) "Continue in ${tile.target.app.label}" else "Continue: ${tile.target.title}, in ${tile.target.app.label}"
        is ContinueTile.App -> "Continue in ${appLabel(tile.packageName) ?: "the app you used last"}"
    }
}
