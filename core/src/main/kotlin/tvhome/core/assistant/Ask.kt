package tvhome.core.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import tvhome.core.apps.LauncherApp

/**
 * M1b lean "Ask Claude" (spec M1b, ADR 0002 "M1b lean"): the words, the request, the reply rules and the limits.
 * Everything here is pure and JVM-tested; the app only draws [AskOutcome] and launches an [OpenTarget] on Open.
 *
 * Data sent to Anthropic: the question (and one follow-up), the earlier answer of this conversation, and the installed
 * TV apps' names and package ids. Never calendar data. Nothing here is logged; every toString hides text.
 */
object AskConfig {
    /** ADR 0002 decision 4: the latest Opus, effort low. One value to change (Sonnet 5.5 is the named alternative). */
    const val MODEL = "claude-opus-5-5"
    const val EFFORT = "low"
    /** Room for low-effort thinking plus a short answer; the prompt asks for under 120 words. */
    const val MAX_TOKENS = 2048
    const val TIMEOUT_MS = 20_000L
    /** ADR 0002 decision 7: default daily question cap. Each API call counts, Retry included (it is spend). */
    const val DAILY_CAP = 20
    const val MAX_QUESTION_CHARS = 500
    const val MAX_APPS = 60
    const val MAX_LABEL_CHARS = 60
    const val MAX_ANSWER_CHARS = 4000
    const val MAX_TITLE_CHARS = 200
    const val MAX_DEEP_LINK_CHARS = 500
}

/** Exact on-screen wording for the Ask view. */
object AskCopy {
    const val TILE_LABEL = "Ask Claude"
    const val TILE_HINT = "Talk or type a question"
    const val NOT_SET_UP_TITLE = "Ask Claude isn't set up yet"
    const val NOT_SET_UP_DETAIL = "Add the API key from your PC: see the setup steps in the README."
    const val CANT_REACH = "Can't reach Claude"
    const val CANT_REACH_DETAIL = "Check the TV's Wi-Fi, then try again."
    const val UNWORKABLE = "Couldn't work that out"
    const val CAP_REACHED = "That's today's questions used up. Ask again tomorrow."
    const val FOLLOW_UP_USED = "That was the one follow-up. Start over to ask something new."
    const val EMPTY_QUESTION = "Say or type a question first."
}

/** One installed app as Claude sees it. Labels are cleaned: no control characters, bounded length. */
data class AskApp(val label: String, val packageName: String)

fun askAppsOf(apps: List<LauncherApp>): List<AskApp> =
    apps.asSequence()
        .map { AskApp(clean(it.label, AskConfig.MAX_LABEL_CHARS), it.packageName) }
        .distinctBy { it.packageName }
        .take(AskConfig.MAX_APPS)
        .toList()

private fun clean(s: String, max: Int): String =
    s.filter { !it.isISOControl() }.trim().take(max)

/** A question cleaned for sending: control characters dropped, trimmed, at most [AskConfig.MAX_QUESTION_CHARS]. */
fun cleanQuestion(s: String): String = clean(s.replace('\n', ' '), AskConfig.MAX_QUESTION_CHARS)

/** One message of the conversation, as sent to the API. [text] is personal data: toString hides it. */
data class Turn(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT }

    override fun toString(): String = "Turn($role, ${text.length} chars)"
}

/** What Claude may ask the app to open. [app] is always one of the installed apps (checked in [AskReply.parse]). */
data class OpenTarget(val app: AskApp, val title: String, val deepLink: String?) {
    override fun toString(): String = "OpenTarget(${app.packageName}, title=${title.length} chars, deepLink=${deepLink != null})"
}

/** A parsed reply: the answer to show, and an optional [open] that launches only when Brian presses Open. */
data class AskReply(val answer: String, val open: OpenTarget?) {
    override fun toString(): String = "AskReply(answer=${answer.length} chars, open=$open)"

