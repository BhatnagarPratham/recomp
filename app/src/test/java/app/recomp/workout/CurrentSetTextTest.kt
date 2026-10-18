package app.recomp.workout

import app.recomp.data.db.ExerciseEntity
import app.recomp.data.db.SessionExerciseEntity
import app.recomp.data.db.SessionExerciseWithSets
import app.recomp.data.db.SetEntryEntity
import app.recomp.domain.model.Equipment
import app.recomp.domain.model.Mechanic
import app.recomp.domain.model.SetType
import org.junit.Assert.assertEquals
import org.junit.Test

/** The ongoing notification names the set the workout screen selects, superset order included (RT-05). */
class CurrentSetTextTest {
    private var nextId = 1L

    private fun exercise(name: String, group: Int?, sets: Int, done: Set<Int> = emptySet(), warmups: Int = 0): SessionExerciseWithSets {
        val id = nextId++
        val entity = ExerciseEntity(
            id = id, name = name, primaryMuscle = "chest", equipment = Equipment.BARBELL, mechanic = Mechanic.COMPOUND,
            movementPattern = "horizontal_push", incrementG = 2_500, defaultRepMin = 8, defaultRepMax = 12, defaultRestSec = 120,
        )
        val entry = SessionExerciseEntity(id = id, sessionId = 1, exerciseId = id, position = id.toInt(), targetSets = sets, repMin = 8, repMax = 12, restSec = 120, supersetGroup = group)
        val rows = List(warmups + sets) { i ->
            SetEntryEntity(
                id = id * 100 + i, sessionExerciseId = id, setIndex = i,
                setType = if (i < warmups) SetType.WARMUP else SetType.WORKING,
                completedAt = if (i in done) 1L else null,
            )
        }
        return SessionExerciseWithSets(entry, entity, rows.reversed()) // any order: sorted by set index
    }

    @Test
    fun `a plain workout names the first unticked set`() {
        val workout = listOf(exercise("Bench press", null, 3, done = setOf(0)), exercise("Row", null, 3))
        assertEquals("Bench press · set 2 of 3", currentSetText(workout))
    }

    @Test
    fun `mid-round in a superset it names the next exercise's set, then the next round`() {
        assertEquals("Shoulder press · set 1 of 3", currentSetText(listOf(exercise("Bench press", 1, 3, done = setOf(0)), exercise("Shoulder press", 1, 3))))
        assertEquals("Bench press · set 2 of 3", currentSetText(listOf(exercise("Bench press", 1, 3, done = setOf(0)), exercise("Shoulder press", 1, 3, done = setOf(0)))))
        // Warm-ups on the first exercise come first, and the card's own numbering is kept.
        assertEquals("Bench press · set 3 of 5", currentSetText(listOf(exercise("Bench press", 1, 3, warmups = 2, done = setOf(0, 1)), exercise("Shoulder press", 1, 3))))
    }

    @Test
    fun `an empty or finished workout says so`() {
        assertEquals("Add an exercise to start", currentSetText(emptyList()))
        assertEquals("All sets done", currentSetText(listOf(exercise("Bench press", null, 1, done = setOf(0)))))
    }
}
