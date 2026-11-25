package com.rendyhd.vicu.util

/**
 * A chip of the quick-add sheet's row (Settings, Input, Quick add), in the default order. Each
 * opens the tray of the same name; at full height each is a row, in the same order.
 */
enum class EntryChip { WHEN, REMIND, PRIORITY, LABELS, PROJECT, REPEAT, TEMPLATE }

/** The named choices of pinned chips; any other choice is custom. */
enum class EntryChipPreset { MINIMAL, STANDARD, EVERYTHING }

/**
 * How the quick-add sheet shows its chips, per device: [order] is every chip in the user's order,
 * [pinned] the ones always on the row, [openFull] whether the sheet opens at full height.
 */
data class EntryChipLayout(
    val order: List<EntryChip> = EntryChip.entries,
    val pinned: Set<EntryChip> = EntryChips.STANDARD,
    val openFull: Boolean = false,
) {
    /** The preset these pinned chips make, or null when they are a custom choice. */
    val preset: EntryChipPreset? get() = EntryChips.presetOf(pinned)
}

/**
 * The rules of the quick-add chip row. A chip is on the row when it is pinned, when it holds a
 * value (read from the title or picked in its tray), or while Details is open; so nothing the task
 * will get is ever out of sight. Standard is the row of earlier versions: When, Remind, Priority
 * and Template (while the account has a template), the rest once they hold something.
 */
object EntryChips {
    val STANDARD: Set<EntryChip> = setOf(EntryChip.WHEN, EntryChip.REMIND, EntryChip.PRIORITY, EntryChip.TEMPLATE)

    fun pinnedOf(preset: EntryChipPreset): Set<EntryChip> = when (preset) {
        EntryChipPreset.MINIMAL -> emptySet()
        EntryChipPreset.STANDARD -> STANDARD
        EntryChipPreset.EVERYTHING -> EntryChip.entries.toSet()
    }

    fun presetOf(pinned: Set<EntryChip>): EntryChipPreset? =
        EntryChipPreset.entries.firstOrNull { pinnedOf(it) == pinned }

    /**
     * The chips the row shows, in order: of the [available] ones (Template only while there is a
     * template), those pinned or holding a value ([held]), or all of them while [details] is open.
     */
    fun shown(layout: EntryChipLayout, available: Set<EntryChip>, held: Set<EntryChip>, details: Boolean): List<EntryChip> =
        layout.order.filter { it in available && (details || it in layout.pinned || it in held) }

    /** Whether Details would show a chip the row does not: without one there is nothing to open. */
    fun detailsReveals(layout: EntryChipLayout, available: Set<EntryChip>, held: Set<EntryChip>): Boolean =
        layout.order.any { it in available && it !in layout.pinned && it !in held }

    /** [order] with the chip at [from] moved to [to]. */
    fun moved(order: List<EntryChip>, from: Int, to: Int): List<EntryChip> {
        if (from !in order.indices || to !in order.indices || from == to) return order
        return order.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * A stored order: the chips it names that this version knows, then any it does not name in
     * their default places at the end. Null or empty is the default order.
     */
    fun readOrder(stored: String?): List<EntryChip> {
        val named = names(stored).mapNotNull(::chipOf).distinct()
        return named + EntryChip.entries.filter { it !in named }
    }

    /** The stored pinned chips; never written (null) is [STANDARD], empty is Minimal. */
    fun readPinned(stored: String?): Set<EntryChip> =
        if (stored == null) STANDARD else names(stored).mapNotNull(::chipOf).toSet()

    /**
     * What to store for [chips], keeping the names of [previous] that this version does not know
     * (chips of a newer version) so that a newer version finds them again.
     */
    fun write(chips: Collection<EntryChip>, previous: String?): String =
        (chips.map { it.name } + names(previous).filter { chipOf(it) == null }).joinToString(",")

    private fun names(stored: String?): List<String> = stored.orEmpty().split(',').filter { it.isNotEmpty() }

    private fun chipOf(name: String): EntryChip? = EntryChip.entries.firstOrNull { it.name == name }
}
