package app.recomp.data.ai

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import java.time.Duration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SpikeMealItem(
    val name: String,
    val quantity: Double,
    val unit: String,
    val grams: Double,
    val kcal: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
    val confidence: String,
)

@Serializable
data class SpikeMealEstimate(
    val items: List<SpikeMealItem>,
    @SerialName("clarifying_question") val clarifyingQuestion: String,
)

data class SpikeResult(
    val estimate: SpikeMealEstimate?,
    val stopReason: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val latencyMs: Long,
)

/**
 * M0 spike: proves the Anthropic Java SDK runs on Android, including under R8.
 *
 * The SDK's class-based `.outputConfig(MyClass::class.java)` derives the schema with
 * `java.lang.reflect.AnnotatedType`, which Android doesn't have, so it would crash on the phone.
 * Instead the schema is written by hand and the reply is parsed with kotlinx.serialization.
 * The key is held in memory only; encrypted storage arrives with the real estimator in M3.
 */
object ClaudeSpikeClient {
    private const val MODEL = "claude-opus-5-5"

    private const val SYSTEM_PROMPT =
        "You estimate nutrition for Indian home-style meals for a lacto-vegetarian user " +
            "(dairy yes, no meat, fish or egg; assume the vegetarian version of ambiguous dishes). " +
            "Split the meal into items. Use typical Indian household portions: a phulka is about 35 g, " +
            "a katori holds about 150 g of dal or sabzi. Give grams and macros per item, and a " +
            "confidence of high, medium or low. Leave clarifying_question empty unless one answer " +
            "would change the estimate a lot."

    private val json = Json { ignoreUnknownKeys = true }

    private val itemSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "name" to mapOf("type" to "string"),
            "quantity" to mapOf("type" to "number"),
            "unit" to mapOf("type" to "string"),
            "grams" to mapOf("type" to "number"),
            "kcal" to mapOf("type" to "number"),
            "protein_g" to mapOf("type" to "number"),
            "carbs_g" to mapOf("type" to "number"),
            "fat_g" to mapOf("type" to "number"),
            "confidence" to mapOf("type" to "string", "enum" to listOf("high", "medium", "low")),
        ),
        "required" to listOf("name", "quantity", "unit", "grams", "kcal", "protein_g", "carbs_g", "fat_g", "confidence"),
        "additionalProperties" to false,
    )

    private val estimateSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "items" to mapOf("type" to "array", "items" to itemSchema),
            "clarifying_question" to mapOf("type" to "string"),
        ),
        "required" to listOf("items", "clarifying_question"),
        "additionalProperties" to false,
    )

    fun estimate(apiKey: String, meal: String): SpikeResult {
        val client = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofSeconds(60))
            .maxRetries(1)
            .build()

        val schema = JsonOutputFormat.Schema.builder()
            .putAllAdditionalProperties(estimateSchema.mapValues { (_, value) -> JsonValue.from(value) })
            .build()

        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(8000L)
            .system(SYSTEM_PROMPT)
            .outputConfig(OutputConfig.builder().format(JsonOutputFormat.builder().schema(schema).build()).build())
            .addUserMessage(meal)
            .build()

        val started = System.nanoTime()
        val response = client.messages().create(params)
        val latencyMs = (System.nanoTime() - started) / 1_000_000

        val stopReason = response.stopReason().map { it.toString() }.orElse("unknown")
        val text = response.content().firstNotNullOfOrNull { block -> block.text().orElse(null)?.text() }
        val estimate = if (stopReason == "end_turn" && text != null) json.decodeFromString<SpikeMealEstimate>(text) else null

        return SpikeResult(
            estimate = estimate,
            stopReason = stopReason,
            inputTokens = response.usage().inputTokens(),
            outputTokens = response.usage().outputTokens(),
            latencyMs = latencyMs,
        )
    }
}