    companion object {
        private val json = Json { ignoreUnknownKeys = false }

        /**
         * Strict: [text] must be one JSON object with a non-blank string `answer` (else null: "Couldn't work that out").
         * `open` is kept only when it is an object whose `app_package` is exactly one of [apps], whose `title` is a
         * non-blank string, and whose `deep_link` is absent, null, or a valid https URL ([validDeepLink]). An invalid
         * `open` is dropped, never repaired, so a bad reply can show text but cannot launch anything.
         */
        fun parse(text: String?, apps: List<AskApp>): AskReply? {
            if (text.isNullOrBlank()) return null
            val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
            val answer = (obj["answer"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.let { clean(it.replace("\r", ""), AskConfig.MAX_ANSWER_CHARS) }
                ?.takeIf { it.isNotBlank() } ?: return null
            return AskReply(answer, openOf(obj["open"], apps))
        }

        private fun openOf(e: JsonElement?, apps: List<AskApp>): OpenTarget? {
            val o = e as? JsonObject ?: return null
            fun str(k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val pkg = str("app_package") ?: return null
            val app = apps.firstOrNull { it.packageName == pkg } ?: return null
            val title = str("title")?.let { clean(it, AskConfig.MAX_TITLE_CHARS) }?.takeIf { it.isNotBlank() } ?: return null
            val link = when (val d = o["deep_link"]) {
                null, JsonNull -> null
                is JsonPrimitive -> if (d.isString) validDeepLink(d.content) else return null
                else -> return null
            }
            return OpenTarget(app, title, link)
        }

        private val HTTPS = Regex("""https://[A-Za-z0-9.-]+(?::\d{1,5})?(?:/[^\s]*)?""")

        /**
         * A deep link is used only if it is a plain https URL (no other scheme, no whitespace, bounded length); it is
         * launched as a VIEW intent restricted to the chosen app's package. Anything else falls back to search.
         */
        fun validDeepLink(s: String): String? {
            val v = s.trim()
            if (v.length > AskConfig.MAX_DEEP_LINK_CHARS || v.any { it.isISOControl() }) return null
            return v.takeIf { HTTPS.matches(it) }
        }
    }
}

/** The fixed instructions plus the installed apps. Claude cannot see the screen; it is told so. */
fun systemPrompt(apps: List<AskApp>): String = buildString {
    append(
        """
        You are Claude, built into the home screen of Brian's Google TV. He talks to you with the remote, so answer
        briefly and plainly, in under 120 words, with no markdown. You can answer general questions. You cannot see
        what is on the screen.
        If he wants to watch something, pick the best match on one of his installed apps (listed below) and fill in
        "open" with that app's exact package id and the title. If it is not on any installed app, say so in the answer,
        suggest something on an installed app instead if you can, and set "open" to that or to null. Only give
        "deep_link" when you are confident of an https link that the app opens directly; otherwise null.
        Reply with JSON only, matching the schema.
        """.trimIndent(),
    )
    append("\n\nInstalled TV apps (name: package id):\n")
    if (apps.isEmpty()) append("(none)\n")
    for (a in apps) append("- ").append(a.label).append(": ").append(a.packageName).append('\n')
}

/** The reply schema (structured outputs). Every object closed; optional values are nullable. */
fun replySchema(apps: List<AskApp>): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("answer") { put("type", "string") }
        putJsonObject("open") {
            putJsonArray("anyOf") {
                add(
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("app_package") {
                                put("type", "string")
                                if (apps.isNotEmpty()) put("enum", buildJsonArray { apps.forEach { add(JsonPrimitive(it.packageName)) } })
                            }
                            putJsonObject("title") { put("type", "string") }
                            putJsonObject("deep_link") {
                                putJsonArray("anyOf") {
                                    add(buildJsonObject { put("type", "string") })
                                    add(buildJsonObject { put("type", "null") })
                                }
                            }
                        }
                        put("required", buildJsonArray { listOf("app_package", "title", "deep_link").forEach { add(JsonPrimitive(it)) } })
                        put("additionalProperties", false)
                    },
                )
                add(buildJsonObject { put("type", "null") })
            }
        }
    }
    put("required", buildJsonArray { add(JsonPrimitive("answer")); add(JsonPrimitive("open")) })
    put("additionalProperties", false)
}

/**
 * The Messages API request body. No streaming, no tools (the lean slice has no web search), thinking left at the
 * model default (adaptive; Opus 5.5 cannot disable it), effort low, the reply forced into [replySchema]. `fallbacks`
 * "default" lets the API re-run a declined request on its recommended model (needs the matching beta header).
 */
fun requestBody(apps: List<AskApp>, turns: List<Turn>): String = buildJsonObject {
    put("model", AskConfig.MODEL)
    put("max_tokens", AskConfig.MAX_TOKENS)
    put("system", systemPrompt(apps))
    put("fallbacks", "default")
    putJsonObject("output_config") {
        put("effort", AskConfig.EFFORT)
        putJsonObject("format") {
            put("type", "json_schema")
            put("schema", replySchema(apps))
        }
    }
    put(
        "messages",
        JsonArray(
            turns.map { t ->
                buildJsonObject {
                    put("role", if (t.role == Turn.Role.USER) "user" else "assistant")
                    put("content", t.text)
                }
            },
        ),
    )
}.toString()

/**
 * The conversation of one visit to the Ask view, in memory only (spec AC6, AC10): a question, then at most one
 * follow-up. Dropped with the view. [canAsk] is false once the follow-up has been answered.
 */
class Conversation {
    private val turns = ArrayList<Turn>()
    private var answered = 0

    val canAsk: Boolean get() = answered < MAX_ANSWERS
    val isFollowUp: Boolean get() = answered > 0

    /** The turns to send for [question] (history plus the new question). Does not change state. */
    fun turnsFor(question: String): List<Turn> = turns + Turn(Turn.Role.USER, question)

    /** Records an answered exchange. [assistantJson] is the raw reply, so the follow-up has Claude's own words. */
    fun record(question: String, assistantJson: String) {
        turns += Turn(Turn.Role.USER, question)
        turns += Turn(Turn.Role.ASSISTANT, assistantJson)
        answered++
    }

    override fun toString(): String = "Conversation(answered=$answered)"

    companion object {
        /** The question plus one follow-up. */
        const val MAX_ANSWERS = 2
    }
}
