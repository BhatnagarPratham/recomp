package app.recomp.domain.training

/**
 * Supersets (RT-05, PRD §12.2): two or three consecutive exercises that share a group number, in a
 * routine or a workout. Each exercise keeps its own target and progression; a superset only changes the
 * order of sets and when rest starts.
 *
 * Groups are lists of nullable numbers, one per exercise in order. Only runs of consecutive exercises
 * with the same number count, so every edit ends with [normalize].
 */
object Supersets {
    const val MAX_SIZE = 3

    /** Index ranges of each block in order: a superset, or one exercise on its own. */
    fun blocks(groups: List<Int?>): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = 0
        for (i in 1..groups.size) {
            if (i == groups.size || groups[i] == null || groups[i] != groups[start]) {
                out += start until i
                start = i
            }
        }
        return out
    }

    /** Groups of one are dropped and each superset is numbered 1, 2, 3… in order, so equal lists mean equal plans. */
    fun normalize(groups: List<Int?>): List<Int?> {
        val out = MutableList<Int?>(groups.size) { null }
        var next = 1
        blocks(groups).filter { it.count() > 1 }.forEach { block ->
            val id = next++
            block.forEach { out[it] = id }
        }
        return out
    }

    /** *Superset with next*: joins the exercise's block with the next one, if that makes at most [MAX_SIZE]. */
    fun canJoinNext(groups: List<Int?>, index: Int): Boolean {
        if (index !in groups.indices || index + 1 !in groups.indices) return false
        val blocks = blocks(groups)
        val mine = blocks.first { index in it }
        val next = blocks.first { index + 1 in it }
        return mine != next && mine.count() + next.count() <= MAX_SIZE
    }

    fun joinNext(groups: List<Int?>, index: Int): List<Int?> {
        if (!canJoinNext(groups, index)) return groups
        val blocks = blocks(groups)
        val joined = blocks.first { index in it }.first..blocks.first { index + 1 in it }.last
        val id = (groups.filterNotNull().maxOrNull() ?: 0) + 1
        return normalize(groups.mapIndexed { i, g -> if (i in joined) id else g })
    }

    /** *Split superset*: every exercise in the exercise's superset goes back to standing alone. */
    fun split(groups: List<Int?>, index: Int): List<Int?> {
        if (groups.getOrNull(index) == null) return groups
        val block = blocks(groups).first { index in it }
        return normalize(groups.mapIndexed { i, g -> if (i in block) null else g })
    }

    /** The superset an exercise is in, as (its place in it, the superset's size), or null on its own. */
    fun position(groups: List<Int?>, index: Int): Pair<Int, Int>? {
        val block = blocks(groups).firstOrNull { index in it }?.takeIf { it.count() > 1 } ?: return null
        return index - block.first to block.count()
    }

    /**
     * *Move up* and *Move down* (RT-03, RT-04), as the new order of the old indices; null when there's
     * nowhere to go. Inside a superset an exercise swaps with its neighbour. At a superset's edge the
     * whole superset moves, and an exercise moving past a superset jumps it, so a move never breaks one
     * up: that's what *Split superset* is for.
     */
    fun move(groups: List<Int?>, index: Int, up: Boolean): List<Int>? {
        if (index !in groups.indices) return null
        val blocks = blocks(groups)
        val at = blocks.indexOfFirst { index in it }
        val block = blocks[at]
        val target = if (up) index - 1 else index + 1
        if (block.count() > 1 && target in block) {
            return groups.indices.map { i -> if (i == index) target else if (i == target) index else i }
        }
        val other = if (up) at - 1 else at + 1
        if (other !in blocks.indices) return null
        val reordered = blocks.toMutableList().apply {
            this[at] = blocks[other]
            this[other] = block
        }
        return reordered.flatMap { it.toList() }
    }

    // The order of sets in a workout

    /** A set as the tick order sees it. */
    data class SetRef(val id: Long, val warmup: Boolean, val done: Boolean)

    /** An exercise in today's workout: its group and its sets in the order the card shows them. */
    data class ExerciseRef(val group: Int?, val sets: List<SetRef>)

    /** One place in the tick order. [round] counts within its [block]. */
    data class Slot(val setId: Long, val exercise: Int, val block: Int, val round: Int, val done: Boolean)

    /**
     * The order ticking walks through. An exercise on its own goes set by set, as its card shows them. A
     * superset goes round by round: its warm-ups first, then the n-th set of each exercise in turn.
     */
    fun order(exercises: List<ExerciseRef>): List<Slot> =
        blocks(exercises.map { it.group }).flatMapIndexed { b, block ->
            if (block.count() == 1) {
                val e = block.first
                exercises[e].sets.mapIndexed { r, s -> Slot(s.id, e, b, r, s.done) }
            } else {
                val warmupRounds = block.maxOf { e -> exercises[e].sets.count { it.warmup } }
                block.flatMap { e ->
                    val (warmups, others) = exercises[e].sets.partition { it.warmup }
                    warmups.mapIndexed { r, s -> Slot(s.id, e, b, r, s.done) } +
                        others.mapIndexed { r, s -> Slot(s.id, e, b, warmupRounds + r, s.done) }
                }.sortedWith(compareBy({ it.round }, { it.exercise }))
            }
        }

    /** The set to select once [setId] is ticked, and whether rest starts. */
    data class AfterTick(val nextSetId: Long?, val rest: Boolean)

    /**
     * Ticking a set selects the next unticked one in [order], wrapping round to any left earlier. In a
     * superset the same round's set of the next exercise follows with no rest; rest starts after the
     * last exercise of a round. There's no rest after the last set of the workout.
     */
    fun afterTick(exercises: List<ExerciseRef>, setId: Long): AfterTick {
        val order = order(exercises)
        val i = order.indexOfFirst { it.setId == setId }
        if (i < 0) return AfterTick(null, rest = false)
        val ticked = order[i]
        val later = order.drop(i + 1).indexOfFirst { !it.done }.takeIf { it >= 0 }?.let { i + 1 + it }
        val nextIndex = later ?: order.take(i).indexOfFirst { !it.done }.takeIf { it >= 0 }
        val next = nextIndex?.let { order[it] } ?: return AfterTick(null, rest = false)
        val sameRound = later != null && next.block == ticked.block && next.round == ticked.round
        return AfterTick(next.setId, rest = !sameRound)
    }
}
