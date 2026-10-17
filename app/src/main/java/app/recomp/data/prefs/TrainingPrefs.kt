package app.recomp.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.recomp.di.SettingsStore
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.SplitTemplate
import app.recomp.domain.training.ExtraPlan
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** An extra session's schedule. [days] are ISO day numbers, Monday = 1. */
@Serializable
data class ExtraSchedule(val routineId: Long, val days: Set<Int> = emptySet()) {
    fun toPlan() = ExtraPlan(routineId, days.map(DayOfWeek::of).toSet())
}

/**
 * Training settings (TRD §4: DataStore). They become part of the persona in M3; until then the M1
 * training setup writes them. [split] is null until setup has run.
 */
@Serializable
data class TrainingSettings(
    val split: SplitTemplate? = null,
    val experience: Experience = Experience.BEGINNER,
    val strengthDaysPerWeek: Int = 3,
    val extras: List<ExtraSchedule> = emptyList(),
    val keepScreenOn: Boolean = false,
) {
    val setupDone: Boolean get() = split != null
}

@Singleton
class TrainingPrefs @Inject constructor(@SettingsStore private val store: DataStore<Preferences>) {
    private val key = stringPreferencesKey("training")
    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<TrainingSettings> = store.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<TrainingSettings>(it) }.getOrNull() } ?: TrainingSettings()
    }

    suspend fun current(): TrainingSettings = settings.first()

    suspend fun update(transform: (TrainingSettings) -> TrainingSettings) {
        store.edit { prefs ->
            val now = prefs[key]?.let { runCatching { json.decodeFromString<TrainingSettings>(it) }.getOrNull() } ?: TrainingSettings()
            prefs[key] = json.encodeToString(transform(now))
        }
    }
}
