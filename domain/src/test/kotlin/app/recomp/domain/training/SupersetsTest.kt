package app.recomp.domain.training

import app.recomp.domain.training.Supersets.AfterTick
import app.recomp.domain.training.Supersets.ExerciseRef
import app.recomp.domain.training.Supersets.SetRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RT-05: joining, splitting and moving supersets, and the order ticking walks through. */
class SupersetsTest {
    // Editing groups

    @Test
    fun `superset with next joins two, then three, and no more`() {
        val none = listOf<Int?>(null, null, null, null)
        val two = Supersets.joinNext(none, 0)
        assertEquals(listOf(1, 1, null, null), two)
        // From the first exercise, "next" is already in its superset.
        assertFalse(Supersets.canJoinNext(two, 0))
        val three = Supersets.joinNext(two, 1)
        assertEquals(listOf(1, 1, 1, null), three)
        assertFalse(Supersets.canJoinNext(three, 2))
        assertEquals(three, Supersets.joinNext(three, 2))
        // The last exercise has no next.
        assertFalse(Supersets.canJoinNext(none, 3))
    }

    @Test
    fun `joining an exercise with a superset that would make four is refused`() {
        val groups = listOf<Int?>(null, 7, 7, 7)
        assertFalse(Supersets.canJoinNext(groups, 0))
        // Two pairs can't merge either.
        assertFalse(Supersets.canJoinNext(listOf(1, 1, 2, 2), 1))
        assertTrue(Supersets.canJoinNext(listOf(1, 1, null, 2, 2), 1))
        assertEquals(listOf(1, 1, 1, 2, 2), Supersets.joinNext(listOf(1, 1, null, 2, 2), 1))
    }

    @Test
    fun `split superset frees every exercise in it and leaves others alone`() {
        assertEquals(listOf(null, null, null, 1, 1), Supersets.split(listOf(4, 4, 4, 9, 9), 1))
        assertEquals(listOf(null, null), Supersets.split(listOf(null, null), 0))
    }

    @Test
    fun `normalize keeps only consecutive runs and numbers them in order`() {
        // A lone 5 or 3 is no superset, and a number split by another exercise isn't one either.
        assertEquals(listOf(null, 1, 1, null, null, 2, 2), Supersets.normalize(listOf(5, 8, 8, 3, null, 2, 2)))
        assertEquals(listOf(null, null, null), Supersets.normalize(listOf(3, null, 3)))
        assertEquals(listOf(0..1, 2..2, 3..3), Supersets.blocks(listOf(1, 1, null, null)))
        assertEquals(1 to 3, Supersets.position(listOf(null, 2, 2, 2), 2))
        assertNull(Supersets.position(listOf(null, 2, 2, 2), 0))
    }

    @Test
    fun `moves swap inside a superset, move a whole superset at its edge, and jump over one`() {
        val groups = listOf<Int?>(null, 1, 1, null)
        // Inside: the second member moves up past the first.
        assertEquals(listOf(0, 2, 1, 3), Supersets.move(groups, 2, up = true))
        // At the top edge the superset moves up past the exercise above it.
        assertEquals(listOf(1, 2, 0, 3), Supersets.move(groups, 1, up = true))
        // An exercise moving down past a superset jumps it.
        assertEquals(listOf(1, 2, 0, 3), Supersets.move(groups, 0, up = false))
        assertEquals(listOf(0, 3, 1, 2), Supersets.move(groups, 3, up = true))
        // Nowhere to go.
        assertNull(Supersets.move(groups, 0, up = true))
        assertNull(Supersets.move(groups, 3, up = false))
    }

    // The tick order

    private var nextId = 1L
    private fun exercise(group: Int?, sets: Int, warmups: Int = 0, done: Set<Int> = emptySet()) =
        ExerciseRef(group, List(warmups + sets) { i -> SetRef(nextId++, warmup = i < warmups, done = i in done) })

    private fun ExerciseRef.set(i: Int) = sets[i].id

    /** The same exercise with these sets ticked. */
    private fun ExerciseRef.done(vararg i: Int) = copy(sets = sets.mapIndexed { j, s -> if (j in i) s.copy(done = true) else s })

    @Test
    fun `a plain workout goes set by set, resting after each, and not after the last`() {
        val a = exercise(null, 3)
        val b = exercise(null, 2)
        val workout = listOf(a, b)
        assertEquals(AfterTick(a.set(1), rest = true), Supersets.afterTick(workout, a.set(0)))
        assertEquals(AfterTick(b.set(0), rest = true), Supersets.afterTick(workout, a.set(2)))
        val almostDone = listOf(exercise(null, 1, done = setOf(0)), exercise(null, 1))
        assertEquals(AfterTick(null, rest = false), Supersets.afterTick(almostDone, almostDone[1].set(0)))
    }

