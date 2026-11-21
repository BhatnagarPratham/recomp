package app.recomp.domain.coach

import app.recomp.domain.persona.ExperiencePolicy
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Scheduled deloads (PRD §4.3, §12.7): a deload week every 6–8 weeks for intermediate lifters and 4–6 for advanced,
 * from `ExperiencePolicy.deloadEveryWeeks`. The card is offered from the window's first week, counted from the last
 * deload week you took (or from your first strength workout), and never for beginners, who deload only on a stall.
 * It's offered, never applied on its own.
 */
object DeloadSchedule {
    /** "Not now" hides the card for a week. */
    const val SNOOZE_DAYS = 7L

    /**
     * Whether the card shows on [today]. [since] is the last deload week accepted, else the first strength workout;
     * null before there is one. [snoozedUntil] is the day "Not now" hides it until.
     */
    fun due(policy: ExperiencePolicy, since: LocalDate?, today: LocalDate, snoozedUntil: LocalDate? = null): Boolean {
        val every = policy.deloadEveryWeeks ?: return false
        if (since == null) return false
        if (snoozedUntil != null && today.isBefore(snoozedUntil)) return false
        return weeksSince(since, today) >= every.first
    }

    fun weeksSince(since: LocalDate, today: LocalDate): Long = ChronoUnit.WEEKS.between(since, today)
}

/** TRD §5.5: a working set counts 1 for the exercise's primary muscle and 0.5 for each secondary one. */
object MuscleSets {
    data class Work(val primary: String, val secondary: List<String>, val workingSets: Int)

    fun weekly(work: List<Work>): Map<String, Double> {
        val sets = linkedMapOf<String, Double>()
        work.forEach { w ->
            if (w.workingSets <= 0) return@forEach
            sets[w.primary] = (sets[w.primary] ?: 0.0) + w.workingSets
            w.secondary.filter { it != w.primary }.forEach { m -> sets[m] = (sets[m] ?: 0.0) + w.workingSets * 0.5 }
        }
        return sets
    }
}
