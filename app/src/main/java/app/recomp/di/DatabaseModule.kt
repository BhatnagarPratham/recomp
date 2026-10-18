package app.recomp.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import app.recomp.data.db.AiDao
import app.recomp.data.db.AppDatabase
import app.recomp.data.db.BodyDao
import app.recomp.data.db.ExerciseDao
import app.recomp.data.db.FoodDao
import app.recomp.data.db.FoodLogDao
import app.recomp.data.db.NutritionDao
import app.recomp.data.db.RoutineDao
import app.recomp.data.db.SavedMealDao
import app.recomp.data.db.WorkoutDao
import app.recomp.data.prefs.LegacyTrainingMigration
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/** Settings (TRD §4: DataStore). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsStore

/** Short-lived workout state that must survive process death, such as the rest timer's end time. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WorkoutStateStore

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

    @Provides
    fun provideExerciseDao(db: AppDatabase): ExerciseDao = db.exerciseDao()

    @Provides
    fun provideRoutineDao(db: AppDatabase): RoutineDao = db.routineDao()

    @Provides
    fun provideWorkoutDao(db: AppDatabase): WorkoutDao = db.workoutDao()

    @Provides
    fun provideNutritionDao(db: AppDatabase): NutritionDao = db.nutritionDao()

    @Provides
    fun provideFoodDao(db: AppDatabase): FoodDao = db.foodDao()

    @Provides
    fun provideFoodLogDao(db: AppDatabase): FoodLogDao = db.foodLogDao()

    @Provides
    fun provideSavedMealDao(db: AppDatabase): SavedMealDao = db.savedMealDao()

    @Provides
    fun provideAiDao(db: AppDatabase): AiDao = db.aiDao()

    @Provides
    fun provideBodyDao(db: AppDatabase): BodyDao = db.bodyDao()

    @Provides
    @Singleton
    @SettingsStore
    fun provideSettingsStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(migrations = listOf(LegacyTrainingMigration)) { context.preferencesDataStoreFile("settings") }

    @Provides
    @Singleton
    @WorkoutStateStore
    fun provideWorkoutStateStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("workout_state") }
}
