package app.recomp.data.prefs

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.recomp.di.SettingsStore
import app.recomp.domain.ai.AiModel
import app.recomp.domain.body.ScanSlot
import app.recomp.domain.food.MealSlot
import app.recomp.domain.food.MealSlots
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.GoalPolicy
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.RampState
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.nutrition.TargetOverrides
import app.recomp.domain.persona.ActivityLevel
import app.recomp.domain.persona.Diet
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.FoodRegion
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Profile
import app.recomp.domain.persona.SplitTemplate
import app.recomp.domain.persona.Units
import app.recomp.domain.persona.WeightSource
import app.recomp.domain.training.ExtraPlan
import app.recomp.domain.training.LogDates
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * An extra session's schedule. [days] are ISO day numbers, Monday = 1. Its name and kind live on the routine.
 * The stored classes check their own values, so a value that can't become a plan never decodes: reads treat it
 * as unreadable (TRD §4) and an import refuses it (§6.5), instead of the app crashing when it's used.
 */
@Serializable
data class ExtraSchedule(val routineId: Long, val days: Set<Int> = emptySet()) {
    init {
        require(days.all { it in 1..7 }) { "Weekdays are 1 to 7, not $days" }
    }

    fun toPlan() = ExtraPlan(routineId, days.map(DayOfWeek::of).toSet())
}

object LocalDateIso : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

enum class FinishKind { GOAL_WEIGHT, BODY_FAT, DATE }

@Serializable
data class StoredFinishLine(
    val kind: FinishKind,
    val bodyFatPct: Double? = null,
    @Serializable(with = LocalDateIso::class) val date: LocalDate? = null,
)

/** [GoalPlan] as stored. The policy's values are stored too, so custom values under Advanced survive. */
@Serializable
data class StoredGoal(
    val goal: Goal,
    val pace: Pace? = null,
    val lossRatePctPerWeek: Double = 0.0,
    val surplusFraction: Double = 0.0,
    val proteinGPerKg: Double,
    val goalWeightKg: Double? = null,
    val finishLine: StoredFinishLine? = null,
    @Serializable(with = LocalDateIso::class) val startedOn: LocalDate,
    val aggressiveAcknowledged: Boolean = false,
) {
    init {
        // GoalPolicy's own rules: rates are magnitudes, and a policy can't both lose and gain.
        GoalPolicy(goal, lossRatePctPerWeek, surplusFraction, proteinGPerKg)
        require(proteinGPerKg.isFinite() && proteinGPerKg > 0) { "Protein per kg must be positive" }
    }

    fun toPlan() = GoalPlan(
        policy = GoalPolicy(goal, lossRatePctPerWeek, surplusFraction, proteinGPerKg),
        pace = pace,
        goalWeightKg = goalWeightKg,
        finishLine = when (finishLine?.kind) {
            null -> null
            FinishKind.GOAL_WEIGHT -> FinishLine.GoalWeight
            FinishKind.BODY_FAT -> finishLine.bodyFatPct?.let(FinishLine::BodyFat)
            FinishKind.DATE -> finishLine.date?.let(FinishLine::ByDate)
        },
        startedOn = startedOn,
        aggressiveAcknowledged = aggressiveAcknowledged,
    )

    companion object {
        fun of(plan: GoalPlan) = StoredGoal(
            goal = plan.goal,
            pace = plan.pace,
            lossRatePctPerWeek = plan.policy.lossRatePctPerWeek,
            surplusFraction = plan.policy.surplusFraction,
            proteinGPerKg = plan.policy.proteinGPerKg,
            goalWeightKg = plan.goalWeightKg,
            finishLine = when (val f = plan.finishLine) {
                null -> null
                FinishLine.GoalWeight -> StoredFinishLine(FinishKind.GOAL_WEIGHT)
                is FinishLine.BodyFat -> StoredFinishLine(FinishKind.BODY_FAT, bodyFatPct = f.pct)
                is FinishLine.ByDate -> StoredFinishLine(FinishKind.DATE, date = f.date)
            },
            startedOn = plan.startedOn,
            aggressiveAcknowledged = plan.aggressiveAcknowledged,
        )
    }
}

