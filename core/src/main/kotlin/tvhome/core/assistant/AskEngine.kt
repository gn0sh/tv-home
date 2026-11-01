package tvhome.core.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tvhome.core.io.readUpTo
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Why a call failed. Enum only: never exception text, response text, or headers. */
enum class ClaudeError { NO_KEY, NETWORK, HTTP_STATUS, TIMEOUT, REFUSED, BAD_RESPONSE }

sealed interface ClaudeResult {
    /** The reply's text (the structured JSON). Personal data: toString hides it. */
    class Text(val text: String) : ClaudeResult {
        override fun toString(): String = "Text(${text.length} chars)"
    }

    data class Failed(val error: ClaudeError) : ClaudeResult
}

/**
 * One request to Claude. Contract: main-safe, cancellable, never throws except CancellationException, and never puts
 * the key or any text into an exception, log or result other than [ClaudeResult.Text].
 */
fun interface ClaudeClient {
    suspend fun send(apps: List<AskApp>, turns: List<Turn>): ClaudeResult
}

/** Where the daily count lives. A date and a number only, never a question. */
interface CountStore {
    fun read(): String?
    fun write(text: String)
}

class FileCountStore(private val file: File) : CountStore {
    override fun read(): String? = if (!file.isFile) null else file.inputStream().use { String(it.readUpTo(64), Charsets.UTF_8) }

    override fun write(text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * The daily question cap (ADR 0002 decision 7). Stored as "yyyy-mm-dd count"; anything unreadable counts as zero for
 * today, a store that cannot be written refuses (fail closed: no count, no call).
 */
class DailyCap(
    private val store: CountStore,
    private val limit: Int = AskConfig.DAILY_CAP,
    private val clock: () -> Instant,
    private val zone: () -> ZoneId,
) {
    @Synchronized
    fun tryConsume(): Boolean {
        val today = clock().atZone(zone()).toLocalDate()
        val used = usedOn(today)
        if (used >= limit) return false
        return runCatching { store.write("$today ${used + 1}") }.isSuccess
    }

    @Synchronized
    fun used(): Int = usedOn(clock().atZone(zone()).toLocalDate())

    private fun usedOn(today: LocalDate): Int {
        val parts = runCatching { store.read() }.getOrNull()?.trim()?.split(' ') ?: return 0
        if (parts.size != 2 || parts[0] != today.toString()) return 0
        return parts[1].toIntOrNull()?.coerceAtLeast(0) ?: 0
    }
}

/** What the Ask view shows after a question. */
sealed interface AskOutcome {
    data class Answer(val reply: AskReply, val canFollowUp: Boolean) : AskOutcome
    object NotSetUp : AskOutcome { override fun toString() = "NotSetUp" }
    /** Offline or any API error: "Can't reach Claude" with Retry. */
    object CantReach : AskOutcome { override fun toString() = "CantReach" }
    /** Malformed, empty or declined reply: "Couldn't work that out". Nothing to open. */
    object Unworkable : AskOutcome { override fun toString() = "Unworkable" }
    object CapReached : AskOutcome { override fun toString() = "CapReached" }
    object FollowUpUsed : AskOutcome { override fun toString() = "FollowUpUsed" }
    object EmptyQuestion : AskOutcome { override fun toString() = "EmptyQuestion" }
}

/**
 * One Ask view's engine. Order: key present, question non-empty, follow-up rule, daily cap, call, strict parse. Only
 * an answered exchange is added to the conversation, so Retry after an error resends the same turn.
 */
class AskEngine(
    private val client: ClaudeClient,
    private val hasKey: () -> Boolean,
    private val cap: DailyCap,
    private val apps: () -> List<AskApp>,
) {
    private val conversation = Conversation()

    val hasKeyNow: Boolean get() = hasKey()
    val canAsk: Boolean get() = conversation.canAsk
    /** True once one question has been answered: the next one is the follow-up. */
    val isFollowUp: Boolean get() = conversation.isFollowUp

    suspend fun ask(rawQuestion: String): AskOutcome {
        if (!hasKey()) return AskOutcome.NotSetUp
        val question = cleanQuestion(rawQuestion)
        if (question.isEmpty()) return AskOutcome.EmptyQuestion
        if (!conversation.canAsk) return AskOutcome.FollowUpUsed
        // The count is a small file write: off the main thread.
        if (!withContext(Dispatchers.IO) { cap.tryConsume() }) return AskOutcome.CapReached
        val installed = apps()
        return when (val r = client.send(installed, conversation.turnsFor(question))) {
            is ClaudeResult.Failed -> when (r.error) {
                ClaudeError.NO_KEY -> AskOutcome.NotSetUp
                ClaudeError.REFUSED, ClaudeError.BAD_RESPONSE -> AskOutcome.Unworkable
                ClaudeError.NETWORK, ClaudeError.HTTP_STATUS, ClaudeError.TIMEOUT -> AskOutcome.CantReach
            }
            is ClaudeResult.Text -> {
                val reply = AskReply.parse(r.text, installed) ?: return AskOutcome.Unworkable
                conversation.record(question, r.text)
                AskOutcome.Answer(reply, conversation.canAsk)
            }
        }
    }

    override fun toString(): String = "AskEngine($conversation)"
}
