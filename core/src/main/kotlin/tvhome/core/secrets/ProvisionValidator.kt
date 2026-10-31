package tvhome.core.secrets

/** Why a provisioned value was refused. Never carries the value. */
enum class ProvisionError { EMPTY, BAD_FORMAT }

sealed interface ProvisionResult {
    /** [value] is the trimmed value to store. toString hides it. */
    class Valid(val value: String) : ProvisionResult {
        override fun toString(): String = "Valid"
    }

    data class Invalid(val reason: ProvisionError) : ProvisionResult
}

/**
 * Validation of values put on the TV over adb. Trim ASCII space and tab only, then require an exact match
 * (whole string, so a trailing newline, CR or NBSP is refused, not silently dropped).
 */
object ProvisionValidator {
    private val CALENDAR_URL =
        Regex("""https://calendar\.google\.com/calendar/ical/[A-Za-z0-9%._-]+/private-[0-9a-f]{32}/basic\.ics""")
    private val API_KEY = Regex("""sk-ant-[A-Za-z0-9_-]{20,500}""")

    fun validateCalendarUrl(input: String): ProvisionResult = check(input, CALENDAR_URL)

    fun validateApiKey(input: String): ProvisionResult = check(input, API_KEY)

    fun validate(key: SecretKey, input: String): ProvisionResult = when (key) {
        SecretKey.CALENDAR_URL -> validateCalendarUrl(input)
        SecretKey.ANTHROPIC_API_KEY -> validateApiKey(input)
    }

    private fun check(input: String, pattern: Regex): ProvisionResult {
        val v = input.trim { it == ' ' || it == '\t' }
        if (v.isEmpty()) return ProvisionResult.Invalid(ProvisionError.EMPTY)
        // matches() = whole-input match; Kotlin's Regex.matches never lets $ skip a final line terminator.
        return if (pattern.matches(v)) ProvisionResult.Valid(v) else ProvisionResult.Invalid(ProvisionError.BAD_FORMAT)
    }
}
