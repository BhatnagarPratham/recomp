package app.recomp.domain.food

import app.recomp.domain.ai.Confidence
import app.recomp.domain.persona.Diet
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs a suspend block that never really suspends, so `:domain` needs no coroutine library. */
fun <T> sync(block: suspend () -> T): T {
    var out: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
    return out!!.getOrThrow()
}

/** An invented food for the parser's corpus. */
data class TestFood(val food: IndexedFood, val diet: FoodDiet)

/** A stand-in for the search index: whole-word matches over invented foods, limited to what [diet] allows. */
class TestIndex(private val foods: List<TestFood>, private val diet: Diet = Diet.NON_VEGETARIAN) : FoodIndex {
    val lookups = mutableListOf<List<String>>()

    override suspend fun lookup(words: List<String>): List<IndexedFood> {
        lookups += words
        return foods.filter { diet.allows(it.diet) && FoodWords.words(it.food.name).containsAll(words) }.map { it.food }
    }

    override val unitLabels: Set<String> = foods.flatMap { f -> f.food.units.map { it.first } }.toSet()
}

/**
 * AI-04's local parser against an invented corpus (TRD §10 M9): fractions, numbers in words, "and", units and
 * synonyms, unknown chunks and the diet filter. The foods and every phrase are made up for the test.
 */
class DescribeParserTest {
    private var nextId = 1L

    private fun food(
        name: String,
        diet: FoodDiet = FoodDiet.VEGAN,
        source: FoodSource = FoodSource.INDB,
        units: List<Pair<String, Double>> = emptyList(),
        useCount: Int = 0,
        lastUnit: String? = null,
        kcal: Double = 100.0,
    ) = TestFood(IndexedFood(nextId++, name, source, Per100g(kcal, 5.0, 15.0, 2.0, 1.0), units, useCount, lastUnit = lastUnit), diet)

    private val foods = listOf(
        food("Chapati/Roti", units = listOf("roti" to 40.0)),
        food("Makki ki roti", units = listOf("roti" to 60.0)),
        food("Dal makhani", FoodDiet.VEGETARIAN, units = listOf("bowl" to 260.0)),
        food("Rajma (kidney beans), boiled", source = FoodSource.SEED),
        food("Boiled rice (Uble chawal)", units = listOf("plate" to 180.0)),
        food("Spanish rice", units = listOf("plate" to 200.0)),
        food("Egg, whole", FoodDiet.EGG, FoodSource.SEED, units = listOf("egg" to 50.0)),
        food("Egg white", FoodDiet.EGG, FoodSource.SEED, units = listOf("egg white" to 33.0)),
        food("Egg curry", FoodDiet.EGG, units = listOf("bowl" to 250.0)),
        food("Milk, whole", FoodDiet.VEGETARIAN, FoodSource.SEED),
        food("Milk, toned (3% fat)", FoodDiet.VEGETARIAN, FoodSource.SEED),
        food("Idli", units = listOf("piece" to 45.0)),
        food("Sambar", units = listOf("bowl" to 200.0)),
        food("Poha"),
        food("Poha with curd (Poha aur dahi)", FoodDiet.VEGETARIAN, units = listOf("plate" to 220.0)),
        food("Paneer", FoodDiet.VEGETARIAN, FoodSource.SEED, units = listOf("piece" to 25.0)),
        food("Paneer, low-fat", FoodDiet.VEGETARIAN, FoodSource.SEED, units = listOf("piece" to 25.0)),
        food("Curd (dahi), whole milk", FoodDiet.VEGETARIAN, FoodSource.SEED),
        food("Curd rice (Dahi bhaat)", FoodDiet.VEGETARIAN, units = listOf("bowl" to 230.0)),
        food("Plain khitchdi (Plain khichri/khichdi)", units = listOf("bowl" to 258.0)),
        food("Biscuit (glucose)", FoodDiet.VEGETARIAN, units = listOf("biscuit" to 6.0)),
        food("Chicken curry", FoodDiet.MEAT, units = listOf("bowl" to 240.0)),
        food("Hot tea (Garam Chai)", FoodDiet.VEGETARIAN, units = listOf("tea cup" to 210.0)),
        food("Oats porridge", FoodDiet.VEGETARIAN),
    )
    private val household = mapOf("katori" to 150.0, "glass" to 250.0, "tbsp" to 15.0)

