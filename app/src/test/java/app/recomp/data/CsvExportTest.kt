package app.recomp.data

import app.recomp.data.backup.CsvExport
import app.recomp.data.repo.ExtraDraft
import app.recomp.data.repo.OnboardingResult
import app.recomp.data.repo.ScanInput
import app.recomp.data.repo.WorkoutRepository.ExtraEntry
import app.recomp.domain.food.Origin
import app.recomp.domain.model.SessionKind
import app.recomp.domain.model.SetType
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Presets
import app.recomp.domain.persona.Profile
import app.recomp.domain.persona.WeightSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** DA-03 (PRD §12.12): the CSV zip's files, columns and rows over real tables. Invented data, in UTC. */
@RunWith(RobolectricTestRunner::class)
class CsvExportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private lateinit var csv: CsvExport
    private val utc: ZoneId = ZoneOffset.UTC

    /** 2026-11-20 at [hour]:[minute] UTC. */
    private fun at(hour: Int, minute: Int = 0, day: Int = 20): Long = LocalDate.of(2026, 11, day).atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Before
    fun setUp() {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
        csv = CsvExport(app.db, app.prefs)
    }

    @After
    fun tearDown() = app.close()

    private fun export(): Map<String, String> {
        val out = ByteArrayOutputStream()
        runBlocking { csv.export(out, utc) }
        return buildMap {
            ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    put(e.name, z.readBytes().decodeToString())
                }
            }
        }
    }

    /** The lines of a file, BOM and line ends taken off. */
    private fun lines(file: String): List<String> = file.removePrefix("﻿").split("\r\n").dropLast(1)

    private suspend fun onboard() {
        val today = LocalDate.of(2026, 11, 20)
        val profile = Profile(Sex.MALE, LocalDate.of(1996, 1, 1), heightCm = 175.0, weightKg = 90.0)
        val plan = GoalPlan.of(Goal.RECOMP, Pace.STANDARD, 80.0, FinishLine.GoalWeight, today)
        val yoga = ExtraDraft(null, "Yoga class", SessionKind.YOGA, setOf(6))
        app.personas.completeOnboarding(OnboardingResult(Presets.REFERENCE.draft.copy(goal = plan), Presets.REFERENCE.key, listOf(yoga), profile, LocalTime.of(4, 0)), now = at(6))
    }

    @Test
    fun `an empty install gives the four files with their headers only`() {
        val files = export()
        assertEquals(setOf("workouts.csv", "food.csv", "weigh-ins.csv", "scans.csv"), files.keys)
        assertEquals(CsvExport.WORKOUT_COLUMNS.joinToString(","), lines(files.getValue("workouts.csv")).single())
        assertEquals(CsvExport.FOOD_COLUMNS.joinToString(","), lines(files.getValue("food.csv")).single())
        assertEquals(CsvExport.WEIGH_IN_COLUMNS.joinToString(","), lines(files.getValue("weigh-ins.csv")).single())
        assertEquals(CsvExport.SCAN_COLUMNS.joinToString(","), lines(files.getValue("scans.csv")).single())
        files.values.forEach { assertTrue(it.startsWith("﻿")) }
    }

    @Test
    fun `workouts have one row per ticked set, a class one row, and nothing for a discarded or running workout`() = runBlocking {
        onboard()
        val routine = app.training.observeNextRoutine().first()!!
        val session = app.workouts.startRoutine(routine.id, at(18))
        val exercises = app.db.workoutDao().sessionExercises(session)
        val first = exercises[0]
        val working = first.sets.filter { it.setType == SetType.WORKING }
        app.workouts.completeSet(working[0].id, 40_000, 10, 2, at(18, 5))
        app.workouts.completeSet(working[1].id, 42_500, 8, null, at(18, 8))
        // One set of the second exercise; the rest are unticked and go when the workout finishes.
        app.workouts.completeSet(exercises[1].sets.first { it.setType == SetType.WORKING }.id, 20_000, 12, 3, at(18, 15))
        app.workouts.finish(session, at(19))

        val yogaRoutine = app.training.observeExtras().first().single()
        app.workouts.logExtra(yogaRoutine, ExtraEntry("Hatha", 60, 3, "Hips, \"slow\""), at(7, day = 21))

        val discarded = app.workouts.startRoutine(routine.id, at(9, day = 21))
        app.workouts.completeSet(app.db.workoutDao().sessionExercises(discarded)[0].sets.first().id, 10_000, 5, null, at(9, 5, day = 21))
        app.workouts.discard(discarded, at(9, 10, day = 21))
        val running = app.workouts.startRoutine(routine.id, at(10, day = 21))
        app.workouts.completeSet(app.db.workoutDao().sessionExercises(running)[0].sets.first().id, 10_000, 5, null, at(10, 5, day = 21))

        val rows = lines(export().getValue("workouts.csv"))
        assertEquals(1 + 3 + 1, rows.size)
        val header = CsvExport.WORKOUT_COLUMNS
        fun cell(row: String, column: String) = row.split(",")[header.indexOf(column)]
        val set1 = rows[1]
        assertEquals("2026-11-20", cell(set1, "date"))
        assertEquals("2026-11-20 18:00", cell(set1, "started"))
        assertEquals("2026-11-20 19:00", cell(set1, "ended"))
        assertEquals("STRENGTH", cell(set1, "kind"))
        assertEquals(routine.name, cell(set1, "routine"))
        assertEquals(first.exercise.name, cell(set1, "exercise"))
        assertEquals("WORKING", cell(set1, "set_type"))
        assertEquals("40", cell(set1, "load_kg"))
        assertEquals("10", cell(set1, "reps"))
        assertEquals("2", cell(set1, "rir"))
        assertEquals("2026-11-20 18:05", cell(set1, "set_done"))
        assertEquals((working[0].setIndex + 1).toString(), cell(set1, "set"))
        assertEquals("42.5", cell(rows[2], "load_kg"))
        assertEquals("", cell(rows[2], "rir"))
        assertEquals(exercises[1].exercise.name, cell(rows[3], "exercise"))
        // The class: its own row, logged at its end, the set columns empty, its style and length filled in, its notes quoted.
        val yoga = rows[4]
        assertTrue(yoga, yoga.startsWith("2026-11-21,2026-11-21 06:00,2026-11-21 07:00,"))
        assertTrue(yoga, yoga.contains(",YOGA,Yoga class,\"Hips, \"\"slow\"\"\",,,,,,,,,Hatha,60"))
    }

    @Test
    fun `food has one row per entry, quick adds included, with the meal's name and escaped text`() = runBlocking {
        onboard()
        app.foodLog.quickAdd(LocalDate.of(2026, 11, 20), "snacks", "=cmd|' /C calc'!A0", 210.0, 20.5, null, 7.25, Origin.PACKAGED, now = at(16))
        app.foodLog.quickAdd(LocalDate.of(2026, 11, 20), "lunch", "Dal, tadka", 300.0, 15.0, 40.0, 8.0, Origin.HOME, now = at(13))
        val rows = lines(export().getValue("food.csv"))
        assertEquals(3, rows.size)
        assertEquals("2026-11-20,Lunch,\"Dal, tadka\",1,serving,,300,15,40,8,,HOME,QUICK_ADD,2026-11-20 13:00".substringBefore(",1,"), rows[1].substringBefore(",1,"))
        val lunch = rows[1].split(",").let { listOf(it[0], it[1]) + it.drop(4) }
        assertEquals("2026-11-20", lunch[0])
        assertTrue(rows[1], rows[1].endsWith(",300,15,40,8,,HOME,QUICK_ADD,2026-11-20 13:00"))
        // A name that looks like a formula stays text; the unknown carbs stay empty, not 0.
        assertTrue(rows[2], rows[2].startsWith("2026-11-20,Snacks,'=cmd|' /C calc'!A0,"))
        assertTrue(rows[2], rows[2].endsWith(",210,20.5,,7.3,,PACKAGED,QUICK_ADD,2026-11-20 16:00"))
    }

    @Test
    fun `weigh-ins and scans are in kg with their sources and the scan's protocol`() = runBlocking {
        onboard()
        app.body.logWeighIn(80_150, WeightSource.HOME_SCALE, at(7))
        app.body.saveScan(
            ScanInput(
                measuredAt = at(10, day = 21), weightG = 80_300, skeletalMuscleG = 33_100, bodyFatG = 20_050, bodyFatPct = 25.0, visceralFatLevel = 8.5,
                minsSinceMeal = 180, workoutBefore = false, bmrKcal = 1700, ecwTbwRatio = 0.381, score = 74, segmental = null, notes = "Test scan",
            ),
        )
        val weighIns = lines(export().getValue("weigh-ins.csv"))
        // The typed weigh-in, then the scan's weight, which the scan also writes as a weigh-in.
        assertEquals("2026-11-20,2026-11-20 07:00,80.15,HOME_SCALE", weighIns[1])
        assertTrue(weighIns[2], weighIns[2].startsWith("2026-11-21,2026-11-21 10:00,80.3,"))
        val scan = lines(export().getValue("scans.csv"))
        assertEquals(2, scan.size)
        assertTrue(scan[1], scan[1].startsWith("2026-11-21,2026-11-21 10:00,"))
        assertTrue(scan[1], scan[1].endsWith(",80.3,33.1,20.05,25,8.5,1700,0.381,74,180,no,yes,Test scan"))
    }
}
