package app.recomp.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.recomp.data.health.HealthFeature
import app.recomp.di.DeviceStore
import app.recomp.domain.nudge.ReminderKind
import app.recomp.domain.nudge.ReminderLedger
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** The reminder ledger as stored. Kinds are names, so one a later build adds or drops is just ignored. */
@Serializable
data class StoredLedger(
    @Serializable(with = LocalDateIso::class) val date: LocalDate,
    val sent: List<String> = emptyList(),
    val handled: Set<String> = emptySet(),
) {
    fun toLedger(): ReminderLedger {
        fun kind(name: String) = ReminderKind.entries.firstOrNull { it.name == name }
        return ReminderLedger(date, sent.mapNotNull(::kind), handled.mapNotNull(::kind).toSet())
    }

    companion object {
        fun of(l: ReminderLedger) = StoredLedger(l.date, l.sent.map { it.name }, l.handled.map { it.name }.toSet())
    }
}

/** When Health Connect was last read, and what went wrong if something did. */
data class HealthSyncState(val lastSyncAt: Long? = null, val error: String? = null)

/**
 * Device state (TRD §4): the `device` DataStore, which exports and Android's own backup leave out, so a restored or
 * transferred phone starts it fresh. M12 keeps the day's reminder ledger here; M13 keeps Health Connect's change
 * tokens and the like, so a restored phone reads Health Connect's last 30 days again.
 */
@Singleton
class DevicePrefs @Inject constructor(@DeviceStore private val store: DataStore<Preferences>) {
    private object Keys {
        val LEDGER = stringPreferencesKey("reminder_ledger")

        /** Health Connect's change token for a feature's record types (TRD §6.3). */
        fun changes(feature: HealthFeature) = stringPreferencesKey("hc_changes_${feature.name.lowercase()}")

        /** The database the tokens were issued against: its restore stamp (`PRAGMA application_id`, TRD §6.5). */
        val DATABASE_STAMP = intPreferencesKey("hc_database_stamp")

        /** InBody record ids already saved as a scan or dismissed (IB-07), so a full re-read doesn't bring them back. */
        val INBODY_HANDLED = stringSetPreferencesKey("hc_inbody_handled")
        val LAST_SYNC_AT = longPreferencesKey("hc_last_sync_at")
        val LAST_SYNC_ERROR = stringPreferencesKey("hc_last_sync_error")
    }

    // Reminders (M12)

    /** Null before the first reminder, or when the stored value can't be read: the day then starts fresh. */
    suspend fun ledger(): ReminderLedger? =
        store.data.first()[Keys.LEDGER]?.let { runCatching { settingsJson.decodeFromString<StoredLedger>(it).toLedger() }.getOrNull() }

    suspend fun setLedger(ledger: ReminderLedger) = store.edit { it[Keys.LEDGER] = settingsJson.encodeToString(StoredLedger.of(ledger)) }

    // Health Connect (M13)

    val healthSync: Flow<HealthSyncState> = store.data.map { HealthSyncState(it[Keys.LAST_SYNC_AT], it[Keys.LAST_SYNC_ERROR]) }.distinctUntilChanged()

    suspend fun changesToken(feature: HealthFeature): String? = store.data.first()[Keys.changes(feature)]

    suspend fun setChangesToken(feature: HealthFeature, token: String?) = store.edit { prefs ->
        if (token == null) prefs.remove(Keys.changes(feature)) else prefs[Keys.changes(feature)] = token
    }

    suspend fun inBodyHandled(): Set<String> = store.data.first()[Keys.INBODY_HANDLED].orEmpty()

    suspend fun addInBodyHandled(ids: Collection<String>) = store.edit { it[Keys.INBODY_HANDLED] = it[Keys.INBODY_HANDLED].orEmpty() + ids }

    /** Keeps only the handled ids Health Connect still has, so the set can't grow for ever. */
    suspend fun keepInBodyHandled(stillThere: Set<String>) = store.edit { it[Keys.INBODY_HANDLED] = it[Keys.INBODY_HANDLED].orEmpty() intersect stillThere }

    suspend fun setSynced(at: Long, error: String?) = store.edit { prefs ->
        prefs[Keys.LAST_SYNC_AT] = at
        if (error == null) prefs.remove(Keys.LAST_SYNC_ERROR) else prefs[Keys.LAST_SYNC_ERROR] = error
    }

    /** A sync that failed before reading anything: the error shows, and the last good sync's time stays. */
    suspend fun setSyncError(error: String) = store.edit { it[Keys.LAST_SYNC_ERROR] = error }

    /**
     * The tokens belong to the database they were issued against. After an import replaced it ([stamp] differs),
     * they're dropped, so the next sync reads the last 30 days again. Returns true when it dropped them.
     */
    suspend fun bindToDatabase(stamp: Int): Boolean {
        var dropped = false
        store.edit { prefs ->
            val bound = prefs[Keys.DATABASE_STAMP]
            if (bound != null && bound != stamp) {
                HealthFeature.entries.forEach { prefs.remove(Keys.changes(it)) }
                dropped = true
            }
            prefs[Keys.DATABASE_STAMP] = stamp
        }
        return dropped
    }
}
