package app.recomp.domain.training

import app.recomp.domain.model.SessionKind
import app.recomp.domain.model.SetType.WARMUP
import app.recomp.domain.model.SetType.WORKING
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.ExperiencePolicy
import app.recomp.domain.model.RepClass
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class WeeklyPlanTest {
    private val wednesday = LocalDate.of(2026, 10, 14)
    private val yoga = 50L
    private val yogaSaturdays = ExtraPlan(yoga, setOf(DayOfWeek.SATURDAY))

    @Test
    fun `weeks start on Monday`() {
        assertEquals(LocalDate.of(2026, 10, 12), WeeklyPlan.weekStart(wednesday))
        assertEquals(LocalDate.of(2026, 10, 12), WeeklyPlan.weekStart(LocalDate.of(2026, 10, 12)))
        assertEquals(LocalDate.of(2026, 10, 12), WeeklyPlan.weekStart(LocalDate.of(2026, 10, 18)))
    }

    @Test
    fun `counts strength days once each and only this week`() {
        val sessions = listOf(
            PlanSession(LocalDate.of(2026, 10, 11), SessionKind.STRENGTH, 1), // last Sunday
            PlanSession(LocalDate.of(2026, 10, 12), SessionKind.STRENGTH, 1),
            PlanSession(LocalDate.of(2026, 10, 12), SessionKind.STRENGTH, null), // a second session that day
            PlanSession(LocalDate.of(2026, 10, 14), SessionKind.STRENGTH, 2),
            PlanSession(LocalDate.of(2026, 10, 13), SessionKind.YOGA, yoga),
        )
        val week = WeeklyPlan.progress(wednesday, sessions, strengthTarget = 3, extras = listOf(yogaSaturdays))
        assertEquals(2, week.strengthDays)
        assertEquals(3, week.strengthTarget)
        assertEquals(listOf(ExtraProgress(yoga, done = 1, planned = 1)), week.extras)
    }

    @Test
    fun `an extra session is due on its scheduled day until it's logged`() {
        val saturday = LocalDate.of(2026, 10, 17)
        assertEquals(listOf(yoga), WeeklyPlan.dueToday(saturday, emptyList(), listOf(yogaSaturdays)))
        assertEquals(emptyList<Long>(), WeeklyPlan.dueToday(wednesday, emptyList(), listOf(yogaSaturdays)))
        val logged = listOf(PlanSession(saturday, SessionKind.YOGA, yoga))
        assertEquals(emptyList<Long>(), WeeklyPlan.dueToday(saturday, logged, listOf(yogaSaturdays)))
    }

    @Test
    fun `summary stats skip warm-ups`() {
        val sets = listOf(LoggedSet(WARMUP, 20_000, 10), LoggedSet(WORKING, 50_000, 8), LoggedSet(WORKING, null, 12))
        val stats = SessionStats.of(startedAt = 1_000, endedAt = 3_601_000, sets = sets)
        assertEquals(SessionStats(durationMs = 3_600_000, sets = 2, reps = 20, volumeG = 400_000), stats)
    }

    @Test
    fun `experience policy maps each rep class to its range`() {
        val beginner = ExperiencePolicy.defaultFor(Experience.BEGINNER)
        assertEquals(8..12, beginner.repsFor(RepClass.COMPOUND))
        assertEquals(10..15, beginner.repsFor(RepClass.ISOLATION))
        assertEquals(12..20, beginner.repsFor(RepClass.HIGH_REP))
        assertEquals(5..8, ExperiencePolicy.defaultFor(Experience.ADVANCED).repsFor(RepClass.COMPOUND))
    }
    @Test
    fun `rest day once today's strength is done or the week's strength days are reached`() {
        fun strength(date: LocalDate) = PlanSession(date, SessionKind.STRENGTH, 1)
        val monday = LocalDate.of(2026, 10, 12)
        // Nothing yet: the next routine is up.
        assertEquals(null, WeeklyPlan.restDay(wednesday, emptyList(), strengthTarget = 3))
        // Trained today, even ad hoc.
        assertEquals(RestReason.DONE_TODAY, WeeklyPlan.restDay(wednesday, listOf(PlanSession(wednesday, SessionKind.STRENGTH, null)), 3))
        // A yoga class today isn't a strength day.
        assertEquals(null, WeeklyPlan.restDay(wednesday, listOf(PlanSession(wednesday, SessionKind.YOGA, yoga)), 3))
        // Two of three done this week, not today.
        assertEquals(null, WeeklyPlan.restDay(wednesday, listOf(strength(monday), strength(monday.plusDays(1))), 3))
        // The week's target is reached (last week's sessions don't count).
        val week = listOf(strength(monday.minusDays(1)), strength(monday), strength(monday.plusDays(1)))
        assertEquals(RestReason.WEEK_DONE, WeeklyPlan.restDay(wednesday, week, 2))
        assertEquals(null, WeeklyPlan.restDay(wednesday, week, 3))
        // Today wins when both hold.
        assertEquals(RestReason.DONE_TODAY, WeeklyPlan.restDay(wednesday, week + strength(wednesday), 2))
    }
}
