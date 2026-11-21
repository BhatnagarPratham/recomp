package app.recomp.data.repo

import androidx.room.withTransaction
import app.recomp.data.db.AppDatabase
import app.recomp.data.db.ExerciseDao
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.domain.persona.Units
import app.recomp.domain.training.Loads
import app.recomp.domain.units.LoadUnits
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** One weight step the switch would move, and the exercises that use it, by name. */
data class StepMove(val fromG: Int, val toG: Int, val exercises: List<String>)

/**
 * Settings → Body data → Units (PR-05, PRD §12.16). Switching asks whether to move exercises' weight steps to the
 * nearest step in the new unit (2.5 kg → 5 lb). It changes nothing else: logged sets, records, body data and food
 * keep their stored grams and cm, and a workout in progress keeps the targets it started with.
 */
@Singleton
class UnitSwitch @Inject constructor(
    private val db: AppDatabase,
    private val exerciseDao: ExerciseDao,
    private val prefs: SettingsPrefs,
) {
    /** The steps that would move on switching to [to], most-used first. Steps already offered in [to] aren't listed. */
    suspend fun preview(to: Units): List<StepMove> =
        exerciseDao.observeActive().first()
            .filter { Loads.logsLoad(it.loadType) }
            .groupBy { it.incrementG }
            .mapNotNull { (from, exercises) ->
                val target = LoadUnits.nearestIncrementG(from, to)
                if (target == from) null else StepMove(from, target, exercises.map { it.name })
            }
            .sortedByDescending { it.exercises.size }

    /** Switches to [to]; with [moveSteps], every active exercise that logs a load gets its step's nearest one in [to]. */
    suspend fun switch(to: Units, moveSteps: Boolean) {
        if (moveSteps) {
            db.withTransaction {
                val moved = exerciseDao.observeActive().first().filter { Loads.logsLoad(it.loadType) }.mapNotNull { e ->
                    val target = LoadUnits.nearestIncrementG(e.incrementG, to)
                    if (target == e.incrementG) null else e.copy(incrementG = target)
                }
                if (moved.isNotEmpty()) exerciseDao.updateAll(moved)
            }
        }
        prefs.updatePersona { it.copy(units = to) }
    }

    suspend fun current(): Units = prefs.persona.first().units
}
