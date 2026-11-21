package app.recomp

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-05's rule (TRD §10): weights and lengths are formatted in one place, so the units setting reaches every screen.
 * This fails on a Kotlin string literal that spells "kg", "lb" or "cm" outside the formatters and
 * the places that stay SI on purpose. Comments and KDoc aren't checked.
 */
class UnitsGuardTest {
    private val roots = listOf(File("src/main/java"), File("../domain/src/main/kotlin"))

    /** Files that may spell units: the formatters, and SI-by-design outputs (CSV, Health Connect). */
    private val allowed = setOf(
        "Labels.kt", "BodyText.kt", "GoalText.kt", "UnitMath.kt", "Units.kt", "UnitsSetting.kt", "UnitFields.kt",
        "CsvExport.kt", "HealthWrites.kt", "HealthConnectWriter.kt",
        // Food stays in grams; its parser reads "kg" in what you type (PRD §12.16).
        "FoodWords.kt",
    )

    /** Unit words inside a string literal: "42 kg", "kg", "$x cm", "in kg". Inches aren't checked: "in" is a common word. */
    private val unit = Regex("""(?<![A-Za-z])(kg|lbs?|cm|pounds?|kilos?|kilograms?|inch(es)?|ft)(?![A-Za-z])""")

    /** Literal text that names a unit without formatting a value: g per kg (protein, PRD §12.16), "/kg". */
    private val exempt = listOf(
        Regex("""\$\{[^}]*\}"""), // a template's code, e.g. ${BodyText.weight(kg, style)}
        Regex("""per kg"""), Regex("""g/kg"""), // protein stays g per kg
        Regex("""Weights in (kg|lb), lengths in (cm|inches)\."""), // the programme report's units line
    )

    private fun literals(line: String): List<String> {
        val code = line.substringBefore("//").trimStart()
        if (code.startsWith("*") || code.startsWith("/*")) return emptyList()
        return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(code).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `no weight or length unit is spelled outside the formatters`() {
        val offenders = roots.flatMap { root -> root.walkTopDown().filter { it.extension == "kt" && it.name !in allowed }.toList() }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    literals(line).firstOrNull { lit -> unit.containsMatchIn(exempt.fold(lit) { acc, r -> r.replace(acc, "") }) }
                        ?.let { "${file.path}:${i + 1}: \"$it\"" }
                }
            }
        assertTrue("Format these through Labels or BodyText with the unit style:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
