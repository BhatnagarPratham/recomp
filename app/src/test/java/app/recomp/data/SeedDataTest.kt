package app.recomp.data

import app.recomp.data.seed.SeedData
import app.recomp.domain.model.LoadType
import app.recomp.domain.model.RepClass
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.ExperiencePolicy
import app.recomp.domain.persona.SplitTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bundled library and templates (EX-01, RT-04) are data, so they get their own checks. */
class SeedDataTest {
    private val exercises = FileSeedSource.exercises()
    private val byKey = exercises.associateBy { it.key }
    private val templates = FileSeedSource.templates()

    @Test
    fun `library has about 80 exercises with unique keys and names`() {
        assertTrue("${exercises.size} exercises", exercises.size in 75..90)
        assertEquals(exercises.size, byKey.size)
        assertEquals(exercises.size, exercises.map { it.name.lowercase() }.toSet().size)
    }

    @Test
    fun `every exercise is complete`() {
        exercises.forEach {
            assertTrue(it.key, it.key.matches(Regex("[a-z0-9_]+")))
            assertTrue(it.key, it.primaryMuscle.isNotBlank() && it.movementPattern.isNotBlank())
            assertTrue(it.key, it.restSec in 30..300)
            assertTrue(it.key, it.incrementG == null || it.incrementG > 0)
        }
    }

    @Test
    fun `every split except custom has a template whose exercises are all in the library`() {
        val splits = templates.map { it.split }
        assertEquals(SplitTemplate.entries.filter { it != SplitTemplate.CUSTOM }.toSet(), splits.toSet())
        assertEquals(splits.size, splits.toSet().size)
        templates.forEach { template ->
            assertTrue(template.split.name, template.routines.size >= 2)
            template.routines.forEach { routine ->
                assertTrue(routine.name, routine.exercises.isNotEmpty())
                routine.exercises.forEach { entry ->
                    entry.options.forEach { assertTrue("${routine.name}: $it", it in byKey) }
                    assertEquals(entry.options.size, entry.options.toSet().size)
                    assertEquals(3, entry.sets)
                }
            }
        }
    }

    @Test
    fun `the PPL template matches the reference table in PRD section 7_3`() {
        val ppl = templates.first { it.split == SplitTemplate.PPL }.routines
        assertEquals(listOf("Push", "Pull", "Legs"), ppl.map { it.name })
        assertEquals(listOf(6, 6, 7), ppl.map { it.exercises.size })
        val alternatives = ppl.flatMap { it.exercises }.filter { it.alternatives.isNotEmpty() }.map { it.exercise }
        // The rows marked "/" in the PRD are the ones confirmed at onboarding.
        assertEquals(
            listOf("dumbbell_shoulder_press", "pec_deck", "cable_overhead_triceps_extension", "machine_chest_supported_row", "barbell_hip_thrust"),
            alternatives,
        )
        assertEquals(LoadType.BODYWEIGHT_PLUS, byKey.getValue("back_extension").loadType)
    }

    @Test
    fun `high-rep exercises get the high-rep range and the rest follow the policy`() {
        val beginner = ExperiencePolicy.defaultFor(Experience.BEGINNER)
        listOf("dumbbell_lateral_raise", "face_pull", "standing_calf_raise", "hip_adduction", "hip_abduction").forEach {
            assertEquals(it, RepClass.HIGH_REP, byKey.getValue(it).repClass)
        }
        val bench = byKey.getValue("bench_press").toEntity(beginner)
        assertEquals(8 to 12, bench.defaultRepMin to bench.defaultRepMax)
        val pushdown = byKey.getValue("triceps_pushdown").toEntity(beginner)
        assertEquals(10 to 15, pushdown.defaultRepMin to pushdown.defaultRepMax)
    }

    @Test
    fun `changing experience moves untouched rep ranges and keeps edited ones`() {
        val beginner = ExperiencePolicy.defaultFor(Experience.BEGINNER)
        val advanced = ExperiencePolicy.defaultFor(Experience.ADVANCED)
        val bench = byKey.getValue("bench_press").toEntity(beginner)
        val squat = byKey.getValue("back_squat").toEntity(beginner).copy(defaultRepMin = 3, defaultRepMax = 5)
        val updated = SeedData.reapplyRepRanges(listOf(bench, squat), byKey, beginner, advanced)
        assertEquals(listOf(bench.copy(defaultRepMin = 5, defaultRepMax = 8)), updated)
    }
}
