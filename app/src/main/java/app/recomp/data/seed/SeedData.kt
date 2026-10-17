package app.recomp.data.seed

import android.content.Context
import app.recomp.data.db.ExerciseEntity
import app.recomp.domain.model.Equipment
import app.recomp.domain.model.LoadType
import app.recomp.domain.model.Mechanic
import app.recomp.domain.model.RepClass
import app.recomp.domain.persona.ExperiencePolicy
import app.recomp.domain.persona.SplitTemplate
import app.recomp.domain.training.Loads
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Bundled training data (EX-01, RT-04): `assets/seed/exercises.json` and `assets/seed/split_templates.json`.
 * They're data, not code, so a fork can change the library or the templates without touching Kotlin.
 */
@Serializable
data class ExerciseSeed(
    val key: String,
    val name: String,
    val primaryMuscle: String,
    val secondaryMuscles: List<String> = emptyList(),
    val equipment: Equipment,
    val mechanic: Mechanic,
    val movementPattern: String,
    val repClass: RepClass,
    val restSec: Int,
    val loadType: LoadType = LoadType.WEIGHTED,
    val unilateral: Boolean = false,
    /** Overrides the equipment's default increment. */
    val incrementG: Int? = null,
) {
    fun toEntity(policy: ExperiencePolicy): ExerciseEntity {
        val reps = policy.repsFor(repClass)
        return ExerciseEntity(
            name = name,
            primaryMuscle = primaryMuscle,
            secondaryMuscles = secondaryMuscles,
            equipment = equipment,
            mechanic = mechanic,
            movementPattern = movementPattern,
            loadType = loadType,
            incrementG = incrementG ?: Loads.defaultIncrementG(equipment),
            defaultRepMin = reps.first,
            defaultRepMax = reps.last,
            defaultRestSec = restSec,
            isUnilateral = unilateral,
            seedKey = key,
        )
    }
}

/** One exercise slot in a template. [alternatives] are offered by the equipment walkthrough (PR-04). */
@Serializable
data class TemplateEntrySeed(val exercise: String, val alternatives: List<String> = emptyList(), val sets: Int = 3) {
    val options: List<String> get() = listOf(exercise) + alternatives
}

@Serializable
data class RoutineSeed(val name: String, val exercises: List<TemplateEntrySeed>)

@Serializable
data class SplitTemplateSeed(val split: SplitTemplate, val routines: List<RoutineSeed>)

@Serializable
private data class TemplatesFile(val templates: List<SplitTemplateSeed>)

object SeedData {
    private val json = Json { ignoreUnknownKeys = false }

    fun parseExercises(text: String): List<ExerciseSeed> = json.decodeFromString(text)

    fun parseTemplates(text: String): List<SplitTemplateSeed> = json.decodeFromString<TemplatesFile>(text).templates

    /**
     * When the experience level changes, seeded exercises whose rep range is still the old policy's
     * default move to the new default. Ranges you edited are left alone.
     */
    fun reapplyRepRanges(
        existing: List<ExerciseEntity>,
        seeds: Map<String, ExerciseSeed>,
        old: ExperiencePolicy,
        new: ExperiencePolicy,
    ): List<ExerciseEntity> = existing.mapNotNull { row ->
        val seed = seeds[row.seedKey] ?: return@mapNotNull null
        val before = old.repsFor(seed.repClass)
        val after = new.repsFor(seed.repClass)
        if (before == after || row.defaultRepMin != before.first || row.defaultRepMax != before.last) return@mapNotNull null
        row.copy(defaultRepMin = after.first, defaultRepMax = after.last)
    }
}

/** Where the seed files come from: assets on the phone, the source tree in tests. */
interface SeedSource {
    fun exercises(): List<ExerciseSeed>
    fun templates(): List<SplitTemplateSeed>
}

@Singleton
class AssetSeedSource @Inject constructor(@ApplicationContext private val context: Context) : SeedSource {
    private val exercises by lazy { SeedData.parseExercises(read("seed/exercises.json")) }
    private val templates by lazy { SeedData.parseTemplates(read("seed/split_templates.json")) }

    override fun exercises() = exercises
    override fun templates() = templates

    private fun read(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }
}
