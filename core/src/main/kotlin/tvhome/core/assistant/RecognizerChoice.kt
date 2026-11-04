package tvhome.core.assistant

/**
 * stub-recognizer-default: holding the assistant role makes our ClaudeRecognitionService the TV's default speech
 * recognizer, so it forwards each request to another installed recognizer. This picks which one: never our own
 * package (that would loop), Google's TV recognizer first, then other Google recognizers, then any other, in a fixed
 * order so the same TV always picks the same one.
 */
object RecognizerChoice {
    data class Candidate(val pkg: String, val cls: String)

    /** Most preferred first: Android TV's Google recognizer, the Google app, Google's speech services, Android System Intelligence. */
    val PREFERRED = listOf(
        "com.google.android.katniss",
        "com.google.android.googlequicksearchbox",
        "com.google.android.tts",
        "com.google.android.as",
    )

    fun pick(candidates: List<Candidate>, ownPackage: String): Candidate? =
        candidates
            .filter { it.pkg.isNotBlank() && it.cls.isNotBlank() && it.pkg != ownPackage }
            .minWithOrNull(
                compareBy<Candidate>({ PREFERRED.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.pkg }, { it.cls }),
            )
}
