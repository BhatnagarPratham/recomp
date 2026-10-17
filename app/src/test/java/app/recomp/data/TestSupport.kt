package app.recomp.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.recomp.data.db.AppDatabase
import app.recomp.data.prefs.TrainingPrefs
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
    private val prefs = TrainingPrefs(PreferenceDataStoreFactory.create { File(dir, "settings.preferences_pb") })

    lateinit var db: AppDatabase
        private set
    lateinit var training: TrainingRepository
        private set
    lateinit var workouts: WorkoutRepository
        private set
    lateinit var progression: ProgressionRepository
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
        progression = ProgressionRepository(db, db.workoutDao(), db.exerciseDao(), db.routineDao(), prefs)
        workouts = WorkoutRepository(db, db.workoutDao(), db.routineDao(), db.exerciseDao(), training, progression)
    }

    /** Simulates process death: everything not written to disk is gone. */
    fun restart() {
        db.close()
        open()
    }

    fun close() = db.close()
}
