package app.recomp.domain.review

import app.recomp.domain.food.Origin
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Intake
import app.recomp.domain.nutrition.LoggedDay
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.GoalPlan
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HM-03, HM-04, TRD §5.8: the weekly review's arithmetic on fixed, invented numbers. */
class WeeklyReviewTest {
    private val monday = LocalDate.of(2026, 11, 2)

    private fun day(d: Int, protein: Int, target: Int?) = LoggedDay(monday.plusDays(d.toLong()), Intake(2000, protein), 2100, target)

    @Test
    fun `protein days hit at 95 percent, leaving out days without a target`() {
        val days = listOf(
            day(0, 114, 120), // 95% exactly: hit
            day(1, 113, 120), // just under
            day(2, 150, 120),
            day(3, 80, null), // the ramp's baseline week: no target, not counted
            day(4, 60, null),
        )
        assertEquals(ProteinDays(hit = 2, counted = 3), WeeklyReview.proteinDays(days))
        assertEquals(ProteinDays(0, 0), WeeklyReview.proteinDays(days.drop(3)))
    }

    @Test
    fun `origin shares are of the week's calories`() {
        val shares = WeeklyReview.originShares(mapOf(Origin.HOME to 6000.0, Origin.ORDERED to 3000.0, Origin.PACKAGED to 1000.0))!!
        assertEquals(10_000.0, shares.totalKcal, 0.0)
        assertEquals(30.0, shares.orderedPct, 1e-9)
        assertEquals(10.0, shares.packagedPct, 1e-9)
        assertEquals(0.0, WeeklyReview.originShares(mapOf(Origin.HOME to 500.0))!!.orderedPct, 0.0)
        assertNull(WeeklyReview.originShares(emptyMap()))
        assertNull(WeeklyReview.originShares(mapOf(Origin.HOME to 0.0)))
    }

    // The finish line

    private val asOf = LocalDate.of(2026, 11, 15)
    private fun cut(finish: FinishLine? = FinishLine.GoalWeight, goalKg: Double? = 78.0) = GoalPlan.of(Goal.FAT_LOSS, Pace.STANDARD, goalKg, finish, asOf)

    @Test
    fun `at this rate the goal weight is reached around a date`() {
        // 81 → 78 kg at 0.5 kg a week: 6 weeks.
        assertEquals(FinishOutlook.Around(asOf.plusDays(42), 78.0), WeeklyReview.outlook(cut(), asOf, 81.0, -0.5))
    }

    @Test
    fun `at the goal weight or past it, the goal is reached, whichever way it goes`() {
        // A cut to 78 kg: at it, or under it, even with no rate yet and whatever the rate says.
        assertEquals(FinishOutlook.Reached(78.0), WeeklyReview.outlook(cut(), asOf, 78.0, -0.5))
        assertEquals(FinishOutlook.Reached(78.0), WeeklyReview.outlook(cut(), asOf, 75.0, -0.56))
        assertEquals(FinishOutlook.Reached(78.0), WeeklyReview.outlook(cut(), asOf, 77.9, 0.2))
        assertEquals(FinishOutlook.Reached(78.0), WeeklyReview.outlook(cut(), asOf, 77.9, null))
        assertEquals(FinishOutlook.Around(asOf.plusDays(7), 78.0), WeeklyReview.outlook(cut(), asOf, 78.5, -0.5))
        // A recomp's goal weight is a way down too.
        val recomp = GoalPlan.of(Goal.RECOMP, Pace.STANDARD, 78.0, FinishLine.GoalWeight, asOf)
        assertEquals(FinishOutlook.Reached(78.0), WeeklyReview.outlook(recomp, asOf, 77.5, -0.1))
        // A lean bulk to 75 kg: at it, or over it.
        val bulk = GoalPlan.of(Goal.LEAN_BULK, Pace.STANDARD, 75.0, FinishLine.GoalWeight, asOf)
        assertEquals(FinishOutlook.Reached(75.0), WeeklyReview.outlook(bulk, asOf, 75.0, 0.25))
        assertEquals(FinishOutlook.Reached(75.0), WeeklyReview.outlook(bulk, asOf, 75.4, 0.3))
        assertEquals(FinishOutlook.Reached(75.0), WeeklyReview.outlook(bulk, asOf, 76.0, -0.1))
        // Under it, a bulk is still on the way, and going down is moving away.
        assertEquals(FinishOutlook.Around(asOf.plusDays(28), 75.0), WeeklyReview.outlook(bulk, asOf, 74.0, 0.25))
        assertEquals(FinishOutlook.MovingAway, WeeklyReview.outlook(bulk, asOf, 74.0, -0.25))
    }

    @Test
    fun `moving away, holding steady and far off have no date`() {
        assertEquals(FinishOutlook.MovingAway, WeeklyReview.outlook(cut(), asOf, 81.0, 0.3))
        assertEquals(FinishOutlook.Steady, WeeklyReview.outlook(cut(), asOf, 81.0, -0.049))
        assertEquals(FinishOutlook.Steady, WeeklyReview.outlook(cut(), asOf, 81.0, 0.04))
        // 0.05 kg a week is moving: 60 weeks.
        assertEquals(FinishOutlook.Around(asOf.plusDays(420), 78.0), WeeklyReview.outlook(cut(), asOf, 81.0, -0.05))
        // 22 kg at 0.1 kg a week is over two years.
        assertEquals(FinishOutlook.FarOff, WeeklyReview.outlook(cut(), asOf, 100.0, -0.1))
    }

