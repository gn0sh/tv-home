package tvhome.core.assistant

/**
 * stub-recognizer-default: holding the assistant role makes our ClaudeRecognitionService the TV's default speech
 * recognizer, so it forwards each request to another installed recognizer. This picks which one
 * (recognizer-target-any-app): only a Google recognizer from PREFERRED that is a system app (preinstalled, or an
 * update of one), in that order; never our own package (that would loop) and never any other app, which could
 * otherwise collect what every app hears. No match means no forwarding (the caller gets ERROR_CLIENT).
 */
object RecognizerChoice {
    /** [system]: the package is FLAG_SYSTEM or FLAG_UPDATED_SYSTEM_APP (read by the app from ApplicationInfo). */
    data class Candidate(val pkg: String, val cls: String, val system: Boolean)

    /** Most preferred first: Android TV's Google recognizer, the Google app, Google's speech services, Android System Intelligence. */
    val PREFERRED = listOf(
        "com.google.android.katniss",
        "com.google.android.googlequicksearchbox",
        "com.google.android.tts",
        "com.google.android.as",
    )

    fun pick(candidates: List<Candidate>, ownPackage: String): Candidate? =
        candidates
            .filter { it.system && it.cls.isNotBlank() && it.pkg != ownPackage && it.pkg in PREFERRED }
            .minWithOrNull(compareBy<Candidate>({ PREFERRED.indexOf(it.pkg) }, { it.cls }))
}