    private fun parse(text: String, index: FoodIndex = TestIndex(foods), household: Map<String, Double> = this.household) =
        sync { DescribeParser.parse(text, index, household) }

    private fun only(text: String, index: FoodIndex = TestIndex(foods)): ParsedItem {
        val meal = parse(text, index)
        assertEquals("unmatched in “$text”", emptyList<String>(), meal.unmatched)
        assertEquals("items in “$text”: ${meal.items.map { it.food.name }}", 1, meal.items.size)
        return meal.items.single()
    }

    private fun assertItem(text: String, name: String, grams: Double, index: FoodIndex = TestIndex(foods)) {
        val item = only(text, index)
        assertEquals(text, name, item.food.name)
        assertEquals(text, grams, item.grams, 1e-6)
    }

    @Test
    fun `the spec's example matches two foods and lists the third`() {
        val meal = parse("2 roti, 1 katori dal makhani and salad")
        assertEquals(listOf("Chapati/Roti", "Dal makhani"), meal.items.map { it.food.name })
        assertEquals(listOf(80.0, 150.0), meal.items.map { it.grams })
        assertEquals(listOf("roti", "katori"), meal.items.map { it.unit })
        assertEquals(listOf(2.0, 1.0), meal.items.map { it.quantity })
        assertEquals(listOf("salad"), meal.unmatched)
        assertEquals("2 roti = Chapati/Roti, 80 g", meal.items[0].counted)
    }

    @Test
    fun `synonyms and plurals find the same food`() {
        assertItem("2 chapati", "Chapati/Roti", 80.0)
        assertItem("3 phulkas", "Chapati/Roti", 120.0)
        assertItem("2 rotis", "Chapati/Roti", 80.0)
        assertItem("2 idlis", "Idli", 90.0)
        assertItem("1 katori dahi", "Curd (dahi), whole milk", 150.0)
        assertItem("1 katori yogurt", "Curd (dahi), whole milk", 150.0)
        assertItem("1 plate chawal", "Boiled rice (Uble chawal)", 180.0)
    }

    @Test
    fun `fractions, however they are written`() {
        assertItem("half a bowl of rajma", "Rajma (kidney beans), boiled", 75.0)
        assertItem("½ katori rajma", "Rajma (kidney beans), boiled", 75.0)
        assertItem("1/2 bowl rajma", "Rajma (kidney beans), boiled", 75.0)
        assertItem("1 1/2 katori rajma", "Rajma (kidney beans), boiled", 225.0)
        assertItem("2½ roti", "Chapati/Roti", 100.0)
    }

    @Test
    fun `an and inside an amount doesn't split the chunk`() {
        assertItem("one and a half plates rice", "Boiled rice (Uble chawal)", 270.0)
        assertItem("1 and 1/2 plate rice", "Boiled rice (Uble chawal)", 270.0)
        val meal = parse("two and a half roti and one and a half katori rajma")
        assertEquals(listOf(100.0, 225.0), meal.items.map { it.grams })
    }

    @Test
    fun `numbers in words, and and with between foods`() {
        val meal = parse("two eggs and a glass of milk")
        assertEquals(listOf("Egg, whole", "Milk, whole"), meal.items.map { it.food.name })
        assertEquals(listOf(100.0, 250.0), meal.items.map { it.grams })
        val idli = parse("a couple of idlis with sambar")
        assertEquals(listOf("Idli", "Sambar"), idli.items.map { it.food.name })
        assertEquals(listOf(90.0, 200.0), idli.items.map { it.grams })
        assertEquals(emptyList<String>(), idli.unmatched)
    }

    @Test
    fun `a dish whose own name has with in it stays one item`() {
        assertItem("1 plate poha with curd", "Poha with curd (Poha aur dahi)", 220.0)
        // Without that dish in the index, the same words are two foods.
        val meal = parse("poha with curd", TestIndex(foods.filterNot { it.food.name.startsWith("Poha with") }))
        assertEquals(listOf("Poha", "Curd (dahi), whole milk"), meal.items.map { it.food.name })
    }

    @Test
    fun `weights go straight to grams`() {
        listOf("150 g paneer", "150g paneer", "paneer 150 g", "paneer - 150g", "0.15 kg paneer", "150 grams of paneer").forEach { text ->
            val item = only(text)
            assertEquals(text, "Paneer", item.food.name)
            assertEquals(text, 150.0, item.grams, 1e-6)
            assertEquals(text, Portions.GRAMS, item.unit)
            assertEquals(text, 150.0, item.quantity, 1e-6)
            assertEquals(text, Confidence.HIGH, item.confidence)
        }
        assertItem("200 ml toned milk", "Milk, toned (3% fat)", 200.0)
        assertItem("1 l milk", "Milk, whole", 1000.0)
    }

