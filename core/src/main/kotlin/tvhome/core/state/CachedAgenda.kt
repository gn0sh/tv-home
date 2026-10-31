package tvhome.core.state

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tvhome.core.calendar.EventInstance
import tvhome.core.calendar.EventTime
import tvhome.core.calendar.InstantRange
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate

/** File access injected so :core stays free of Android; the app passes one rooted in noBackupFilesDir. */
interface CacheFile {
    fun read(): String?

    /** Must replace the content all-or-nothing (temp file + rename). */
    fun writeAtomic(text: String)
}

/** java.io implementation: write to a sibling temp file, then atomic rename. */
class JvmCacheFile(private val file: File) : CacheFile {
    override fun read(): String? = if (file.isFile) file.readText(Charsets.UTF_8) else null

    override fun writeAtomic(text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }
}

/**
 * Expanded events for [window], as fetched at [fetchedAt]. Persisted as JSON v1. It holds events only: no URL, no
 * headers, no error text. Titles are truncated to [MAX_TITLE] chars.
 */
data class CachedAgenda(val fetchedAt: Instant, val window: InstantRange, val events: List<EventInstance>) {
    fun encode(): String = json.encodeToString(Dto.serializer(), toDto())

    /** True when the cached window still covers all of today and tomorrow (start of today to end of tomorrow). */
    fun covers(needed: InstantRange): Boolean = !window.start.isAfter(needed.start) && !window.end.isBefore(needed.end)

    private fun toDto() = Dto(
        v = 1,
        fetchedAt = fetchedAt.toString(),
        windowStart = window.start.toString(),
        windowEnd = window.end.toString(),
        events = events.map { EventDto(it.uid.take(MAX_TITLE), it.title.take(MAX_TITLE), it.start.toDto(), it.end?.toDto()) },
    )

    companion object {
        const val MAX_TITLE = 200
        private val json = Json { ignoreUnknownKeys = true }

        /** Null for anything that is not a valid v1 document (corrupt, truncated, other version): treated as no cache. */
        fun decode(text: String?): CachedAgenda? {
            if (text == null) return null
            return try {
                val d = json.decodeFromString(Dto.serializer(), text)
                if (d.v != 1) return null
                CachedAgenda(
                    Instant.parse(d.fetchedAt),
                    InstantRange(Instant.parse(d.windowStart), Instant.parse(d.windowEnd)),
                    d.events.map { EventInstance(it.uid, it.title, it.start.toTime(), it.end?.toTime()) },
                )
            } catch (_: Exception) {
                null
            }
        }

        fun load(file: CacheFile): CachedAgenda? = try { decode(file.read()) } catch (_: Exception) { null }
    }
}

enum class SaveResult { SAVED, FAILED }

/** Never throws: a failed save leaves the previous cache in place and the app keeps going. */
fun CacheFile.save(c: CachedAgenda): SaveResult = try {
    writeAtomic(c.encode())
    SaveResult.SAVED
} catch (_: Exception) {
    SaveResult.FAILED
}

@Serializable private class Dto(
    val v: Int,
    val fetchedAt: String,
    val windowStart: String,
    val windowEnd: String,
    val events: List<EventDto>,
)

@Serializable private class EventDto(val uid: String, val title: String, val start: TimeDto, val end: TimeDto? = null)

/** kind "at" uses [value] as an ISO instant; kind "day" uses an ISO local date. */
@Serializable private class TimeDto(val kind: String, val value: String)

private fun EventTime.toDto() = when (this) {
    is EventTime.At -> TimeDto("at", instant.toString())
    is EventTime.AllDay -> TimeDto("day", date.toString())
}

private fun TimeDto.toTime(): EventTime = when (kind) {
    "at" -> EventTime.At(Instant.parse(value))
    "day" -> EventTime.AllDay(LocalDate.parse(value))
    else -> throw IllegalArgumentException("kind")
}
