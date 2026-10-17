package app.recomp.domain.food.indb

import app.recomp.domain.food.FoodCaveat
import app.recomp.domain.food.FoodDiet
import app.recomp.domain.persona.FoodRegion
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The build-time INDB converter (TRD §6.4) against a synthetic workbook laid out like INDB's, so no
 * test needs the real file, which is never committed.
 */
class IndbConverterTest {
    private val nutrientHeader = listOf(
        "food_code", "food_name", "primarysource", "energy_kcal", "carb_g", "protein_g", "fat_g", "fibre_g",
        "servings_unit", "unit_serving_energy_kcal", "unit_serving_protein_g",
    )
    private val recipeHeader = listOf("recipe_code", "recipe_name", "ingredient_name_org", "amount_org", "unit_org", "food_code", "food_name", "amount", "unit")

    private fun nutrients(vararg rows: List<Any?>) = Xlsx.table(ByteArrayInputStream(workbook("Nutrient Data", listOf(nutrientHeader) + rows)))
    private fun recipes(vararg rows: List<Any?>) = Xlsx.table(ByteArrayInputStream(workbook("Sheet1", listOf(recipeHeader) + rows)))

    @Test
    fun `reads shared strings, numbers, inline strings and gaps by column`() {
        val sheets = Xlsx.read(ByteArrayInputStream(workbook("S", listOf(listOf("a", null, 3.5), listOf(null, "b & <c>")), inline = true)))
        assertEquals(listOf(listOf("a", null, "3.5"), listOf(null, "b & <c>")), sheets.getValue("S"))
        assertEquals(27, Xlsx.columnIndex("AB12"))
    }

    @Test
    fun `converts recipes with diet tags, serving units and the frying caveat`() {
        // Invented rows laid out like INDB's: no real INDB values are committed (TRD §6.4).
        val n = nutrients(
            listOf("TST001", "Test spiced milk  (Test doodh) ", "test", 40.0, 5.0, 2.0, 1.5, 0, "Tea Cup", 80.0, 4.0),
            listOf("TST002", "Test fried bread ", "test", 600.0, 50.0, 5.0, 42.0, 2.0, "piece", 300.0, 2.5),
            listOf("TST003", "Test meat stew", "test", 120.0, 3.0, 10.0, 7.0, 0, "bowl", 240.0, 20.0),
            listOf("TST004", "Test vegetable broth", "test", 10.0, 1.0, 0.5, 0.2, 0.5, "", 25.0, 1.25),
            listOf("TST005", "Test light soup", "test", 80.0, 5.0, 1.0, 6.0, 1.0, "Tablespoon", 0, 0.2),
            listOf("TST006", "Broken row", "test", null, 1.0, 1.0, 1.0, 0, "bowl", 1.0, 1.0),
        )
        val r = recipes(
            listOf("TST001", "Test spiced milk", "Milk", "50", "ml", "L002", "Milk, whole", 50, "ml"),
            listOf("TST001", "Test spiced milk", "Sugar", "2", "tsp", "I502", "Sugar, white", 2, "tsp"),
            listOf("TST002", "Test fried bread", "Flour", "100", "g", "A019", "Wheat flour", 100, "g"),
            listOf("TST002", "Test fried bread", "Fat", "for frying", "", "T508", "Oil, sunflower", 2, "C"),
            listOf("TST003", "Test meat stew", "Mutton", "250", "g", "O064", "Mutton, muscle", 250, "g"),
            listOf("TST004", "Test vegetable broth", "Cauliflower", "100", "g", "D036", "Cauliflower", 100, "g"),
            listOf("TST005", "Test light soup", "Fat", "for frying", "", "T508", "Oil, sunflower", 2, "tbsp"),
        )
        val result = IndbConverter.convert(n, r)
        assertEquals(listOf("TST006"), result.skipped)
        val byKey = result.foods.associateBy { it.key }

        val milk = byKey.getValue("indb:TST001")
        assertEquals(FoodDiet.VEGETARIAN, milk.diet)
        assertEquals(FoodRegion.INDIAN, milk.region)
        assertEquals("Test spiced milk (Test doodh)", milk.name)
        assertEquals(40.0, milk.per100g.kcal, 1e-9)
        // grams = per-serving energy ÷ per-100 g energy × 100
        assertEquals(listOf("tea cup" to 200.0), milk.units)
        assertNull(milk.caveat)

        val bread = byKey.getValue("indb:TST002")
        assertEquals("Test fried bread", bread.name)
        assertEquals(FoodDiet.VEGAN, bread.diet)
        assertEquals(FoodCaveat.FRYING_OIL, bread.caveat)
        assertEquals(listOf("piece" to 50.0), bread.units)

        assertEquals(FoodDiet.MEAT, byKey.getValue("indb:TST003").diet)
        // A blank serving unit is a "serving".
        assertEquals(listOf("serving" to 250.0), byKey.getValue("indb:TST004").units)
        // Fried, but too light to be inflated by it; zero serving energy falls back to protein.
        val soup = byKey.getValue("indb:TST005")
        assertNull(soup.caveat)
        assertEquals(listOf("tbsp" to 20.0), soup.units)

        assertTrue(result.summary(), result.summary().startsWith("5 recipes (3 vegan, 1 vegetarian, 0 egg, 0 fish, 1 meat)"))
    }

