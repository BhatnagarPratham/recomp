package app.recomp.data

import app.recomp.data.db.DailyActivityEntity
import app.recomp.data.prefs.StoredReminders
import app.recomp.data.repo.CustomFood
import app.recomp.data.repo.OnboardingResult
import app.recomp.data.repo.ScanInput
import app.recomp.domain.nutrition.MainNumber
import app.recomp.di.DatabaseModule
import app.recomp.domain.body.ScanSlot
import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.Origin
import app.recomp.domain.food.Per100g
import app.recomp.domain.food.PortionUnit
import app.recomp.domain.nudge.NudgeLine
import app.recomp.domain.nudge.Reminder
import app.recomp.domain.nudge.ReminderKind
import app.recomp.domain.nudge.Reminders
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.persona.Diet
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Presets
import app.recomp.domain.persona.Profile
import app.recomp.domain.persona.WeightSource
import app.recomp.reminders.ReminderText
import app.recomp.ui.body.BodyText
import app.recomp.ui.nudge.EveningWindow
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.w3c.dom.Element

/**
 * Reminders and the evening nudge over real rows (RM-01, NU-23, GL-08, PO-11, IB-06; PRD §12.8): the skip rules
 * read the day's data, at most two are sent, the nudge follows the goal, and the ledger is device state. Invented data.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private lateinit var savedZone: TimeZone
    private val zone = ZoneId.of("Asia/Kolkata")
    private val runner get() = app.reminders
    private val shown get() = app.notifier.shown

    /** A Monday. */
    private val day0 = LocalDate.of(2026, 11, 16)

    private fun date(d: Int): LocalDate = day0.plusDays(d.toLong())

    private fun at(d: Int, hour: Int, minute: Int = 0): Long = date(d).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val g = PortionUnit("g", 1.0, PortionUnit.Kind.GRAMS)

    @Before
    fun setUp() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
    }

    @After
    fun tearDown() {
        app.close()
        TimeZone.setDefault(savedZone)
    }

    /** Invented body data: 80 kg, so recomp's protein target is 128 g. The ramp is off, so the target applies at once. */
    private suspend fun onboard(goal: Goal = Goal.RECOMP, d: Int = 0, diet: Diet = Diet.LACTO_VEGETARIAN) {
        val profile = Profile(Sex.MALE, LocalDate.of(1996, 1, 1), heightCm = 175.0, weightKg = 80.0)
        val plan = GoalPlan.of(goal, Pace.STANDARD, null, null, date(d))
        val draft = Presets.REFERENCE.draft.copy(goal = plan, diet = diet, proteinRamp = false)
        app.personas.completeOnboarding(OnboardingResult(draft, Presets.REFERENCE.key, emptyList(), profile, LocalTime.of(4, 0)), now = at(d, 5))
    }

    private suspend fun turnOn(vararg kinds: ReminderKind, days: Set<DayOfWeek> = emptySet()) =
        app.prefs.updateReminders { r -> kinds.fold(r) { acc, k -> acc.with(k, true) }.copy(workoutDays = days) }

    private suspend fun food(name: String, kcal: Double, protein: Double, diet: FoodDiet = FoodDiet.VEGETARIAN): Long =
        app.foods.createCustom(CustomFood(name, null, Per100g(kcal, protein, 5.0, 5.0), diet, null), now = at(-10, 12))

    /** Logs [grams] of a food on an earlier day, so it counts as one of your foods without touching today. */
    private suspend fun ate(foodId: Long, grams: Double, d: Int = -1, slot: String = "dinner") =
        app.foodLog.log(date(d), slot, app.foods.food(foodId)!!, grams, g, Origin.HOME, now = at(d, 20))

    private suspend fun quick(d: Int, slot: String, kcal: Double, protein: Double, hour: Int = 13) =
        app.foodLog.quickAdd(date(d), slot, "Test meal", kcal, protein, null, null, Origin.HOME, now = at(d, hour))

    private suspend fun run(d: Int, hour: Int, minute: Int = 0) = runner.run(at(d, hour, minute), zone).map { it.kind }

    // The cap and the skip rules over a day

    @Test
    fun `nothing rings until a reminder is turned on`() = runBlocking {
        onboard()
        assertTrue(run(0, 7, 30).isEmpty())
        assertTrue(run(0, 20, 30).isEmpty())
        assertNull(runner.nextWake(at(0, 6), zone))
        assertNull(app.device.ledger())
    }

    @Test
    fun `with nothing logged all day, the first two due are sent and the third isn't`() = runBlocking {
        onboard()
        turnOn(ReminderKind.WEIGH_IN, ReminderKind.LUNCH, ReminderKind.EVENING)
        assertEquals(listOf(ReminderKind.WEIGH_IN), run(0, 7, 30))
        assertEquals(listOf(ReminderKind.LUNCH), run(0, 14, 30))
        assertTrue(run(0, 20, 30).isEmpty()) // protein is short, but two have gone
        assertEquals(listOf(ReminderKind.WEIGH_IN, ReminderKind.LUNCH), app.device.ledger()!!.sent)
        assertEquals("Lunch isn't logged yet", shown.last().title)
        // The next day starts again.
        assertEquals(listOf(ReminderKind.WEIGH_IN), run(1, 7, 30))
    }

    @Test
    fun `a reminder is skipped once its job is done, and the evening one is then the second`() = runBlocking {
        onboard()
        turnOn(ReminderKind.WEIGH_IN, ReminderKind.LUNCH, ReminderKind.EVENING)
        app.body.logWeighIn(80_000, WeightSource.HOME_SCALE, at(0, 7))
        assertTrue(run(0, 7, 30).isEmpty())
        assertEquals(listOf(ReminderKind.LUNCH), run(0, 14, 30))
        quick(0, "lunch", 600.0, 20.0, hour = 15)
        assertEquals(listOf(ReminderKind.EVENING), run(0, 20, 30))
        assertEquals("108 g of protein left today", shown.last().title)
    }

    @Test
    fun `the lunch reminder watches one meal, renamed or picked`() = runBlocking {
        onboard()
        turnOn(ReminderKind.LUNCH)
        // Food in another meal doesn't count as lunch.
        quick(0, "breakfast", 400.0, 15.0, hour = 9)
        app.prefs.edit { mealSlots = mealSlots.map { if (it.key == "lunch") it.copy(name = "Tiffin") else it } }
        assertEquals(listOf(ReminderKind.LUNCH), run(0, 14, 30))
        assertEquals("Tiffin isn't logged yet", shown.single().title)
        // Watching Snacks instead: an entry there skips it.
        app.prefs.updateReminders { it.copy(lunchSlot = "snacks") }
        quick(1, "snacks", 200.0, 5.0, hour = 11)
        assertTrue(run(1, 14, 30).isEmpty())
        // The watched meal removed: the meal for the time of day is watched again.
        app.prefs.edit { mealSlots = mealSlots.map { if (it.key == "snacks") it.copy(removed = true) else it } }
        assertEquals(listOf(ReminderKind.LUNCH), run(2, 14, 30))
        assertEquals("Tiffin isn't logged yet", shown.last().title)
    }

    @Test
    fun `with notifications off, or the channel switched off, nothing is sent or counted`() = runBlocking {
        onboard()
        turnOn(ReminderKind.WEIGH_IN, ReminderKind.LUNCH, ReminderKind.EVENING)
        app.notifier.allowed = false
        assertTrue(run(0, 7, 30).isEmpty())
        assertTrue(shown.isEmpty())
        assertTrue(app.device.ledger()!!.sent.isEmpty())
        app.notifier.allowed = true
        app.notifier.blocked += ReminderKind.LUNCH
        assertTrue(run(0, 14, 30).isEmpty())
        assertEquals(listOf(ReminderKind.EVENING), run(0, 20, 30))
    }

    @Test
    fun `the ledger survives the app being killed, so a repeated wake sends nothing twice`() = runBlocking {
        onboard()
        turnOn(ReminderKind.WEIGH_IN)
        assertEquals(listOf(ReminderKind.WEIGH_IN), run(0, 7, 30))
        app.restart()
        assertTrue(run(0, 7, 35).isEmpty())
        assertEquals(1, shown.size)
        assertEquals(Instant.ofEpochMilli(at(1, 7, 30)), runner.nextWake(at(0, 7, 36), zone))
    }

    @Test
    fun `turning a reminder on after its time waits for tomorrow`() = runBlocking {
        onboard()
        val before = runner.inputs()
        turnOn(ReminderKind.LUNCH)
        runner.settingsChanged(before, runner.inputs(), at(0, 15), zone)
        assertTrue(run(0, 15).isEmpty())
        assertEquals(Instant.ofEpochMilli(at(1, 14, 30)), runner.nextWake(at(0, 15), zone))
        assertEquals(listOf(ReminderKind.LUNCH), run(1, 14, 31))
    }

    @Test
    fun `a wake after a restart sends what's just due and drops what's hours old`() = runBlocking {
        onboard()
        turnOn(ReminderKind.WEIGH_IN, ReminderKind.EVENING)
        // The phone was off from the night before until 20:40.
        assertEquals(Instant.ofEpochMilli(at(0, 20, 40)), runner.nextWake(at(0, 20, 40), zone))
        assertEquals(listOf(ReminderKind.EVENING), run(0, 20, 40))
    }

    // The evening nudge follows the goal

    @Test
    fun `recomp nudges protein with your own foods, sized to what's left`() = runBlocking {
        onboard()
        val curd = food("Test hung curd", kcal = 105.0, protein = 11.0)
        val paneer = food("Test paneer", kcal = 265.0, protein = 18.0)
        val roti = food("Test roti", kcal = 300.0, protein = 9.0)
        repeat(2) { ate(curd, 200.0) }
        repeat(3) { ate(paneer, 100.0) }
        repeat(5) { ate(roti, 80.0) }
        quick(0, "lunch", 900.0, 106.0) // 22 g of 128 left: under a quarter, so no nudge yet
        turnOn(ReminderKind.EVENING)
        assertTrue(app.snapshots.evening(app.snapshots.load(at(0, 20, 30))).lines.isEmpty())

        app.foodLog.delete(app.foodLog.day(date(0)).map { it.id })
        quick(0, "lunch", 900.0, 96.0) // 32 g left: exactly a quarter
        val nudge = app.snapshots.evening(app.snapshots.load(at(0, 20, 30)))
        assertEquals(listOf(NudgeLine.ProteinLeft(32, 128)), nudge.lines)
        assertEquals(listOf("Test hung curd", "Test paneer"), nudge.suggestions.map { it.name }) // roti isn't a protein food
        assertEquals("Test hung curd · 290 g · 32 g protein · 305 kcal", ReminderText.suggestion(nudge.suggestions[0]))
        assertEquals("Test paneer · 180 g · 32 g protein · 477 kcal", ReminderText.suggestion(nudge.suggestions[1]))

        assertEquals(listOf(ReminderKind.EVENING), run(0, 20, 30))
        val sent = shown.single()
        assertEquals("32 g of protein left today", sent.title)
        assertEquals("Test hung curd · 290 g · 32 g protein · 305 kcal\nTest paneer · 180 g · 32 g protein · 477 kcal", sent.text)
        assertEquals(2, sent.suggestions.size)
    }

    @Test
    fun `fat loss adds steps once they're tracked and hides them until then`() = runBlocking {
        onboard(Goal.FAT_LOSS)
        quick(0, "lunch", 700.0, 30.0)
        val before = app.snapshots.evening(app.snapshots.load(at(0, 20, 30)))
        assertEquals(1, before.lines.size)
        assertTrue(before.lines.single() is NudgeLine.ProteinLeft)

        app.db.checkinDao().upsert(DailyActivityEntity(date(0), steps = 3_000, stepsSyncedAt = at(0, 20)))
        val after = app.snapshots.evening(app.snapshots.load(at(0, 20, 30)))
        assertEquals(NudgeLine.StepsLeft(5_000, 8_000), after.lines[1])
        turnOn(ReminderKind.EVENING)
        run(0, 20, 30)
        assertTrue(shown.single().text.startsWith("5,000 steps left today"))
    }

    @Test
    fun `lean bulk nudges the calories still to eat, and maintain stays quiet`() = runBlocking {
        onboard(Goal.LEAN_BULK)
        turnOn(ReminderKind.EVENING)
        val target = app.nutrition.targetOn(date(0))!!.kcal
        quick(0, "lunch", target - 600.0, 20.0)
        assertEquals(listOf(ReminderKind.EVENING), run(0, 20, 30))
        assertEquals("You're 600 kcal short tonight", shown.single().title)
        assertTrue(shown.single().suggestions.isEmpty())

        app.close()
        app = TestApp(File(tmp.root, "maintain").apply { mkdirs() })
        onboard(Goal.MAINTAIN)
        turnOn(ReminderKind.EVENING)
        assertTrue(run(0, 20, 30).isEmpty())
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `suggestions keep to the diet, and a hidden food is never offered`() = runBlocking {
        onboard(diet = Diet.NON_VEGETARIAN)
        val chicken = food("Test chicken", kcal = 165.0, protein = 31.0, diet = FoodDiet.MEAT)
        val curd = food("Test hung curd", kcal = 105.0, protein = 11.0)
        repeat(4) { ate(chicken, 150.0) }
        repeat(2) { ate(curd, 200.0) }
        suspend fun names() = app.snapshots.evening(app.snapshots.load(at(0, 20, 30))).suggestions.map { it.name }
        assertEquals(listOf("Test chicken", "Test hung curd"), names())
        app.personas.setDiet(Diet.LACTO_VEGETARIAN)
        assertEquals(listOf("Test hung curd"), names())
        app.foods.hide(curd)
        assertTrue(names().isEmpty())
    }

    @Test
    fun `tapping a suggestion logs it into the evening's meal, and Undo takes it back`() = runBlocking {
        onboard()
        val curd = food("Test hung curd", kcal = 105.0, protein = 11.0)
        repeat(2) { ate(curd, 200.0) }
        val suggestion = app.snapshots.evening(app.snapshots.load(at(0, 20, 30))).suggestions.single()
        assertEquals(400.0, suggestion.grams, 0.0) // 128 g left: twice the usual 200 g

        val logged = app.snapshots.log(suggestion, now = at(0, 20, 45), zone = zone)!!
        val entry = app.foodLog.entry(logged.entryId)!!
        assertEquals(date(0), entry.logDate)
        assertEquals("dinner", entry.mealSlot)
        assertEquals(400.0, entry.grams!!, 0.0)
        assertEquals(44.0, entry.proteinG, 1e-9)
        assertEquals("Logged Test hung curd · 400 g in Dinner", ReminderText.logged(logged.suggestion, logged.slot))
        assertEquals(3, app.foods.food(curd)!!.useCount)
        assertEquals(84, (app.snapshots.load(at(0, 20, 46)).food!!.main as MainNumber.ProteinLeft).left)

        app.snapshots.undo(logged)
        assertNull(app.foodLog.entry(logged.entryId))
        assertEquals(2, app.foods.food(curd)!!.useCount)

        // Tapped after midnight, it still belongs to the same day and its last meal.
        val late = app.snapshots.log(suggestion, now = at(1, 1), zone = zone)!!
        assertEquals(date(0), app.foodLog.entry(late.entryId)!!.logDate)
        assertEquals("dinner", late.slot.key)
    }

    // Workout day (PO-11)

    @Test
    fun `the workout reminder names the next routine and counts the lifts due to go up`() = runBlocking {
        onboard(d = -7)
        turnOn(ReminderKind.WORKOUT, days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        // One round of the rotation with every set at the top of its range, so the next Push goes up.
        val rotation = app.training.observeRotation().first()
        rotation.forEachIndexed { i, routine ->
            val start = at(-6 + i * 2, 18)
            val id = app.workouts.startRoutine(routine.id, now = start)
            app.workouts.observeSessionExercises(id).first().forEach { ex ->
                ex.sets.forEach { app.workouts.completeSet(it.id, if (ex.exercise.loadType.name == "BODYWEIGHT") null else 20_000, 20, rir = null, now = start + 60_000) }
            }
            app.workouts.finish(id, endedAt = start + 3_600_000)
        }
        val snapshot = app.snapshots.load(at(0, 8))
        assertEquals(rotation.first().name, snapshot.nextRoutine)
        assertTrue("some lifts are due", snapshot.liftsDue > 1)
        assertEquals(app.progression.preview(rotation.first().id).count { it.weightUp }, snapshot.liftsDue)

        assertEquals(listOf(ReminderKind.WORKOUT), run(0, 8))
        assertEquals("${rotation.first().name} is next", shown.single().title)
        assertEquals("${snapshot.liftsDue} lifts are due for a weight increase.", shown.single().text)
        // Tuesday isn't one of its days.
        assertTrue(run(1, 8).isEmpty())
        // Wednesday: a strength workout already done that morning skips it.
        val early = app.workouts.startRoutine(rotation.first().id, now = at(2, 6))
        app.workouts.observeSessionExercises(early).first().first().sets.first().let { app.workouts.completeSet(it.id, 20_000, 8, rir = null, now = at(2, 6, 10)) }
        app.workouts.finish(early, endedAt = at(2, 7))
        assertTrue(run(2, 8).isEmpty())
        assertEquals(1, shown.size)
    }

    @Test
    fun `the workout line reads right for none, one and several`() {
        assertEquals("Your targets are ready.", ReminderText.liftsDue(0))
        assertEquals("1 lift is due for a weight increase.", ReminderText.liftsDue(1))
        assertEquals("3 lifts are due for a weight increase.", ReminderText.liftsDue(3))
    }

    // Weekly check-in and scan slot

    @Test
    fun `the check-in reminder rings on Monday when one is waiting, and not once it's been opened`() = runBlocking {
        onboard(d = -7)
        turnOn(ReminderKind.CHECKIN)
        (-7..-1).forEach {
            app.body.logWeighIn(80_000, WeightSource.HOME_SCALE, at(it, 7))
            quick(it, "lunch", 2_300.0, 120.0)
        }
        assertEquals(listOf(ReminderKind.CHECKIN), run(0, 9))
        assertEquals("Your weekly check-in is ready", shown.single().title)
        assertTrue(shown.single().text.startsWith("Week of 9 Nov"))

        // The next Monday, opened before 09:00.
        app.checkins.catchUp(date(7), at(7, 8))
        app.checkins.open(date(0), now = at(7, 8, 30))
        assertTrue(run(7, 9).isEmpty())
    }

    @Test
    fun `the scan reminder rings 3 hours before the slot with the protocol, unless a scan is logged`() = runBlocking {
        onboard()
        app.prefs.setScanSlot(ScanSlot(DayOfWeek.MONDAY, LocalTime.of(10, 0)))
        turnOn(ReminderKind.SCAN)
        assertTrue(run(0, 6, 59).isEmpty())
        assertEquals(listOf(ReminderKind.SCAN), run(0, 7))
        assertEquals("Scan at ${BodyText.time(LocalTime.of(10, 0))} · eat by ${BodyText.time(LocalTime.of(8, 0))} · before your workout", shown.single().text)

        // Next Monday an early scan is already in.
        app.body.saveScan(
            ScanInput(at(7, 6, 30), weightG = 80_000, skeletalMuscleG = 33_000, bodyFatG = 20_000, bodyFatPct = 25.0, visceralFatLevel = 9.0, minsSinceMeal = 600, workoutBefore = false),
        )
        assertTrue(run(7, 7).isEmpty())
        assertEquals(1, shown.size)
    }

    // Today's evening card

    @Test
    fun `Today's card opens at the nudge time and stays until the day ends, reminder on or off`() {
        val start = LocalTime.of(4, 0)
        fun open(d: Int, hour: Int, minute: Int = 0, reminders: Reminders = Reminders()) =
            EveningWindow.open(Instant.ofEpochMilli(at(d, hour, minute)), LocalDate.from(date(if (hour < 4) d - 1 else d)), start, zone, reminders)
        assertFalse(open(0, 20, 29))
        assertTrue(open(0, 20, 30)) // every reminder is off here
        assertTrue(open(1, 1)) // past midnight, still Monday's log day
        assertFalse(open(1, 4))
        val earlier = Reminders(evening = Reminder(true, LocalTime.of(19, 0)))
        assertTrue(open(0, 19, 0, earlier))
        assertFalse(open(0, 18, 59, earlier))
    }

    // Settings and device state

    @Test
    fun `reminders are settings, stored whole and exported`() = runBlocking {
        val set = Reminders(
            weighIn = Reminder(true, LocalTime.of(6, 45)), lunchSlot = "snacks", workout = Reminder(true, LocalTime.of(8, 15)),
            workoutDays = setOf(DayOfWeek.TUESDAY, DayOfWeek.SATURDAY), scan = true,
        )
        app.prefs.updateReminders { set }
        assertEquals(set, app.prefs.reminders.first())
        assertEquals(set, StoredReminders.of(set).toReminders())
        val exported = app.prefs.exportable()
        assertNotNull(exported[stringPreferencesKey("reminders")])
        assertTrue(app.prefs.problems(exported).isEmpty())
        // A backup with a reminder at minute 2,000, or on weekday 9, is refused rather than crashing later.
        val key = stringPreferencesKey("reminders")
        assertEquals(listOf("reminders"), app.prefs.problems(mutablePreferencesOf(key to """{"lunch":{"on":true,"minutes":2000}}""")))
        assertEquals(listOf("reminders"), app.prefs.problems(mutablePreferencesOf(key to """{"workoutDays":[9]}""")))
        // A reminder a build doesn't store yet keeps its default.
        assertEquals(Reminders().copy(scan = true), StoredReminders(scan = true).toReminders())
    }

    @Test
    fun `the ledger is device state, out of exports and out of Android's backup`() = runBlocking {
        onboard()
        turnOn(ReminderKind.WEIGH_IN)
        run(0, 7, 30)
        assertNotNull(app.device.ledger())
        // Nothing of it is in the settings an export carries, and importing settings leaves it alone.
        val exported = app.prefs.exportable()
        assertTrue(exported.asMap().keys.none { it.name.contains("ledger") })
        app.prefs.replaceAll(exported)
        assertEquals(listOf(ReminderKind.WEIGH_IN), app.device.ledger()!!.sent)

        // Cloud backup and device transfer list what they include; the device store isn't in either.
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/data_extraction_rules.xml"))
        listOf("cloud-backup", "device-transfer").forEach { section ->
            val nodes = (doc.getElementsByTagName(section).item(0) as Element).getElementsByTagName("include")
            val paths = (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("path") }
            assertTrue("$section lists what it includes", paths.isNotEmpty())
            assertTrue("$section leaves out the device store", paths.none { it.contains(DatabaseModule.DEVICE_FILE) || it == "datastore" || it == "." })
        }
    }
}
