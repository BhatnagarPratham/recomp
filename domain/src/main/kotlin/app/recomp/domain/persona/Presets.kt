package app.recomp.domain.persona

import app.recomp.domain.nutrition.Goal

/**
 * A starting point for onboarding (PRD §4.4). [goal] pre-selects a goal card; null values in [draft]
 * are *Asked* in the review step. The goal plan itself is built by the goal flow.
 *
 * Presets are data files (PS-05): one JSON file each under `assets/presets`, read by `:app`'s `PresetSource`.
 * A fork adds a preset by adding a file.
 */
data class Preset(
    val key: String,
    val name: String,
    val summary: String,
    val goal: Goal?,
    val draft: PersonaDraft,
)
