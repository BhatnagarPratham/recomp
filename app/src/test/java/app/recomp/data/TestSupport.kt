package app.recomp.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.recomp.data.ai.AiKeyStore
import app.recomp.data.ai.Connectivity
import app.recomp.data.ai.SecretCipher
import app.recomp.data.backup.BackupManager
import app.recomp.data.db.AppDatabase
import app.recomp.data.food.FoodFiles
import app.recomp.data.food.FoodSeeder
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.prefs.LegacyTrainingMigration
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.data.repo.AiRepository
import app.recomp.data.repo.BodyRepository
import app.recomp.data.repo.CheckinRepository
import app.recomp.data.repo.FoodLogRepository
import app.recomp.data.repo.FoodRepository
import app.recomp.data.repo.NutritionRepository
import app.recomp.data.repo.PersonaRepository
import app.recomp.data.repo.ProgressionRepository
import app.recomp.data.repo.TrainingRepository
import app.recomp.data.repo.WorkoutRepository
import app.recomp.data.seed.SeedData
import app.recomp.data.seed.SeedSource
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.EstimatorReply
import app.recomp.domain.ai.MealEstimate
import app.recomp.domain.ai.MealEstimator
import app.recomp.domain.ai.MealRequest
import app.recomp.domain.ai.TokenUsage
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The exported schemas in app/schemas (TRD §4), for building a database as an older version left it. */
object Schemas {
    val dir = File("schemas/app.recomp.data.db.AppDatabase")

    val current: Int get() = dir.listFiles()!!.maxOf { it.nameWithoutExtension.toInt() }

    fun of(version: Int) = Json.parseToJsonElement(File(dir, "$version.json").readText()).jsonObject.getValue("database").jsonObject

    /** Creates an empty database exactly as Room would have at [version], from the exported schema. */
    fun createAt(version: Int, file: File): SQLiteDatabase {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val s = of(version)
        s.getValue("entities").jsonArray.forEach { entity ->
            val e = entity.jsonObject
            val table = e.getValue("tableName").jsonPrimitive.content
            db.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            e["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            // FTS tables keep their content table in sync with triggers (v5's food_fts).
            e["contentSyncTriggers"]?.jsonArray?.forEach { db.execSQL(it.jsonPrimitive.content) }
        }
        s.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = version
        return db
    }
}

/** Reads the real seed files from the source tree; Gradle runs unit tests from the module directory. */
object FileSeedSource : SeedSource {
    private val exercises by lazy { SeedData.parseExercises(File("src/main/assets/seed/exercises.json").readText()) }
    private val templates by lazy { SeedData.parseTemplates(File("src/main/assets/seed/split_templates.json").readText()) }
    override fun exercises() = exercises
    override fun templates() = templates
}

/** The real basics from the source tree, plus an INDB stand-in a test can set (the real file is never committed). */
class TestFoodFiles : FoodFiles {
    var seedsText: String = File("src/main/assets/seed/foods.json").readText()
    var indbText: String? = null
    override fun seeds() = seedsText
    override fun indb() = indbText
}

/** Stands in for Tink: reversible, but only with the same associated data, so a mixed-up secret fails to open. */
class FakeCipher : SecretCipher {
    var broken = false

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = associatedData + byteArrayOf(0) + plaintext.reversedArray()

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        check(!broken) { "This phone's Keystore key is gone" }
        val prefix = associatedData + byteArrayOf(0)
        check(ciphertext.take(prefix.size) == prefix.toList()) { "Wrong associated data" }
        return ciphertext.drop(prefix.size).toByteArray().reversedArray()
    }
}

class FakeConnectivity : Connectivity {
    override val online = MutableStateFlow(true)
}

/** A clock a test can move, in a fixed zone. */
class TestClock(var instant: Instant = Instant.parse("2026-10-18T06:30:00Z"), private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = TestClock(instant, zone)
    override fun instant(): Instant = instant
}

/** Claude, scripted: each request gets [next]'s reply. [hold] makes requests wait until a test completes it. */
class FakeEstimator : MealEstimator {
    val requests = mutableListOf<MealRequest>()
    var next: (MealRequest) -> EstimatorReply = { reply(AiStatus.OK, MealEstimate(emptyList(), null)) }
    var hold: CompletableDeferred<Unit>? = null

