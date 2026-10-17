package app.recomp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutineDao {
    /** Strength routines in rotation order (RT-02). */
    @Query("SELECT * FROM routine WHERE kind = 'STRENGTH' AND archived = 0 ORDER BY rotation_index, id")
    fun observeRotation(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routine WHERE kind = 'STRENGTH' AND archived = 0 ORDER BY rotation_index, id")
    suspend fun rotation(): List<RoutineEntity>

    /** Extra sessions such as a yoga class (PRD §7.6). */
    @Query("SELECT * FROM routine WHERE kind != 'STRENGTH' AND archived = 0 ORDER BY rotation_index, id")
    fun observeExtras(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routine WHERE kind != 'STRENGTH' AND archived = 0 ORDER BY rotation_index, id")
    suspend fun extras(): List<RoutineEntity>

    @Query("SELECT * FROM routine WHERE id = :id")
    fun observe(id: Long): Flow<RoutineEntity?>

    @Query("SELECT * FROM routine WHERE id = :id")
    suspend fun get(id: Long): RoutineEntity?

    @Query("SELECT COALESCE(MAX(rotation_index), -1) FROM routine WHERE kind = 'STRENGTH' AND archived = 0")
    suspend fun maxRotationIndex(): Int

    @Insert
    suspend fun insert(routine: RoutineEntity): Long

    @Update
    suspend fun update(routine: RoutineEntity)

    @Update
    suspend fun updateAll(routines: List<RoutineEntity>)

    @Transaction
    @Query("SELECT * FROM routine_exercise WHERE routine_id = :routineId ORDER BY position")
    fun observeExercises(routineId: Long): Flow<List<RoutineExerciseWithExercise>>

    @Transaction
    @Query("SELECT * FROM routine_exercise WHERE routine_id = :routineId ORDER BY position")
    suspend fun exercises(routineId: Long): List<RoutineExerciseWithExercise>

    @Insert
    suspend fun insertExercises(rows: List<RoutineExerciseEntity>)

    @Query("DELETE FROM routine_exercise WHERE routine_id = :routineId")
    suspend fun deleteExercises(routineId: Long)

    /** Rewrites a routine's exercise list in one transaction: edits and save-back (RT-03, RT-04). */
    @Transaction
    suspend fun replaceExercises(routineId: Long, rows: List<RoutineExerciseEntity>) {
        deleteExercises(routineId)
        insertExercises(rows.mapIndexed { i, row -> row.copy(id = 0, routineId = routineId, position = i) })
    }

    @Transaction
    suspend fun insertWithExercises(routine: RoutineEntity, rows: List<RoutineExerciseEntity>): Long {
        val id = insert(routine)
        insertExercises(rows.mapIndexed { i, row -> row.copy(id = 0, routineId = id, position = i) })
        return id
    }
}