    @Test
    fun `the amount can come after the name`() {
        assertItem("roti x2", "Chapati/Roti", 80.0)
        assertItem("roti 2", "Chapati/Roti", 80.0)
        assertItem("rajma 1 katori", "Rajma (kidney beans), boiled", 150.0)
        assertItem("2-3 biscuits", "Biscuit (glucose)", 15.0)
    }

    @Test
    fun `no amount means one serving`() {
        assertItem("roti", "Chapati/Roti", 40.0)
        assertItem("katori rajma", "Rajma (kidney beans), boiled", 150.0)
        assertItem("glass of milk", "Milk, whole", 250.0)
    }

    @Test
    fun `every typed word must be in the name, so dal makhani never becomes plain dal`() {
        val plain = TestIndex(listOf(food("Dal", units = listOf("bowl" to 200.0))))
        val meal = parse("1 katori dal makhani", plain)
        assertEquals(emptyList<ParsedItem>(), meal.items)
        assertEquals(listOf("1 katori dal makhani"), meal.unmatched)
    }

    @Test
    fun `an exact name beats a longer one, and your own exact name beats the database's`() {
        val dal = food("Dal", units = listOf("bowl" to 200.0))
        val makhani = food("Dal makhani", FoodDiet.VEGETARIAN, source = FoodSource.CUSTOM, useCount = 40)
        assertEquals("Dal", only("dal", TestIndex(listOf(makhani, dal))).food.name)

        val mine = food("Dal", source = FoodSource.CUSTOM, kcal = 130.0)
        val used = dal.copy(food = dal.food.copy(useCount = 25))
        val picked = only("1 bowl dal", TestIndex(listOf(used, mine)))
        assertEquals(mine.food.id, picked.food.id)
        assertEquals("a bowl is a katori for a food with no bowl of its own", 150.0, picked.grams, 1e-6)
    }

    @Test
    fun `without an exact name, the food you log most wins, then the everyday one`() {
        assertEquals("Boiled rice (Uble chawal)", only("rice").food.name)
        val logged = foods.map { if (it.food.name == "Spanish rice") it.copy(food = it.food.copy(useCount = 3)) else it }
        assertEquals("Spanish rice", only("rice", TestIndex(logged)).food.name)
        assertEquals("Paneer", only("paneer").food.name)
        assertEquals("Milk, whole", only("milk").food.name)
        assertEquals("Egg, whole", only("egg").food.name)
    }

    @Test
    fun `an alias in brackets or after a slash is an exact name`() {
        assertEquals(Confidence.HIGH, only("1 katori curd").confidence)
        assertEquals("Curd (dahi), whole milk", only("curd").food.name)
        assertItem("1 bowl khichdi", "Plain khitchdi (Plain khichri/khichdi)", 258.0)
        assertItem("1 bowl khichri", "Plain khitchdi (Plain khichri/khichdi)", 258.0)
    }

    @Test
    fun `a food's own unit beats the household measure of the same word`() {
        assertItem("1 bowl dal makhani", "Dal makhani", 260.0)
        assertItem("1 bowl rajma", "Rajma (kidney beans), boiled", 150.0)
        assertItem("1 katori dal makhani", "Dal makhani", 150.0)
        assertItem("1 cup tea", "Hot tea (Garam Chai)", 210.0)
        assertItem("2 tbsp curd", "Curd (dahi), whole milk", 30.0)
        assertItem("1 tsp curd", "Curd (dahi), whole milk", 5.0)
        assertItem("2 pcs paneer", "Paneer", 50.0)
    }

    @Test
    fun `calibrated household measures are used`() {
        val item = parse("1 katori rajma", household = mapOf("katori" to 180.0)).items.single()
        assertEquals(180.0, item.grams, 1e-6)
    }

    @Test
    fun `a unit word that is part of the name is read as the name`() {
        assertItem("egg curry", "Egg curry", 250.0)
        assertItem("1 egg curry", "Egg curry", 250.0)
        assertItem("2 egg whites", "Egg white", 66.0)
        assertItem("2 eggs", "Egg, whole", 100.0)
    }

