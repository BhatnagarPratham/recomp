package app.recomp.domain.ai

import app.recomp.domain.food.Origin
import app.recomp.domain.food.Per100g
import app.recomp.domain.persona.Diet
import app.recomp.domain.persona.FoodRegion
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI-01 to AI-03 and NU-17's rules (TRD §6.2): the gate, spend, validation and prompts. Values are invented. */
class AiTest {
    @Test
    fun `the gate checks off, then the key, then the cap, then the connection`() {
        fun state(enabled: Boolean = true, key: Boolean = true, spend: Double = 0.0, cap: Double? = null, online: Boolean = true) =
            AiGate.state(enabled, key, spend, cap, online)

        assertEquals(AiState.READY, state())
        assertEquals(AiState.OFF, state(enabled = false, key = false, spend = 9.0, cap = 1.0, online = false))
        assertEquals(AiState.NO_KEY, state(key = false, spend = 9.0, cap = 1.0, online = false))
        assertEquals(AiState.OVER_CAP, state(spend = 5.0, cap = 5.0, online = false))
        assertEquals(AiState.OFFLINE, state(spend = 4.99, cap = 5.0, online = false))
        assertEquals(AiState.READY, state(spend = 4.99, cap = 5.0))
        // No cap means no limit, whatever the spend.
        assertEquals(AiState.READY, state(spend = 500.0, cap = null))
        // A zero cap pauses AI at once.
        assertEquals(AiState.OVER_CAP, state(spend = 0.0, cap = 0.0))
    }

    @Test
    fun `spend counts toward the calendar month and a cap lifts on the 1st`() {
        assertEquals(LocalDate.of(2026, 10, 1), AiGate.monthStart(LocalDate.of(2026, 10, 18)))
        assertEquals(LocalDate.of(2026, 11, 1), AiGate.resumesOn(LocalDate.of(2026, 10, 31)))
        assertEquals(LocalDate.of(2027, 1, 1), AiGate.resumesOn(LocalDate.of(2026, 12, 1)))
    }

    @Test
    fun `a stored model id maps back to its model, and anything else to Opus 5_5`() {
        assertEquals(AiModel.HAIKU_5_5, AiModel.of("claude-haiku-5-5"))
        assertEquals(AiModel.OPUS_5_5, AiModel.of(null))
        assertEquals(AiModel.OPUS_5_5, AiModel.of("claude-retired-1"))
        assertFalse(AiModel.HAIKU_5_5.serverFallback)
        assertTrue(AiModel.OPUS_5_5.serverFallback)
    }

    @Test
    fun `cost is tokens times the table, with cache writes at 1_25 times input`() {
        val opus = TokenUsage("claude-opus-5-5", inputTokens = 2_000, outputTokens = 1_500)
        assertEquals(0.008 + 0.03, AiPricing.cost(opus), 1e-12)

        val cached = TokenUsage("claude-opus-5-5", inputTokens = 100, outputTokens = 0, cacheReadTokens = 1_000_000, cacheWriteTokens = 1_000_000)
        assertEquals(0.0004 + 0.20 + 5.00, AiPricing.cost(cached), 1e-12)

        assertEquals(0.0002 + 0.00075, AiPricing.cost(TokenUsage("claude-haiku-5-5", 2_000, 1_500)), 1e-12)
    }

    @Test
    fun `a refused attempt and its fallback are each priced at their own model`() {
        val attempts = listOf(
            TokenUsage("claude-opus-5-5", inputTokens = 1_000, outputTokens = 0),
            TokenUsage("claude-opus-5", inputTokens = 1_000, outputTokens = 1_000),
        )
        assertEquals(0.004 + 0.005 + 0.025, AiPricing.cost(attempts), 1e-12)
    }

