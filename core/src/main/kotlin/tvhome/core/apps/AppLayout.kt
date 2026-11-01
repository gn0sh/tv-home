package tvhome.core.apps

/**
 * The user's customisation of the app row (M2g): a saved order and a hidden set, both by package name. Pure functions
 * over package lists, so they are unit-tested without Android. [installed] is always the default order of what is
 * installed now (see [AppOrder.order]); the saved order wins where it knows a package.
 */
object AppLayout {
    /**
     * Every installed package (hidden ones too), in the saved order. Packages the saved order does not know (new
     * installs) go at the end in [installed] order; saved packages that are no longer installed are dropped.
     */
    fun arrange(order: List<String>, installed: List<String>): List<String> {
        val present = installed.toHashSet()
        val out = LinkedHashSet<String>()
        for (p in order) if (p in present) out += p
        for (p in installed) out += p
        return out.toList()
    }

    /** The tile row: [arrange] without the [hidden] packages. */
    fun apply(order: List<String>, hidden: Set<String>, installed: List<String>): List<String> =
        arrange(order, installed).filter { it !in hidden }

    /** The hidden apps that are still installed, in row order (uninstalled ones are dropped). */
    fun hiddenApps(order: List<String>, hidden: Set<String>, installed: List<String>): List<String> =
        arrange(order, installed).filter { it in hidden }

    /** [hidden] without packages that are no longer installed, so the Hidden tile's count stays true. */
    fun prune(hidden: Set<String>, installed: List<String>): Set<String> = hidden intersect installed.toSet()

    /** The new saved order after moving [pkg] one visible place left; unchanged at the front or if unknown. */
    fun moveLeft(order: List<String>, hidden: Set<String>, installed: List<String>, pkg: String): List<String> =
        swapVisible(order, hidden, installed, pkg, -1)

    /** The new saved order after moving [pkg] one visible place right; unchanged at the end or if unknown. */
    fun moveRight(order: List<String>, hidden: Set<String>, installed: List<String>, pkg: String): List<String> =
        swapVisible(order, hidden, installed, pkg, +1)

    /** The new saved order with [pkg] first; unchanged if unknown. */
    fun moveToFront(order: List<String>, installed: List<String>, pkg: String): List<String> {
        val all = arrange(order, installed)
        if (pkg !in all) return all
        return listOf(pkg) + all.filter { it != pkg }
    }

    /** [hidden] plus [pkg]. */
    fun hide(hidden: Set<String>, pkg: String): Set<String> = hidden + pkg

    /** [hidden] minus [pkg]. The app returns to its saved place in the order. */
    fun unhide(hidden: Set<String>, pkg: String): Set<String> = hidden - pkg

    private fun swapVisible(order: List<String>, hidden: Set<String>, installed: List<String>, pkg: String, step: Int): List<String> {
        val all = arrange(order, installed)
        val visible = all.filter { it !in hidden }
        val i = visible.indexOf(pkg)
        val j = i + step
        if (i < 0 || j !in visible.indices) return all
        val mutable = all.toMutableList()
        val a = mutable.indexOf(visible[i])
        val b = mutable.indexOf(visible[j])
        mutable[a] = visible[j]
        mutable[b] = visible[i]
        return mutable
    }

    /** Stored form: packages one per line (package names never contain a newline). */
    fun encodeOrder(order: List<String>): String = order.joinToString("\n")

    fun decodeOrder(s: String?): List<String> = s.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
