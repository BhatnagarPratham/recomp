package app.recomp.data.repo

import androidx.room.withTransaction
import app.recomp.data.db.AppDatabase
import app.recomp.data.db.ExerciseDao
import app.recomp.data.db.ExerciseEntity
import app.recomp.data.db.RoutineDao
import app.recomp.data.db.RoutineEntity
import app.recomp.data.db.RoutineExerciseEntity
import app.recomp.data.db.WorkoutDao
import app.recomp.data.prefs.ExtraSchedule
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.data.prefs.TrainingSettings
import app.recomp.data.seed.SeedData
import app.recomp.data.seed.SeedSource
import app.recomp.domain.model.Equipment
import app.recomp.domain.model.LoadType
import app.recomp.domain.model.Mechanic
import app.recomp.domain.model.ProgressionScheme
import app.recomp.domain.model.RepClass
import app.recomp.domain.model.SessionKind
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.ExperiencePolicy
import app.recomp.domain.persona.SplitTemplate
import app.recomp.domain.training.Loads
import app.recomp.domain.training.Rotation
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** One slot of the equipment walkthrough (PR-04): pick the variant your gym has and its smallest step. */
data class WalkthroughItem(
    val slotKey: String,
    val routines: List<String>,
    /** The template's default first, then its alternatives. */
    val options: List<ExerciseEntity>,
    val selectedId: Long,
)

data class ExtraDraft(val routineId: Long?, val name: String, val kind: SessionKind, val days: Set<Int>)

data class TrainingSetup(
    val split: SplitTemplate,
    val experience: Experience,
    val strengthDaysPerWeek: Int,
    val extras: List<ExtraDraft>,
    /** Walkthrough slot key → chosen exercise id. */
    val choices: Map<String, Long>,
    /** Exercise id → smallest weight step in grams. */
    val increments: Map<Long, Int>,
)

