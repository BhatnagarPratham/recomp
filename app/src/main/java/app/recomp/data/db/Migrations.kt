package app.recomp.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Hand-written migrations, each covered by MigrationTest. The SQL mirrors the exported schema JSON. */
object Migrations {

    /** M1, training core: seed keys on exercises, plus routines, sessions, sets and extra-session details. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `exercise` ADD COLUMN `seed_key` TEXT")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_exercise_seed_key` ON `exercise` (`seed_key`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `routine` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                    "`kind` TEXT NOT NULL, `rotation_index` INTEGER NOT NULL, `archived` INTEGER NOT NULL)",
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `routine_exercise` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`routine_id` INTEGER NOT NULL, `exercise_id` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                    "`target_sets` INTEGER NOT NULL, `rep_min` INTEGER NOT NULL, `rep_max` INTEGER NOT NULL, " +
                    "`rest_sec` INTEGER NOT NULL, `scheme` TEXT NOT NULL, `superset_group` INTEGER, " +
                    "FOREIGN KEY(`routine_id`) REFERENCES `routine`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`exercise_id`) REFERENCES `exercise`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_routine_exercise_routine_id` ON `routine_exercise` (`routine_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_routine_exercise_exercise_id` ON `routine_exercise` (`exercise_id`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `workout_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`routine_id` INTEGER, `kind` TEXT NOT NULL, `status` TEXT NOT NULL, `started_at` INTEGER NOT NULL, " +
                    "`ended_at` INTEGER, `log_date` TEXT NOT NULL, `notes` TEXT, `hc_record_id` TEXT, " +
                    "FOREIGN KEY(`routine_id`) REFERENCES `routine`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_workout_session_status_started_at` ON `workout_session` (`status`, `started_at`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_workout_session_routine_id` ON `workout_session` (`routine_id`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `session_exercise` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`session_id` INTEGER NOT NULL, `exercise_id` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                    "`target_sets` INTEGER NOT NULL, `rep_min` INTEGER NOT NULL, `rep_max` INTEGER NOT NULL, " +
                    "`rest_sec` INTEGER NOT NULL, `notes` TEXT, `recommendation_json` TEXT, `user_overrode` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`session_id`) REFERENCES `workout_session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`exercise_id`) REFERENCES `exercise`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_exercise_session_id` ON `session_exercise` (`session_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_exercise_exercise_id` ON `session_exercise` (`exercise_id`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `set_entry` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`session_exercise_id` INTEGER NOT NULL, `set_index` INTEGER NOT NULL, `set_type` TEXT NOT NULL, " +
                    "`load_g` INTEGER, `reps` INTEGER, `rir` INTEGER, `completed_at` INTEGER, " +
                    "FOREIGN KEY(`session_exercise_id`) REFERENCES `session_exercise`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_set_entry_session_exercise_id` ON `set_entry` (`session_exercise_id`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `yoga_detail` (`session_id` INTEGER NOT NULL, `style` TEXT NOT NULL, " +
                    "`duration_min` INTEGER NOT NULL, `intensity` INTEGER, `focus_areas` TEXT NOT NULL, `poses` TEXT NOT NULL, " +
                    "PRIMARY KEY(`session_id`), " +
                    "FOREIGN KEY(`session_id`) REFERENCES `workout_session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
        }
    }

    /** M2, progression: PRs, plus each exercise's stall Hold or Deload point. SQL from schemas/3.json. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `exercise` ADD COLUMN `stall_reset_at` INTEGER")
            db.execSQL("ALTER TABLE `exercise` ADD COLUMN `stall_reset_keeps_baseline` INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `personal_record` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`exercise_id` INTEGER NOT NULL, `session_id` INTEGER NOT NULL, `type` TEXT NOT NULL, `value` INTEGER NOT NULL, " +
                    "`load_g` INTEGER, `set_entry_id` INTEGER, `achieved_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`exercise_id`) REFERENCES `exercise`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
                    "FOREIGN KEY(`session_id`) REFERENCES `workout_session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_personal_record_exercise_id` ON `personal_record` (`exercise_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_personal_record_session_id` ON `personal_record` (`session_id`)")
        }
    }
}
