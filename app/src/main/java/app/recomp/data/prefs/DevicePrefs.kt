package app.recomp.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.recomp.data.health.HealthFeature
import app.recomp.di.DeviceStore
import app.recomp.domain.export.Written
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

/** When Recomp last wrote to Health Connect (DA-04), and what went wrong if something did. */
data class HealthShareState(val lastWriteAt: Long? = null, val error: String? = null)

/**
 * Where the automatic backup goes (DA-02): a folder on the phone ([folder] true, a tree address) or one file that's
 * overwritten each week (a document address, for Google Drive). The address and its permission belong to this
 * phone, so they're device state.
 */
data class BackupTarget(val uri: String, val folder: Boolean, val label: String)

/** The automatic backup's last outcome: when it last worked, and why the latest try didn't. */
data class AutoBackupState(val target: BackupTarget? = null, val lastSuccessAt: Long? = null, val error: String? = null)

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

        /** DA-04: the records written to Health Connect in the sync window, as `id → date|hash` JSON. */
        val HC_WRITTEN = stringPreferencesKey("hc_written")

        /**
         * DA-04's switch: Settings → Health Connect → *Share with other apps*. Device state, not a setting, so a
         * backup or the safety copy can never turn it back on: sharing always starts with your own tap here.
         */
        val HC_SHARE = booleanPreferencesKey("hc_share")
        val HC_LAST_WRITE_AT = longPreferencesKey("hc_last_write_at")
        val HC_WRITE_ERROR = stringPreferencesKey("hc_write_error")

        /** DA-02: the automatic backup's target and its last outcome. */
        val BACKUP_URI = stringPreferencesKey("backup_uri")
        val BACKUP_IS_FOLDER = booleanPreferencesKey("backup_is_folder")
        val BACKUP_LABEL = stringPreferencesKey("backup_label")
        val BACKUP_LAST_AT = longPreferencesKey("backup_last_at")
        val BACKUP_ERROR = stringPreferencesKey("backup_error")
    }

    // Health Connect writes (M16, DA-04)

    /** Whether Recomp writes to Health Connect. Off until you turn it on on this phone. */
    val shareOn: Flow<Boolean> = store.data.map { it[Keys.HC_SHARE] ?: false }.distinctUntilChanged()

    suspend fun setShareOn(on: Boolean) = store.edit { it[Keys.HC_SHARE] = on }

    val healthShare: Flow<HealthShareState> = store.data.map { HealthShareState(it[Keys.HC_LAST_WRITE_AT], it[Keys.HC_WRITE_ERROR]) }.distinctUntilChanged()

    /** What's been written in the window. An unreadable value reads as nothing written: the next sync writes it all again. */
    suspend fun written(): Map<String, Written> = store.data.first()[Keys.HC_WRITTEN]?.let { text ->
        runCatching {
            settingsJson.decodeFromString<Map<String, String>>(text).mapNotNull { (id, value) ->
                val (date, hash) = value.split('|').takeIf { it.size == 2 } ?: return@mapNotNull null
                id to Written(LocalDate.parse(date), hash)
            }.toMap()
        }.getOrNull()
    }.orEmpty()

    suspend fun setWritten(written: Map<String, Written>, at: Long) = store.edit { prefs ->
        prefs[Keys.HC_WRITTEN] = settingsJson.encodeToString(written.mapValues { (_, w) -> "${w.date}|${w.hash}" })
        prefs[Keys.HC_LAST_WRITE_AT] = at
        prefs.remove(Keys.HC_WRITE_ERROR)
    }

    suspend fun setWriteError(error: String) = store.edit { it[Keys.HC_WRITE_ERROR] = error }

    /** Sharing was turned off: what was written is forgotten, so turning it on again writes the last 30 days afresh. */
    suspend fun clearWritten() = store.edit { prefs ->
        prefs.remove(Keys.HC_WRITTEN)
        prefs.remove(Keys.HC_WRITE_ERROR)
    }

    // Automatic backup (M16, DA-02)

    val autoBackup: Flow<AutoBackupState> = store.data.map { prefs ->
        val target = prefs[Keys.BACKUP_URI]?.let { BackupTarget(it, prefs[Keys.BACKUP_IS_FOLDER] ?: true, prefs[Keys.BACKUP_LABEL].orEmpty()) }
        AutoBackupState(target, prefs[Keys.BACKUP_LAST_AT], prefs[Keys.BACKUP_ERROR])
    }.distinctUntilChanged()

    suspend fun setBackupTarget(target: BackupTarget?) = store.edit { prefs ->
        if (target == null) {
            listOf(Keys.BACKUP_URI, Keys.BACKUP_IS_FOLDER, Keys.BACKUP_LABEL, Keys.BACKUP_ERROR).forEach { prefs.remove(it) }
        } else {
            prefs[Keys.BACKUP_URI] = target.uri
            prefs[Keys.BACKUP_IS_FOLDER] = target.folder
            prefs[Keys.BACKUP_LABEL] = target.label
            prefs.remove(Keys.BACKUP_ERROR)
        }
    }

    suspend fun setBackedUp(at: Long) = store.edit { prefs ->
        prefs[Keys.BACKUP_LAST_AT] = at
        prefs.remove(Keys.BACKUP_ERROR)
    }

    suspend fun setBackupError(error: String) = store.edit { it[Keys.BACKUP_ERROR] = error }

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