    @Test
    fun `stock coded as vegetable but worded as chicken makes the recipe meat`() {
        val n = nutrients(listOf("TST101", "Test clear soup", "test", 40.0, 5.0, 2.0, 1.0, 0.5, "bowl", 80.0, 4.0))
        val r = recipes(listOf("TST101", "Test clear soup", "Chicken stock", "240", "ml", "K507", "Stock cubes, vegetable", 240, "ml"))
        assertEquals(FoodDiet.MEAT, IndbConverter.convert(n, r).foods.single().diet)
    }

    @Test
    fun `units too big to be a serving, or that just mean grams, are dropped`() {
        val n = nutrients(
            listOf("TST201", "Test big curry", "test", 100.0, 10.0, 4.0, 5.0, 1.0, "bowl", 700.0, 28.0),
            listOf("TST202", "Test sauce", "test", 100.0, 10.0, 4.0, 5.0, 1.0, "gm", 3.0, 0.12),
            listOf("TST203", "Test drink", "test", 50.0, 10.0, 1.0, 0.5, 0.0, "ml", 2.5, 0.05),
            listOf("TST204", "Test thali", "test", 100.0, 10.0, 4.0, 5.0, 1.0, "plate", 500.0, 20.0),
        )
        val r = recipes(*listOf("TST201", "TST202", "TST203", "TST204").map { listOf(it, "Test", "Rice", "100", "g", "A014", "Rice", 100, "g") }.toTypedArray())
        val units = IndbConverter.convert(n, r).foods.associate { it.key to it.units }
        assertEquals(emptyList<Pair<String, Double>>(), units["indb:TST201"]) // 700 g
        assertEquals(emptyList<Pair<String, Double>>(), units["indb:TST202"])
        assertEquals(emptyList<Pair<String, Double>>(), units["indb:TST203"])
        assertEquals(listOf("plate" to 500.0), units["indb:TST204"])
    }

    @Test
    fun `a recipe with no ingredients is tagged meat, so only non-vegetarians see it`() {
        val n = nutrients(listOf("TST301", "Test mystery dish", "test", 100.0, 10.0, 5.0, 5.0, 1.0, "bowl", 200.0, 10.0))
        val result = IndbConverter.convert(n, recipes())
        assertEquals(FoodDiet.MEAT, result.foods.single().diet)
    }

    @Test
    fun `a missing column fails the conversion`() {
        val broken = Xlsx.table(ByteArrayInputStream(workbook("Nutrient Data", listOf(listOf("food_code", "food_name")))))
        assertThrows(IllegalArgumentException::class.java) { IndbConverter.convert(broken, recipes()) }
    }

    @Test
    fun `writes the food file with escaping and only the fields a food has`() {
        val n = nutrients(listOf("TST302", "Test \"special\" \\ mix", "test", 50.0, 5.0, 2.0, 2.0, 0, "cup", 100.0, 4.0))
        val r = recipes(listOf("TST302", "Test mix", "Milk", "1", "C", "L002", "Milk", 1, "C"))
        val text = FoodFileWriter.write("indb-test", IndbConverter.convert(n, r).foods)
        assertTrue(text, text.startsWith("{\"version\":\"indb-test\",\"foods\":["))
        assertTrue(text, text.contains("\"name\":\"Test \\\"special\\\" \\\\ mix\""))
        assertTrue(text, text.contains("\"diet\":\"VEGETARIAN\",\"region\":\"INDIAN\",\"kcal\":50,\"protein\":2,\"carbs\":5,\"fat\":2,\"fiber\":0"))
        assertTrue(text, text.contains("\"units\":[{\"label\":\"cup\",\"grams\":200}]"))
        assertTrue(text, !text.contains("caveat"))
        assertEquals("\"\\u0001\"", FoodFileWriter.quote("\u0001"))
    }

    @Test
    fun `unit labels are normalised`() {
        assertEquals("tbsp", IndbConverter.unitLabel(" Tablespoon "))
        assertEquals("tall glass", IndbConverter.unitLabel("Tall  glass"))
        assertEquals("serving", IndbConverter.unitLabel(null))
    }

    /** A one-sheet .xlsx: strings go to the shared-strings table (or inline), numbers into <v>. */
    private fun workbook(sheet: String, rows: List<List<Any?>>, inline: Boolean = false): ByteArray {
        val shared = mutableListOf<String>()
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val sheetXml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"$MAIN\"><sheetData>")
            rows.forEachIndexed { r, row ->
                append("<row r=\"${r + 1}\">")
                row.forEachIndexed { c, value ->
                    val ref = ('A' + c).toString() + (r + 1)
                    when (value) {
                        null -> Unit
                        is Number -> append("<c r=\"$ref\"><v>$value</v></c>")
                        else -> if (inline) {
                            append("<c r=\"$ref\" t=\"inlineStr\"><is><t>${esc(value.toString())}</t></is></c>")
                        } else {
                            shared += value.toString()
                            append("<c r=\"$ref\" t=\"s\"><v>${shared.size - 1}</v></c>")
                        }
                    }
                }
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }
        val sharedXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><sst xmlns=\"$MAIN\">" +
            shared.joinToString("") { "<si><t xml:space=\"preserve\">${esc(it)}</t></si>" } + "</sst>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("xl/workbook.xml", "<?xml version=\"1.0\"?><workbook xmlns=\"$MAIN\" xmlns:r=\"$REL\"><sheets><sheet name=\"$sheet\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\" Type=\"worksheet\"/></Relationships>")
            put("xl/sharedStrings.xml", sharedXml)
            put("xl/worksheets/sheet1.xml", sheetXml)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    }
}
