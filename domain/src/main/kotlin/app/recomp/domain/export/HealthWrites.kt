package app.recomp.domain.export

import app.recomp.domain.model.SessionKind
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Which kind of Health Connect record (DA-04). */
enum class OutKind { WORKOUT, MEAL, WEIGHT }

/** Health Connect's meal types, as Recomp's default meals map onto them; a meal you added is UNKNOWN. */
enum class OutMealType { BREAKFAST, LUNCH, DINNER, SNACK, UNKNOWN }

/**
 * One record Recomp writes to Health Connect (DA-04, TRD §10 M16), described without Health Connect's classes so the
 * rules can be tested here. [id] is its `clientRecordId`, stable for the same thing across edits, so an update replaces
 * it and a delete finds it. [date] is the log date it belongs to, which decides whether it's in the sync window.
 */
sealed interface OutRecord {
    val id: String
    val date: LocalDate
    val kind: OutKind

    /** What the record says, so an unchanged one isn't written again. */
    val hash: String get() = HealthWrites.sha(toString())
}

/** A workout you did in Recomp: a strength session or a class. Never one read from another app. */
data class OutWorkout(
    override val id: String,
    override val date: LocalDate,
    val start: Instant,
    val end: Instant,
    val sessionKind: SessionKind,
    val title: String,
    /** What was done, e.g. "Bench press: 40 kg × 10, 42.5 kg × 8". Edits show up here, so they're written again. */
    val notes: String?,
) : OutRecord {
    override val kind get() = OutKind.WORKOUT
}

/** One meal of one log date: the sum of its entries, quick adds included. Unknown macros stay unknown. */
data class OutMeal(
    override val id: String,
    override val date: LocalDate,
    val start: Instant,
    val end: Instant,
    val mealType: OutMealType,
    val name: String,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double?,
    val fatG: Double?,
    val fiberG: Double?,
) : OutRecord {
    override val kind get() = OutKind.MEAL
}

/** A weigh-in you logged. A scan's weight is never one: the scale's own app writes it. */
data class OutWeight(
    override val id: String,
    override val date: LocalDate,
    val at: Instant,
    val grams: Long,
) : OutRecord {
    override val kind get() = OutKind.WEIGHT
}

/** A session as the sync reads it from the tables. */
data class SessionRow(
    val id: Long,
    val logDate: LocalDate,
    val startedAt: Long,
    val endedAt: Long,
    val kind: SessionKind,
    /** The routine's name, or the class's style. */
    val title: String,
    /** Set by M13 for a session read from another app: such a session is never written back. */
    val importedFrom: String?,
    val sets: List<SetRow> = emptyList(),
)

/** One ticked set, as the notes describe it. [loadG] is null for bodyweight; for an assisted set it's the assistance. */
data class SetRow(val exercise: String, val loadG: Long?, val assisted: Boolean, val reps: Int?)

/** One food entry: what a meal adds up. */
data class FoodRow(val logDate: LocalDate, val slot: String, val kcal: Double, val proteinG: Double, val carbsG: Double?, val fatG: Double?, val fiberG: Double?)

/** A weigh-in row. [fromScan] is a scan's weight (source SCAN, or linked to a scan). */
data class WeighInRow(val id: Long, val logDate: LocalDate, val measuredAt: Long, val grams: Long, val fromScan: Boolean)

/** A meal slot as the records need it: its key, name and usual start (minutes after midnight). */
data class MealTime(val key: String, val name: String, val startMinutes: Int)

/** What one sync does: what to write, what to delete, and what's then known to be written. */
data class WritePlan(val upserts: List<OutRecord>, val deletes: List<Pair<OutKind, String>>, val written: Map<String, Written>)

/** A record already written: its date (for the window) and its [hash]. Device state: never exported. */
data class Written(val date: LocalDate, val hash: String)

/** DA-04's rules (PRD §12.12, TRD §10 M16). */
object HealthWrites {
    /** Turning sharing on writes this many days, and each sync looks this far back for edits and deletes. */
    const val WINDOW_DAYS = 30L

    /** How long a meal's record lasts from the slot's usual start. */
    const val MEAL_MINUTES = 30L

    fun workoutId(sessionId: Long) = "recomp-workout-$sessionId"
    fun mealId(date: LocalDate, slot: String) = "recomp-meal-$date-$slot"
    fun weightId(rowId: Long) = "recomp-weight-$rowId"

    fun kindOf(id: String): OutKind? = when {
        id.startsWith("recomp-workout-") -> OutKind.WORKOUT
        id.startsWith("recomp-meal-") -> OutKind.MEAL
        id.startsWith("recomp-weight-") -> OutKind.WEIGHT
        else -> null
    }

    fun mealType(slot: String): OutMealType = when (slot) {
        "breakfast" -> OutMealType.BREAKFAST
        "lunch" -> OutMealType.LUNCH
        "dinner" -> OutMealType.DINNER
        "snacks" -> OutMealType.SNACK
        else -> OutMealType.UNKNOWN
    }

