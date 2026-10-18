package app.recomp.ui.onboarding

import app.recomp.domain.nutrition.BmrFormula
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.GoalPolicy
import app.recomp.domain.nutrition.NutritionTargets
import app.recomp.domain.persona.AdviceReason
import app.recomp.domain.persona.GoalAdvice
import app.recomp.domain.persona.GuardrailNote
import app.recomp.ui.components.Labels
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The goal flow's sentences (PRD §4.6 to §4.9) and where each target came from (PR-02). */
object GoalText {
    fun n(value: Number): String = NumberFormat.getIntegerInstance().format(value.toDouble().roundToInt())

    fun kcal(value: Number) = "${n(value)} kcal"

    /** Up to two decimals, without trailing zeros: 0.25, 1.6, 78. */
    fun d(value: Double): String = java.math.BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    fun cardTitle(goal: Goal) = when (goal) {
        Goal.FAT_LOSS -> "Lose fat"
        Goal.RECOMP -> "Lose fat and build muscle (Recomp)"
        Goal.MAINTAIN -> "Maintain"
        Goal.LEAN_BULK -> "Build muscle (Lean bulk)"
    }

    fun cardDescription(goal: Goal) = when (goal) {
        Goal.FAT_LOSS -> "A deficit while keeping your strength."
        Goal.RECOMP -> "A gentle deficit with high protein. Works best if you're new to lifting or carrying extra fat."
        Goal.MAINTAIN -> "Keep your weight steady and get stronger."
        Goal.LEAN_BULK -> "A small surplus to grow faster with little fat gain."
    }

    fun advice(a: GoalAdvice): String {
        val fat = a.basis.bodyFatPct?.let { "body fat around ${d(it)}%" } ?: "a BMI of ${d(a.basis.bmi)}"
        return when (a.reason) {
            AdviceReason.UNDERWEIGHT -> "With a BMI of ${d(a.basis.bmi)}, below 18.5, losing weight isn't safe. Keep your weight steady and get stronger."
            AdviceReason.LOSE_FAT -> "You want to lose fat, so a steady deficit fits."
            AdviceReason.BOTH_NEW_TO_LIFTING -> "You want both and you're new to lifting, so a recomp fits."
            AdviceReason.BOTH_HIGHER_FAT -> "You want both and you're carrying some extra fat ($fat), so a recomp fits."
            AdviceReason.BOTH_LEAN_AND_TRAINED -> "Recomposition is slow for lean, trained lifters ($fat). Build muscle now and lose fat in a later phase."
            AdviceReason.MUSCLE_HIGH_FAT -> "With $fat, lose some fat while you build muscle first, then bulk."
            AdviceReason.MUSCLE -> "A small surplus builds muscle fastest with little fat gain."
            AdviceReason.STAY -> "You want to stay as you are, so keep your weight steady and get stronger."
        }
    }

    fun guardrail(note: GuardrailNote) = when (note) {
        GuardrailNote.LOW_BMI_MAINTAIN -> "At a BMI below 18.5, losing weight isn't safe, so your goal is Maintain."
        GuardrailNote.UNDER_18_GENTLE_ONLY -> "Under 18, deficits are limited to Gentle. Involve a parent or doctor before you start."
        GuardrailNote.AGGRESSIVE_NEEDS_ACK -> AGGRESSIVE_WARNING
    }

    const val AGGRESSIVE_WARNING =
        "An aggressive pace makes it harder to keep muscle and harder to stick with. You'll feel hungrier and recover less well between sessions. " +
            "It's still held to the caps and the safety floor."

    const val LOW_BMI_CARD = "Unavailable at a BMI below 18.5."

    const val NOT_MEDICAL_ADVICE =
        "Not medical advice. If you have a medical condition, are pregnant or breastfeeding, check these targets with a professional."

    /** "≈0.5 kg a week" for a pace card. */
    fun perWeek(kgPerWeek: Double): String {
        if (kgPerWeek == 0.0) return "Weight stays about the same"
        val sign = if (kgPerWeek < 0) "−" else "+"
        return "≈$sign${"%.2f".format(abs(kgPerWeek)).trimEnd('0').trimEnd('.')} kg a week"
    }

    /** "mid-March 2027": the projection is approximate, so it never names a day. */
    fun around(date: LocalDate, locale: Locale = Locale.getDefault()): String {
        val part = when (date.dayOfMonth) {
            in 1..10 -> "early"
            in 11..20 -> "mid"
            else -> "late"
        }
        return "$part-${date.month.getDisplayName(TextStyle.FULL, locale)} ${date.year}"
    }

