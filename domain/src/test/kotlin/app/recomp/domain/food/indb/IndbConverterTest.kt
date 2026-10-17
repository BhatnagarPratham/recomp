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
        val n = nutrients(
            listOf("ASC001", "Hot tea (Garam Chai)", "asc_manual", 16.144, 2.58, 0.388, 0.532, 0, "tea cup", 33.98, 0.8165),
            listOf("ASC107", "Poori ", "asc_manual", 738.0, 40.0, 1.4, 78.0, 2.0, "poori", 922.5, 1.75),
            listOf("ASC232", "Mutton yakhni", "asc_manual", 104.0, 3.0, 8.3, 6.0, 0, "bowl", 241.28, 19.3),
            listOf("BFP030", "Cauliflower canjee", "bfp_manual", 7.0, 1.0, 0.4, 0.1, 0.5, "", 12.5, 0.7),
            listOf("ASC079", "Clear tomato soup", "asc_manual", 80.0, 5.0, 1.0, 6.0, 1.0, "Tablespoon", 0, 0.2),
            listOf("OSR001", "Broken row", "osr", null, 1.0, 1.0, 1.0, 0, "bowl", 1.0, 1.0),
        )
        val r = recipes(
            listOf("ASC001", "Hot tea", "Milk", "50", "ml", "L002", "Milk, whole, Cow", 50, "ml"),
            listOf("ASC001", "Hot tea", "Sugar", "2", "tsp", "I502", "Sugar, white", 2, "tsp"),
            listOf("ASC107", "Poori", "Atta", "100", "g", "A019", "Wheat flour, atta", 100, "g"),
            listOf("ASC107", "Poori", "Fat", "for frying", "", "T508", "Oil, sunflower", 2, "C"),
            listOf("ASC232", "Mutton yakhni", "Mutton", "250", "g", "O064", "MUTTON, muscle", 250, "g"),
            listOf("BFP030", "Cauliflower canjee", "Cauliflower", "100", "g", "D036", "Cauliflower", 100, "g"),
            listOf("ASC079", "Clear tomato soup", "Fat", "for frying", "", "T508", "Oil, sunflower", 2, "tbsp"),
        )
        val result = IndbConverter.convert(n, r)
        assertEquals(listOf("OSR001"), result.skipped)
        val byKey = result.foods.associateBy { it.key }

        val tea = byKey.getValue("indb:ASC001")
        assertEquals(FoodDiet.VEGETARIAN, tea.diet)
        assertEquals(FoodRegion.INDIAN, tea.region)
        assertEquals("Hot tea (Garam Chai)", tea.name)
        assertEquals(16.14, tea.per100g.kcal, 1e-9)
        // grams = per-serving energy ÷ per-100 g energy × 100
        assertEquals(listOf("tea cup" to 210.5), tea.units)
        assertNull(tea.caveat)

        val poori = byKey.getValue("indb:ASC107")
        assertEquals("Poori", poori.name)
        assertEquals(FoodDiet.VEGAN, poori.diet)
        assertEquals(FoodCaveat.FRYING_OIL, poori.caveat)
        assertEquals(listOf("poori" to 125.0), poori.units)

        assertEquals(FoodDiet.MEAT, byKey.getValue("indb:ASC232").diet)
        // A blank serving unit is a "serving".
        assertEquals(listOf("serving" to 178.6), byKey.getValue("indb:BFP030").units)
        // Fried, but too light to be inflated by it; zero serving energy falls back to protein.
        val soup = byKey.getValue("indb:ASC079")
        assertNull(soup.caveat)
        assertEquals(listOf("tbsp" to 20.0), soup.units)

        assertTrue(result.summary(), result.summary().startsWith("5 recipes (3 vegan, 1 vegetarian, 0 egg, 0 fish, 1 meat)"))
    }

    @Test
    fun `a recipe with no ingredients is tagged meat, so only non-vegetarians see it`() {
        val n = nutrients(listOf("ASC500", "Mystery dish", "asc", 100.0, 10.0, 5.0, 5.0, 1.0, "bowl", 200.0, 10.0))
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
        val n = nutrients(listOf("ASC009", "Chai \"special\" \\ mix", "asc", 50.0, 5.0, 2.0, 2.0, 0, "cup", 100.0, 4.0))
        val r = recipes(listOf("ASC009", "Chai", "Milk", "1", "C", "L002", "Milk", 1, "C"))
        val text = FoodFileWriter.write("indb-test", IndbConverter.convert(n, r).foods)
        assertTrue(text, text.startsWith("{\"version\":\"indb-test\",\"foods\":["))
        assertTrue(text, text.contains("\"name\":\"Chai \\\"special\\\" \\\\ mix\""))
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
