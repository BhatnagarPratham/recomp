package app.recomp.data.ai

import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.EstimatorReply
import app.recomp.domain.ai.MealEstimate
import app.recomp.domain.ai.MealEstimator
import app.recomp.domain.ai.MealRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException

/**
 * Describe it over whichever provider the request names (AI-05, TRD §6.2). The provider returns a JSON object
 * and a status from the shared table; the same [MealSchema] decoding runs on every provider's reply, and
 * `MealCheck` validates the items afterwards, so a provider with looser structured outputs gets no looser checks.
 */
@Singleton
class ProviderMealEstimator @Inject constructor(private val providers: AiProviders) : MealEstimator {
    override suspend fun estimate(request: MealRequest): EstimatorReply {
        val call = AiCall(request.target, request.system, request.user, MealSchema.schema, request.upperBound, request.images)
        val r = providers.of(request.target.provider).send(call)
        fun done(status: AiStatus, estimate: MealEstimate? = null, detail: String? = r.detail) =
            EstimatorReply(status, estimate, r.servedBy, r.attempts, r.latencyMs, r.text, detail)
        val json = r.json
        if (r.status != AiStatus.OK || json == null) return done(if (r.status == AiStatus.OK) AiStatus.INVALID else r.status)
        return try {
            done(AiStatus.OK, MealSchema.parse(json.toString()))
        } catch (e: SerializationException) {
            done(AiStatus.INVALID, detail = e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            done(AiStatus.INVALID, detail = e.javaClass.simpleName)
        }
    }
}
