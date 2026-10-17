package app.recomp.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.recomp.data.db.AppDatabase
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.prefs.LegacyTrainingMigration
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.data.repo.NutritionRepository
import app.recomp.data.repo.PersonaRepository
import app.recomp.data.repo.ProgressionRepository
import app.recomp.data.repo.TrainingRepository
import app.recomp.data.repo.WorkoutRepository
import app.recomp.data.seed.SeedData
import app.recomp.data.seed.SeedSource
import java.io.File

/** Reads the real seed files from the source tree; Gradle runs unit tests from the module directory. */
object FileSeedSource : SeedSource {
    private val exercises by lazy { SeedData.parseExercises(File("src/main/assets/seed/exercises.json").readText()) }
    private val templates by lazy { SeedData.parseTemplates(File("src/main/assets/seed/split_templates.json").readText()) }
    override fun exercises() = exercises
    override fun templates() = templates
}

/** A file-backed database and settings store, so a test can "kill the app" by closing and reopening. */
class TestApp(private val dir: File) {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbFile = File(dir, "recomp-test.db")
    val prefs = SettingsPrefs(PreferenceDataStoreFactory.create(migrations = listOf(LegacyTrainingMigration)) { File(dir, "settings.preferences_pb") })
    val days = DayBoundary(prefs)

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
        personas = PersonaRepository(prefs, db.nutritionDao(), training, days)
        nutrition = NutritionRepository(db.nutritionDao(), prefs, days)
    }

    /** Simulates process death: everything not written to disk is gone. */
    fun restart() {
        db.close()
        open()
    }

    fun close() = db.close()
}