/**
 * The persona as stored (TRD §4: the `persona` key). Fields are nullable or defaulted because an
 * install upgraded from M1/M2 has a training setup but no goal yet; [onboardedOn] marks a complete one.
 * Extra sessions are stored by routine id: their names and kinds live on the routines.
 */
@Serializable
data class StoredPersona(
    val goal: StoredGoal? = null,
    val experience: Experience = Experience.BEGINNER,
    val diet: Diet? = null,
    val region: FoodRegion? = null,
    val split: SplitTemplate? = null,
    val strengthDaysPerWeek: Int = 3,
    val extras: List<ExtraSchedule> = emptyList(),
    val weightSource: WeightSource = WeightSource.HOME_SCALE,
    val units: Units = Units.METRIC,
    val proteinRamp: Boolean? = null,
    val presetKey: String? = null,
    @Serializable(with = LocalDateIso::class) val onboardedOn: LocalDate? = null,
) {
    val onboarded: Boolean get() = onboardedOn != null && goal != null && diet != null && region != null && split != null
}

/** Body data (PR-01) as stored under `profile`. Never part of the persona (PS-06). */
@Serializable
data class StoredProfile(
    val sex: Sex,
    @Serializable(with = LocalDateIso::class) val birthDate: LocalDate,
    val heightCm: Double,
    val weightKg: Double,
    val activity: ActivityLevel = ActivityLevel.MODERATE,
) {
    fun toProfile() = Profile(sex, birthDate, heightCm, weightKg, activity)

    companion object {
        fun of(p: Profile) = StoredProfile(p.sex, p.birthDate, p.heightCm, p.weightKg, p.activity)
    }
}

@Serializable
data class StoredOverrides(val kcal: Int? = null, val proteinG: Int? = null, val fatG: Int? = null) {
    fun toOverrides() = TargetOverrides(kcal, proteinG, fatG)

    companion object {
        fun of(o: TargetOverrides) = StoredOverrides(o.kcal, o.proteinG, o.fatG)
    }
}

@Serializable
data class StoredRamp(
    @Serializable(with = LocalDateIso::class) val countFrom: LocalDate,
    @Serializable(with = LocalDateIso::class) val baselineEnd: LocalDate? = null,
    val baselineMeanG: Int? = null,
) {
    fun toState() = RampState(countFrom, baselineEnd, baselineMeanG)

    companion object {
        fun of(s: RampState) = StoredRamp(s.countFrom, s.baselineEnd, s.baselineMeanG)
    }
}

/** A meal slot as stored under `meal_slots` (NU-10). */
@Serializable
data class StoredMealSlot(val key: String, val name: String, val removed: Boolean = false) {
    fun toSlot() = MealSlot(key, name, removed)

    companion object {
        fun of(slot: MealSlot) = StoredMealSlot(slot.key, slot.name, slot.removed)
    }
}

/**
 * AI settings (AI-01, AI-03, TRD §4). [monthlyCapUsd] null is no cap. The API key isn't here: it's encrypted
 * in its own `secrets` store (`AiKeyStore`).
 */
data class AiSettings(
    val enabled: Boolean = false,
    val model: AiModel = AiModel.DEFAULT,
    val monthlyCapUsd: Double? = null,
    val noticeSeen: Boolean = false,
)

/** The usual scan slot (IB-02) as stored under `scan_slot`: an ISO weekday and minutes after midnight. */
@Serializable
data class StoredScanSlot(val day: Int, val minutes: Int) {
    init {
        require(day in 1..7 && minutes in 0 until 24 * 60) { "No such slot: day $day, minute $minutes" }
    }

    fun toSlot() = ScanSlot(DayOfWeek.of(day), LocalTime.of(minutes / 60, minutes % 60))

    companion object {
        fun of(slot: ScanSlot) = StoredScanSlot(slot.day.value, slot.time.hour * 60 + slot.time.minute)
    }
}