    @Test
    fun `no trend yet needs data, and no finish line has no outlook`() {
        assertEquals(FinishOutlook.NeedsData(), WeeklyReview.outlook(cut(), asOf, null, null))
        assertEquals(FinishOutlook.NeedsData(), WeeklyReview.outlook(cut(), asOf, 81.0, null))
        assertNull(WeeklyReview.outlook(cut(finish = null), asOf, 81.0, -0.5))
        assertNull(WeeklyReview.outlook(cut(goalKg = null), asOf, 81.0, -0.5))
    }

    @Test
    fun `a date finish line shows the change expected by then`() {
        val bulk = GoalPlan.of(Goal.LEAN_BULK, Pace.STANDARD, null, FinishLine.ByDate(asOf.plusWeeks(8)), asOf)
        assertEquals(FinishOutlook.ByDate(asOf.plusWeeks(8), 2.0), WeeklyReview.outlook(bulk, asOf, 70.0, 0.25))
        assertEquals(FinishOutlook.NeedsData(), WeeklyReview.outlook(bulk, asOf, 70.0, null))
        // A date that has passed is M19's Goal reached, not a projection.
        assertNull(WeeklyReview.outlook(bulk, asOf.plusWeeks(9), 72.0, 0.25))
    }

    @Test
    fun `a body-fat finish line runs on the scans' slope`() {
        val recomp = GoalPlan.of(Goal.RECOMP, Pace.STANDARD, null, FinishLine.BodyFat(15.0), asOf)
        assertEquals(FinishOutlook.Around(asOf.plusDays(42), 15.0, bodyFat = true), WeeklyReview.outlook(recomp, asOf, 81.0, -0.2, bodyFatPct = 18.0, bodyFatPctPerWeek = -0.5))
        assertEquals(FinishOutlook.MovingAway, WeeklyReview.outlook(recomp, asOf, 81.0, -0.2, bodyFatPct = 18.0, bodyFatPctPerWeek = 0.3))
        assertEquals(FinishOutlook.NeedsData(bodyFat = true), WeeklyReview.outlook(recomp, asOf, 81.0, -0.2))
        // At the body-fat goal or under it, it's reached.
        assertEquals(FinishOutlook.Reached(15.0, bodyFat = true), WeeklyReview.outlook(recomp, asOf, 81.0, -0.2, bodyFatPct = 15.0, bodyFatPctPerWeek = -0.5))
        assertEquals(FinishOutlook.Reached(15.0, bodyFat = true), WeeklyReview.outlook(recomp, asOf, 81.0, -0.2, bodyFatPct = 14.2, bodyFatPctPerWeek = 0.1))
    }

    // The calendar

    @Test
    fun `the calendar marks what happened, Monday first, with no missed-day marks`() {
        val month = YearMonth.of(2026, 11)
        val rows = WeeklyReview.calendar(
            month,
            strength = setOf(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 10, 30)),
            extra = setOf(LocalDate.of(2026, 11, 7)),
            food = setOf(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3)),
        )
        assertTrue(rows.all { it.size == 7 })
        val days = rows.flatten()
        // 1 November 2026 is a Sunday, so the first row has six empty days before it.
        assertEquals(LocalDate.of(2026, 11, 1).dayOfWeek.value - 1, days.indexOfFirst { it != null })
        assertEquals(DayOfWeek.SUNDAY, LocalDate.of(2026, 11, 1).dayOfWeek)
        assertEquals(30, days.count { it != null })
        val byDate = days.filterNotNull().associateBy { it.date }
        assertEquals(CalendarDay(LocalDate.of(2026, 11, 2), strength = true, extra = false, food = true), byDate[LocalDate.of(2026, 11, 2)])
        assertEquals(CalendarDay(LocalDate.of(2026, 11, 7), strength = false, extra = true, food = false), byDate[LocalDate.of(2026, 11, 7)])
        // A day with nothing is simply blank: no streak, nothing marked as missed. October's workout isn't in November.
        assertEquals(CalendarDay(LocalDate.of(2026, 11, 4), strength = false, extra = false, food = false), byDate[LocalDate.of(2026, 11, 4)])
        assertEquals(27, days.filterNotNull().count { !it.strength && !it.extra && !it.food })
    }

    @Test
    fun `a week's calendar is the month of its Thursday`() {
        // 26 Oct to 1 Nov: its Thursday is 29 Oct. 28 Sep to 4 Oct: its Thursday is 1 Oct.
        assertEquals(YearMonth.of(2026, 10), WeeklyReview.monthOf(LocalDate.of(2026, 10, 26)))
        assertEquals(YearMonth.of(2026, 10), WeeklyReview.monthOf(LocalDate.of(2026, 9, 28)))
        assertEquals(YearMonth.of(2026, 12), WeeklyReview.monthOf(LocalDate.of(2026, 11, 30)))
    }
}
