package app.recomp.domain.training

/**
 * An extra session's focus areas (YG-03, PRD §12.13). Stored as these keys in `yoga_detail.focus_areas`,
 * in this order, so a session's chips always read the same way whatever order they were tapped in.
 */
object FocusAreas {
    val ALL = listOf("hips", "hamstrings", "back", "shoulders", "core", "full_body")

    /** Keeps only known areas, once each, in [ALL]'s order. Unknown keys (from a newer build) are kept at the end. */
    fun normalize(areas: Collection<String>): List<String> {
        val set = areas.toSet()
        return ALL.filter { it in set } + set.filter { it !in ALL }.sorted()
    }

    fun toggle(areas: List<String>, area: String): List<String> = normalize(if (area in areas) areas - area else areas + area)
}