/** Exercise library, routines, rotation and the training setup (EX, RT, PR-04). */
@Singleton
class TrainingRepository @Inject constructor(
    private val db: AppDatabase,
    private val exerciseDao: ExerciseDao,
    private val routineDao: RoutineDao,
    private val workoutDao: WorkoutDao,
    private val seeds: SeedSource,
    private val prefs: SettingsPrefs,
) {
    /** The training part of the persona (PS-03). */
    val settings: Flow<TrainingSettings> = prefs.training

    fun observeLibrary(): Flow<List<ExerciseEntity>> = exerciseDao.observeActive()
    fun observeExercise(id: Long): Flow<ExerciseEntity?> = exerciseDao.observe(id)
    suspend fun exercise(id: Long): ExerciseEntity? = exerciseDao.get(id)
    fun observeRotation(): Flow<List<RoutineEntity>> = routineDao.observeRotation()
    fun observeExtras(): Flow<List<RoutineEntity>> = routineDao.observeExtras()
    fun observeRoutine(id: Long) = routineDao.observe(id)
    fun observeRoutineExercises(id: Long) = routineDao.observeExercises(id)

    /** The routine the rotation suggests next (RT-02). */
    fun observeNextRoutine(): Flow<RoutineEntity?> =
        combine(routineDao.observeRotation(), workoutDao.observeRecentStrengthRoutineIds()) { rotation, recent ->
            Rotation.next(rotation.map { it.id }, recent)?.let { id -> rotation.first { it.id == id } }
        }

    fun policy(experience: Experience) = ExperiencePolicy.defaultFor(experience)

    /** Inserts any bundled exercise that isn't in the library yet. Safe to call on every launch. */
    suspend fun ensureLibrarySeeded() {
        val policy = policy(prefs.currentTraining().experience)
        val present = exerciseDao.seeded().mapNotNull { it.seedKey }.toSet()
        val missing = seeds.exercises().filter { it.key !in present }
        if (missing.isNotEmpty()) exerciseDao.insertAll(missing.map { it.toEntity(policy) })
    }

    // Training setup and equipment walkthrough

    suspend fun walkthrough(split: SplitTemplate): List<WalkthroughItem> {
        ensureLibrarySeeded()
        val bySeed = exerciseDao.seeded().associateBy { it.seedKey!! }
        val current = prefs.currentTraining()
        val inRotation = routineDao.rotation().associateWith { routineDao.exercises(it.id).map { row -> row.exercise } }

        if (split == SplitTemplate.CUSTOM) {
            // Switching to Custom starts empty, so there's nothing to walk through yet.
            if (current.split != SplitTemplate.CUSTOM) return emptyList()
            // No template: walk through whatever your routines already hold, to confirm increments.
            return inRotation.entries.flatMap { (routine, exercises) -> exercises.map { routine.name to it } }
                .groupBy({ it.second }, { it.first })
                .map { (exercise, routines) -> WalkthroughItem("id:${exercise.id}", routines.distinct(), listOf(exercise), exercise.id) }
        }

        val template = seeds.templates().first { it.split == split }
        val currentIds = if (current.split == split) inRotation.values.flatten().map { it.id }.toSet() else emptySet()
        val slots = LinkedHashMap<String, Pair<MutableList<String>, List<ExerciseEntity>>>()
        template.routines.forEach { routine ->
            routine.exercises.forEach { entry ->
                val slot = slots.getOrPut(entry.exercise) { mutableListOf<String>() to entry.options.map { bySeed.getValue(it) } }
                slot.first += routine.name
            }
        }
        return slots.map { (key, slot) ->
            val (routines, options) = slot
            WalkthroughItem(key, routines, options, options.firstOrNull { it.id in currentIds }?.id ?: options.first().id)
        }
    }

    /**
     * Applies the training setup in one transaction. A new split archives the current rotation (history
     * stays) and builds the template's routines; the same split swaps the chosen variants in place, so
     * your other edits survive.
     */
    suspend fun applySetup(setup: TrainingSetup) {
        ensureLibrarySeeded()
        val before = prefs.currentTraining()
        val newPolicy = policy(setup.experience)
        val extraIds = db.withTransaction {
            if (before.experience != setup.experience) {
                val seedsByKey = seeds.exercises().associateBy { it.key }
                exerciseDao.updateAll(SeedData.reapplyRepRanges(exerciseDao.seeded(), seedsByKey, policy(before.experience), newPolicy))
            }
            setup.increments.forEach { (id, grams) ->
                exerciseDao.get(id)?.takeIf { it.incrementG != grams }?.let { exerciseDao.update(it.copy(incrementG = grams)) }
            }

            if (before.split != setup.split || routineDao.rotation().isEmpty()) {
                rebuildRotation(setup.split, setup.choices)
            } else {
                swapChoicesInPlace(setup.choices)
            }
            saveExtras(setup.extras)
        }
        prefs.updatePersona {
            it.copy(
                split = setup.split,
                experience = setup.experience,
                strengthDaysPerWeek = setup.strengthDaysPerWeek,
                extras = setup.extras.zip(extraIds) { draft, id -> ExtraSchedule(id, draft.days) },
            )
        }
    }

    private suspend fun rebuildRotation(split: SplitTemplate, choices: Map<String, Long>) {
        routineDao.updateAll(routineDao.rotation().map { it.copy(archived = true) })
        if (split == SplitTemplate.CUSTOM) return
        val bySeed = exerciseDao.seeded().associateBy { it.seedKey!! }
        val byId = bySeed.values.associateBy { it.id }
        seeds.templates().first { it.split == split }.routines.forEachIndexed { index, routine ->
            val rows = routine.exercises.map { entry ->
                val exercise = choices[entry.exercise]?.let(byId::get) ?: bySeed.getValue(entry.exercise)
                planRowFor(exercise, sets = entry.sets)
            }
            routineDao.insertWithExercises(RoutineEntity(name = routine.name, kind = SessionKind.STRENGTH, rotationIndex = index), rows)
        }
    }

    private suspend fun swapChoicesInPlace(choices: Map<String, Long>) {
        val template = seeds.templates().associateBy { it.split }[prefs.currentTraining().split] ?: return
        val bySeed = exerciseDao.seeded().associateBy { it.seedKey!! }
        val slotOptions = template.routines.flatMap { it.exercises }.associate { entry ->
            entry.exercise to entry.options.mapNotNull { bySeed[it]?.id }.toSet()
        }
        routineDao.rotation().forEach { routine ->
            val rows = routineDao.exercises(routine.id)
            var changed = false
            val updated = rows.map { row ->
                val slot = slotOptions.entries.firstOrNull { row.entry.exerciseId in it.value } ?: return@map row.entry
                val chosen = choices[slot.key]?.takeIf { it != row.entry.exerciseId } ?: return@map row.entry
                changed = true
                planRowFor(exerciseDao.get(chosen)!!, sets = row.entry.targetSets)
            }
            if (changed) routineDao.replaceExercises(routine.id, updated)
        }
    }

    /** Creates, renames or archives extra-session routines; returns their ids in [drafts] order. */
    private suspend fun saveExtras(drafts: List<ExtraDraft>): List<Long> {
        val keep = drafts.mapNotNull { it.routineId }.toSet()
        val existing = mutableListOf<RoutineEntity>()
        routineDao.extras().forEach { if (it.id in keep) existing += it else routineDao.update(it.copy(archived = true)) }
        return drafts.mapIndexed { index, draft ->
            val row = existing.firstOrNull { it.id == draft.routineId }
            if (row == null) {
                routineDao.insert(RoutineEntity(name = draft.name, kind = draft.kind, rotationIndex = index))
            } else {
                routineDao.update(row.copy(name = draft.name, kind = draft.kind, rotationIndex = index))
                row.id
            }
        }
    }

    // Routines (RT-01, RT-04)

    fun planRowFor(exercise: ExerciseEntity, sets: Int = DEFAULT_SETS) = RoutineExerciseEntity(
        routineId = 0,
        exerciseId = exercise.id,
        position = 0,
        targetSets = sets,
        repMin = exercise.defaultRepMin,
        repMax = exercise.defaultRepMax,
        restSec = exercise.defaultRestSec,
        scheme = if (exercise.loadType == LoadType.BODYWEIGHT) ProgressionScheme.REPS_ONLY else ProgressionScheme.DOUBLE_PROGRESSION,
    )

    suspend fun createRoutine(name: String): Long =
        routineDao.insert(RoutineEntity(name = name, kind = SessionKind.STRENGTH, rotationIndex = routineDao.maxRotationIndex() + 1))

    suspend fun renameRoutine(id: Long, name: String) {
        routineDao.get(id)?.let { routineDao.update(it.copy(name = name)) }
    }

    suspend fun archiveRoutine(id: Long) {
        routineDao.get(id)?.let { routineDao.update(it.copy(archived = true)) }
    }

    /** Moves a routine one place earlier or later in the rotation. */
    suspend fun moveRoutine(id: Long, up: Boolean) = db.withTransaction {
        val rotation = routineDao.rotation().toMutableList()
        val from = rotation.indexOfFirst { it.id == id }
        val to = if (up) from - 1 else from + 1
        if (from < 0 || to !in rotation.indices) return@withTransaction
        rotation.add(to, rotation.removeAt(from))
        routineDao.updateAll(rotation.mapIndexed { i, r -> r.copy(rotationIndex = i) })
    }

    suspend fun saveRoutineExercises(routineId: Long, rows: List<RoutineExerciseEntity>) = routineDao.replaceExercises(routineId, rows)

    /** A stall *Swap* (PO-05) also changes the routine, keeping that slot's targets. */
    suspend fun swapInRoutine(routineId: Long, oldExerciseId: Long, newExerciseId: Long) = db.withTransaction {
        val replacement = exerciseDao.get(newExerciseId) ?: return@withTransaction
        val rows = routineDao.exercises(routineId).map { it.entry }
        if (rows.none { it.exerciseId == oldExerciseId }) return@withTransaction
        routineDao.replaceExercises(
            routineId,
            rows.map { if (it.exerciseId == oldExerciseId) planRowFor(replacement, it.targetSets).copy(scheme = it.scheme, supersetGroup = it.supersetGroup) else it },
        )
    }

    // Exercises (EX-02, EX-03)

    /**
     * Creates a custom exercise (EX-02). It starts from the experience policy's range for its mechanic
     * and the equipment's default increment; everything is editable afterwards (EX-03).
     */
    suspend fun createCustomExercise(name: String, mechanic: Mechanic, equipment: Equipment): Long {
        val policy = policy(prefs.currentTraining().experience)
        val reps = policy.repsFor(if (mechanic == Mechanic.COMPOUND) RepClass.COMPOUND else RepClass.ISOLATION)
        return exerciseDao.insert(
            ExerciseEntity(
                name = name,
                primaryMuscle = "other",
                equipment = equipment,
                mechanic = mechanic,
                movementPattern = "other",
                loadType = if (equipment == Equipment.BODYWEIGHT || equipment == Equipment.BAND) LoadType.BODYWEIGHT else LoadType.WEIGHTED,
                incrementG = Loads.defaultIncrementG(equipment),
                defaultRepMin = reps.first,
                defaultRepMax = reps.last,
                defaultRestSec = if (mechanic == Mechanic.COMPOUND) 120 else 90,
                isCustom = true,
            ),
        )
    }

    suspend fun insertExercise(exercise: ExerciseEntity): Long = exerciseDao.insert(exercise)

    suspend fun updateExercise(exercise: ExerciseEntity) = exerciseDao.update(exercise)

    suspend fun setKeepScreenOn(on: Boolean) = prefs.setKeepScreenOn(on)

    companion object {
        /** PRD §7.3: templates default to 3 working sets per exercise. */
        const val DEFAULT_SETS = 3
    }
}