    /** What the pace step and finish line say for one rate. */
    fun projection(kgPerWeek: Double, weeksToGoal: Double?, goalDate: LocalDate?, today: LocalDate, finishByDate: LocalDate?): String {
        val rate = perWeek(kgPerWeek)
        return when {
            finishByDate != null -> {
                val change = kgPerWeek * java.time.temporal.ChronoUnit.DAYS.between(today, finishByDate) / 7
                "$rate, about ${if (change < 0) "−" else "+"}${d((abs(change) * 10).roundToInt() / 10.0)} kg by ${around(finishByDate)}"
            }
            weeksToGoal == 0.0 -> "$rate. You're at your goal weight already."
            weeksToGoal != null && goalDate != null -> "$rate, goal weight around ${around(goalDate)}"
            else -> rate
        }
    }

    fun rateLabel(policy: GoalPolicy) = when (policy.goal) {
        Goal.FAT_LOSS, Goal.RECOMP -> "${d(policy.lossRatePctPerWeek)}% of body weight a week"
        Goal.LEAN_BULK -> "+${d(policy.surplusFraction * 100)}% of maintenance"
        Goal.MAINTAIN -> "Maintenance"
    }

    // Where each number came from (PR-02)

    /**
     * Settings shows what a change would save (TRD §5.4), which can differ from the target in force: while a check-in
     * proposal waits, after *Keep*, or after an *Adjust*. Null when they agree.
     */
    fun inForceNote(computedKcal: Int, inForceKcal: Int?, checkinWaiting: Boolean): String? {
        if (inForceKcal == null || inForceKcal == computedKcal) return null
        return "Today's target is ${kcal(inForceKcal)} until you " + if (checkinWaiting) "answer the check-in or change something here." else "change something here."
    }

    private fun tens(kcal: Double): Int = (kcal / 10).roundToInt() * 10

    /** [estimateWeek] is the check-in week whose maintenance estimate set TDEE (TRD §5.4), when [t] uses one. */
    fun calories(t: NutritionTargets, policy: GoalPolicy, estimateWeek: LocalDate? = null): String {
        val set = t.overrides.kcal
        if (set != null) return if (set < t.floorKcal) "Set by you, raised to your safety floor of ${kcal(t.floorKcal)}." else "Set by you."
        val bmr = when (t.bmrFormula) {
            BmrFormula.MIFFLIN_ST_JEOR -> "${kcal(t.bmrKcal)} at rest (Mifflin-St Jeor, from your sex, age, height and weight)"
            BmrFormula.KATCH_MCARDLE -> "${kcal(t.bmrKcal)} at rest (Katch-McArdle, from your scan's lean mass)"
        }
        val base = if (t.adaptiveTdee) {
            val from = estimateWeek?.let { " from your check-in of ${it.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))}" } ?: " from your check-ins"
            "Maintenance is about ${kcal(tens(t.tdeeKcal))},$from (the formula says ${n(tens(t.formulaTdeeKcal))})."
        } else {
            "Maintenance is about ${kcal(t.tdeeKcal)}: $bmr × ${t.activityFactor} for your activity."
        }
        val adj = t.energyAdjustmentKcal
        val goal = when {
            adj < 0 -> " Your goal takes off ${kcal(-adj)} a day for ${rateLabel(policy)}" + (if (t.adjustmentCapped) ", capped at 25% of maintenance." else ".")
            adj > 0 -> " Your goal adds ${kcal(adj)} a day, ${rateLabel(policy)}" + (if (t.adjustmentCapped) ", capped at 15%." else ".")
            else -> " Your goal eats at maintenance."
        }
        val floor = if (t.floored) " Raised to your safety floor of ${kcal(t.floorKcal)}." else ""
        return base + goal + floor + " Training is already counted, so workouts never add food."
    }

    fun protein(t: NutritionTargets): String {
        if (t.overrides.proteinG != null) return "Set by you. Shown first everywhere, because it's what keeps your muscle."
        val ref = if (t.referenceIsGoalWeight) "your goal weight" else "your current weight"
        return "${d(t.proteinGPerKg)} g per kg of $ref (${d(t.referenceKg)} kg). Shown first everywhere, because it's what keeps your muscle."
    }

    fun fat(t: NutritionTargets): String = when {
        t.overrides.fatG != null -> "Set by you."
        t.fatFromBodyWeight -> "Your floor of 0.6 g per kg (${d(t.referenceKg)} kg), which is more than a quarter of your calories."
        else -> "A quarter of your calories. It never goes below 0.6 g per kg."
    }

    const val CARBS = "What's left of your calories after protein and fat. Fiber is shown, not targeted."

    fun goalSummary(goal: Goal, pacePolicy: GoalPolicy, paceLabel: String, goalWeightKg: Double?): String = buildString {
        append(Labels.goal(goal))
        if (goal != Goal.MAINTAIN) append(" · $paceLabel")
        if (goalWeightKg != null) append(" · goal weight ${d(goalWeightKg)} kg")
        if (goal != Goal.MAINTAIN && paceLabel == "Custom") append(" (${rateLabel(pacePolicy)})")
    }
}
