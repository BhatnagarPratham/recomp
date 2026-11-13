package app.recomp.data.ai

import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.TokenUsage
import java.io.IOException
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One HTTP request an adapter built. [headers] may hold the key, so this is never logged or put in an exception. */
class WireRequest(val url: String, val headers: Map<String, String>, val body: JsonObject) {
    override fun toString() = "WireRequest"
}

/** How a request asks for JSON. Only the OpenAI-compatible adapter uses more than [SCHEMA]. */
enum class WireMode {
    /** The provider's structured outputs, with the hand-written schema. */
    SCHEMA,

    /** Plain JSON mode with the schema written into the prompt, for a server that refuses the schema. */
    JSON_OBJECT,
}

/**
 * What the providers without an SDK share (OpenAI, Gemini and OpenAI-compatible servers; TRD §6.2): one JSON POST
 * over OkHttp with the same rules as the Anthropic adapter. Each attempt times out after 20 s. One retry, and only
 * when the request clearly wasn't processed (429, 5xx, no connection). A request that was sent but never answered is
 * costed at its upper bound. OkHttp's own recovery is off and connections aren't reused, so nothing is ever sent
 * again behind the adapter's back. Redirects aren't followed, so a key can't be carried to another address. A reply
 * is read up to [MAX_REPLY_BYTES]. Nothing is logged, and an exception is kept only as its class name: its message
 * could hold a header.
 */
abstract class HttpProvider : AiProvider {
    /** Tests shorten the timeout. */
    internal var timeout: Duration = AiWire.TIMEOUT

    protected open fun firstMode(call: AiCall): WireMode = WireMode.SCHEMA

    /** A second shape to try when the server refuses this one outright, or null. It doesn't count as the retry. */
    protected open fun nextMode(call: AiCall, mode: WireMode, code: Int): WireMode? = null

    /** The server took [mode]: remembered, where that saves a refused first attempt next time. */
    protected open fun accepted(call: AiCall, mode: WireMode) = Unit

    abstract fun request(call: AiCall, mode: WireMode = WireMode.SCHEMA): WireRequest

    /** The status of an error reply, from its HTTP code and, where the provider says more, its body. */
    protected abstract fun errorStatus(code: Int, body: JsonObject?): AiStatus

    /** A short machine-readable reason from an error body (a code or a type), never its message. */
    protected abstract fun errorDetail(body: JsonObject?): String?

    /** Maps a 2xx reply onto the shared result: stop reason first, then the text, then its tokens. */
    protected abstract fun read(call: AiCall, body: JsonObject, latencyMs: Long): AiResult

    private fun client(): OkHttpClient = shared.newBuilder().callTimeout(timeout).readTimeout(timeout).writeTimeout(timeout).build()