    @Test
    fun `a request's upper bound is its prompt at 3 characters a token, the overhead and every allowed output token`() {
        val bound = AiPricing.upperBound("claude-opus-5-5", promptChars = 3_000, maxOutputTokens = 8_000)
        assertEquals(1_000 + AiPricing.PROMPT_OVERHEAD_TOKENS, bound.inputTokens)
        assertEquals(8_000L, bound.outputTokens)
        assertEquals((2_500 * 4.0 + 8_000 * 20.0) / 1_000_000, AiPricing.cost(bound), 1e-12)
        val request = MealRequest("dummy-key", AiModel.OPUS_5_5, "s".repeat(2_000), "u".repeat(1_000))
        assertEquals(bound, request.upperBound)
        assertEquals(MealRequest.MAX_OUTPUT_TOKENS, request.upperBound.outputTokens)
    }

    @Test
    fun `a model the table doesn't know is priced at its highest rates`() {
        val unknown = AiPricing.rates("claude-unknown-9")
        assertEquals(AiPricing.table.values.maxOf { it.input }, unknown.input, 0.0)
        assertEquals(AiPricing.table.values.maxOf { it.output }, unknown.output, 0.0)
        assertTrue(AiPricing.cost(TokenUsage("claude-unknown-9", 1_000, 1_000)) >= AiPricing.cost(TokenUsage("claude-opus-5-5", 1_000, 1_000)))
    }

    private fun item(
        name: String = "Phulka",
        grams: Double = 70.0,
        kcal: Double = 200.0,
        protein: Double = 6.0,
        carbs: Double = 40.0,
        fat: Double = 2.0,
        quantity: Double = 2.0,
    ) = EstimatedItem(name, quantity, "piece", grams, kcal, protein, carbs, fat, fiberG = 4.0, Confidence.HIGH, " medium size ")

    @Test
    fun `items without a positive weight or with impossible numbers are rejected and named`() {
        val estimate = MealEstimate(
            listOf(
                item(),
                item(name = "Salad", grams = 0.0),
                item(name = "Chaas", grams = -50.0),
                item(name = "Pickle", fat = -1.0),
                item(name = "Sauce", kcal = Double.NaN),
                item(name = " ", grams = 10.0),
            ),
            clarifyingQuestion = "  ",
        )
        val checked = MealCheck.check(estimate)
        assertEquals(listOf("Phulka"), checked.items.map { it.name })
        assertEquals(listOf("Salad", "Chaas", "Pickle", "Sauce", "An unnamed item"), checked.rejected)
        assertEquals("medium size", checked.items.single().assumptions)
        assertNull(checked.clarifyingQuestion)
    }

    @Test
    fun `totals are recomputed from the kept items`() {
        val checked = MealCheck.check(MealEstimate(listOf(item(kcal = 200.0, protein = 6.0), item(kcal = 150.0, protein = 9.0), item(grams = 0.0, kcal = 999.0)), "Ghee?"))
        assertEquals(350.0, checked.totals.kcal, 1e-9)
        assertEquals(15.0, checked.totals.proteinG, 1e-9)
        assertEquals(8.0, checked.totals.fiberG!!, 1e-9)
        assertEquals("Ghee?", checked.clarifyingQuestion)
    }

    @Test
    fun `an item is flagged when its calories are more than 15 percent from its macros`() {
        // 4 × 6 + 4 × 40 + 9 × 2 = 202 kcal from macros.
        assertFalse(MealCheck.mismatched(item(kcal = 202.0)))
        assertFalse(MealCheck.mismatched(item(kcal = 232.0)))
        assertTrue(MealCheck.mismatched(item(kcal = 233.0)))
        assertTrue(MealCheck.mismatched(item(kcal = 171.0)))
        assertFalse(MealCheck.mismatched(item(kcal = 172.0)))
        // Small items: a gap under 10 kcal never flags.
        assertFalse(MealCheck.mismatched(item(kcal = 12.0, protein = 0.5, carbs = 0.5, fat = 0.0)))
        assertTrue(MealCheck.mismatched(item(kcal = 20.0, protein = 0.5, carbs = 0.5, fat = 0.0)))
    }

