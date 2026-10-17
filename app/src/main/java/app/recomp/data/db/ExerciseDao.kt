package app.recomp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseDao {
    @Query("SELECT COUNT(*) FROM exercise WHERE archived = 0")
    fun observeActiveCount(): Flow<Int>

    @Insert
    suspend fun insertAll(exercises: List<ExerciseEntity>)
}
