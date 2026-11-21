package app.recomp.domain.coach

import app.recomp.domain.nutrition.Goal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import app.recomp.domain.units.UnitStyle

/** HM-06, PRD §12.7: the report sends only the ticked sections, and targets in relative terms unless body trends are ticked. Invented numbers. */
class ProgrammeReportTest {
    private val goal = GoalFacts(Goal.FAT_LOSS, "Standard", "goal weight", kcal = 2000, proteinG = 160, maintenanceKcal = 2500.0, referenceKg = 80.0)
    private val food = FoodFacts(daysLogged = 6, kcal = 1900, kcalTarget = 2000, proteinG = 150, proteinTargetG = 160, proteinHit = 4, proteinCounted = 6, orderedPct = 12.4, packagedPct = 3.0)
    private val training = TrainingFacts(
        experience = "intermediate",
        split = "Push/Pull/Legs",
        strengthDaysPerWeek = 3,
        routines = listOf(
            RoutineFacts(11, "Push", listOf(RoutineLine(1, "Bench press", 3, 6, 10, "60 kg"), RoutineLine(2, "Overhead press", 3, 6, 10, "35 kg"))),
            RoutineFacts(12, "Legs", listOf(RoutineLine(3, "Squat", 3, 6, 10, "80 kg"))),
        ),
        sessions = "Strength 2 of 3 days",
        records = listOf("Bench press: heaviest weight 60 kg"),
        stalled = listOf("Overhead press"),
        setsPerMuscle = mapOf("chest" to 6.0, "triceps" to 3.0),
        library = listOf(LibraryLine(1, "Bench press", "chest"), LibraryLine(2, "Overhead press", "shoulders"), LibraryLine(3, "Squat", "quads"), LibraryLine(4, "Incline press", "chest"), LibraryLine(5, "Leg press", "quads")),
    )
    private val body = BodyFacts(trendChangeKg = -0.4, trendChangePct = -0.5, waistChangeCm = -0.6, scanChanges = listOf("+0.4 kg muscle · within noise"))
    private val facts = ProgrammeFacts(goal, food, training, body)

    @Test
    fun `each combination of sections sends only what's ticked`() {
        val names = listOf("## Goal and targets", "## Food this week", "## Training", "## Body trends", "## A note from the person")
        for (mask in 0 until 32) {
            val sections = ReportSections(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0, mask and 8 != 0, if (mask and 16 != 0) "only 2 days next week" else "")
            val text = ProgrammeReports.build(facts, sections, UnitStyle.METRIC).text
            val ticked = listOf(sections.goal, sections.food, sections.training, sections.body, sections.note.isNotBlank())
            names.zip(ticked).forEach { (name, on) -> assertEquals("$name in mask $mask", on, name in text) }
            // Body trends only when ticked.
            assertEquals(sections.body, "Trend weight" in text)
        }
    }

    @Test
    fun `targets go in relative terms unless body trends are ticked`() {
        val relative = ProgrammeReports.build(facts, ReportSections(), UnitStyle.METRIC).text
        assertTrue(relative, "Calories: -20% of maintenance" in relative)
        assertTrue("Protein: 2.0 g per kg" in relative)
        assertTrue("calories 95% of target, protein 94% of target" in relative)
        // No absolute calories or grams anywhere.
        listOf("2000", "2500", "160 g", "1900", "150 g", "kcal").forEach { assertFalse(it, it in relative) }

        val absolute = ProgrammeReports.build(facts, ReportSections(body = true), UnitStyle.METRIC).text
        assertTrue("Calories: 2000 kcal a day; maintenance about 2500 kcal" in absolute)
        assertTrue("Protein: 160 g a day" in absolute)
        assertEquals(TargetTerms.ABSOLUTE, ReportSections(body = true).terms)
        assertEquals(TargetTerms.RELATIVE, ReportSections().terms)
    }

