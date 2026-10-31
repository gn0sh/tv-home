package tvhome.core.calendar

import org.junit.jupiter.api.Assertions.fail
import java.time.ZoneId

/** Test helpers over the ByteArray / [ParseOutcome] contract (ADR 0004). */
internal fun parseOutcome(
    text: String,
    window: InstantRange,
    zone: ZoneId,
    focus: InstantRange? = null,
    limits: IcsParser.Limits = IcsParser.Limits(),
    nanoTime: () -> Long = System::nanoTime,
): ParseOutcome = IcsParser.parse(text.toByteArray(Charsets.UTF_8), window, zone, focus, limits, nanoTime)

/** The events of a [ParseOutcome.Complete]; fails the test for any other outcome. */
internal fun ParseOutcome.complete(): List<EventInstance> = completeOutcome().events

internal fun ParseOutcome.completeOutcome(): ParseOutcome.Complete =
    this as? ParseOutcome.Complete ?: fail("expected Complete, got $this")

internal fun parseComplete(
    text: String,
    window: InstantRange,
    zone: ZoneId,
    focus: InstantRange? = null,
    limits: IcsParser.Limits = IcsParser.Limits(),
): List<EventInstance> = parseOutcome(text, window, zone, focus, limits).complete()
