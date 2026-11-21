package app.recomp.data.repo

import app.recomp.data.db.TargetReason
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.data.prefs.StoredGoal
import app.recomp.domain.persona.GoalChange
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.GoalProgress
import app.recomp.domain.persona.GoalStatus
import app.recomp.domain.persona.Guardrails
import app.recomp.domain.persona.MaintenanceBreak
import app.recomp.domain.persona.Profile
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The goal's own state (GL-06, PRD §12.15, TRD §5.8): whether its finish line is reached, and the maintenance
 * break. The state lives on the stored goal plan; a break is two target versions, `BREAK` when it starts and
 * `RESUME` when it ends.
 *
 * The automatic resume is the one place a target version is written without you asking at that moment: accepting
 * the break agreed to it. Everything else here is an answer to a card.
 */
@Singleton
class GoalRepository @Inject constructor(
    private val prefs: SettingsPrefs,
    private val days: DayBoundary,
    private val personas: PersonaRepository,
    private val body: BodyRepository,
) {
    /** [refresh] and the answers each read the plan and write it back. */
    private val mutex = Mutex()

    /** What Today and the check-in show, for the goal as the guardrails leave it. It follows the day and the trend. */
    val status: Flow<GoalStatus> = combine(prefs.persona, prefs.profile, days.observeToday(), body.observeTrend()) { persona, profile, today, trend ->
        val plan = persona.goal?.toPlan()?.takeIf { persona.onboarded }
        if (plan == null || profile == null) GoalStatus.None
        else GoalProgress.status(Guardrails.apply(profile.copy(weightKg = trend.trendKg ?: profile.weightKg), plan, today).plan, today)
    }.distinctUntilChanged()

    private suspend fun storedPlan(): GoalPlan? = prefs.currentPersona().takeIf { it.onboarded }?.goal?.toPlan()

    private suspend fun store(plan: GoalPlan, profile: Profile? = null) = prefs.edit {
        persona = this.persona.copy(goal = StoredGoal.of(plan))
        // As a Settings change does: the body data holds the trend weight the new targets used (TRD §5.0).
        if (profile != null) setProfile(profile)
    }

    /**
     * Run at launch, on each new log day, and with each check-in. A break whose end date has come resumes the goal,
     * however many days the app stayed closed; then the finish line is checked against the trend and the date.
     */
    suspend fun refresh(now: Long = System.currentTimeMillis()) = withContext(NonCancellable) {
        mutex.withLock {
            val today = days.of(now)
            var plan = storedPlan() ?: return@withLock
            val until = plan.breakUntil
            if (until != null && MaintenanceBreak.shouldResume(plan, today)) plan = resume(plan, endedOn = until, today, now) ?: return@withLock
            // TODO(M15): a body-fat finish line reads the latest scan (IB-05); it can't be chosen before then.
            GoalProgress.markReached(plan, body.trendKg(), bodyFatPct = null, today)?.let { store(it) }
        }
    }

    /**
     * Accepts the maintenance break for [weeks] (1 or 2), or starts the 2 weeks at maintenance after a reached goal.
     * Maintenance targets apply from today. False when there's nothing to start.
     */
    suspend fun startBreak(weeks: Int, now: Long = System.currentTimeMillis()): Boolean = answer(now) { plan, today ->
        val reached = plan.reachedOn != null
        val allowed = if (reached) (GoalProgress.status(plan, today) as? GoalStatus.Reached)?.offerMaintenance == true && weeks == MaintenanceBreak.REACHED_WEEKS
        else MaintenanceBreak.due(plan, today) && weeks in MaintenanceBreak.WEEKS
        if (!allowed) return@answer false
        write(MaintenanceBreak.start(plan, today, weeks), TargetReason.BREAK, today, now)
    }

    /** *End early*: the goal's targets are back from today, and the 12 weeks count from today. */
    suspend fun endBreakEarly(now: Long = System.currentTimeMillis()): Boolean = answer(now) { plan, today ->
        if (!plan.onBreak || plan.reachedOn != null) false else resume(plan, endedOn = today, today, now) != null
    }

    /** *Not now*: the break is suggested again in 4 weeks. */
    suspend fun snoozeBreak(now: Long = System.currentTimeMillis()): Boolean = answer(now) { plan, today ->
        if (!MaintenanceBreak.due(plan, today)) return@answer false
        store(MaintenanceBreak.snooze(plan, today))
        true
    }

    /** *Keep going*: the finish line is cleared. After the 2 weeks at maintenance, the goal's targets come back too. */
    suspend fun keepGoing(now: Long = System.currentTimeMillis()): Boolean = answer(now) { plan, today ->
        if (plan.reachedOn == null) return@answer false
        val next = GoalProgress.keepGoing(plan, today)
        if (plan.onBreak) write(next, TargetReason.RESUME, today, now) else store(next)
        true
    }

    private suspend fun answer(now: Long, block: suspend (GoalPlan, LocalDate) -> Boolean): Boolean = withContext(NonCancellable) {
        mutex.withLock {
            val plan = storedPlan() ?: return@withLock false
            block(plan, days.of(now))
        }
    }

    private suspend fun resume(plan: GoalPlan, endedOn: LocalDate, today: LocalDate, now: Long): GoalPlan? {
        val next = MaintenanceBreak.end(plan, endedOn)
        return next.takeIf { write(it, TargetReason.RESUME, today, now) }?.let { storedPlan() }
    }

    /**
     * Writes [plan]'s targets as a version from [today] and stores the plan: the target first, as a Settings save
     * does, so a failure in between leaves a state the next [refresh] or save puts right. The guardrails apply as
     * everywhere (PRD §4.9); if they've since moved the goal, that goal is what's stored. False before there's body data.
     */
    private suspend fun write(plan: GoalPlan, reason: TargetReason, today: LocalDate, now: Long): Boolean {
        val profile = personas.currentProfile() ?: return false
        val guarded = GoalChange.afterGuardrails(plan, Guardrails.apply(profile, plan, today).plan, today)
        val targets = personas.targetsFor(profile, guarded, prefs.overrides.first(), today, personas.maintenance())
        personas.writeTarget(targets, guarded, today, reason, now)
        store(guarded, profile)
        return true
    }
}