    /** Workouts you did here: finished, with a sensible span. Sessions read from other apps (M13) are never written. */
    fun workouts(sessions: List<SessionRow>): List<OutWorkout> = sessions.filter { it.importedFrom == null && it.endedAt > it.startedAt }.map { s ->
        OutWorkout(workoutId(s.id), s.logDate, Instant.ofEpochMilli(s.startedAt), Instant.ofEpochMilli(s.endedAt), s.kind, s.title, notes(s.sets))
    }

    /** "Bench press: 40 kg × 10, 42.5 kg × 8" per exercise, in order. Null when there are no sets (a class). */
    fun notes(sets: List<SetRow>): String? {
        if (sets.isEmpty()) return null
        val byExercise = LinkedHashMap<String, MutableList<String>>()
        sets.forEach { set ->
            val load = when {
                set.loadG == null -> "BW"
                set.assisted -> if (set.loadG == 0L) "BW" else "assist ${kg(set.loadG)} kg"
                else -> "${kg(set.loadG)} kg"
            }
            byExercise.getOrPut(set.exercise) { mutableListOf() } += if (set.reps == null) load else "$load × ${set.reps}"
        }
        return byExercise.entries.joinToString("\n") { (name, done) -> "$name: ${done.joinToString(", ")}" }
    }

    private fun kg(grams: Long): String = (grams / 1000.0).let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }

    /**
     * One record per meal of each log date with entries, spanning the slot's usual start on that log date (a start
     * before "day starts at" falls on the next calendar day, so a 1 AM snack sits on the log date it belongs to).
     * Carbs, fat and fibre add up only the entries that have them, and stay unknown when none does.
     */
    fun meals(entries: List<FoodRow>, slots: List<MealTime>, dayStart: LocalTime, zone: ZoneId): List<OutMeal> {
        val bySlot = slots.associateBy { it.key }
        return entries.groupBy { it.logDate to it.slot }.map { (key, rows) ->
            val (date, slot) = key
            val meal = bySlot[slot] ?: MealTime(slot, slot, NOON)
            val time = LocalTime.of(meal.startMinutes / 60, meal.startMinutes % 60)
            val calendar = if (time.isBefore(dayStart)) date.plusDays(1) else date
            val start = calendar.atTime(time).atZone(zone).toInstant()
            fun sumOf(pick: (FoodRow) -> Double?): Double? = rows.mapNotNull(pick).takeIf { it.isNotEmpty() }?.sum()
            OutMeal(
                mealId(date, slot), date, start, start.plusSeconds(MEAL_MINUTES * 60), mealType(slot), meal.name,
                kcal = rows.sumOf { it.kcal }, proteinG = rows.sumOf { it.proteinG },
                carbsG = sumOf { it.carbsG }, fatG = sumOf { it.fatG }, fiberG = sumOf { it.fiberG },
            )
        }.sortedWith(compareBy({ it.date }, { it.start }))
    }

    /** Weigh-ins you logged. A scan's weight is never written: the scale's app writes it to Health Connect itself. */
    fun weights(rows: List<WeighInRow>): List<OutWeight> =
        rows.filter { !it.fromScan }.map { OutWeight(weightId(it.id), it.logDate, Instant.ofEpochMilli(it.measuredAt), it.grams) }

    /**
     * What to write and delete. [current] are the records of the window (from [from], or of all time when [from] is
     * null). A record is written when it's new or changed since [written] says; one written before whose date is in
     * the window and that's no longer there is deleted. What's older than the window is forgotten, not deleted: a
     * later edit there is followed by *Resync everything*. With [everything], every record is written again.
     */
    fun plan(current: List<OutRecord>, written: Map<String, Written>, from: LocalDate?, everything: Boolean = false): WritePlan {
        fun inWindow(date: LocalDate) = from == null || !date.isBefore(from)
        val now = current.filter { inWindow(it.date) }
        val ids = now.map { it.id }.toSet()
        val upserts = now.filter { everything || written[it.id]?.hash != it.hash }
        val deletes = written.filter { (id, w) -> inWindow(w.date) && id !in ids }.mapNotNull { (id, _) -> kindOf(id)?.let { it to id } }
        // Once done, what's known is exactly what's there now; older entries are forgotten.
        return WritePlan(upserts, deletes.sortedBy { it.second }, now.associate { it.id to Written(it.date, it.hash) })
    }

    /** The first log date of the window that ends on [today]. */
    fun windowStart(today: LocalDate): LocalDate = today.minusDays(WINDOW_DAYS - 1)

    private const val NOON = 12 * 60

    /**
     * When the default meals usually start, as M9's `MealTimes.DEFAULT_STARTS` has them. Until M9's slot times are on
     * `main` this is the one place to switch to `MealTimes.start`: a meal you added starts an hour after the one
     * before it in your list.
     */
    val DEFAULT_STARTS = mapOf("breakfast" to 5 * 60, "lunch" to 12 * 60, "snacks" to 16 * 60, "dinner" to 19 * 60)

    fun mealTimes(slots: List<Pair<String, String>>): List<MealTime> {
        var previous: Int? = null
        return slots.map { (key, name) ->
            val start = DEFAULT_STARTS[key] ?: ((previous ?: (NOON - 60)) + 60).coerceAtMost(24 * 60 - 1)
            previous = start
            MealTime(key, name, start)
        }
    }

    fun sha(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}