/** What training reads from the persona: the part M1's interim setup used to store on its own. */
data class TrainingSettings(
    val split: SplitTemplate? = null,
    val experience: Experience = Experience.BEGINNER,
    val strengthDaysPerWeek: Int = 3,
    val extras: List<ExtraSchedule> = emptyList(),
    val keepScreenOn: Boolean = false,
) {
    val setupDone: Boolean get() = split != null
}

internal val settingsJson = Json { ignoreUnknownKeys = true }

internal object SettingsKeys {
    val PERSONA = stringPreferencesKey("persona")
    val PROFILE = stringPreferencesKey("profile")
    val DAY_START_MINUTES = intPreferencesKey("day_start_minutes")
    val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    val TARGET_OVERRIDES = stringPreferencesKey("target_overrides")
    val PROTEIN_RAMP = stringPreferencesKey("protein_ramp")
    val MEAL_SLOTS = stringPreferencesKey("meal_slots")

    /** Household measures you calibrated, label → grams (NU-12). Missing labels use the region's defaults. */
    val PORTION_SIZES = stringPreferencesKey("portion_sizes")

    /** IB-02: the usual scan weekday and time. */
    val SCAN_SLOT = stringPreferencesKey("scan_slot")

    /** The Monday of the latest weekly check-in you opened (PRD §12.1), so Today's card goes once it's seen. */
    val CHECKIN_SEEN = stringPreferencesKey("checkin_seen")

    /** The bundled food files last imported (TRD §6.4), so launches only re-import when they change. */
    val FOOD_DATA_VERSION = stringPreferencesKey("food_data_version")

    /** AI-01: `ON` or `OFF`; absent is off. */
    val AI_MODE = stringPreferencesKey("ai_mode")
    val AI_MODEL = stringPreferencesKey("ai_model")

    /** AI-03's monthly cap in dollars; absent is no cap. */
    val AI_MONTHLY_CAP_USD = doublePreferencesKey("ai_monthly_cap_usd")

    /** The one-time privacy notice before the first estimate (TRD §6.2). */
    val AI_NOTICE_SEEN = booleanPreferencesKey("ai_notice_seen")

    /** M1/M2's `TrainingSettings` JSON, moved into [PERSONA] by [LegacyTrainingMigration]. */
    val LEGACY_TRAINING = stringPreferencesKey("training")

    /** Where a value that no longer decodes is kept, untouched, before anything is written over it. */
    fun unreadableCopy(key: Preferences.Key<String>) = stringPreferencesKey("${key.name}_unreadable")

    /**
     * Settings that never leave the device in an export (TRD §6.5). Secrets live in their own store, never
     * here; this list and [looksSecret] are the safety net if one ever lands in `settings`.
     */
    val NOT_EXPORTED: Set<String> = emptySet()

    private val SECRET_NAME = Regex("(?i)(api[_-]?key|secret|token|password|keyset)")

    fun looksSecret(name: String): Boolean = SECRET_NAME.containsMatchIn(name)

    fun exported(name: String): Boolean = name !in NOT_EXPORTED && !looksSecret(name)
}

/**
 * Moves M1/M2's `training` JSON into the persona and `keep_screen_on`, then deletes it (TRD §4). Runs
 * once, before the first read after the upgrade, inside DataStore's own write, so it's atomic.
 */
