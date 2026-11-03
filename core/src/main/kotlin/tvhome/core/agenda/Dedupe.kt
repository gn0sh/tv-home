package tvhome.core.agenda

import tvhome.core.calendar.EventInstance
import tvhome.core.calendar.EventTime

/**
 * One appointment that reaches the feed twice (calendar-dedupe): a second copy of the same UID (an invite on two
 * calendars, a re-imported event), or one booking written twice with different title forms ("Barber: VIP Beard Trim –
 * Holiday Barber Shop" from the booking site and "VIP Beard Trim" from the invite). Real TV, 2026-11: the hero said the
 * first as Now and "Then VIP Beard Trim at 1:30 PM" as Next.
 *
 * Two instances are the same event only when their start and end are equal (all-day dates or instants) AND they share
 * a non-empty UID or their titles match ([titlesMatch]). Different times are never merged. Within one start/end the
 * events are taken in a fixed order (longest title, then title, then uid), so feed order does not change the result
 * (dedupe-order-dependent): the first is kept whole, uid and title together (dedupe-title-swap-fields), and only the
 * events that match it directly, or share a uid with one of those, are dropped (no title-only chains,
 * dedupe-transitive-chain). Kept events stay in feed order; the agenda sorts the days itself.
 */
fun dedupeEvents(events: List<EventInstance>): List<EventInstance> {
    if (events.size < 2) return events
    val slots = LinkedHashMap<Pair<EventTime, EventTime?>, MutableList<Int>>()
    events.forEachIndexed { i, e -> slots.getOrPut(e.start to e.end) { ArrayList() } += i }
    val keep = BooleanArray(events.size)
    val order = compareByDescending<Int> { events[it].title.length }.thenBy { events[it].title }.thenBy { events[it].uid }
    for (idx in slots.values) {
        // dedupe-transitive-chain: take the best remaining event, drop only what matches IT directly (plus anything that
        // shares a uid with a dropped one: a uid is real identity), repeat. "Soccer practice" folds into one child's
        // event, but "Soccer practice Jack" and "Soccer practice Emma" both stay.
        val left = idx.sortedWith(order).toMutableList()
        while (left.isNotEmpty()) {
            val best = left.removeAt(0)
            keep[best] = true
            val gone = left.filter { sameEvent(events[best], events[it]) }.toMutableSet()
            val uids = (gone.map { events[it].uid } + events[best].uid).filter { it.isNotEmpty() }.toMutableSet()
            do {
                val more = left.filter { it !in gone && events[it].uid in uids }
                gone += more
                more.forEach { uids += events[it].uid }
            } while (more.isNotEmpty())
            left.removeAll(gone)
        }
    }
    return events.filterIndexed { i, _ -> keep[i] }
}

/** Same start and end are checked by the caller; this is the identity rule. */
private fun sameEvent(a: EventInstance, b: EventInstance): Boolean {
    if (a.uid.isNotEmpty() && a.uid == b.uid) return true
    return titlesMatch(a.title, b.title, allDay = a.start is EventTime.AllDay)
}

/**
 * Titles of two events at the same start and end that name one appointment. Case, spacing and punctuation are ignored
 * ([normalizeTitle]); an empty title matches nothing.
 * - Equal titles always match (a duplicate feed is far likelier than two identical events at one time).
 * - All-day events match only on equal titles (dedupe-allday-containment): every all-day event of a day shares its
 *   start and end, so "Birthday" and "Jack's birthday", or "School" and "No school", are different events.
 * - Timed events also match when one title contains the other as whole words and the shorter one has at least two
 *   words (dedupe-generic-timed): "VIP Beard Trim" is in the barber booking's title, but "Pickup" and "School pickup"
 *   stay apart.
 */
fun titlesMatch(a: String, b: String, allDay: Boolean): Boolean {
    val x = normalizeTitle(a)
    val y = normalizeTitle(b)
    if (x.isEmpty() || y.isEmpty()) return false
    if (x == y) return true
    if (allDay) return false
    val (short, long) = if (x.length <= y.length) x to y else y to x
    return short.count { it == ' ' } >= 1 && " $long ".contains(" $short ")
}

/** Lower case, letters and digits only, single spaces: "Barber: VIP  Beard-Trim!" -> "barber vip beard trim". */
fun normalizeTitle(t: String): String =
    t.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")