    override suspend fun send(call: AiCall): AiResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - started) / 1_000_000
        fun failure(status: AiStatus, detail: String?, maybeBilled: Boolean = false) =
            AiResult(status, null, null, call.target.model, if (maybeBilled) listOf(call.upperBound) else emptyList(), elapsedMs(), detail)

        try {
            val client = client()
            var mode = firstMode(call)
            var attempt = 1
            while (true) {
                val reply = try {
                    post(client, request(call, mode))
                } catch (e: IOException) {
                    val name = e.javaClass.simpleName
                    when {
                        AiWire.notSent(e) && attempt < AiWire.MAX_ATTEMPTS -> {
                            delay(AiWire.DEFAULT_RETRY_WAIT_MS)
                            attempt++
                            continue
                        }
                        AiWire.notSent(e) -> return@withContext failure(AiStatus.NETWORK, name)
                        AiWire.timedOut(e) -> return@withContext failure(AiStatus.TIMEOUT, name, maybeBilled = true)
                        else -> return@withContext failure(AiStatus.NETWORK, name, maybeBilled = true)
                    }
                }
                val json = if (reply.tooBig) null else AiWire.parseObject(reply.body)
                if (reply.code in 200..299) {
                    accepted(call, mode)
                    // A 2xx that isn't JSON, or is far too long to be a reply, was still answered and may have been billed.
                    return@withContext when {
                        reply.tooBig -> failure(AiStatus.INVALID, "too long", maybeBilled = true)
                        json == null -> failure(AiStatus.INVALID, "not JSON", maybeBilled = true)
                        else -> read(call, json, elapsedMs())
                    }
                }
                val next = nextMode(call, mode, reply.code)
                if (next != null) {
                    mode = next
                    continue
                }
                val status = errorStatus(reply.code, json)
                if ((status == AiStatus.RATE_LIMIT || status == AiStatus.SERVER) && attempt < AiWire.MAX_ATTEMPTS) {
                    delay(AiWire.retryDelayMs(reply.retryAfterMs, reply.retryAfter))
                    attempt++
                    continue
                }
                // A provider that says it ran out of time had started the work: like a timeout, it may have been billed.
                return@withContext failure(
                    status,
                    listOfNotNull("HTTP ${reply.code}", errorDetail(json)?.let(::token)).joinToString(" "),
                    maybeBilled = status == AiStatus.TIMEOUT,
                )
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never let an exception escape: one about a bad header or URL can carry the key in its message.
            failure(AiStatus.UNEXPECTED, e.javaClass.simpleName)
        }
    }

    private class Reply(val code: Int, val body: String, val tooBig: Boolean, val retryAfterMs: String?, val retryAfter: String?)

    private fun post(client: OkHttpClient, request: WireRequest): Reply {
        val http = Request.Builder().url(request.url).post(request.body.toString().toRequestBody(JSON))
            .apply { request.headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return client.newCall(http).execute().use { response ->
            // Never the whole of an endless or huge body: that would end the app, not the request.
            val source = response.body?.source()
            val tooBig = source?.request(MAX_REPLY_BYTES + 1) == true
            val body = if (source == null || tooBig) "" else source.readUtf8()
            Reply(response.code, body, tooBig, response.header("retry-after-ms"), response.header(RETRY_AFTER))
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Far more than any reply (a long meal is a few KB): past this a body isn't one. */
        const val MAX_REPLY_BYTES = 1L shl 20

        /**
         * The client every request starts from. It holds no key: each request carries its own header.
         *
         * OkHttp would re-send a POST by itself when a reused connection drops after the request went out, and the
         * first send may have been billed. So its recovery is off, and no connection is kept for reuse: requests
         * are minutes apart, and a stale connection would otherwise fail a request that was never really sent.
         */
        private val shared: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(10)).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                // OkHttp re-sends a 503 that says `Retry-After: 0` on its own. Moving the header aside leaves the
                // one retry, and how long it waits, to the adapter.
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    val after = response.header("Retry-After") ?: return@addNetworkInterceptor response
                    response.newBuilder().removeHeader("Retry-After").header(RETRY_AFTER, after).build()
                }
                .build()
        }

        private const val RETRY_AFTER = "x-recomp-retry-after"

        /** A provider's code or type, cut down to a short plain token so nothing else can ride along in it. */
        fun token(text: String): String = text.filter { it.isLetterOrDigit() || it in "_.-" }.take(48)

        fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

        fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

        fun JsonObject.long(name: String): Long = (this[name] as? JsonPrimitive)?.longOrNull ?: 0L

        /**
         * One attempt priced at the model that was asked for: a provider may answer with a dated snapshot's name,
         * which the price table wouldn't know. [cached] and [cacheWrite] are taken out of [prompt], which every one
         * of these providers reports with both inside it.
         *
         * A usage with no input and no output isn't one: an empty object, zeros from a proxy, or counts that aren't
         * numbers. The request was answered, so it's costed at its upper bound, as a reply with no usage at all is.
         */
        fun usage(call: AiCall, prompt: Long, cached: Long, output: Long, cacheWrite: Long = 0): TokenUsage {
            if (prompt <= 0 && output <= 0) return call.upperBound
            val total = prompt.coerceAtLeast(0)
            val read = cached.coerceIn(0, total)
            val written = cacheWrite.coerceIn(0, total - read)
            return TokenUsage(call.target.model, total - read - written, output.coerceAtLeast(0), read, written, call.target.rates)
        }
    }
}
