package app.recomp.domain.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RotationTest {
    private val push = 1L
    private val pull = 2L
    private val legs = 3L
    private val ppl = listOf(push, pull, legs)

    @Test
    fun `empty rotation has no next routine`() {
        assertNull(Rotation.next(emptyList(), listOf(push)))
    }

    @Test
    fun `first ever workout starts the rotation`() {
        assertEquals(push, Rotation.next(ppl, emptyList()))
    }

    @Test
    fun `next follows the last routine done`() {
        assertEquals(pull, Rotation.next(ppl, listOf(push)))
        assertEquals(legs, Rotation.next(ppl, listOf(pull, push)))
    }

    @Test
    fun `rotation wraps around`() {
        assertEquals(push, Rotation.next(ppl, listOf(legs, pull, push)))
    }

    @Test
    fun `a missed week doesn't break the sequence because dates play no part`() {
        assertEquals(legs, Rotation.next(ppl, listOf(pull)))
    }

    @Test
    fun `doing a different routine than suggested moves the sequence on from it`() {
        // Pull was due, but you did Legs: Push is next, not Pull.
        assertEquals(push, Rotation.next(ppl, listOf(legs, push)))
    }

    @Test
    fun `ad-hoc workouts are skipped`() {
        assertEquals(legs, Rotation.next(ppl, listOf(null, null, pull)))
    }

    @Test
    fun `routines from an old split are skipped`() {
        val oldUpper = 10L
        assertEquals(push, Rotation.next(ppl, listOf(oldUpper)))
        assertEquals(pull, Rotation.next(ppl, listOf(oldUpper, push)))
    }

    @Test
    fun `a single-routine rotation always repeats it`() {
        assertEquals(push, Rotation.next(listOf(push), listOf(push)))
    }
}
