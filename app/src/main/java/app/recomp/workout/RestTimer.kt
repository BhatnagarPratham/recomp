package app.recomp.workout

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import app.recomp.di.WorkoutStateStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A running rest: when it ends (epoch ms) and how long it was set for, for the progress bar. */
data class RestState(val endsAt: Long, val totalSec: Int) {
    fun remainingMs(now: Long): Long = (endsAt - now).coerceAtLeast(0)
}

/**
 * The rest timer (WL-05, TRD §6.1). It stores an absolute end time rather than a countdown, so it
 * survives process death. The workout screen and [ActiveWorkoutService] both observe it; the service
 * owns the "rest over" alert.
 */
@Singleton
class RestTimer @Inject constructor(@WorkoutStateStore private val store: DataStore<Preferences>) {
    private val endsAtKey = longPreferencesKey("rest_ends_at")
    private val totalKey = intPreferencesKey("rest_total_sec")

    val state: Flow<RestState?> = store.data
        .map { prefs -> prefs[endsAtKey]?.let { RestState(it, prefs[totalKey] ?: 0) } }
        .distinctUntilChanged()

    suspend fun start(seconds: Int, now: Long = System.currentTimeMillis()) {
        store.edit {
            it[endsAtKey] = now + seconds * 1000L
            it[totalKey] = seconds
        }
    }

    /** "+30 s": extends a running rest, or starts a fresh one of [seconds] if it has already ended. */
    suspend fun add(seconds: Int, now: Long = System.currentTimeMillis()) {
        store.edit {
            val end = it[endsAtKey]
            if (end != null && end > now) {
                it[endsAtKey] = end + seconds * 1000L
                it[totalKey] = (it[totalKey] ?: 0) + seconds
            } else {
                it[endsAtKey] = now + seconds * 1000L
                it[totalKey] = seconds
            }
        }
    }

    suspend fun skip() {
        store.edit {
            it.remove(endsAtKey)
            it.remove(totalKey)
        }
    }

    /** Clears the rest only if it's still the one that ended, so a "+30 s" racing the alert wins. */
    suspend fun clearIfEndsAt(endsAt: Long) {
        store.edit {
            if (it[endsAtKey] == endsAt) {
                it.remove(endsAtKey)
                it.remove(totalKey)
            }
        }
    }
}