object LegacyTrainingMigration : DataMigration<Preferences> {
    @Serializable
    private data class LegacyTrainingSettings(
        val split: SplitTemplate? = null,
        val experience: Experience = Experience.BEGINNER,
        val strengthDaysPerWeek: Int = 3,
        val extras: List<ExtraSchedule> = emptyList(),
        val keepScreenOn: Boolean = false,
    )

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = currentData.contains(SettingsKeys.LEGACY_TRAINING)

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        val legacy = currentData[SettingsKeys.LEGACY_TRAINING]?.let { runCatching { settingsJson.decodeFromString<LegacyTrainingSettings>(it) }.getOrNull() }
        if (legacy != null && !currentData.contains(SettingsKeys.PERSONA)) {
            val persona = StoredPersona(
                experience = legacy.experience,
                split = legacy.split,
                strengthDaysPerWeek = legacy.strengthDaysPerWeek,
                extras = legacy.extras,
            )
            prefs[SettingsKeys.PERSONA] = settingsJson.encodeToString(persona)
            prefs[SettingsKeys.KEEP_SCREEN_ON] = legacy.keepScreenOn
        }
        prefs.remove(SettingsKeys.LEGACY_TRAINING)
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

/**
 * Settings (TRD §4: DataStore): the persona, body data, "day starts at", keep-screen-on, target
 * overrides and the protein ramp's state. One key each, so a write to one never races another.
 */
@Singleton
class SettingsPrefs @Inject constructor(@SettingsStore private val store: DataStore<Preferences>) {
    private companion object {
        const val AI_ON = "ON"
        const val AI_OFF = "OFF"
    }

    private inline fun <reified T> Preferences.decode(key: Preferences.Key<String>): T? =
        this[key]?.let { runCatching { settingsJson.decodeFromString<T>(it) }.getOrNull() }

    private inline fun <reified T> Preferences.unreadable(key: Preferences.Key<String>): Boolean =
        this[key]?.let { runCatching { settingsJson.decodeFromString<T>(it) }.isFailure } ?: false

    /**
     * The JSON settings that are stored but no longer decode, e.g. after a downgrade from a build with a
     * new enum value. Reads fall back to defaults, so onboarding opens; the raw value is never lost.
     */
    private fun Preferences.unreadableKeys(): List<Preferences.Key<String>> = buildList {
        if (unreadable<StoredPersona>(SettingsKeys.PERSONA)) add(SettingsKeys.PERSONA)
        if (unreadable<StoredProfile>(SettingsKeys.PROFILE)) add(SettingsKeys.PROFILE)
        if (unreadable<StoredOverrides>(SettingsKeys.TARGET_OVERRIDES)) add(SettingsKeys.TARGET_OVERRIDES)
        if (unreadable<StoredRamp>(SettingsKeys.PROTEIN_RAMP)) add(SettingsKeys.PROTEIN_RAMP)
        if (unreadable<List<StoredMealSlot>>(SettingsKeys.MEAL_SLOTS)) add(SettingsKeys.MEAL_SLOTS)
        if (unreadable<Map<String, Double>>(SettingsKeys.PORTION_SIZES)) add(SettingsKeys.PORTION_SIZES)
        if (unreadable<StoredScanSlot>(SettingsKeys.SCAN_SLOT)) add(SettingsKeys.SCAN_SLOT)
    }

    /** Copies each unreadable value aside (once) before a write could replace it with a default. */
    private fun MutablePreferences.keepUnreadable() {
        unreadableKeys().forEach { key ->
            val copy = SettingsKeys.unreadableCopy(key)
            if (!contains(copy)) this[copy] = this[key]!!
        }
    }

    /** Names of the settings that couldn't be read, so the app can say so. */
    val unreadable: Flow<List<String>> = store.data.map { prefs -> prefs.unreadableKeys().map { it.name } }.distinctUntilChanged()

    val persona: Flow<StoredPersona> = store.data.map { it.decode<StoredPersona>(SettingsKeys.PERSONA) ?: StoredPersona() }.distinctUntilChanged()

    val profile: Flow<Profile?> = store.data.map { it.decode<StoredProfile>(SettingsKeys.PROFILE)?.toProfile() }.distinctUntilChanged()

    /** PR-03. */
    val dayStart: Flow<LocalTime> = store.data.map { prefs ->
        prefs[SettingsKeys.DAY_START_MINUTES]?.takeIf { it in 0 until 24 * 60 }?.let { LocalTime.of(it / 60, it % 60) } ?: LogDates.DEFAULT_DAY_START
    }.distinctUntilChanged()

    val overrides: Flow<TargetOverrides> =
        store.data.map { it.decode<StoredOverrides>(SettingsKeys.TARGET_OVERRIDES)?.toOverrides() ?: TargetOverrides() }.distinctUntilChanged()

