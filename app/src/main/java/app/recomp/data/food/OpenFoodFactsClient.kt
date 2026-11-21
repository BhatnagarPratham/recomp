package app.recomp.data.food

import app.recomp.domain.food.OffProduct
import app.recomp.domain.food.OffThrottle
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** How an Open Food Facts request ended. */
sealed interface OffReply<out T> {
    data class Ok<T>(val value: T) : OffReply<T>
    /** The barcode isn't on Open Food Facts (or a search found nothing is [Ok] with an empty list). */
    data object NotFound : OffReply<Nothing>
    /** We're over its limits: wait [waitMs] before asking again. */
    data class Busy(val waitMs: Long) : OffReply<Nothing>
    /** No connection, a server error or a reply that wasn't JSON. */
    data object Unreachable : OffReply<Nothing>
}

/**
 * Open Food Facts (NU-21, NU-11; TRD §6.4, §10 M18). Its terms ask every app to identify itself in the
 * User-Agent, `Recomp/<version> (<contact>)`, with the contact from the builder's own `local.properties`, never
 * the public repository. Only the barcode or the search words are sent (PRD §12.14). [OffThrottle] keeps to its
 * 15 product reads and 10 searches a minute and backs off on 429.
 */
@Singleton
class OpenFoodFactsClient @Inject constructor(
    @Named(USER_AGENT) private val userAgent: String,
    @Named(BASE_URL) private val baseUrl: String,
) {
    private val throttle = OffThrottle()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /** One product by barcode. */
    suspend fun product(code: String, now: Long = System.currentTimeMillis()): OffReply<OffProduct> {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/v2/product").addPathSegment(code)
            .addQueryParameter("fields", FIELDS)
            .build()
        return get(OffThrottle.Kind.PRODUCT, url, now) { json ->
            val product = json["product"] as? JsonObject
            if ((json["status"] as? JsonPrimitive)?.intOrNull != 1 || product == null) null else decode(product, fallbackCode = code)
        }
    }

    /** A text search, for the International region's search (NU-11). Only called on submit or after a pause. */
    suspend fun search(words: String, now: Long = System.currentTimeMillis()): OffReply<List<OffProduct>> {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("cgi/search.pl")
            .addQueryParameter("search_terms", words.trim())
            .addQueryParameter("search_simple", "1")
            .addQueryParameter("action", "process")
            .addQueryParameter("json", "1")
            .addQueryParameter("page_size", SEARCH_PAGE.toString())
            .addQueryParameter("fields", FIELDS)
            .build()
        return get(OffThrottle.Kind.SEARCH, url, now) { json ->
            (json["products"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { p -> decode(p, fallbackCode = null) } }
        }
    }

    private suspend fun <T : Any> get(kind: OffThrottle.Kind, url: HttpUrl, now: Long, read: (JsonObject) -> T?): OffReply<T> {
        val wait = throttle.waitMs(kind, now)
        if (wait > 0) return OffReply.Busy(wait)
        throttle.sentAt(kind, now)
        val request = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json").build()
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { response ->
                    when {
                        response.code == 429 -> {
                            val retry = response.header("Retry-After")?.toLongOrNull()?.times(1_000)
                            throttle.refused(now, retry)
                            OffReply.Busy(throttle.waitMs(kind, now))
                        }
                        response.code == 404 -> {
                            throttle.answered()
                            OffReply.NotFound
                        }
                        !response.isSuccessful -> OffReply.Unreachable
                        else -> {
                            throttle.answered()
                            val body = response.body?.string().orEmpty()
                            val json = runCatching { lenient.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return@use OffReply.Unreachable
                            read(json)?.let { OffReply.Ok(it) } ?: OffReply.NotFound
                        }
                    }
                }
            } catch (e: IOException) {
                OffReply.Unreachable
            }
        }
    }

    companion object {
        const val USER_AGENT = "off_user_agent"
        const val BASE_URL = "off_base_url"
        const val DEFAULT_BASE_URL = "https://world.openfoodfacts.org/"
        const val SEARCH_PAGE = 20

        /** Only what the app uses, so replies stay small. */
        const val FIELDS = "code,product_name,product_name_en,brands,nutriments,ingredients_analysis_tags,allergens_tags,serving_quantity"

        /** `Recomp/0.1.0 (you@example.com)`; without a contact, Open Food Facts still gets the app and its version. */
        fun userAgent(version: String, contact: String?): String =
            "Recomp/$version" + (contact?.trim()?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: " (no contact set)")

        private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * A product object, whatever it lacks. Numbers come as numbers or as strings ("12.5"); energy may be
         * `energy-kcal_100g`, `energy-kj_100g` or the older `energy_100g` (kJ).
         */
        fun decode(p: JsonObject, fallbackCode: String?): OffProduct? {
            val code = p.string("code") ?: fallbackCode ?: return null
            val n = p["nutriments"] as? JsonObject ?: JsonObject(emptyMap())
            fun num(key: String) = (n[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.replace(',', '.').toDoubleOrNull() }
            val tags = listOf("ingredients_analysis_tags", "allergens_tags").flatMap { key ->
                (p[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            }.toSet()
            return OffProduct(
                code = code,
                name = p.string("product_name") ?: p.string("product_name_en"),
                brand = p.string("brands")?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
                kcal100g = num("energy-kcal_100g"),
                kj100g = num("energy-kj_100g") ?: num("energy_100g"),
                protein100g = num("proteins_100g"),
                carbs100g = num("carbohydrates_100g"),
                fat100g = num("fat_100g"),
                fiber100g = num("fiber_100g"),
                tags = tags,
                servingG = (p["serving_quantity"] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }?.takeIf { it > 0 },
            )
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    }
}
