package app.recomp.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import app.recomp.domain.model.ProgressionScheme
import app.recomp.domain.model.SessionKind
import app.recomp.domain.model.SessionStatus
import app.recomp.domain.model.SetType
import app.recomp.domain.progression.PrType
import java.time.LocalDate

/**
 * A routine (RT-01). Strength routines rotate in [rotationIndex] order (RT-02). Every other kind is an
 * extra session such as a yoga class (PRD §7.6); its schedule lives in settings. Archived, never deleted.
 */
@Entity(tableName = "routine")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: SessionKind,
    @ColumnInfo(name = "rotation_index") val rotationIndex: Int,
    val archived: Boolean = false,
)

@Entity(
    tableName = "routine_exercise",
    foreignKeys = [
        ForeignKey(RoutineEntity::class, ["id"], ["routine_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ExerciseEntity::class, ["id"], ["exercise_id"]),
    ],
    indices = [Index("routine_id"), Index("exercise_id")],
)
data class RoutineExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "routine_id") val routineId: Long,
    @ColumnInfo(name = "exercise_id") val exerciseId: Long,
    val position: Int,
    @ColumnInfo(name = "target_sets") val targetSets: Int,
    @ColumnInfo(name = "rep_min") val repMin: Int,
    @ColumnInfo(name = "rep_max") val repMax: Int,
    @ColumnInfo(name = "rest_sec") val restSec: Int,
    val scheme: ProgressionScheme,
    @ColumnInfo(name = "superset_group") val supersetGroup: Int? = null,
)

/** Only one session is ACTIVE at a time (TRD §6.1). [logDate] is fixed at write time using the day boundary. */
@Entity(
    tableName = "workout_session",
    foreignKeys = [ForeignKey(RoutineEntity::class, ["id"], ["routine_id"])],
    indices = [Index("status", "started_at"), Index("routine_id")],
)
data class WorkoutSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "routine_id") val routineId: Long?,
    val kind: SessionKind,
    val status: SessionStatus,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    @ColumnInfo(name = "log_date") val logDate: LocalDate,
    val notes: String? = null,
    @ColumnInfo(name = "hc_record_id") val hcRecordId: String? = null,
)

/**
 * One exercise in a session. Sets, rep range, rest and superset group are copied from the routine when
 * the workout starts, so changes made mid-workout never touch the template until you save them back
 * (RT-03). [supersetGroup] joins consecutive exercises into a superset (RT-05, `Supersets`).
 */
@Entity(
    tableName = "session_exercise",
    foreignKeys = [
        ForeignKey(WorkoutSessionEntity::class, ["id"], ["session_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ExerciseEntity::class, ["id"], ["exercise_id"]),
    ],
    indices = [Index("session_id"), Index("exercise_id")],
)
data class SessionExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "exercise_id") val exerciseId: Long,
    val position: Int,
    @ColumnInfo(name = "target_sets") val targetSets: Int,
    @ColumnInfo(name = "rep_min") val repMin: Int,
    @ColumnInfo(name = "rep_max") val repMax: Int,
    @ColumnInfo(name = "rest_sec") val restSec: Int,
    val notes: String? = null,
    /** The progression engine's output, frozen when the workout starts (TRD §5.1). Written from M2. */
    @ColumnInfo(name = "recommendation_json") val recommendationJson: String? = null,
    @ColumnInfo(name = "user_overrode") val userOverrode: Boolean = false,
    @ColumnInfo(name = "superset_group") val supersetGroup: Int? = null,
)

/**
 * A set. Rows exist from the start of the workout; null load or reps means "not entered", so the UI
 * shows ghost values. [completedAt] is set when the set is ticked (WL-06).
 */
@Entity(
    tableName = "set_entry",
    foreignKeys = [ForeignKey(SessionExerciseEntity::class, ["id"], ["session_exercise_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("session_exercise_id")],
)
data class SetEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_exercise_id") val sessionExerciseId: Long,
    @ColumnInfo(name = "set_index") val setIndex: Int,
    @ColumnInfo(name = "set_type") val setType: SetType = SetType.WORKING,
    @ColumnInfo(name = "load_g") val loadG: Int? = null,
    val reps: Int? = null,
    val rir: Int? = null,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
)

/** Details of an extra session of any kind (yoga, mobility, cardio, sport): YG-01. */
@Entity(
    tableName = "yoga_detail",
    foreignKeys = [ForeignKey(WorkoutSessionEntity::class, ["id"], ["session_id"], onDelete = ForeignKey.CASCADE)],
)
data class YogaDetailEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: Long,
    val style: String,
    @ColumnInfo(name = "duration_min") val durationMin: Int,
    val intensity: Int? = null,
    @ColumnInfo(name = "focus_areas") val focusAreas: List<String> = emptyList(),
    val poses: List<String> = emptyList(),
)

/** A PR (PO-04), written once when a workout finishes. [value] is grams, reps or gram-reps by [type]. */
@Entity(
    tableName = "personal_record",
    foreignKeys = [
        ForeignKey(ExerciseEntity::class, ["id"], ["exercise_id"]),
        ForeignKey(WorkoutSessionEntity::class, ["id"], ["session_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("exercise_id"), Index("session_id")],
)
data class PersonalRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "exercise_id") val exerciseId: Long,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    val type: PrType,
    val value: Long,
    @ColumnInfo(name = "load_g") val loadG: Int? = null,
    @ColumnInfo(name = "set_entry_id") val setEntryId: Long? = null,
    @ColumnInfo(name = "achieved_at") val achievedAt: Long,
)

/** A completed set of an exposure (TRD §4 hot query), with its session's details. */
data class ExposureSetRow(
    @Embedded val set: SetEntryEntity,
    @ColumnInfo(name = "x_session_id") val sessionId: Long,
    @ColumnInfo(name = "x_started_at") val startedAt: Long,
    @ColumnInfo(name = "x_log_date") val logDate: LocalDate,
    @ColumnInfo(name = "x_routine_name") val routineName: String?,
)

data class RoutineExerciseWithExercise(
    @Embedded val entry: RoutineExerciseEntity,
    @Relation(parentColumn = "exercise_id", entityColumn = "id") val exercise: ExerciseEntity,
)

data class SessionExerciseWithSets(
    @Embedded val entry: SessionExerciseEntity,
    @Relation(parentColumn = "exercise_id", entityColumn = "id") val exercise: ExerciseEntity,
    @Relation(parentColumn = "id", entityColumn = "session_exercise_id") val sets: List<SetEntryEntity>,
)

data class PlanSessionRow(
    @ColumnInfo(name = "log_date") val logDate: LocalDate,
    val kind: SessionKind,
    @ColumnInfo(name = "routine_id") val routineId: Long?,
)
