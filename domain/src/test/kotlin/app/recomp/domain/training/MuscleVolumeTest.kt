package app.recomp.domain.training

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuscleVolumeTest {
    private val wednesday = LocalDate.of(2026, 11, 18) // the week of Monday 16 November
    private fun row(date: LocalDate, primary: String, sets: Int, vararg secondary: String) = VolumeRow(date, primary, secondary.toList(), sets)
    private fun List<MuscleSets>.of(muscle: String) = firstOrNull { it.muscle == muscle }?.sets

    @Test
    fun `a working set is one for the primary muscle and half for each secondary`() {
        val week = MuscleVolume.week(listOf(row(wednesday, "chest", 3, "triceps", "front_delts"), row(wednesday, "triceps", 3)), wednesday)
        assertEquals(3.0, week.of("chest"))
        assertEquals(4.5, week.of("triceps"))
        assertEquals(1.5, week.of("front_delts"))
        assertEquals(listOf("triceps", "chest", "front_delts"), week.map { it.muscle }) // most sets first
    }

    @Test
    fun `the week runs Monday to Sunday by log date`() {
        val monday = LocalDate.of(2026, 11, 16)
        val rows = listOf(
            row(monday.minusDays(1), "quads", 4), // last Sunday
            row(monday, "quads", 3),
            row(monday.plusDays(6), "quads", 2), // this Sunday
            row(monday.plusDays(7), "quads", 5), // next Monday
        )
        assertEquals(5.0, MuscleVolume.week(rows, wednesday).of("quads"))
        assertEquals(5.0, MuscleVolume.week(rows, monday).of("quads"))
        assertEquals(5.0, MuscleVolume.week(rows, monday.plusDays(6)).of("quads"))
        assertEquals(4.0, MuscleVolume.week(rows, monday.minusDays(1)).of("quads"))
    }

    @Test
    fun `exercises with no working sets add nothing`() {
        // The caller counts only WORKING sets, so warm-ups, drop and failure sets arrive as zero.
        val week = MuscleVolume.week(listOf(row(wednesday, "lats", 0, "biceps")), wednesday)
        assertTrue(week.isEmpty())
    }

    @Test
    fun `muscles in the rotation show at zero, and custom exercises' placeholder never shows`() {
        val week = MuscleVolume.week(listOf(row(wednesday, "other", 3, "biceps")), wednesday, alsoShow = listOf("calves", "other"))
        assertEquals(0.0, week.of("calves"))
        assertEquals(1.5, week.of("biceps"))
        assertEquals(null, week.of("other"))
    }

    @Test
    fun `a muscle listed twice counts once`() {
        val week = MuscleVolume.week(listOf(row(wednesday, "glutes", 2, "glutes", "hamstrings", "hamstrings")), wednesday)
        assertEquals(2.0, week.of("glutes"))
        assertEquals(1.0, week.of("hamstrings"))
    }

    @Test
    fun `half sets print as a half`() {
        assertEquals("7.5", MuscleVolume.format(MuscleSets("chest", 15)))
        assertEquals("12", MuscleVolume.format(MuscleSets("chest", 24)))
        assertEquals("0", MuscleVolume.format(MuscleSets("chest", 0)))
    }

    @Test
    fun `a band needs a low end below its high end`() {
        assertTrue(MuscleVolume.validBand(MuscleVolume.DEFAULT_BAND_MIN, MuscleVolume.DEFAULT_BAND_MAX))
        assertTrue(MuscleVolume.validBand(0, 1))
        assertFalse(MuscleVolume.validBand(10, 10))
        assertFalse(MuscleVolume.validBand(-1, 10))
        assertFalse(MuscleVolume.validBand(10, 61))
    }
}
