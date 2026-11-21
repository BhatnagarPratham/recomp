package app.recomp.data.seed

import android.content.Context
import android.util.Log
import app.recomp.domain.model.SessionKind
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.persona.Diet
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.ExtraSession
import app.recomp.domain.persona.FoodRegion
import app.recomp.domain.persona.PersonaDraft
import app.recomp.domain.persona.Preset
import app.recomp.domain.persona.SplitTemplate
import app.recomp.domain.persona.WeightSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** An extra session in a preset file. [days] are ISO day numbers, Monday = 1; none leaves the days to you. */
@Serializable
data class PresetExtraFile(val name: String, val kind: SessionKind, val days: Set<Int> = emptySet())

/**
 * One preset as its file under `assets/presets` holds it (PS-05, PRD §4.4). A value left out is *Asked*: the
 * review step can't finish until it's chosen. [order] sorts the list (ties by name), so a fork's file can go
 * anywhere in it. Enum values are the Kotlin names, e.g. `"goal": "RECOMP"`, `"split": "PPL"`.
 */
@Serializable
data class PresetFile(
    val key: String,
    val name: String,
    val summary: String,
    val order: Int = 100,
    val goal: Goal? = null,
    val experience: Experience? = null,
    val diet: Diet? = null,
    val region: FoodRegion? = null,
    val split: SplitTemplate? = null,
    val strengthDaysPerWeek: Int? = null,
    val extras: List<PresetExtraFile> = emptyList(),
    val weightSource: WeightSource = WeightSource.HOME_SCALE,
    val proteinRamp: Boolean? = null,
) {
    init {
        require(key.isNotBlank() && name.isNotBlank()) { "A preset needs a key and a name" }
        require(strengthDaysPerWeek == null || strengthDaysPerWeek in 1..7) { "Strength days are 1 to 7, not $strengthDaysPerWeek" }
        require(extras.all { it.kind.isExtra && it.name.isNotBlank() && it.days.all { d -> d in 1..7 } }) { "An extra session is yoga, mobility, cardio or sport, on weekdays 1 to 7" }
    }

    fun toPreset() = Preset(
        key = key,
        name = name,
        summary = summary,
        goal = goal,
        draft = PersonaDraft(
            experience = experience,
            diet = diet,
            region = region,
            split = split,
            strengthDaysPerWeek = strengthDaysPerWeek,
            extras = extras.map { ExtraSession(it.name, it.kind, it.days.map(DayOfWeek::of).toSet()) },
            weightSource = weightSource,
            proteinRamp = proteinRamp,
        ),
    )
}

object PresetData {
    const val DIR = "presets"

    // Strict, unlike the settings: a misspelt field in a preset file should fail the build's test, not be ignored.
    private val json = Json

    /** Throws when the file doesn't fit: an unknown field or enum value, or a value out of range. */
    fun parse(text: String): PresetFile = json.decodeFromString<PresetFile>(text)

    /**
     * The presets from named file texts, as the phone loads them: a file that doesn't decode, or repeats a key already
     * read, is skipped and named in [onSkip], so a fork's mistake can't stop onboarding. `PresetsTest` keeps the
     * bundled files strict.
     */
    fun load(files: List<Pair<String, String>>, onSkip: (String, Throwable) -> Unit = { _, _ -> }): List<Preset> {
        val read = mutableListOf<PresetFile>()
        for ((name, text) in files.sortedBy { it.first }) {
            try {
                val file = parse(text)
                require(read.none { it.key == file.key }) { "Key ${file.key} is used twice" }
                read += file
            } catch (e: IllegalArgumentException) {
                // kotlinx.serialization's SerializationException is an IllegalArgumentException too.
                onSkip(name, e)
            }
        }
        return sorted(read)
    }

    /** The list as onboarding shows it. A key used twice is a mistake in the files. */
    fun sorted(files: List<PresetFile>): List<Preset> {
        val twice = files.groupBy { it.key }.filterValues { it.size > 1 }.keys
        require(twice.isEmpty()) { "Preset keys used twice: $twice" }
        return files.sortedWith(compareBy({ it.order }, { it.name })).map { it.toPreset() }
    }
}

/** Where the presets come from: assets on the phone, the source tree in tests. */
interface PresetSource {
    fun presets(): List<Preset>

    fun byKey(key: String?): Preset? = presets().firstOrNull { it.key == key }
}

/** Every `.json` file under `assets/presets`, so adding a file adds a preset with no Kotlin change. */
@Singleton
class AssetPresetSource @Inject constructor(@ApplicationContext private val context: Context) : PresetSource {
    private val presets by lazy {
        val names = context.assets.list(PresetData.DIR).orEmpty().filter { it.endsWith(".json") }
        val texts = names.map { name -> name to context.assets.open("${PresetData.DIR}/$name").bufferedReader().use { it.readText() } }
        PresetData.load(texts) { name, e -> Log.w(TAG, "Skipped the preset file $name: ${e.javaClass.simpleName}") }
    }

    override fun presets(): List<Preset> = presets

    private companion object {
        const val TAG = "Presets"
    }
}