    val ramp: Flow<RampState?> = store.data.map { it.decode<StoredRamp>(SettingsKeys.PROTEIN_RAMP)?.toState() }.distinctUntilChanged()

    /** NU-10: the default four until you edit them. */
    val mealSlots: Flow<List<MealSlot>> = store.data.map { prefs ->
        prefs.decode<List<StoredMealSlot>>(SettingsKeys.MEAL_SLOTS)?.map { it.toSlot() }?.takeIf { it.isNotEmpty() } ?: MealSlots.DEFAULT
    }.distinctUntilChanged()

    /** NU-12: household measures you calibrated. */
    val portionSizes: Flow<Map<String, Double>> =
        store.data.map { it.decode<Map<String, Double>>(SettingsKeys.PORTION_SIZES) ?: emptyMap() }.distinctUntilChanged()

    /** IB-02. Null until set; the first scan sets it. */
    val scanSlot: Flow<ScanSlot?> =
        store.data.map { prefs -> prefs.decode<StoredScanSlot>(SettingsKeys.SCAN_SLOT)?.let { runCatching { it.toSlot() }.getOrNull() } }.distinctUntilChanged()

    /** The week of the latest check-in you opened; null before the first. */
    val checkinSeen: Flow<LocalDate?> =
        store.data.map { prefs -> prefs[SettingsKeys.CHECKIN_SEEN]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } }.distinctUntilChanged()

    suspend fun setCheckinSeen(weekStart: LocalDate) = store.edit { it[SettingsKeys.CHECKIN_SEEN] = weekStart.toString() }

    suspend fun setScanSlot(slot: ScanSlot) = store.edit { prefs ->
        prefs.keepUnreadable()
        prefs[SettingsKeys.SCAN_SLOT] = settingsJson.encodeToString(StoredScanSlot.of(slot))
    }

    /**
     * The settings in [prefs] this version can't use (TRD §6.5): a known key holding the wrong type, JSON that
     * doesn't decode, or a value out of range. Import refuses a backup with any, so a bad file can't stop the
     * app from starting.
     */
    fun problems(prefs: Preferences): List<String> {
        val values = prefs.asMap().mapKeys { it.key.name }
        fun wrongType(key: Preferences.Key<*>, type: kotlin.reflect.KClass<*>) = values[key.name]?.let { !type.isInstance(it) } ?: false
        fun <T> decodes(key: Preferences.Key<String>, decode: (String) -> T, valid: (T) -> Boolean = { true }): Boolean =
            (values[key.name] as? String)?.let { text -> runCatching { valid(decode(text)) }.getOrDefault(false) } ?: true
        val strings = listOf(
            SettingsKeys.PERSONA, SettingsKeys.PROFILE, SettingsKeys.TARGET_OVERRIDES, SettingsKeys.PROTEIN_RAMP, SettingsKeys.MEAL_SLOTS,
            SettingsKeys.PORTION_SIZES, SettingsKeys.SCAN_SLOT, SettingsKeys.FOOD_DATA_VERSION, SettingsKeys.LEGACY_TRAINING,
            SettingsKeys.AI_MODE, SettingsKeys.AI_MODEL, SettingsKeys.CHECKIN_SEEN,
        )
        return buildList {
            strings.filter { wrongType(it, String::class) }.forEach { add(it.name) }
            if (wrongType(SettingsKeys.DAY_START_MINUTES, Int::class) || (values[SettingsKeys.DAY_START_MINUTES.name] as? Int)?.let { it !in 0 until 24 * 60 } == true) {
                add(SettingsKeys.DAY_START_MINUTES.name)
            }
            if (wrongType(SettingsKeys.KEEP_SCREEN_ON, Boolean::class)) add(SettingsKeys.KEEP_SCREEN_ON.name)
            if (wrongType(SettingsKeys.AI_MONTHLY_CAP_USD, Double::class)) add(SettingsKeys.AI_MONTHLY_CAP_USD.name)
            if (wrongType(SettingsKeys.AI_NOTICE_SEEN, Boolean::class)) add(SettingsKeys.AI_NOTICE_SEEN.name)
            // Decoding checks the stored classes' own rules; converting checks what the screens will do with them.
            if (!decodes(SettingsKeys.PERSONA, { settingsJson.decodeFromString<StoredPersona>(it).also { p -> p.goal?.toPlan(); p.extras.forEach(ExtraSchedule::toPlan) } })) {
                add(SettingsKeys.PERSONA.name)
            }
            if (!decodes(SettingsKeys.PROFILE, { settingsJson.decodeFromString<StoredProfile>(it).toProfile() })) add(SettingsKeys.PROFILE.name)
            if (!decodes(SettingsKeys.TARGET_OVERRIDES, { settingsJson.decodeFromString<StoredOverrides>(it) })) add(SettingsKeys.TARGET_OVERRIDES.name)
            if (!decodes(SettingsKeys.PROTEIN_RAMP, { settingsJson.decodeFromString<StoredRamp>(it) })) add(SettingsKeys.PROTEIN_RAMP.name)
            if (!decodes(SettingsKeys.MEAL_SLOTS, { settingsJson.decodeFromString<List<StoredMealSlot>>(it) })) add(SettingsKeys.MEAL_SLOTS.name)
            if (!decodes(SettingsKeys.PORTION_SIZES, { settingsJson.decodeFromString<Map<String, Double>>(it) })) add(SettingsKeys.PORTION_SIZES.name)
            if (!decodes(SettingsKeys.SCAN_SLOT, { settingsJson.decodeFromString<StoredScanSlot>(it).toSlot() })) add(SettingsKeys.SCAN_SLOT.name)
            if (!decodes(SettingsKeys.CHECKIN_SEEN, LocalDate::parse)) add(SettingsKeys.CHECKIN_SEEN.name)
        }.distinct()
    }

