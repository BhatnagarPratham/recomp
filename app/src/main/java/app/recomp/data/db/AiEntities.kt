package app.recomp.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import app.recomp.domain.ai.AiKind
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.food.Origin
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * A meal description kept to estimate later (AI-02). [reason] is why it's a draft: the failed request's
 * status, or null when you saved it yourself (AI not ready). Never sent on its own.
 */
@Entity(tableName = "meal_draft", indices = [Index("log_date")])
data class MealDraftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "log_date") val logDate: LocalDate,
    @ColumnInfo(name = "meal_slot") val mealSlot: String,
    val text: String,
    @ColumnInfo(name = "photo_path") val photoPath: String? = null,
    val origin: Origin,
    val reason: AiStatus? = null,
)

/**
 * One AI request (AI-03 spend, and debugging). It's written as PENDING at the request's upper-bound cost before
 * sending, then settled. Tokens are summed over every attempt (a refused one and its fallback); [costUsd] prices
 * each attempt at its own model, or stays the upper bound for a request that was sent but never answered.
 * [model] is the model that served the reply. [responseJson] is the reply's text, never the prompt or the key.
 */
@Entity(tableName = "ai_request_log", indices = [Index("created_at")])
data class AiRequestLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val kind: AiKind,
    val model: String,
    @ColumnInfo(name = "input_tokens") val inputTokens: Long,
    @ColumnInfo(name = "output_tokens") val outputTokens: Long,
    @ColumnInfo(name = "cache_read_tokens") val cacheReadTokens: Long,
    @ColumnInfo(name = "cache_write_tokens") val cacheWriteTokens: Long,
    @ColumnInfo(name = "cost_usd") val costUsd: Double,
    val status: AiStatus,
    @ColumnInfo(name = "latency_ms") val latencyMs: Long,
    @ColumnInfo(name = "response_json") val responseJson: String?,
)

/** This month's spend for Settings (AI-03). */
data class SpendRow(
    @ColumnInfo(name = "cost_usd") val costUsd: Double,
    val requests: Int,
)

@Dao
interface AiDao {
    @Insert
    suspend fun insertRequest(row: AiRequestLogEntity): Long

    @Query("SELECT * FROM ai_request_log WHERE id = :id")
    suspend fun request(id: Long): AiRequestLogEntity?

    /** Settles a PENDING row with the reply's real usage. */
    @Update
    suspend fun updateRequest(row: AiRequestLogEntity)

    /** Spend and request count since [fromMs] (the month's start, local time). */
    @Query("SELECT COALESCE(SUM(cost_usd), 0) AS cost_usd, COUNT(*) AS requests FROM ai_request_log WHERE created_at >= :fromMs")
    fun observeSpend(fromMs: Long): Flow<SpendRow>

    @Query("SELECT COALESCE(SUM(cost_usd), 0) FROM ai_request_log WHERE created_at >= :fromMs")
    suspend fun spendSince(fromMs: Long): Double

    @Insert
    suspend fun insertDraft(draft: MealDraftEntity): Long

    @Query("SELECT * FROM meal_draft WHERE id = :id")
    suspend fun draft(id: Long): MealDraftEntity?

    @Query("SELECT * FROM meal_draft WHERE log_date = :date ORDER BY created_at, id")
    fun observeDrafts(date: LocalDate): Flow<List<MealDraftEntity>>

    @Update
    suspend fun updateDraft(draft: MealDraftEntity)

    @Query("DELETE FROM meal_draft WHERE id = :id")
    suspend fun deleteDraft(id: Long)
}