    @Test
    fun `a superset selects the same set of the next exercise with no rest, then rests after the round`() {
        val a = exercise(1, 3)
        val b = exercise(1, 3)
        val c = exercise(null, 3)
        val workout = listOf(a, b, c)
        assertEquals(listOf(a.set(0), b.set(0), a.set(1), b.set(1), a.set(2), b.set(2)), Supersets.order(workout).take(6).map { it.setId })
        assertEquals(AfterTick(b.set(0), rest = false), Supersets.afterTick(workout, a.set(0)))
        assertEquals(AfterTick(a.set(1), rest = true), Supersets.afterTick(listOf(a.done(0), b, c), b.set(0)))
        assertEquals(AfterTick(c.set(0), rest = true), Supersets.afterTick(listOf(a.done(0, 1, 2), b.done(0, 1), c), b.set(2)))
    }

    @Test
    fun `three exercises go round in turn`() {
        val a = exercise(1, 2)
        val b = exercise(1, 2)
        val c = exercise(1, 2)
        val workout = listOf(a, b, c)
        assertEquals(AfterTick(b.set(0), rest = false), Supersets.afterTick(workout, a.set(0)))
        assertEquals(AfterTick(c.set(0), rest = false), Supersets.afterTick(listOf(a.done(0), b, c), b.set(0)))
        assertEquals(AfterTick(a.set(1), rest = true), Supersets.afterTick(listOf(a.done(0), b.done(0), c), c.set(0)))
    }

    @Test
    fun `an extra set on one exercise is a round of its own`() {
        val a = exercise(1, 4)
        val b = exercise(1, 3)
        assertEquals(AfterTick(a.set(3), rest = true), Supersets.afterTick(listOf(a.done(0, 1, 2), b.done(0, 1)), b.set(2)))
        assertEquals(AfterTick(null, rest = false), Supersets.afterTick(listOf(exercise(1, 2, done = setOf(0, 1)), exercise(1, 1, done = setOf(0))), 999))
    }

    @Test
    fun `warm-ups come first, round by round, before the working rounds pair up`() {
        val a = exercise(1, 2, warmups = 2)
        val b = exercise(1, 2)
        val workout = listOf(a, b)
        assertEquals(listOf(a.set(0), a.set(1), a.set(2), b.set(0), a.set(3), b.set(1)), Supersets.order(workout).map { it.setId })
        assertEquals(AfterTick(a.set(1), rest = true), Supersets.afterTick(workout, a.set(0)))
        assertEquals(AfterTick(b.set(0), rest = false), Supersets.afterTick(workout, a.set(2)))
    }

    @Test
    fun `a set already ticked is skipped, and a finished round rests`() {
        val a = exercise(1, 2)
        val b = exercise(1, 2, done = setOf(0))
        val workout = listOf(a, b)
        assertEquals(AfterTick(a.set(1), rest = true), Supersets.afterTick(workout, a.set(0)))
        // Wrapping back to a set left in an earlier round rests too.
        val c = exercise(1, 2, done = setOf(1))
        val d = exercise(1, 2, done = setOf(0))
        assertEquals(AfterTick(c.set(0), rest = true), Supersets.afterTick(listOf(c, d), d.set(1)))
    }

    @Test
    fun `starting a round on the second exercise selects the first exercise's set of that round with no rest`() {
        // The B machine was free first.
        val a = exercise(1, 3)
        val b = exercise(1, 3)
        val workout = listOf(a, b)
        assertEquals(AfterTick(a.set(0), rest = false), Supersets.afterTick(workout, b.set(0)))
        // Then A's set ends the round: rest, and on to round 2.
        assertEquals(AfterTick(a.set(1), rest = true), Supersets.afterTick(listOf(a, b.done(0)), a.set(0)))
        // A skipped all along (its machine is taken): from the second round on, ticks on B rest as usual.
        assertEquals(AfterTick(a.set(2), rest = true), Supersets.afterTick(listOf(a, b.done(0)), b.set(1)))
        assertEquals(AfterTick(a.set(0), rest = true), Supersets.afterTick(listOf(a, b.done(0, 1)), b.set(2)))
        // In a three-exercise round the later exercise comes before the earlier one.
        val x = exercise(1, 1)
        val y = exercise(1, 1)
        val z = exercise(1, 1)
        assertEquals(AfterTick(z.set(0), rest = false), Supersets.afterTick(listOf(x, y, z), y.set(0)))
    }
}
