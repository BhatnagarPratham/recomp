package app.recomp.domain.persona

import app.recomp.domain.model.SessionKind
import app.recomp.domain.nutrition.Goal

/**
 * A starting point for onboarding (PRD §4.4). [goal] pre-selects a goal card; null values in [draft]
 * are *Asked* in the review step. The goal plan itself is built by the goal flow.
 */
data class Preset(
    val key: String,
    val name: String,
    val summary: String,
    val goal: Goal?,
    val draft: PersonaDraft,
)

/** PRD §4.4. Kotlin objects in the MVP; JSON files under assets/presets from v1.1 (PS-05). */
object Presets {
    val REFERENCE = Preset(
        key = "reference_vegetarian_recomp",
        name = "Vegetarian recomp",
        summary = "Lose fat and build muscle on home-cooked Indian vegetarian food. Push/Pull/Legs, a weekly yoga class and body-composition scans.",
        goal = Goal.RECOMP,
        draft = PersonaDraft(
            experience = Experience.BEGINNER,
            diet = Diet.LACTO_VEGETARIAN,
            region = FoodRegion.INDIAN,
            split = SplitTemplate.PPL,
            extras = listOf(ExtraSession("Yoga class", SessionKind.YOGA)),
            weightSource = WeightSource.SCAN,
        ),
    )

    val FAT_LOSS_STARTER = Preset(
        key = "fat_loss_starter",
        name = "Fat loss starter",
        summary = "Lose fat while keeping your strength. Two full-body days, A and B.",
        goal = Goal.FAT_LOSS,
        draft = PersonaDraft(experience = Experience.BEGINNER, split = SplitTemplate.FULL_BODY_AB),
    )

    val LEAN_BULK = Preset(
        key = "lean_bulk",
        name = "Lean bulk",
        summary = "Build muscle in a small surplus. Upper/Lower, four days a week.",
        goal = Goal.LEAN_BULK,
        draft = PersonaDraft(experience = Experience.INTERMEDIATE, split = SplitTemplate.UPPER_LOWER),
    )

    val MAINTAIN = Preset(
        key = "maintain_stronger",
        name = "Maintain and get stronger",
        summary = "Keep your weight steady and get stronger. Two full-body days, A and B.",
        goal = Goal.MAINTAIN,
        draft = PersonaDraft(split = SplitTemplate.FULL_BODY_AB),
    )

    val CUSTOM = Preset(
        key = "custom",
        name = "Custom",
        summary = "Choose everything yourself.",
        goal = null,
        draft = PersonaDraft(),
    )

    val all: List<Preset> = listOf(REFERENCE, FAT_LOSS_STARTER, LEAN_BULK, MAINTAIN, CUSTOM)

    fun byKey(key: String?): Preset? = all.firstOrNull { it.key == key }
}
