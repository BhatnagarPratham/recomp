package app.recomp.domain.food

import app.recomp.domain.ai.PhotoScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NU-21 and NU-11's Open Food Facts rules, barcode checks, the rate limit and photo sizes. Invented products. */
class OpenFoodFactsTest {
    private fun product(
        kcal: Double? = 402.0, kj: Double? = null, protein: Double? = 10.0, carbs: Double? = 64.0, fat: Double? = 9.5, fiber: Double? = null,
        name: String? = "Oat crunch", brand: String? = "Testbrand", tags: Set<String> = emptySet(),
    ) = OffProduct("4006381333931", name, brand, kcal, kj, protein, carbs, fat, fiber, tags)

    @Test
    fun `values per 100 g take kcal, or convert kJ, and leave missing fields blank`() {
        assertEquals(OffValues(402.0, 10.0, 64.0, 9.5, null), OpenFoodFacts.values(product()))
        assertTrue(OpenFoodFacts.values(product()).complete)
        assertEquals(400.1, OpenFoodFacts.values(product(kcal = null, kj = 1674.0)).kcal!!, 0.05)
        val sparse = OpenFoodFacts.values(product(kcal = null, kj = null, protein = null))
        assertNull(sparse.kcal)
        assertNull(sparse.proteinG)
        assertFalse(sparse.complete)
        // Values no food can have are dropped, not passed on.
        assertNull(OpenFoodFacts.values(product(fat = 140.0, kcal = 4020.0)).fatG)
        assertNull(OpenFoodFacts.values(product(kcal = 4020.0)).kcal)
    }

    @Test
    fun `the name falls back to the brand, then the barcode`() {
        assertEquals("Oat crunch", OpenFoodFacts.displayName(product()))
        assertEquals("Testbrand", OpenFoodFacts.displayName(product(name = " ")))
        assertEquals("4006381333931", OpenFoodFacts.displayName(product(name = null, brand = null)))
        assertEquals("off:4006381333931", OpenFoodFacts.sourceKey("4006381333931"))
    }

    @Test
    fun `the diet tag comes from the analysis, with egg and fish from the allergens`() {
        assertEquals(FoodDiet.VEGAN, OpenFoodFacts.diet(setOf("en:vegan", "en:vegetarian")))
        assertEquals(FoodDiet.VEGETARIAN, OpenFoodFacts.diet(setOf("en:non-vegan", "en:vegetarian", "en:milk")))
        assertEquals(FoodDiet.EGG, OpenFoodFacts.diet(setOf("en:non-vegan", "en:vegetarian", "en:eggs")))
        assertEquals(FoodDiet.FISH, OpenFoodFacts.diet(setOf("en:non-vegetarian", "en:fish")))
        assertEquals(FoodDiet.MEAT, OpenFoodFacts.diet(setOf("en:non-vegetarian")))
        assertNull(OpenFoodFacts.diet(setOf("en:vegan-status-unknown", "en:vegetarian-status-unknown")))
    }

    @Test
    fun `only barcodes with a right check digit are looked up`() {
        assertTrue(Barcodes.valid("4006381333931")) // EAN-13
        assertTrue(Barcodes.valid("96385074")) // EAN-8
        assertTrue(Barcodes.valid("036000291452")) // UPC-A
        assertFalse(Barcodes.valid("4006381333932"))
        assertFalse(Barcodes.valid("12345"))
        assertFalse(Barcodes.valid("40063813339A1"))
    }

    @Test
    fun `a UPC-E is checked and looked up as its UPC-A`() {
        // The check digit is the expanded UPC-A's, not the 8 digits' own (review S1).
        assertEquals("042100005264", Barcodes.upcEtoUpcA("04252614"))
        assertEquals("042100005264", Barcodes.lookupCode("04252614", upcE = true))
        assertEquals("065100004327", Barcodes.lookupCode("06543217", upcE = true))
        // Each zero-suppression rule, by the last of the six digits.
        assertEquals("042100005264", Barcodes.upcEtoUpcA("04252614")) // 0–2
        assertEquals("012300000455", Barcodes.upcEtoUpcA("01234535")) // 3
        assertEquals("012340000058", Barcodes.upcEtoUpcA("01234548")) // 4
        assertEquals("012345000065", Barcodes.upcEtoUpcA("01234565")) // 5–9
        // Its own mod-10 passes, but the UPC-A's doesn't: a misread.
        assertNull(Barcodes.lookupCode("00000017", upcE = true))
        assertNull(Barcodes.upcEtoUpcA("24252614"))
        // Other formats are checked as read.
        assertEquals("96385074", Barcodes.lookupCode("96385074"))
        assertNull(Barcodes.lookupCode("4006381333932"))
    }

    @Test
    fun `taking a slot checks and records together, so the last one goes once`() {
        val t = OffThrottle()
        repeat(9) { assertEquals(0L, t.tryAcquire(OffThrottle.Kind.SEARCH, 1_000L)) }
        assertEquals(0L, t.tryAcquire(OffThrottle.Kind.SEARCH, 1_000L))
        assertEquals(60_000L, t.tryAcquire(OffThrottle.Kind.SEARCH, 1_000L))
        // A refused try records nothing: the window still opens a minute after the first.
        assertEquals(0L, t.tryAcquire(OffThrottle.Kind.SEARCH, 61_000L))
    }

    @Test
    fun `the throttle keeps to the per-minute limits and backs off on a 429`() {
        val t = OffThrottle()
        repeat(10) { t.sentAt(OffThrottle.Kind.SEARCH, 1_000L + it) }
        assertEquals(59_000L, t.waitMs(OffThrottle.Kind.SEARCH, 2_000))
        assertEquals(0L, t.waitMs(OffThrottle.Kind.SEARCH, 61_000))
        // Product reads have their own budget.
        assertEquals(0L, t.waitMs(OffThrottle.Kind.PRODUCT, 2_000))

        val b = OffThrottle()
        b.refused(now = 0, retryAfterMs = null)
        assertEquals(60_000L, b.waitMs(OffThrottle.Kind.PRODUCT, 0))
        b.refused(now = 60_000, retryAfterMs = null)
        assertEquals(120_000L, b.waitMs(OffThrottle.Kind.SEARCH, 60_000))
        b.answered()
        b.refused(now = 300_000, retryAfterMs = 5_000)
        assertEquals(5_000L, b.waitMs(OffThrottle.Kind.SEARCH, 300_000))
    }

    @Test
    fun `photos scale to about 1500 px on the long edge, never up`() {
        assertEquals(1500 to 1125, PhotoScale.size(4000, 3000))
        assertEquals(844 to 1500, PhotoScale.size(2250, 4000))
        assertEquals(800 to 600, PhotoScale.size(800, 600))
        assertEquals(2, PhotoScale.sampleSize(4000, 3000))
        assertEquals(1, PhotoScale.sampleSize(2000, 1500))
        assertEquals(4, PhotoScale.sampleSize(8000, 6000))
    }
}
