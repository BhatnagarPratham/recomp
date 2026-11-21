package app.recomp.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.recomp.di.DeviceStore
import app.recomp.domain.nudge.ReminderKind
import app.recomp.domain.nudge.ReminderLedger
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
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

/**
 * Device state (TRD §4): the `device` DataStore, which exports and Android's own backup leave out. M12 keeps the
 * day's reminder ledger here, so a restored or transferred phone starts it fresh.
 */
@Singleton
class DevicePrefs @Inject constructor(@DeviceStore private val store: DataStore<Preferences>) {
    /** Null before the first reminder, or when the stored value can't be read: the day then starts fresh. */
    suspend fun ledger(): ReminderLedger? =
        store.data.first()[LEDGER]?.let { runCatching { settingsJson.decodeFromString<StoredLedger>(it).toLedger() }.getOrNull() }

    suspend fun setLedger(ledger: ReminderLedger) = store.edit { it[LEDGER] = settingsJson.encodeToString(StoredLedger.of(ledger)) }

    private companion object {
        val LEDGER = stringPreferencesKey("reminder_ledger")
    }
}
