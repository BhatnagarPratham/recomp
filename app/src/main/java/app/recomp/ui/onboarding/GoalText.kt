package app.recomp.ui.onboarding

import app.recomp.domain.nutrition.BmrFormula
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.GoalPolicy
import app.recomp.domain.nutrition.NutritionTargets
import app.recomp.domain.persona.AdviceReason
import app.recomp.domain.persona.Expectation
import app.recomp.domain.persona.Intent
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

    /** The body-fat finish line's goal weight, from the scan's lean mass (IB-05). */
    fun bodyFatGoal(leanKg: Double, pct: Double): String =
        "Lean mass ${d(leanKg)} kg from your scan, so ${d(pct)}% body fat is about ${d(app.recomp.domain.persona.GoalWeights.forBodyFat(leanKg, pct))} kg."

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
            AdviceReason.NO_DEFICIT -> "While you're pregnant or breastfeeding, losing weight is off, so keep your weight steady and get stronger. $ASK_A_DOCTOR"
        }
    }

    fun guardrail(note: GuardrailNote) = when (note) {
        GuardrailNote.LOW_BMI_MAINTAIN -> "At a BMI below 18.5, losing weight isn't safe, so your goal is Maintain."
        GuardrailNote.PREGNANT_MAINTAIN -> "While you're pregnant or breastfeeding, fat loss and recomp are off, so your goal is Maintain. $ASK_A_DOCTOR"
        GuardrailNote.PREGNANT_KCAL_AT_MAINTENANCE -> "Your own calorie target was below maintenance, so it isn't used while you're pregnant or breastfeeding."
        GuardrailNote.UNDER_18_GENTLE_ONLY -> "Under 18, deficits are limited to Gentle. Involve a parent or doctor before you start."
        GuardrailNote.AGGRESSIVE_NEEDS_ACK -> AGGRESSIVE_WARNING
    }

    const val AGGRESSIVE_WARNING =
        "An aggressive pace makes it harder to keep muscle and harder to stick with. You'll feel hungrier and recover less well between sessions. " +
            "It's still held to the caps and the safety floor."

    const val LOW_BMI_CARD = "Unavailable at a BMI below 18.5."

    // Pregnancy and breastfeeding (GL-09)

    const val ASK_A_DOCTOR = "Ask your doctor or midwife about calories and protein."
    const val PREGNANT_CARD = "Unavailable while pregnant or breastfeeding."
    const val PREGNANT_TITLE = "Pregnant or breastfeeding"
    const val PREGNANT_BODY = "Turns off fat loss and recomp. It stays on this phone and is never sent to AI."
    const val PREGNANT_ON = "Fat loss and recomp are off, and nothing will suggest eating below maintenance. $ASK_A_DOCTOR"

    /** Why a deficit goal's card is greyed out. */
    fun unavailable(block: GuardrailNote?): String? = when (block) {
        GuardrailNote.PREGNANT_MAINTAIN -> PREGNANT_CARD
        GuardrailNote.LOW_BMI_MAINTAIN -> LOW_BMI_CARD
        else -> null
    }

    // Goal switch (PS-04)

    /** *What to expect*: one line on what the scale does after this change of goal. */
    fun expect(e: Expectation): String = when (e) {
        Expectation.DEFICIT_TO_SURPLUS -> "The scale will jump 1–2 kg in the first two weeks as water and glycogen come back; judge from week 3."
        Expectation.DEFICIT_TO_MAINTENANCE -> "The scale may rise about 1 kg in the first two weeks as water and glycogen come back. That isn't fat; judge from week 3."
        Expectation.MAINTENANCE_TO_SURPLUS -> "Expect a quick 0.5–1 kg in the first two weeks from more food and water, then a slow climb; judge from week 3."
        Expectation.SURPLUS_TO_DEFICIT -> "The scale will drop 1–2 kg in the first two weeks, mostly water and glycogen; judge fat loss from week 3."
        Expectation.MAINTENANCE_TO_DEFICIT -> "The first week or two often drop faster than your pace, mostly water; judge from week 3."
        Expectation.SURPLUS_TO_MAINTENANCE -> "The scale may drop about 1 kg in the first two weeks as water settles, then hold steady."
        Expectation.GENTLER_DEFICIT -> "A gentler deficit: the scale moves more slowly now, so watch your waist and your lifts as well."
        Expectation.LARGER_DEFICIT -> "A larger deficit: the scale should fall faster now. Holding your lifts steady is the win."
    }

    const val FRESH_START = "Comparisons start again from today: \"since you started\", the weekly review and the 12-week count. Your history stays."
    const val BREAK_TARGETS = "You're on a maintenance break, so calories are at maintenance. A calorie target of your own returns when your goal resumes."
    const val ENDS_BREAK = "Saving ends your maintenance break: these targets apply from today."

    // Describe your goal (GL-03)

    const val DESCRIBE_TITLE = "None of these fit? Describe your goal"
    const val DESCRIBE_BODY = "In your own words. You'll get a suggestion with the reason for it."
    const val THREE_QUESTIONS = "Answer 3 quick questions instead"
    const val AI_UNCLEAR = "The AI couldn't tell what you want from that, so here are three quick questions instead."
    const val AI_NOT_READY = "AI isn't ready right now, so here are three quick questions instead."

    fun aiFailed(reason: String) = "$reason, so here are three quick questions instead."

    fun intent(i: Intent) = when (i) {
        Intent.LOSE_FAT -> "Lose fat"
        Intent.BUILD_MUSCLE -> "Build muscle"
        Intent.BOTH -> "Both"
        Intent.MAINTAIN -> "Stay as I am"
    }

    fun aiRead(i: Intent) = "From what you wrote: ${intent(i).lowercase()}. Change it below if that's not right."

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