    override suspend fun estimate(request: MealRequest): EstimatorReply {
        requests += request
        hold?.await()
        return next(request)
    }

    companion object {
        fun reply(
            status: AiStatus,
            estimate: MealEstimate? = null,
            attempts: List<TokenUsage> = listOf(TokenUsage("claude-opus-5-5", 2_000, 1_000)),
            servedBy: String = "claude-opus-5-5",
        ) = EstimatorReply(status, estimate, servedBy, attempts, latencyMs = 1_234, responseText = estimate?.let { "{\"items\":[]}" })
    }
}

/** A settings store whose writes can be made to fail, as on a full disk. */
class FlakyStore(private val inner: DataStore<Preferences>) : DataStore<Preferences> {
    var failWrites = false
    override val data: Flow<Preferences> = inner.data

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        if (failWrites) throw IOException("No space left on device")
        return inner.updateData(transform)
    }
}

/** A file-backed database and settings store, so a test can "kill the app" by closing and reopening. */
class TestApp(private val dir: File) {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbFile = File(dir, "recomp-test.db")
    /** The settings store itself, so a test can plant a key the app never writes there, or make its writes fail. */
    val store = FlakyStore(PreferenceDataStoreFactory.create(migrations = listOf(LegacyTrainingMigration)) { File(dir, "settings.preferences_pb") })
    val prefs = SettingsPrefs(store)
    val days = DayBoundary(prefs)
    val foodFiles = TestFoodFiles()
    val cipher = FakeCipher()
    val keys = AiKeyStore(PreferenceDataStoreFactory.create { File(dir, "secrets.preferences_pb") }, cipher)
    val connectivity = FakeConnectivity()
    val estimator = FakeEstimator()
    val clock = TestClock()

    lateinit var db: AppDatabase
        private set
    lateinit var training: TrainingRepository
        private set
    lateinit var workouts: WorkoutRepository
        private set
    lateinit var progression: ProgressionRepository
        private set
    lateinit var personas: PersonaRepository
        private set
    lateinit var nutrition: NutritionRepository
        private set
    lateinit var foods: FoodRepository
        private set
    lateinit var foodLog: FoodLogRepository
        private set
    lateinit var foodSeeder: FoodSeeder
        private set
    lateinit var ai: AiRepository
        private set
    lateinit var body: BodyRepository
        private set
    lateinit var backup: BackupManager
        private set
    lateinit var checkins: CheckinRepository
        private set

    init {
        open()
    }

    fun open() {
        db = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        training = TrainingRepository(db, db.exerciseDao(), db.routineDao(), db.workoutDao(), FileSeedSource, prefs)
        progression = ProgressionRepository(db, db.workoutDao(), db.exerciseDao(), db.routineDao(), prefs, db.nutritionDao(), days)
        workouts = WorkoutRepository(db, db.workoutDao(), db.routineDao(), db.exerciseDao(), training, progression, days)
        body = BodyRepository(db, db.bodyDao(), db.workoutDao(), prefs, days)
        personas = PersonaRepository(prefs, db.nutritionDao(), training, days, body)
        nutrition = NutritionRepository(db.nutritionDao(), db.foodLogDao(), db.checkinDao(), prefs, days)
        foods = FoodRepository(db, db.foodDao(), db.foodLogDao(), prefs)
        foodLog = FoodLogRepository(db, db.foodDao(), db.foodLogDao(), db.savedMealDao(), db.aiDao(), prefs, nutrition)
        foodSeeder = FoodSeeder(db, db.foodDao(), foodFiles, prefs)
        ai = AiRepository(db, db.aiDao(), db.foodDao(), prefs, keys, connectivity, estimator, foods, clock)
        backup = BackupManager(context, db, prefs)
        checkins = CheckinRepository(db, db.checkinDao(), db.nutritionDao(), db.foodLogDao(), db.bodyDao(), prefs, days, personas, nutrition)
    }

    /** Simulates process death: everything not written to disk is gone. */
    fun restart() {
        db.close()
        open()
    }

    fun close() = db.close()
}
