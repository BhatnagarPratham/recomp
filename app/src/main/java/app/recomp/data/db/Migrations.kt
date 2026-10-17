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

    /** M3a, personas and targets: versioned daily targets (PS-02). SQL from schemas/4.json. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `nutrition_target` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`effective_from` TEXT NOT NULL, `goal` TEXT NOT NULL, `kcal` INTEGER NOT NULL, `protein_g` INTEGER NOT NULL, " +
                    "`fat_g` INTEGER NOT NULL, `carbs_g` INTEGER NOT NULL, `tdee_estimate` INTEGER NOT NULL, `bmr_kcal` INTEGER NOT NULL, " +
                    "`reason` TEXT NOT NULL, `created_at` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_nutrition_target_effective_from` ON `nutrition_target` (`effective_from`)")
        }
    }

    /** M3b, food logging: foods with a full-text index, their units, the food log and saved meals. SQL from schemas/5.json. */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `food` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `brand` TEXT, " +
                    "`source` TEXT NOT NULL, `barcode` TEXT, `source_key` TEXT, `diet` TEXT NOT NULL, `region` TEXT, `kcal_100g` REAL NOT NULL, " +
                    "`protein_100g` REAL NOT NULL, `carbs_100g` REAL NOT NULL, `fat_100g` REAL NOT NULL, `fiber_100g` REAL NOT NULL, `caveat` TEXT, " +
                    "`use_count` INTEGER NOT NULL, `last_used_at` INTEGER, `archived` INTEGER NOT NULL, `created_at` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_food_source_key` ON `food` (`source_key`)")

            // External-content FTS4 over food.name; Room's triggers keep it in sync (Room creates them itself only on a fresh install).
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `food_fts` USING FTS4(`name` TEXT NOT NULL, content=`food`)")
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_food_fts_BEFORE_UPDATE BEFORE UPDATE ON `food` " +
                    "BEGIN DELETE FROM `food_fts` WHERE `docid`=OLD.`rowid`; END",
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_food_fts_BEFORE_DELETE BEFORE DELETE ON `food` " +
                    "BEGIN DELETE FROM `food_fts` WHERE `docid`=OLD.`rowid`; END",
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_food_fts_AFTER_UPDATE AFTER UPDATE ON `food` " +
                    "BEGIN INSERT INTO `food_fts`(`docid`, `name`) VALUES (NEW.`rowid`, NEW.`name`); END",
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_food_fts_AFTER_INSERT AFTER INSERT ON `food` " +
                    "BEGIN INSERT INTO `food_fts`(`docid`, `name`) VALUES (NEW.`rowid`, NEW.`name`); END",
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `food_unit` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `food_id` INTEGER NOT NULL, " +
                    "`label` TEXT NOT NULL, `grams` REAL NOT NULL, `calibrated` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`food_id`) REFERENCES `food`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_food_unit_food_id_label` ON `food_unit` (`food_id`, `label`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `food_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `log_date` TEXT NOT NULL, " +
                    "`meal_slot` TEXT NOT NULL, `food_id` INTEGER, `name` TEXT NOT NULL, `quantity` REAL NOT NULL, `unit_label` TEXT NOT NULL, " +
                    "`grams` REAL, `kcal` REAL NOT NULL, `protein_g` REAL NOT NULL, `carbs_g` REAL, `fat_g` REAL, `fiber_g` REAL, " +
                    "`origin` TEXT NOT NULL, `source` TEXT NOT NULL, `ai_request_id` INTEGER, `logged_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`food_id`) REFERENCES `food`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_food_log_log_date` ON `food_log` (`log_date`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_food_log_food_id` ON `food_log` (`food_id`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `saved_meal` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                    "`default_slot` TEXT NOT NULL, `use_count` INTEGER NOT NULL, `last_used_at` INTEGER, `created_at` INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `saved_meal_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `saved_meal_id` INTEGER NOT NULL, " +
                    "`position` INTEGER NOT NULL, `food_id` INTEGER, `name` TEXT NOT NULL, `quantity` REAL NOT NULL, `unit_label` TEXT NOT NULL, " +
                    "`grams` REAL, `kcal` REAL NOT NULL, `protein_g` REAL NOT NULL, `carbs_g` REAL, `fat_g` REAL, `fiber_g` REAL, `origin` TEXT NOT NULL, " +
                    "FOREIGN KEY(`saved_meal_id`) REFERENCES `saved_meal`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`food_id`) REFERENCES `food`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_meal_item_saved_meal_id` ON `saved_meal_item` (`saved_meal_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_meal_item_food_id` ON `saved_meal_item` (`food_id`)")
        }
    }

    /** M3c, AI logging: drafts to estimate later (AI-02) and the request log behind spend and the cap (AI-03). SQL from schemas/6.json. */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `meal_draft` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `created_at` INTEGER NOT NULL, " +
                    "`log_date` TEXT NOT NULL, `meal_slot` TEXT NOT NULL, `text` TEXT NOT NULL, `photo_path` TEXT, `origin` TEXT NOT NULL, `reason` TEXT)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_meal_draft_log_date` ON `meal_draft` (`log_date`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `ai_request_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `created_at` INTEGER NOT NULL, " +
                    "`kind` TEXT NOT NULL, `model` TEXT NOT NULL, `input_tokens` INTEGER NOT NULL, `output_tokens` INTEGER NOT NULL, " +
                    "`cache_read_tokens` INTEGER NOT NULL, `cache_write_tokens` INTEGER NOT NULL, `cost_usd` REAL NOT NULL, `status` TEXT NOT NULL, " +
                    "`latency_ms` INTEGER NOT NULL, `response_json` TEXT)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_request_log_created_at` ON `ai_request_log` (`created_at`)")
        }
    }
}