    /** A low-confidence match is never an item: it's a guess, offered with the unmatched parts (S1). */
    private fun guess(text: String, index: FoodIndex = TestIndex(foods)): ParsedItem {
        val meal = parse(text, index)
        assertEquals("items in “$text”: ${meal.items.map { it.food.name }}", emptyList<ParsedItem>(), meal.items)
        assertEquals("unmatched in “$text”", emptyList<String>(), meal.unmatched)
        val g = meal.guesses.single()
        assertEquals(Confidence.LOW, g.confidence)
        assertEquals(listOf(g.typed), meal.unsure)
        return g
    }

    @Test
    fun `a unit the food doesn't have falls back to its usual serving, as a guess`() {
        val item = guess("2 slices paneer")
        assertEquals("Paneer", item.food.name)
        assertEquals(50.0, item.grams, 1e-6)
        assertEquals("piece", item.unit)
        assertEquals(Confidence.LOW, item.confidence)
        assertTrue(item.note, "slice" in item.note)
    }

    @Test
    fun `no serving size counts 100 g each and says so`() {
        val item = only("2 oats porridge")
        assertEquals(200.0, item.grams, 1e-6)
        assertEquals(Portions.GRAMS, item.unit)
        assertEquals(200.0, item.quantity, 1e-6)
        assertTrue(item.note, "100 g" in item.note)
        assertTrue(item.confidence != Confidence.HIGH)
    }

    @Test
    fun `the unit you logged a food in last is its serving`() {
        val rajma = food("Rajma masala", source = FoodSource.CUSTOM, useCount = 4, lastUnit = "katori")
        val item = only("rajma masala", TestIndex(listOf(rajma)))
        assertEquals(150.0, item.grams, 1e-6)
        assertEquals("katori", item.unit)
        assertEquals(Confidence.HIGH, item.confidence)
    }

    @Test
    fun `confidence follows how sure the match and the amount are`() {
        assertEquals(Confidence.HIGH, only("2 roti").confidence)
        assertEquals(Confidence.HIGH, only("1 katori rajma").confidence)
        assertEquals("only everyday words differ: rice is boiled rice", Confidence.MEDIUM, only("1 plate rice").confidence)
        assertEquals("not an exact name and never logged", "Spanish rice", guess("1 plate spanish").food.name)
        val logged = foods.map { if (it.food.name == "Spanish rice") it.copy(food = it.food.copy(useCount = 2)) else it }
        assertEquals(Confidence.MEDIUM, only("1 plate spanish", TestIndex(logged)).confidence)
    }

    @Test
    fun `the diet filter keeps excluded foods out`() {
        val vegetarian = parse("1 bowl chicken curry, 2 eggs, 1 katori dal makhani", TestIndex(foods, Diet.LACTO_VEGETARIAN))
        assertEquals(listOf("Dal makhani"), vegetarian.items.map { it.food.name })
        assertEquals(listOf("1 bowl chicken curry", "2 eggs"), vegetarian.unmatched)

        val vegan = parse("1 katori dal makhani, 2 roti, 1 glass milk", TestIndex(foods, Diet.VEGAN))
        assertEquals(listOf("Chapati/Roti"), vegan.items.map { it.food.name })

        val all = parse("1 bowl chicken curry, 2 eggs")
        assertEquals(listOf("Chicken curry", "Egg, whole"), all.items.map { it.food.name })
    }

    @Test
    fun `unknown chunks are listed as typed, and nothing is nothing`() {
        assertEquals(listOf("blorptastic stew"), parse("blorptastic stew").unmatched)
        assertEquals(ParsedMeal(emptyList(), emptyList()), parse(""))
        assertEquals(ParsedMeal(emptyList(), emptyList()), parse(" , ,, ; "))
        assertEquals(ParsedMeal(emptyList(), emptyList()), parse("and"))
        assertEquals(ParsedMeal(emptyList(), emptyList()), parse("2"))
        val meal = parse("2 roti, zorple, 1 katori rajma")
        assertEquals(2, meal.items.size)
        assertEquals(listOf("zorple"), meal.unmatched)
    }

    @Test
    fun `commas, semicolons, plus signs, ampersands and new lines all separate foods`() {
        val meal = parse("2 roti; 1 katori rajma + 1 glass milk\n2 idli & sambar")
        assertEquals(listOf("Chapati/Roti", "Rajma (kidney beans), boiled", "Milk, whole", "Idli", "Sambar"), meal.items.map { it.food.name })
        assertEquals(emptyList<String>(), meal.unmatched)
    }