    /** Every setting an export carries (DA-01, TRD §6.5): all of them except the ones that stay on the device. */
    suspend fun exportable(): Preferences {
        val all = store.data.first()
        return all.toMutablePreferences().apply { all.asMap().keys.filter { !SettingsKeys.exported(it.name) }.forEach { remove(it) } }.toPreferences()
    }

    /**
     * Replaces every setting with [imported] (TRD §6.5), keeping the ones that never leave the device. Older
     * backups get the same DataStore migrations an upgraded install does.
     */
    suspend fun replaceAll(imported: Preferences) {
        var incoming = imported.toMutablePreferences().apply { imported.asMap().keys.filter { !SettingsKeys.exported(it.name) }.forEach { remove(it) } }.toPreferences()
        if (LegacyTrainingMigration.shouldMigrate(incoming)) incoming = LegacyTrainingMigration.migrate(incoming)
        store.edit { prefs ->
            val kept = prefs.asMap().filterKeys { !SettingsKeys.exported(it.name) }
            prefs.clear()
            prefs += incoming
            kept.forEach { (key, value) -> @Suppress("UNCHECKED_CAST") prefs[key as Preferences.Key<Any>] = value }
        }
    }

    suspend fun foodDataVersion(): String? = store.data.first()[SettingsKeys.FOOD_DATA_VERSION]

    suspend fun setFoodDataVersion(version: String) = store.edit { it[SettingsKeys.FOOD_DATA_VERSION] = version }

    val training: Flow<TrainingSettings> = store.data.map { prefs ->
        val p = prefs.decode<StoredPersona>(SettingsKeys.PERSONA) ?: StoredPersona()
        TrainingSettings(p.split, p.experience, p.strengthDaysPerWeek, p.extras, prefs[SettingsKeys.KEEP_SCREEN_ON] ?: false)
    }.distinctUntilChanged()

    suspend fun currentPersona(): StoredPersona = persona.first()
    suspend fun currentTraining(): TrainingSettings = training.first()
    suspend fun currentDayStart(): LocalTime = dayStart.first()