    @Test
    fun `exercises and routines go by report-local refs, mapped back`() {
        val report = ProgrammeReports.build(facts, ReportSections(), UnitStyle.METRIC)
        assertTrue("- r1 Push:" in report.text)
        assertTrue("  - e1 Bench press: 3 × 6–10, 60 kg" in report.text)
        assertTrue("  e4 Incline press: chest" in report.text)
        assertEquals(mapOf("e1" to 1L, "e2" to 2L, "e3" to 3L, "e4" to 4L, "e5" to 5L), report.exercises)
        assertEquals(mapOf("r1" to 11L, "r2" to 12L), report.routines)
        // The library lists only exercises not already in a routine.
        assertFalse("e1 Bench press: chest" in report.text)
    }

    @Test
    fun `a long report trims per-exercise detail first`() {
        val big = training.copy(
            routines = (1..12).map { r -> RoutineFacts(100L + r, "Routine $r", (1..12).map { RoutineLine(r * 100L + it, "Exercise $r-$it with a long name", 3, 8, 12, "${20 + it} kg") }) },
            library = (1..400).map { LibraryLine(10_000L + it, "Library exercise number $it", if (it % 2 == 0) "chest" else "biceps") },
        )
        val report = ProgrammeReports.build(facts.copy(training = big), ReportSections(), UnitStyle.METRIC)
        assertTrue(report.trimmed)
        assertFalse("21 kg" in report.text)
        assertTrue(report.text.length <= ProgrammeReports.MAX_CHARS)
    }

    @Test
    fun `the cap holds even when the library alone is too long, keeping every routine exercise`() {
        // Every library exercise trains a routine muscle, so trimming by muscle alone wouldn't be enough.
        val huge = training.copy(library = training.library + (1..2_000).map { LibraryLine(20_000L + it, "Chest exercise with a long name number $it", "chest") })
        val report = ProgrammeReports.build(facts.copy(training = huge), ReportSections(), UnitStyle.METRIC)
        assertTrue(report.trimmed)
        assertTrue(report.text.length.toString(), report.text.length <= ProgrammeReports.MAX_CHARS)
        listOf("e1 Bench press", "e2 Overhead press", "e3 Squat").forEach { assertTrue(it, it in report.text) }
    }

    @Test
    fun `the report says its units at the top and sends body trends in them`() {
        val metric = ProgrammeReports.build(facts, ReportSections(body = true), UnitStyle.METRIC).text.lines()
        assertEquals("Weights in kg, lengths in cm.", metric[1])
        assertTrue(metric.any { it.startsWith("Trend weight: -0.4 kg this week") })
        assertTrue("Waist: -0.6 cm" in metric)

        val imperial = ProgrammeReports.build(facts, ReportSections(goal = true, body = true), UnitStyle.IMPERIAL).text.lines()
        assertEquals("Weights in lb, lengths in inches.", imperial[1])
        // A 0.4 kg drop is 0.88 lb, and 0.6 cm is 0.24 in, to one decimal.
        assertTrue(imperial.toString(), imperial.any { it.startsWith("Trend weight: -0.9 lb this week") })
        assertTrue("Waist: -0.2 in" in imperial)
        // Protein stays per kg in relative terms.
        val relative = ProgrammeReports.build(facts, ReportSections(), UnitStyle.IMPERIAL).text.lines()
        assertTrue("Protein: 2.0 g per kg" in relative)
    }

    @Test
    fun `nothing personal is in the facts, so it can't be sent`() {
        // ProgrammeFacts has no field for name, sex, age, height or the food log; the text has only these sections.
        val text = ProgrammeReports.build(facts, ReportSections(body = true, note = "left knee hurts on hack squats"), UnitStyle.METRIC).text
        listOf("Sex", "sex:", "Age", "Height", "cm tall", "years old").forEach { assertFalse(it, it in text) }
        assertTrue("left knee hurts on hack squats" in text)
    }
}
