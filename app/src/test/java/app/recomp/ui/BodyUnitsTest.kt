package app.recomp.ui

import app.recomp.data.db.MeasurementType
import app.recomp.data.db.ScanDraftEntity
import app.recomp.domain.body.MobilityMarker
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.GoalPolicy
import app.recomp.domain.nutrition.NutritionTargets
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.persona.ActivityLevel
import app.recomp.domain.persona.Units
import app.recomp.domain.units.UnitMath
import app.recomp.domain.units.UnitStyle
import app.recomp.ui.body.BodyEntry
import app.recomp.ui.body.BodyText
import app.recomp.ui.body.MobilityDraft
import app.recomp.ui.body.MobilityText
import app.recomp.ui.body.ReadingText
import app.recomp.ui.body.ScanForm
import app.recomp.ui.onboarding.AboutForm
import app.recomp.ui.onboarding.GoalForm
import app.recomp.ui.onboarding.GoalText
import app.recomp.ui.onboarding.UnitFields
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Body weight, lengths, height, scans and onboarding in lb and in (PR-05, PRD §12.16). Invented numbers. */
class BodyUnitsTest {
    private val lb = UnitStyle.IMPERIAL
    private val kg = UnitStyle.METRIC
    private val german = UnitStyle(Units.IMPERIAL, ',')
    private lateinit var locale: Locale

