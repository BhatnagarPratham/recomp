package app.recomp.data

import app.recomp.data.food.OffReply
import app.recomp.data.food.OpenFoodFactsClient
import app.recomp.domain.food.OffProduct
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** NU-21 and NU-11's Open Food Facts requests against a mock server, with invented products. */
class OpenFoodFactsClientTest {
    private val server = MockWebServer()
    private lateinit var client: OpenFoodFactsClient

    @Before
    fun setUp() {
        server.start()
        client = OpenFoodFactsClient(OpenFoodFactsClient.userAgent("0.1.0", "test-contact"), server.url("/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `a product lookup sends only the barcode, with the app's User-Agent`() = runBlocking {
        server.enqueue(
            json(
                """{"code":"4006381333931","status":1,"product":{"product_name":"Oat crunch","brands":"Testbrand, Other",
                "nutriments":{"energy-kcal_100g":402,"proteins_100g":"10.5","carbohydrates_100g":64,"fat_100g":9.5},
                "ingredients_analysis_tags":["en:vegan"],"allergens_tags":[],"serving_quantity":"30"}}""",
            ),
        )
        val reply = client.product("4006381333931", now = 0) as OffReply.Ok
        assertEquals(
            OffProduct("4006381333931", "Oat crunch", "Testbrand", 402.0, null, 10.5, 64.0, 9.5, null, setOf("en:vegan"), 30.0),
            reply.value,
        )
        val request = server.takeRequest()
        assertEquals("/api/v2/product/4006381333931", request.requestUrl!!.encodedPath)
        assertEquals(setOf("fields"), request.requestUrl!!.queryParameterNames)
        assertEquals("Recomp/0.1.0 (test-contact)", request.getHeader("User-Agent"))
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `missing fields stay null, and the older kJ field is read`() = runBlocking {
        server.enqueue(json("""{"status":1,"product":{"code":"96385074","nutriments":{"energy_100g":1674}}}"""))
        val p = (client.product("96385074", now = 0) as OffReply.Ok).value
        assertNull(p.name)
        assertNull(p.brand)
        assertNull(p.kcal100g)
        assertEquals(1674.0, p.kj100g)
        assertNull(p.protein100g)
        assertEquals(emptySet<String>(), p.tags)
    }

    @Test
    fun `a product that isn't there is not found, whether by 404 or status 0`() = runBlocking {
        server.enqueue(json("""{"status":0,"status_verbose":"product not found"}""", code = 404))
        assertEquals(OffReply.NotFound, client.product("4006381333931", now = 0))
        server.enqueue(json("""{"status":0}"""))
        assertEquals(OffReply.NotFound, client.product("4006381333931", now = 1))
    }

    @Test
    fun `a 429 backs off, and nothing more is sent until the wait is over`() = runBlocking {
        server.enqueue(json("{}", code = 429).setHeader("Retry-After", "30"))
        val busy = client.product("4006381333931", now = 0) as OffReply.Busy
        assertEquals(30_000L, busy.waitMs)
        assertTrue(client.search("oat bar", now = 10_000) is OffReply.Busy)
        assertEquals(1, server.requestCount)
        server.enqueue(json("""{"count":0,"products":[]}"""))
        assertEquals(OffReply.Ok(emptyList<OffProduct>()), client.search("oat bar", now = 31_000))
    }

    @Test
    fun `a search sends only the words and reads every product it can`() = runBlocking {
        server.enqueue(
            json(
                """{"count":2,"products":[{"code":"1","product_name":"Oat bar","nutriments":{"energy-kcal_100g":"410"}},
                {"product_name":"No code, skipped"}]}""",
            ),
        )
        val products = (client.search(" oat bar ", now = 0) as OffReply.Ok).value
        assertEquals(listOf("Oat bar"), products.map { it.name })
        assertEquals(410.0, products.single().kcal100g)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/cgi/search.pl", url.encodedPath)
        assertEquals("oat bar", url.queryParameter("search_terms"))
    }

    @Test
    fun `no connection or a reply that isn't JSON is unreachable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(OffReply.Unreachable, client.product("4006381333931", now = 0))
        server.enqueue(MockResponse().setBody("<html>"))
        assertEquals(OffReply.Unreachable, client.product("4006381333931", now = 1))
        assertEquals("Recomp/0.1.0 (no contact set)", OpenFoodFactsClient.userAgent("0.1.0", " "))
    }
}