    @Test
    fun `a new weight scales the macros and keeps the amount`() {
        val bigger = item(grams = 70.0, kcal = 200.0, protein = 6.0).scaledTo(105.0)
        assertEquals(2.0, bigger.quantity, 0.0)
        assertEquals(105.0, bigger.grams, 0.0)
        assertEquals(300.0, bigger.kcal, 1e-9)
        assertEquals(9.0, bigger.proteinG, 1e-9)
        assertEquals(6.0, bigger.fiberG, 1e-9)
        assertEquals(Per100g(kcal = 200.0 / 0.7, proteinG = 6.0 / 0.7, carbsG = 40.0 / 0.7, fatG = 2.0 / 0.7, fiberG = 4.0 / 0.7), item().per100g)
    }

    @Test
    fun `the system prompt follows the persona's diet and region`() {
        val veg = MealPrompt.system(Diet.LACTO_VEGETARIAN, FoodRegion.INDIAN, mapOf("katori" to 180.0, "glass" to 250.0), emptyList())
        assertTrue(veg.contains("lacto-vegetarian. No meat, fish or egg"))
        assertTrue(veg.contains("vegetarian version of ambiguous dishes, such as biryani, momos"))
        assertTrue(veg.contains("phulka or roti, katori"))
        assertTrue(veg.contains("1 katori = 180 g; 1 glass = 250 g"))
        assertFalse(veg.contains("own foods"))

        val vegan = MealPrompt.system(Diet.VEGAN, FoodRegion.INTERNATIONAL, emptyMap(), emptyList())
        assertTrue(vegan.contains("vegan. No meat, fish, egg, dairy"))
        assertTrue(vegan.contains("cup, slice, piece, tablespoon"))
        assertFalse(vegan.contains("household measures"))

        // Every diet and region has its own rule, and none is blank.
        assertEquals(Diet.entries.size, Diet.entries.map(MealPrompt::dietRule).distinct().size)
        assertEquals(FoodRegion.entries.size, FoodRegion.entries.map(MealPrompt::regionRule).distinct().size)
    }

    @Test
    fun `the system prompt explains every origin, so it doesn't change with the meal's`() {
        val system = MealPrompt.system(Diet.EGGETARIAN, FoodRegion.INDIAN, emptyMap(), emptyList())
        assertTrue(system.contains("- Home: cooked at home"))
        assertTrue(system.contains("- Ordered: restaurant or delivery food. Assume restaurant levels of oil, ghee, butter and cream"))
        assertTrue(system.contains("- Packaged: packaged food"))
        assertEquals(system, MealPrompt.system(Diet.EGGETARIAN, FoodRegion.INDIAN, emptyMap(), emptyList()))
    }

    @Test
    fun `your own foods are listed with their values and units, at most 30`() {
        val foods = (1..40).map { OwnFood("Food $it", Per100g(150.0, 7.5, 20.0, 4.0, 2.0), listOf("piece" to 60.0, "g" to 1.0)) }
        val system = MealPrompt.system(Diet.NON_VEGETARIAN, FoodRegion.INDIAN, emptyMap(), foods)
        assertTrue(system.contains("- Food 1: per 100 g 150 kcal, 7.5 g protein, 20 g carbohydrate, 4 g fat, 2 g fibre; 1 piece = 60 g"))
        assertTrue(system.contains("- Food 30:"))
        assertFalse(system.contains("- Food 31:"))
    }

    @Test
    fun `the user message carries the origin, the meal and any answer to a clarifying question`() {
        assertEquals("Origin: Ordered\nMeal: 2 phulka, dal", MealPrompt.user("  2 phulka, dal ", Origin.ORDERED))
        val again = MealPrompt.user("dal", Origin.HOME, Clarification("Was it tempered with ghee?", "yes, a spoon"))
        assertTrue(again.startsWith("Origin: Home\nMeal: dal\n\nYou asked: Was it tempered with ghee?\nThe user answered: yes, a spoon"))
        assertTrue(again.endsWith("don't ask again."))
    }

    @Test
    fun `a request never prints its key`() {
        val request = MealRequest("dummy-key", AiModel.OPUS_5_5, "system", "user")
        assertFalse(request.toString().contains("dummy"))
    }
}