    @Test
    fun `case, stray punctuation and filler words don't matter`() {
        assertItem("  2 ROTI!! ", "Chapati/Roti", 80.0)
        assertItem("some fresh paneer (150 g)", "Paneer", 150.0)
        assertItem("1 small katori of the rajma", "Rajma (kidney beans), boiled", 150.0)
    }

    @Test
    fun `a chunk's name leaves out its amount and unit`() {
        assertEquals("cucumber salad", DescribeParser.nameOf("1 katori cucumber salad"))
        assertEquals("salad", DescribeParser.nameOf("salad"))
        assertEquals("paneer", DescribeParser.nameOf("150 g paneer"))
        assertEquals("2", DescribeParser.nameOf(" 2 "))
    }

    @Test
    fun `a long description is cut off, not refused`() {
        val long = List(40) { "2 roti" }.joinToString(", ")
        val meal = parse(long)
        assertEquals(DescribeParser.MAX_CHUNKS, meal.items.size)
        assertNotNull(parse("x".repeat(5_000)))
    }

    @Test
    fun `the item in the review's shape carries the food's numbers for its weight`() {
        val item = only("2 roti").estimated()
        assertEquals("Chapati/Roti", item.name)
        assertEquals(80.0, item.grams, 1e-6)
        assertEquals(80.0, item.kcal, 1e-6)
        assertEquals(4.0, item.proteinG, 1e-6)
        assertEquals(2.0, item.quantity, 1e-6)
        assertEquals("roti", item.unit)
    }

    // PR #15's review: the reviewer's probes as cases, over invented foods named like the database's.

    @Test
    fun `S1 - a wrong dish that merely contains the word is a guess, never a match`() {
        val index = TestIndex(
            listOf(
                food("Vegetarian egg kofta curry", FoodDiet.VEGETARIAN, units = listOf("bowl" to 250.0)),
                food("Coffee drops", units = listOf("cup" to 240.0), kcal = 440.0),
                food("Apple mousse", FoodDiet.VEGETARIAN, units = listOf("bowl" to 365.0)),
                food("Stewed apple", units = listOf("bowl" to 150.0)),
                food("Banana raita", FoodDiet.VEGETARIAN, units = listOf("bowl" to 200.0)),
                food("Banana cake", FoodDiet.EGG, units = listOf("slice" to 60.0)),
                food("Bread upma", units = listOf("bowl" to 200.0)),
            ),
        )
        listOf("1 egg" to "Vegetarian egg kofta curry", "1 apple" to "Apple mousse", "2 bananas" to "Banana raita", "2 slices bread" to "Bread upma")
            .forEach { (typed, dish) -> assertEquals(typed, dish, guess(typed, index).food.name) }
        // With other parts typed, the guess still isn't counted.
        val coffee = parse("1 cup coffee with milk", index)
        assertTrue(coffee.items.none { it.food.name == "Coffee drops" })
        assertEquals(listOf("1 cup coffee"), coffee.guesses.map { it.typed })
        assertTrue(coffee.unsure.containsAll(listOf("1 cup coffee", "milk")))
    }

    @Test
    fun `S1 - a word the name says the food is without never matches`() {
        val index = TestIndex(listOf(food("Mayonnaise without eggs", FoodDiet.VEGETARIAN, units = listOf("cup" to 387.0)), food("Eggless cake (no egg)", FoodDiet.VEGETARIAN)))
        val meal = parse("2 eggs", index)
        assertEquals(emptyList<ParsedItem>(), meal.items + meal.guesses)
        assertEquals(listOf("2 eggs"), meal.unmatched)
        assertTrue(FoodWords.onlyNegated("Mayonnaise without eggs", "egg"))
        assertTrue(!FoodWords.onlyNegated("Egg curry, no onion", "egg"))
    }

    @Test
    fun `S1 - a sure match with an unsure one keeps only the sure one as matched`() {
        val meal = parse("2 roti and 2 slices paneer")
        assertEquals(listOf("Chapati/Roti"), meal.items.map { it.food.name })
        assertEquals(listOf("2 slices paneer"), meal.guesses.map { it.typed })
    }

