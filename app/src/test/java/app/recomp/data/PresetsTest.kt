package app.recomp.data

import app.recomp.data.seed.PresetData
import app.recomp.domain.model.SessionKind
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.persona.Diet
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.FoodRegion
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Presets
import app.recomp.domain.persona.SplitTemplate
import app.recomp.domain.persona.WeightSource
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PS-05: the presets are data files under `assets/presets` (PRD §4.4). Every bundled one decodes and completes. */
class PresetsTest {
    private val files = FilePresetSource.dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }

    @Test
    fun `every bundled preset decodes, under a key that matches its file`() {
        assertEquals(5, files.size)
        files.forEach { file ->
            val preset = PresetData.parse(file.readText())
            assertEquals(file.nameWithoutExtension, preset.key)
            assertTrue(file.name, preset.name.isNotBlank() && preset.summary.isNotBlank())
        }
        assertEquals(files.size, FilePresetSource.presets().map { it.key }.toSet().size)
    }

    @Test
    fun `every bundled preset completes once its asked values are chosen`() {
        val plan = GoalPlan.of(Goal.MAINTAIN, Pace.STANDARD, null, null, LocalDate.of(2026, 10, 17))
        FilePresetSource.presets().forEach { preset ->
            // Exactly the values the file leaves out are asked, and nothing else stands in the way.
            val asked = preset.draft.missing
            assertTrue(preset.key, "goal" in asked)
            val filled = preset.draft.copy(
                goal = plan,
                experience = preset.draft.experience ?: Experience.BEGINNER,
                diet = preset.draft.diet ?: Diet.NON_VEGETARIAN,
                region = preset.draft.region ?: FoodRegion.INTERNATIONAL,
                split = preset.draft.split ?: SplitTemplate.FULL_BODY_AB,
            )
            val persona = filled.complete()
            assertNotNull(preset.key, persona)
            assertTrue(preset.key, persona!!.strengthDaysPerWeek in 1..7)
            assertTrue(preset.key, persona.extras.all { it.kind.isExtra })
        }
    }

    @Test
    fun `the five presets match the PRD table, in its order`() {
        assertEquals(listOf("reference_vegetarian_recomp", "fat_loss_starter", "lean_bulk", "maintain_stronger", "custom"), Presets.all.map { it.key })
        with(Presets.REFERENCE) {
            assertEquals(Goal.RECOMP, goal)
            assertEquals(Experience.BEGINNER, draft.experience)
            assertEquals(Diet.LACTO_VEGETARIAN, draft.diet)
            assertEquals(FoodRegion.INDIAN, draft.region)
            assertEquals(SplitTemplate.PPL, draft.split)
            assertEquals(listOf("Yoga class" to SessionKind.YOGA), draft.extras.map { it.name to it.kind })
            assertEquals(WeightSource.HOME_SCALE, draft.weightSource)
            assertTrue(draft.missing == listOf("goal"))
        }
        assertEquals(Goal.FAT_LOSS to SplitTemplate.FULL_BODY_AB, Presets.FAT_LOSS_STARTER.goal to Presets.FAT_LOSS_STARTER.draft.split)
        assertEquals(Experience.BEGINNER, Presets.FAT_LOSS_STARTER.draft.experience)
        assertEquals(listOf("goal", "diet", "region"), Presets.FAT_LOSS_STARTER.draft.missing)
        assertEquals(Goal.LEAN_BULK to SplitTemplate.UPPER_LOWER, Presets.LEAN_BULK.goal to Presets.LEAN_BULK.draft.split)
        assertEquals(Experience.INTERMEDIATE, Presets.LEAN_BULK.draft.experience)
        assertEquals(Goal.MAINTAIN to SplitTemplate.FULL_BODY_AB, Presets.MAINTAIN.goal to Presets.MAINTAIN.draft.split)
        assertNull(Presets.MAINTAIN.draft.experience)
        assertNull(Presets.CUSTOM.goal)
        assertEquals(listOf("goal", "experience", "diet", "region", "split"), Presets.CUSTOM.draft.missing)
        assertEquals(Presets.LEAN_BULK, Presets.byKey("lean_bulk"))
        assertNull(Presets.byKey("gone"))
    }

    @Test
    fun `a fork's file adds a preset, and a mistake in one is caught`() {
        val added = PresetData.parse(
            """{"key":"powerlifting_bulk","name":"Powerlifting bulk","summary":"Three heavy days.","order":3,"goal":"LEAN_BULK","experience":"ADVANCED","diet":"NON_VEGETARIAN","region":"INTERNATIONAL","split":"UPPER_LOWER","strengthDaysPerWeek":3,"extras":[{"name":"Mobility","kind":"MOBILITY","days":[3,7]}],"proteinRamp":false}""",
        )
        val all = PresetData.sorted(files.map { PresetData.parse(it.readText()) } + added)
        // Ordered by `order`, then by name.
        assertEquals(listOf("lean_bulk", "powerlifting_bulk"), all.map { it.key }.filter { it.endsWith("bulk") })
        val preset = all.first { it.key == "powerlifting_bulk" }
        assertEquals(listOf("goal"), preset.draft.missing)
        assertEquals(3, preset.draft.strengthDaysPerWeek)
        assertEquals(false, preset.draft.proteinRamp)
        assertEquals(setOf(java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.SUNDAY), preset.draft.extras.single().days)

        fun fails(json: String) = assertTrue(json, runCatching { PresetData.parse(json) }.isFailure)
        val base = """"key":"x","name":"X","summary":"s""""
        fails("""{$base,"goal":"SHRED"}""")
        fails("""{$base,"spilt":"PPL"}""")
        fails("""{$base,"strengthDaysPerWeek":9}""")
        fails("""{$base,"extras":[{"name":"Lifting","kind":"STRENGTH"}]}""")
        fails("""{$base,"extras":[{"name":"Yoga","kind":"YOGA","days":[8]}]}""")
        fails("""{"name":"X","summary":"s"}""")
        assertTrue(runCatching { PresetData.sorted(listOf(added, added)) }.isFailure)
    }

    @Test
    fun `on the phone a bad file is skipped, not a crash (N3)`() {
        val good = files.map { it.name to it.readText() }
        val skipped = mutableListOf<String>()
        val presets = PresetData.load(good + ("broken.json" to """{"key":"x","name":"X","summary":"s","goal":"SHRED"}""") + ("zz_copy.json" to good.first().second)) { name, _ -> skipped += name }
        assertEquals(listOf("broken.json", "zz_copy.json"), skipped)
        assertEquals(FilePresetSource.presets(), presets)
    }
}