    suspend fun updatePersona(transform: (StoredPersona) -> StoredPersona) = store.edit { prefs ->
        prefs.keepUnreadable()
        prefs[SettingsKeys.PERSONA] = settingsJson.encodeToString(transform(prefs.decode<StoredPersona>(SettingsKeys.PERSONA) ?: StoredPersona()))
    }

    /** Several settings in one atomic write, e.g. finishing onboarding. */
    suspend fun edit(block: SettingsEditor.() -> Unit) = store.edit { prefs ->
        prefs.keepUnreadable()
        SettingsEditor(prefs).block()
    }

    suspend fun setKeepScreenOn(on: Boolean) = store.edit { it[SettingsKeys.KEEP_SCREEN_ON] = on }

    val ai: Flow<AiSettings> = store.data.map { prefs ->
        AiSettings(
            enabled = prefs[SettingsKeys.AI_MODE] == AI_ON,
            model = AiModel.of(prefs[SettingsKeys.AI_MODEL]),
            monthlyCapUsd = prefs[SettingsKeys.AI_MONTHLY_CAP_USD]?.takeIf { it >= 0 },
            noticeSeen = prefs[SettingsKeys.AI_NOTICE_SEEN] ?: false,
        )
    }.distinctUntilChanged()

    suspend fun setAiEnabled(on: Boolean) = store.edit { it[SettingsKeys.AI_MODE] = if (on) AI_ON else AI_OFF }

    suspend fun setAiModel(model: AiModel) = store.edit { it[SettingsKeys.AI_MODEL] = model.id }

    suspend fun setAiMonthlyCap(usd: Double?) = store.edit { prefs ->
        if (usd == null) prefs.remove(SettingsKeys.AI_MONTHLY_CAP_USD) else prefs[SettingsKeys.AI_MONTHLY_CAP_USD] = usd
    }

    suspend fun setAiNoticeSeen() = store.edit { it[SettingsKeys.AI_NOTICE_SEEN] = true }

    inner class SettingsEditor internal constructor(private val prefs: MutablePreferences) {
        var persona: StoredPersona
            get() = prefs.decode<StoredPersona>(SettingsKeys.PERSONA) ?: StoredPersona()
            set(value) { prefs[SettingsKeys.PERSONA] = settingsJson.encodeToString(value) }

        fun setProfile(profile: Profile) {
            prefs[SettingsKeys.PROFILE] = settingsJson.encodeToString(StoredProfile.of(profile))
        }

        fun setDayStart(time: LocalTime) {
            prefs[SettingsKeys.DAY_START_MINUTES] = time.hour * 60 + time.minute
        }

        fun setOverrides(overrides: TargetOverrides) {
            if (overrides.isEmpty) prefs.remove(SettingsKeys.TARGET_OVERRIDES)
            else prefs[SettingsKeys.TARGET_OVERRIDES] = settingsJson.encodeToString(StoredOverrides.of(overrides))
        }

        var mealSlots: List<MealSlot>
            get() = prefs.decode<List<StoredMealSlot>>(SettingsKeys.MEAL_SLOTS)?.map { it.toSlot() }?.takeIf { it.isNotEmpty() } ?: MealSlots.DEFAULT
            set(value) { prefs[SettingsKeys.MEAL_SLOTS] = settingsJson.encodeToString(value.map(StoredMealSlot::of)) }

        var portionSizes: Map<String, Double>
            get() = prefs.decode<Map<String, Double>>(SettingsKeys.PORTION_SIZES) ?: emptyMap()
            set(value) {
                if (value.isEmpty()) prefs.remove(SettingsKeys.PORTION_SIZES)
                else prefs[SettingsKeys.PORTION_SIZES] = settingsJson.encodeToString(value)
            }

        fun setRamp(state: RampState?) {
            if (state == null) prefs.remove(SettingsKeys.PROTEIN_RAMP)
            else prefs[SettingsKeys.PROTEIN_RAMP] = settingsJson.encodeToString(StoredRamp.of(state))
        }
    }
}
