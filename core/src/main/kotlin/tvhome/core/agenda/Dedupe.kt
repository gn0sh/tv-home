package tvhome.core.agenda

import tvhome.core.calendar.EventInstance

/**
 * One appointment that reaches the feed twice (calendar-dedupe): a second copy of the same UID (an invite on two
 * calendars, a re-imported event), or one booking written twice with different title forms ("Barber: VIP Beard Trim –
 * Holiday Barber Shop" from the booking site and "VIP Beard Trim" from the invite). Real TV, 2026-11: the hero said the
 * first as Now and "Then VIP Beard Trim at 1:30 PM" as Next.
 *
 * Two instances are the same event only when their start and end are equal (all-day dates or instants) AND they share
 * a UID or their titles match ([sameTitle]). Different times are never merged. The longer title is kept (it says more);
 * order is otherwise unchanged.
 */
fun dedupeEvents(events: List<EventInstance>): List<EventInstance> {
    if (events.size < 2) return events
    val kept = ArrayList<EventInstance>(events.size)
    val byTime = HashMap<Pair<Any, Any?>, MutableList<Int>>() // (start, end) -> indexes into kept
    for (e in events) {
        val slot = byTime.getOrPut(e.start to e.end) { ArrayList() }
        val i = slot.firstOrNull { k -> kept[k].uid.isNotEmpty() && kept[k].uid == e.uid || sameTitle(kept[k].title, e.title) }
        if (i == null) {
            slot += kept.size
            kept += e
        } else if (e.title.length > kept[i].title.length) {
            kept[i] = kept[i].copy(title = e.title)
        }
    }
    return kept
}

/**
 * Titles that name the same thing: equal, or one contained in the other as whole words, after [normalizeTitle] (case,
 * spacing and punctuation ignored). An empty title matches nothing.
 */
fun sameTitle(a: String, b: String): Boolean {
    val x = normalizeTitle(a)
    val y = normalizeTitle(b)
    if (x.isEmpty() || y.isEmpty()) return false
    if (x == y) return true
    val (short, long) = if (x.length <= y.length) x to y else y to x
    return " $long ".contains(" $short ")
}

/** Lower case, letters and digits only, single spaces: "Barber: VIP  Beard-Trim!" -> "barber vip beard trim". */
fun normalizeTitle(t: String): String =
    t.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")