    @Before
    fun setUp() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.UK)
    }

    @After
    fun tearDown() = Locale.setDefault(locale)

    // BodyText

    @Test
    fun `body weight and lengths show in lb and in, with the locale's decimal mark`() {
        assertEquals("176.4 lb", BodyText.weight(80_000, lb))
        assertEquals("80.0 kg", BodyText.weight(80_000, kg))
        assertEquals("176,4 lb", BodyText.weight(80_000, german))
        assertEquals("−1.1 lb", BodyText.signedWeight(-0.5, lb))
        assertEquals("0.0 lb", BodyText.signedWeight(-0.02, lb))
        assertEquals("32.25 in", BodyText.length(81.915, lb))
        assertEquals("32 in", BodyText.length(81.28, lb))
        assertEquals("32,25 in", BodyText.length(81.915, german))
        assertEquals("81.5 cm", BodyText.length(81.5, kg))
        assertEquals("−0.4 in", BodyText.signedLength(-1.016, lb))
        assertEquals("−0,4 in", BodyText.signedLength(-1.016, german))
        assertEquals("Weight, muscle and fat in lb. Off-protocol scans are marked ◌.", BodyText.scanTableCaption(lb))
    }

    @Test
    fun `typed lb accept either mark and land on whole grams`() {
        val grams = UnitMath.lbToG(BigDecimal("176.4"))
        assertEquals(grams, BodyText.parseWeight("176.4", lb))
        assertEquals(grams, BodyText.parseWeight("176,4", german))
        assertEquals("176.4 lb", BodyText.weight(grams, lb))
        assertEquals(81.915, BodyText.parseLength("32,25", german)!!, 1e-9)
    }

    @Test
    fun `the 0_2 lb stepper never drifts`() {
        var lbValue = BigDecimal("100.0")
        while (lbValue <= BigDecimal("300.0")) {
            val start = UnitMath.lbToG(lbValue)
            var g = start
            repeat(10) { g = BodyText.weightStep(g, up = true, lb) }
            assertEquals(BodyText.weight((lbValue + BigDecimal("2.0")).let(UnitMath::lbToG), lb), BodyText.weight(g, lb))
            repeat(10) { g = BodyText.weightStep(g, up = false, lb) }
            assertEquals("from $lbValue", start, g)
            lbValue += BigDecimal("0.1")
        }
        // One press from a kg-era weight moves to the lb grid: 0.2 lb from what's shown.
        assertEquals("176.6 lb", BodyText.weight(BodyText.weightStep(80_000, up = true, lb), lb))
        assertEquals("−0.2" to "+0.2", BodyEntry.weightStepLabels(lb))
        assertEquals("−0,2" to "+0,2", BodyEntry.weightStepLabels(german))
        assertEquals(BodyEntry.MAX_G, BodyEntry.weightStep(BodyEntry.MAX_G, up = true, kg))
    }

    @Test
    fun `the weigh-in sheet keeps the stored grams when the typed text is unchanged`() {
        assertEquals("176.4", BodyEntry.weightText(80_000, lb))
        assertEquals(80_000, BodyEntry.typedWeight("176.4", 80_000, lb))
        assertEquals(80_000, BodyEntry.typedWeight(" 176.4 ", 80_000, lb))
        assertEquals(UnitMath.lbToG(BigDecimal("176.5")), BodyEntry.typedWeight("176.5", 80_000, lb))
        assertNull(BodyEntry.typedWeight("5", 80_000, lb))
    }

    // Height

    @Test
    fun `height reads 5 ft 9 in both ways`() {
        val cm = UnitMath.heightCm(5, 9.0)!!
        assertEquals(175.26, cm, 1e-9)
        assertEquals("5 ft 9 in", BodyText.height(cm, lb))
        assertEquals("5 ft 9 in", BodyText.height(175.0, lb))
        assertEquals("175 cm", BodyText.height(175.0, kg))
        assertEquals("5" to "9", UnitFields.heightTexts(175.0, lb))
        assertEquals(cm, UnitFields.parseHeightCm("5", "9", lb)!!, 1e-9)
        assertEquals(UnitMath.inToCm(71.5), UnitFields.parseHeightCm("5", "11,5", lb)!!, 1e-9)
        assertEquals(UnitMath.inToCm(72.0), UnitFields.parseHeightCm("6", "", lb)!!, 1e-9)
        assertNull(UnitFields.parseHeightCm("5", "12", lb))
        assertNull(UnitFields.parseHeightCm("five", "9", lb))
    }

    // Waist

    @Test
    fun `the waist sheet keeps exact cm, never tenths`() {
        // A kg-era reading shows to the hundredth of an inch, and saving it unchanged keeps it.
        assertEquals("32.09", BodyEntry.waistText(81.5, lb))
        assertEquals(81.5, BodyEntry.typedWaist("32.09", 81.5, lb)!!, 0.0)
        // A typed quarter inch is stored as its exact cm, which shows back as typed (81.9 cm would read 32.24 in).
        val typed = BodyEntry.typedWaist("32.25", 81.5, lb)!!
        assertEquals(81.915, typed, 1e-9)
        assertEquals("32.25 in", BodyText.length(typed, lb))
        // The stepper snaps to the quarter inch, then moves by quarters without drifting.
        var cm = BodyEntry.waistStep(81.5, up = true, lb)
        assertEquals("32.25 in", BodyText.length(cm, lb))
        repeat(40) { cm = BodyEntry.waistStep(cm, up = true, lb) }
        assertEquals("42.25 in", BodyText.length(cm, lb))
        repeat(40) { cm = BodyEntry.waistStep(cm, up = false, lb) }
        assertEquals(81.915, cm, 1e-9)
        // In cm it's half centimetres and one decimal, as before.
        assertEquals(82.0, BodyEntry.waistStep(81.5, up = true, kg), 1e-9)
        assertEquals(82.3, BodyEntry.typedWaist("82,3", 81.5, kg)!!, 1e-9)
        assertNull(BodyEntry.typedWaist("3", 81.5, lb))
    }

    // Other tape sites and mobility markers (M17)

    @Test
    fun `a tape site shows and types in inches and is stored as exact cm`() {
        assertEquals("13.75 in", BodyText.length(34.925, lb))
        assertEquals("13,75", BodyEntry.lengthText(34.925, german))
        // A kg-era reading saved unchanged keeps its cm.
        assertEquals("13.78", BodyEntry.lengthText(35.0, lb))
        assertEquals(35.0, BodyEntry.typedLength("13.78", 35.0, lb, BodyEntry.TAPE_CM)!!, 0.0)
        // A typed quarter inch is its exact cm, which shows back as typed.
        val typed = BodyEntry.typedLength("13.75", 35.0, lb, BodyEntry.TAPE_CM)!!
        assertEquals(34.925, typed, 1e-9)
        assertEquals("13.75 in", BodyText.length(typed, lb))
        assertEquals(34.925, BodyEntry.typedLength("13,75", 35.0, german, BodyEntry.TAPE_CM)!!, 1e-9)
        assertNull(BodyEntry.typedLength("3", 35.0, lb, BodyEntry.TAPE_CM))
        // The card's change is one decimal.
        assertEquals("+0.3 in", BodyText.signedLength(0.762, lb))
        assertEquals("−¼" to "+¼", BodyEntry.lengthStepLabels(lb))
        assertEquals("−½" to "+½", BodyEntry.lengthStepLabels(kg))
    }

    @Test
    fun `the measurement sheet's quarter-inch stepper never drifts`() {
        var cm = BodyEntry.lengthStep(35.0, up = true, lb, BodyEntry.TAPE_CM)
        assertEquals("14 in", BodyText.length(cm, lb))
        val start = cm
        repeat(100) { cm = BodyEntry.lengthStep(cm, up = true, lb, BodyEntry.TAPE_CM) }
        assertEquals("39 in", BodyText.length(cm, lb))
        repeat(100) { cm = BodyEntry.lengthStep(cm, up = false, lb, BodyEntry.TAPE_CM) }
        assertEquals(start, cm, 1e-9)
        // Edited readings step the same way, and stay in range.
        assertEquals(UnitMath.inToCm(14.25), ReadingText.step(MeasurementType.ARM, start, up = true, lb), 1e-9)
        assertEquals(BodyEntry.MIN_TAPE_CM, BodyEntry.lengthStep(BodyEntry.MIN_TAPE_CM, up = false, lb, BodyEntry.TAPE_CM), 0.0)
        assertEquals(35.5, BodyEntry.lengthStep(35.0, up = true, kg, BodyEntry.TAPE_CM), 1e-9)
    }

    @Test
    fun `sit-and-reach is a signed length that converts`() {
        assertEquals(-6.35, MobilityDraft.typedReach("-2.5", 0.0, lb)!!, 1e-9)
        assertEquals(-6.35, MobilityDraft.typedReach("\u22122,5", 0.0, german)!!, 1e-9)
        assertEquals("−2.5 in", BodyText.signedLengthValue(-6.35, lb))
        assertEquals("+2.5 in", BodyText.signedLengthValue(6.35, lb))
        assertEquals("0 in", BodyText.signedLengthValue(0.0, lb))
        assertEquals("2.5 in short", MobilityText.value(MobilityMarker.SIT_AND_REACH, -6.35, lb))
        assertEquals("2.5 in past your toes", MobilityText.value(MobilityMarker.SIT_AND_REACH, 6.35, lb))
        assertEquals("At your toes", MobilityText.value(MobilityMarker.SIT_AND_REACH, 0.0, lb))
        // Unchanged text keeps a kg-era reading; the stepper moves by quarter inches through zero.
        assertEquals("-1.77", BodyEntry.lengthText(-4.5, lb))
        assertEquals(-4.5, MobilityDraft.typedReach("-1.77", -4.5, lb)!!, 0.0)
        var reach = UnitMath.inToCm(-0.5)
        repeat(4) { reach = MobilityDraft.reachStep(reach, up = true, lb) }
        assertEquals("+0.5 in", BodyText.signedLengthValue(reach, lb))
        assertEquals(UnitMath.inToCm(0.5), reach, 1e-9)
        assertNull(MobilityDraft.typedReach("30", 0.0, lb))
        assertEquals(mapOf(MobilityMarker.SIT_AND_REACH to -6.35), MobilityDraft(-6.35, null, null, 0).values())
        // An edited reading types the same way.
        assertEquals(-6.35, ReadingText.typed(MeasurementType.SIT_AND_REACH, "-2.5", 0.0, lb)!!, 1e-9)
    }

    @Test
    fun `the squat hold and toe touch don't convert in lb`() {
        assertEquals("45 s", MobilityText.value(MobilityMarker.SQUAT_HOLD, 45.0, lb))
        assertEquals("1:05", MobilityText.value(MobilityMarker.SQUAT_HOLD, 65.0, lb))
        assertEquals("Yes", MobilityText.value(MobilityMarker.TOE_TOUCH, 1.0, lb))
        assertEquals("Not yet", MobilityText.value(MobilityMarker.TOE_TOUCH, 0.0, lb))
        assertEquals(50.0, ReadingText.step(MeasurementType.SQUAT_HOLD, 45.0, up = true, lb), 0.0)
        assertEquals("45", ReadingText.text(MeasurementType.SQUAT_HOLD, 45.0, lb))
        assertEquals(60.0, ReadingText.typed(MeasurementType.SQUAT_HOLD, "60", 45.0, lb)!!, 0.0)
        assertEquals(mapOf(MobilityMarker.SQUAT_HOLD to 45.0, MobilityMarker.TOE_TOUCH to 1.0), MobilityDraft(null, 45, true, 0).values())
    }

    // Scan form

    private val draft = ScanDraftEntity(measuredAt = 1, weightG = 80_000, bodyFatPct = 25.0)

    @Test
    fun `the scan form keeps stored grams while its text is unchanged`() {
        val form = ScanForm.of(draft, lb)
        assertEquals("176.4", form.weight)
        assertEquals("44.1", form.fatMass)
        assertEquals(80_000, form.weightG)
        assertEquals(20_000, form.fatG)
        val typed = form.copy(weight = "176.5")
        assertEquals(UnitMath.lbToG(BigDecimal("176.5")), typed.weightG)
        // Typing it back as shown keeps the stored grams again.
        assertEquals(80_000, typed.copy(weight = "176.4").weightG)
    }

    @Test
    fun `the scan form's kg and lb switch converts what's typed and keeps exact grams`() {
        val metric = ScanForm.of(draft, lb).inUnits(Units.METRIC)
        assertEquals("80.0", metric.weight)
        assertEquals("20.0", metric.fatMass)
        assertEquals("kg", metric.style.weight)
        assertEquals(80_000, metric.weightG)
        assertEquals(20_000, metric.fatG)
        // Typed in lb, then switched: shown in kg, still the grams the lb value gave.
        val typedLb = ScanForm(loaded = true, style = lb, weight = "176.5", muscle = "77.2")
        val grams = typedLb.weightG!!
        val switched = typedLb.inUnits(Units.METRIC)
        assertEquals("80.1", switched.weight)
        assertEquals(grams, switched.weightG)
        assertEquals(typedLb.muscleG, switched.muscleG)
        // Typed in kg it's what you typed, to the gram.
        assertEquals(35_050, switched.copy(muscle = "35.05").muscleG)
        // A segment typed in lb is saved in grams.
        val seg = typedLb.copy(segments = listOf("7.7", "", "", "", ""))
        assertEquals(UnitMath.lbToG(BigDecimal("7.7")), seg.segmentValues[0].getOrNull())
        assertTrue(seg.segmentValues[1].isSuccess)
    }

    // Onboarding

    @Test
    fun `units default from the phone's region`() {
        assertEquals(Units.IMPERIAL, UnitFields.defaultUnits(Locale.US))
        assertEquals(Units.IMPERIAL, UnitFields.defaultUnits(Locale("en", "LR")))
        assertEquals(Units.IMPERIAL, UnitFields.defaultUnits(Locale("my", "MM")))
        assertEquals(Units.METRIC, UnitFields.defaultUnits(Locale.UK))
        assertEquals(Units.METRIC, UnitFields.defaultUnits(Locale("en", "IN")))
        assertEquals(Units.METRIC, UnitFields.defaultUnits(Locale.GERMANY))
        assertEquals(Units.METRIC, UnitFields.defaultUnits(Locale.ENGLISH))
    }

    private val today = LocalDate.of(2026, 11, 22)

    @Test
    fun `About you in lb and in makes a profile in cm and kg`() {
        val form = AboutForm(Sex.MALE, LocalDate.of(1990, 1, 1), height = "5", heightIn = "9", weight = "176.4", activity = ActivityLevel.MODERATE, units = Units.IMPERIAL)
        val profile = form.profile(today)!!
        assertEquals(175.26, profile.heightCm, 1e-9)
        assertEquals(UnitMath.lbToG(BigDecimal("176.4")) / 1000.0, profile.weightKg, 0.0)
        assertNull(form.copy(heightIn = "13").profile(today))
        assertNull(form.copy(weight = "50").profile(today))
    }

    @Test
    fun `a stored profile shown in lb keeps its values until they're changed, through a units switch`() {
        val stored = AboutForm(Sex.FEMALE, LocalDate.of(1990, 1, 1), height = "165.5", weight = "61.3").profile(today)!!
        val shown = AboutForm.of(stored, Units.IMPERIAL)
        assertEquals("5", shown.height)
        assertEquals("5", shown.heightIn)
        assertEquals("135.1", shown.weight)
        assertEquals(stored, shown.profile(today))
        assertEquals(stored, shown.inUnits(Units.METRIC).profile(today))
        assertEquals("165.5", shown.inUnits(Units.METRIC).height)
        assertEquals("61.3", shown.inUnits(Units.METRIC).weight)
        assertEquals(UnitMath.inToCm(66.0), shown.copy(heightIn = "6").heightCm!!, 1e-9)
    }

    @Test
    fun `a suggested goal weight rounds to a whole lb, and that's what's saved`() {
        assertEquals("172", UnitFields.suggestedText(78.0, lb))
        assertEquals("78", UnitFields.suggestedText(78.0, kg))
        val goal = GoalForm(goalWeight = UnitFields.suggestedText(78.0, lb), units = Units.IMPERIAL)
        assertEquals(UnitMath.lbToG(BigDecimal(172)) / 1000.0, goal.goalWeightKg!!, 0.0)
        assertEquals("172 lb", GoalText.weight(goal.goalWeightKg!!, lb))
        // Switching units keeps the exact kg.
        val metric = goal.inUnits(Units.METRIC)
        assertEquals("78", metric.goalWeight)
        assertEquals(goal.goalWeightKg, metric.goalWeightKg)
    }

    // GoalText

    @Test
    fun `the goal's weights in lb, with protein still per kg`() {
        assertEquals("≈−1.1 lb a week", GoalText.perWeek(-0.5, lb))
        assertEquals("≈−0.5 kg a week", GoalText.perWeek(-0.5, kg))
        assertEquals("≈+0.25 kg a week", GoalText.perWeek(0.25, kg))
        assertEquals("≈+0,6 lb a week", GoalText.perWeek(0.25, german))
        val today = LocalDate.of(2026, 1, 1)
        assertTrue(GoalText.projection(-0.5, null, null, today, today.plusDays(70), lb).contains("about −11 lb by"))
        assertTrue(GoalText.projection(-0.5, null, null, today, today.plusDays(70), kg).contains("about −5 kg by"))
        assertTrue(GoalText.goalSummary(Goal.MAINTAIN, GoalPolicy.defaultFor(Goal.MAINTAIN, Pace.STANDARD), "Standard", 78.0, lb).endsWith(" · goal weight 172 lb"))
        assertEquals("Lean mass 132.3 lb from your scan, so 20% body fat is about 165.3 lb.", GoalText.bodyFatGoal(60.0, 20.0, lb))
        val targets = NutritionTargets(1700.0, 2400.0, -400.0, 2000, 140, 60, 200, referenceKg = 78.0, referenceIsGoalWeight = true, proteinGPerKg = 1.8, fatFromBodyWeight = true)
        assertTrue(GoalText.protein(targets, lb).startsWith("1.8 g per kg of your goal weight (172 lb)."))
        assertTrue(GoalText.protein(targets, kg).startsWith("1.8 g per kg of your goal weight (78 kg)."))
        assertTrue(GoalText.fat(targets, lb).startsWith("Your floor of 0.6 g per kg (172 lb)"))
    }
}
