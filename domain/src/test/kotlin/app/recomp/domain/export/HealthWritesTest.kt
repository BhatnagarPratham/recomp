package app.recomp.domain.export

import app.recomp.domain.model.SessionKind
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DA-04's records (TRD §10 M16): what's written for a day, through edits and deletes. Invented values. */
class HealthWritesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 11, 20)
    private val dayStart = LocalTime.of(4, 0)
    private fun ms(hour: Int, minute: Int = 0, date: LocalDate = day) = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    private val slots = HealthWrites.mealTimes(listOf("breakfast" to "Breakfast", "lunch" to "Lunch", "snacks" to "Snacks", "dinner" to "Dinner", "pre_bed" to "Before bed"))

    private fun session(id: Long, sets: List<SetRow> = listOf(SetRow("Bench press", 40_000, false, 10)), imported: String? = null, kind: SessionKind = SessionKind.STRENGTH) =
        SessionRow(id, day, ms(18), ms(19), kind, "Push", imported, sets)

    @Test
    fun `a workout you did is written with what you did, and another app's never is`() {
        val sets = listOf(
            SetRow("Bench press", 40_000, false, 10), SetRow("Bench press", 42_500, false, 8),
            SetRow("Pull-up", null, false, 6), SetRow("Assisted dip", 20_000, true, 8), SetRow("Assisted dip", 0, true, 5),
        )
        val workouts = HealthWrites.workouts(listOf(session(7, sets), session(8, imported = "hc-123"), session(9, emptyList(), kind = SessionKind.YOGA)))
        assertEquals(listOf("recomp-workout-7", "recomp-workout-9"), workouts.map { it.id })
        val push = workouts[0]
        assertEquals("Push", push.title)
        assertEquals(SessionKind.STRENGTH, push.sessionKind)
        assertEquals("Bench press: 40 kg × 10, 42.5 kg × 8\nPull-up: BW × 6\nAssisted dip: assist 20 kg × 8, BW × 5", push.notes)
        assertNull(workouts[1].notes)
        // A session with no span (never finished properly) isn't written.
        assertTrue(HealthWrites.workouts(listOf(session(10).copy(endedAt = ms(18)))).isEmpty())
    }

    @Test
    fun `one record per meal with quick adds summed, unknown macros left unknown`() {
        val entries = listOf(
            FoodRow(day, "lunch", 300.0, 15.0, 40.0, 8.0, 6.0),
            FoodRow(day, "lunch", 210.0, 20.5, null, 7.25, null), // a quick add with no carbs or fibre
            FoodRow(day, "snacks", 150.0, 5.0, null, null, null),
            FoodRow(day.minusDays(1), "lunch", 100.0, 2.0, 10.0, 1.0, 1.0),
        )
        val meals = HealthWrites.meals(entries, slots, dayStart, zone)
        assertEquals(listOf("recomp-meal-2026-11-19-lunch", "recomp-meal-2026-11-20-lunch", "recomp-meal-2026-11-20-snacks"), meals.map { it.id })
        val lunch = meals[1]
        assertEquals(510.0, lunch.kcal, 1e-9)
        assertEquals(35.5, lunch.proteinG, 1e-9)
        assertEquals(40.0, lunch.carbsG!!, 1e-9)
        assertEquals(15.25, lunch.fatG!!, 1e-9)
        assertEquals(6.0, lunch.fiberG!!, 1e-9)
        assertEquals(OutMealType.LUNCH, lunch.mealType)
        assertEquals("Lunch", lunch.name)
        assertEquals(ms(12), lunch.start.toEpochMilli())
        assertEquals(30L * 60_000, lunch.end.toEpochMilli() - lunch.start.toEpochMilli())
        val snacks = meals[2]
        assertNull("only quick adds without carbs: unknown, not 0", snacks.carbsG)
        assertNull(snacks.fatG)
        assertEquals(OutMealType.SNACK, snacks.mealType)
    }

    @Test
    fun `meal types follow the default meals, and a meal you added is unknown and timed after the one before`() {
        assertEquals(OutMealType.BREAKFAST, HealthWrites.mealType("breakfast"))
        assertEquals(OutMealType.DINNER, HealthWrites.mealType("dinner"))
        assertEquals(OutMealType.UNKNOWN, HealthWrites.mealType("pre_bed"))
        assertEquals(listOf(5 * 60, 12 * 60, 16 * 60, 19 * 60, 20 * 60), slots.map { it.startMinutes })
        val meal = HealthWrites.meals(listOf(FoodRow(day, "pre_bed", 100.0, 10.0, null, null, null)), slots, dayStart, zone).single()
        assertEquals(OutMealType.UNKNOWN, meal.mealType)
        assertEquals("Before bed", meal.name)
        assertEquals(ms(20), meal.start.toEpochMilli())
    }

    @Test
    fun `a meal whose usual time is before the day starts sits on its log date, the next calendar morning`() {
        val late = listOf("breakfast" to "Breakfast", "midnight" to "Midnight snack").let { HealthWrites.mealTimes(it) }.map { if (it.key == "midnight") it.copy(startMinutes = 60) else it }
        val meal = HealthWrites.meals(listOf(FoodRow(day, "midnight", 200.0, 10.0, null, null, null)), late, dayStart, zone).single()
        assertEquals(day, meal.date)
        assertEquals(ms(1, date = day.plusDays(1)), meal.start.toEpochMilli())
    }

    @Test
    fun `typed weigh-ins are written and a scan's weight never is`() {
        val weights = HealthWrites.weights(
            listOf(WeighInRow(1, day, ms(7), 80_150, fromScan = false), WeighInRow(2, day, ms(10), 80_300, fromScan = true)),
        )
        assertEquals(listOf("recomp-weight-1"), weights.map { it.id })
        assertEquals(80_150L, weights.single().grams)
    }

    @Test
    fun `the first sync writes the window, then only changes, and deletes what's gone`() {
        val from = HealthWrites.windowStart(day)
        assertEquals(day.minusDays(29), from)
        val workout = HealthWrites.workouts(listOf(session(7))).single()
        val meal = HealthWrites.meals(listOf(FoodRow(day, "lunch", 300.0, 15.0, null, null, null)), slots, dayStart, zone).single()
        val weight = HealthWrites.weights(listOf(WeighInRow(1, day, ms(7), 80_150, false))).single()

        val first = HealthWrites.plan(listOf(workout, meal, weight), emptyMap(), from)
        assertEquals(3, first.upserts.size)
        assertTrue(first.deletes.isEmpty())

        // Nothing changed: nothing written.
        val again = HealthWrites.plan(listOf(workout, meal, weight), first.written, from)
        assertTrue(again.upserts.isEmpty() && again.deletes.isEmpty())

        // A set edited afterwards (M10): the workout is written again under the same id.
        val edited = HealthWrites.workouts(listOf(session(7, listOf(SetRow("Bench press", 45_000, false, 10))))).single()
        assertEquals(workout.id, edited.id)
        assertNotEquals(workout.hash, edited.hash)
        val afterEdit = HealthWrites.plan(listOf(edited, meal, weight), first.written, from)
        assertEquals(listOf(edited), afterEdit.upserts)

        // The weigh-in deleted, and the meal's only entry deleted: both go by their ids.
        val afterDelete = HealthWrites.plan(listOf(edited), afterEdit.written, from)
        assertEquals(listOf(OutKind.MEAL to meal.id, OutKind.WEIGHT to weight.id), afterDelete.deletes)
        assertEquals(setOf(edited.id), afterDelete.written.keys)
    }

    @Test
    fun `what's older than the window is forgotten, not deleted, and everything rewrites it all`() {
        val old = HealthWrites.weights(listOf(WeighInRow(1, day.minusDays(40), ms(7, date = day.minusDays(40)), 81_000, false))).single()
        val recent = HealthWrites.weights(listOf(WeighInRow(2, day, ms(7), 80_000, false))).single()
        val written = mapOf(old.id to Written(old.date, old.hash), recent.id to Written(recent.date, recent.hash))
        val from = HealthWrites.windowStart(day)

        // The old row isn't in the window's records; it's not deleted from Health Connect, just forgotten.
        val plan = HealthWrites.plan(listOf(recent), written, from)
        assertTrue(plan.deletes.isEmpty())
        assertTrue(plan.upserts.isEmpty())
        assertEquals(setOf(recent.id), plan.written.keys)

        // Resync everything: all of time, every record written again.
        val all = HealthWrites.plan(listOf(old, recent), emptyMap(), null, everything = true)
        assertEquals(listOf(old, recent), all.upserts)
    }

    @Test
    fun `ids are stable and tell their kind`() {
        assertEquals("recomp-meal-2026-11-20-lunch", HealthWrites.mealId(day, "lunch"))
        assertEquals(OutKind.WORKOUT, HealthWrites.kindOf("recomp-workout-3"))
        assertEquals(OutKind.MEAL, HealthWrites.kindOf("recomp-meal-2026-11-20-lunch"))
        assertEquals(OutKind.WEIGHT, HealthWrites.kindOf("recomp-weight-9"))
        assertNull(HealthWrites.kindOf("something-else"))
    }
}