    @Test
    fun `S2 - a slash after a longer name is another spelling of its last word, not a bare name`() {
        assertEquals(
            listOf(listOf("dal", "parantha", "paratha"), listOf("dal", "parantha"), listOf("dal", "paratha")),
            FoodWords.aliases("Dal parantha/paratha"),
        )
        assertTrue(FoodWords.aliases("Quinoa khichdi/khichri").none { it == listOf("khichri") })
        assertTrue(FoodWords.aliases("Okra/Lady's fingers fry (Bhindi sabzi/sabji/subji)").none { it.size == 1 && it[0] in setOf("sabji", "subji") })
        assertTrue("between single words each side is a name", FoodWords.aliases("Chapati/Roti").containsAll(listOf(listOf("chapati"), listOf("roti"))))
        assertTrue("after one word too", listOf("okra") in FoodWords.aliases("Okra/Lady's fingers fry (Bhindi sabzi/sabji/subji)"))

        val index = TestIndex(
            listOf(
                food("Dal parantha/paratha", FoodDiet.VEGETARIAN, units = listOf("parantha" to 90.0)),
                food("Quinoa khichdi/khichri", units = listOf("bowl" to 250.0)),
                food("Okra/Lady's fingers fry (Bhindi sabzi/sabji/subji)", units = listOf("bowl" to 150.0)),
            ),
        )
        assertEquals("Dal parantha/paratha", guess("paratha", index).food.name)
        assertEquals("Quinoa khichdi/khichri", guess("khichdi", index).food.name)
        assertEquals("Quinoa khichdi/khichri", guess("1 bowl khichdi", index).food.name)
        assertEquals(Confidence.LOW, guess("subji", index).confidence)
        assertEquals(Confidence.LOW, guess("1 bowl sabji", index).confidence)
        // The full spelling is still exact.
        assertEquals(Confidence.HIGH, parse("1 bowl quinoa khichri", index).items.single().confidence)
        assertEquals(Confidence.HIGH, parse("1 bowl bhindi subji", index).items.single().confidence)
        // And the everyday version is a sure match when there is one.
        val plain = TestIndex(listOf(food("Plain parantha/paratha", units = listOf("parantha" to 80.0)), food("Dal parantha/paratha", FoodDiet.VEGETARIAN)))
        assertEquals("Plain parantha/paratha", only("2 paratha", plain).food.name)
        assertEquals(Confidence.MEDIUM, only("2 paratha", plain).confidence)
    }

    @Test
    fun `N1 - a comma between digits is a decimal point`() {
        assertItem("1,5 roti", "Chapati/Roti", 60.0)
        assertEquals(1.5, only("1,5 roti").quantity, 1e-9)
        assertEquals(listOf(80.0, 150.0), parse("2 roti,1 katori rajma").items.map { it.grams })
    }

    @Test
    fun `N2 - a dish-named unit too big for one piece isn't taken as one`() {
        val index = TestIndex(listOf(food("Gulab jamun", FoodDiet.VEGETARIAN, units = listOf("gulab jamun" to 156.0)), food("Boiled egg", FoodDiet.EGG, units = listOf("egg" to 151.0))))
        val jamun = guess("2 gulab jamun", index)
        assertEquals(200.0, jamun.grams, 1e-6)
        assertTrue(jamun.note, "100 g" in jamun.note)
        assertEquals(Confidence.LOW, guess("2 boiled eggs", index).confidence)
        // A piece-sized unit is still used, and your own foods' units are yours.
        val small = TestIndex(listOf(food("Gulab jamun", FoodDiet.VEGETARIAN, units = listOf("gulab jamun" to 40.0))))
        assertEquals(80.0, only("2 gulab jamun", small).grams, 1e-6)
        val mine = TestIndex(listOf(food("Gulab jamun", FoodDiet.VEGETARIAN, FoodSource.CUSTOM, units = listOf("gulab jamun" to 156.0))))
        assertEquals(312.0, only("2 gulab jamun", mine).grams, 1e-6)
    }

    @Test
    fun `a food from a source added later counts as your own`() {
        assertEquals(setOf(FoodSource.SEED, FoodSource.INDB, FoodSource.OFF), IndexedFood.BUNDLED)
        FoodSource.entries.forEach { source ->
            val food = IndexedFood(1, "X", source, Per100g(1.0, 1.0, 1.0, 1.0))
            assertEquals(source.name, source !in IndexedFood.BUNDLED, food.own)
        }
    }
}
