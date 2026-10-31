package tvhome.core.secrets

import tvhome.core.io.readUpTo
import java.io.InputStream

/** What a provisioning request asks for. Values never appear in [toString]. */
sealed interface ProvisionCommand {
    class Store(val key: SecretKey, val value: String) : ProvisionCommand {
        override fun toString(): String = "Store($key)"
    }

    data class Clear(val key: SecretKey) : ProvisionCommand
    data class Rejected(val reason: ProvisionError) : ProvisionCommand
    object NoOp : ProvisionCommand {
        override fun toString(): String = "NoOp"
    }
}

/** The text ProvisionActivity shows. Fixed strings only: never the value, never exception text. */
enum class ProvisionOutcome(val message: String) {
    SAVED_CALENDAR("Calendar saved"),
    SAVED_API_KEY("API key saved"),
    CLEARED_CALENDAR("Calendar removed"),
    CLEARED_API_KEY("API key removed"),
    INVALID("Not valid. Nothing was saved."),
    FAILED("Could not save."),
    NOTHING("Nothing to do."),
}

/** Parsing and applying provisioning requests (activity extras and provider stream). Pure; the Android glue is thin. */
object Provision {
    const val EXTRA_CLEAR = "clear"

    /** Largest value accepted from the provider stream; the longest valid value (an API key) is about 520 bytes. */
    const val MAX_VALUE_BYTES = 1024

    fun keyOf(name: String?): SecretKey? = when (name) {
        "calendar_url" -> SecretKey.CALENDAR_URL
        "anthropic_api_key" -> SecretKey.ANTHROPIC_API_KEY
        else -> null
    }

    fun nameOf(k: SecretKey): String = when (k) {
        SecretKey.CALENDAR_URL -> "calendar_url"
        SecretKey.ANTHROPIC_API_KEY -> "anthropic_api_key"
    }

    /**
     * [extra] reads a string extra by name. `clear` wins and names the key to wipe. Otherwise exactly one key extra is
     * expected (two at once is refused, not guessed at), validated by [ProvisionValidator].
     */
    fun fromExtras(extra: (String) -> String?): ProvisionCommand {
        val clear = extra(EXTRA_CLEAR)
        if (clear != null) {
            val k = keyOf(clear) ?: return ProvisionCommand.Rejected(ProvisionError.BAD_FORMAT)
            return ProvisionCommand.Clear(k)
        }
        val present = SecretKey.entries.mapNotNull { k -> extra(nameOf(k))?.let { k to it } }
        if (present.isEmpty()) return ProvisionCommand.NoOp
        if (present.size > 1) return ProvisionCommand.Rejected(ProvisionError.BAD_FORMAT)
        return validated(present[0].first, present[0].second)
    }

    /** A value written to the provider's stream: read at most [MAX_VALUE_BYTES], strict UTF-8, then validated. */
    fun fromStream(key: SecretKey, input: InputStream): ProvisionCommand {
        val bytes = input.readUpTo(MAX_VALUE_BYTES + 1)
        if (bytes.size > MAX_VALUE_BYTES) return ProvisionCommand.Rejected(ProvisionError.BAD_FORMAT)
        val text = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            return ProvisionCommand.Rejected(ProvisionError.BAD_FORMAT)
        }
        return validated(key, text)
    }

    private fun validated(k: SecretKey, raw: String): ProvisionCommand = when (val r = ProvisionValidator.validate(k, raw)) {
        is ProvisionResult.Valid -> ProvisionCommand.Store(k, r.value)
        is ProvisionResult.Invalid -> ProvisionCommand.Rejected(r.reason)
    }

    /** Applies [cmd] to [store]. Catches everything: a store failure is [ProvisionOutcome.FAILED] with no text. */
    fun apply(store: SecretStore, cmd: ProvisionCommand): ProvisionOutcome = try {
        when (cmd) {
            is ProvisionCommand.Store -> {
                store.put(cmd.key, cmd.value)
                if (cmd.key == SecretKey.CALENDAR_URL) ProvisionOutcome.SAVED_CALENDAR else ProvisionOutcome.SAVED_API_KEY
            }
            is ProvisionCommand.Clear -> {
                store.clear(cmd.key)
                if (cmd.key == SecretKey.CALENDAR_URL) ProvisionOutcome.CLEARED_CALENDAR else ProvisionOutcome.CLEARED_API_KEY
            }
            is ProvisionCommand.Rejected -> ProvisionOutcome.INVALID
            ProvisionCommand.NoOp -> ProvisionOutcome.NOTHING
        }
    } catch (_: Throwable) {
        ProvisionOutcome.FAILED
    }
}

/** What the release uncaught-exception handler logs (`#3`): exception class names only, never message or stack. */
object CrashSummary {
    private const val MAX_DEPTH = 5

    fun of(t: Throwable): String {
        val names = ArrayList<String>(MAX_DEPTH)
        var cur: Throwable? = t
        while (cur != null && names.size < MAX_DEPTH) {
            names += cur.javaClass.name
            cur = cur.cause.takeIf { it !== cur }
        }
        return "tvhome crash: " + names.joinToString(" <- ")
    }
}
