package app.recomp.domain.persona

import app.recomp.data.FilePresetSource

/**
 * The bundled presets by name, for tests. Since PS-05 they're data files under `assets/presets`, read here from
 * the source tree; this keeps the names tests have always used (`Presets.REFERENCE`).
 */
object Presets {
    val all: List<Preset> get() = FilePresetSource.presets()

    fun byKey(key: String?): Preset? = FilePresetSource.byKey(key)

    private fun named(key: String): Preset = requireNotNull(byKey(key)) { "No preset file with the key $key" }

    val REFERENCE: Preset get() = named("reference_vegetarian_recomp")
    val FAT_LOSS_STARTER: Preset get() = named("fat_loss_starter")
    val LEAN_BULK: Preset get() = named("lean_bulk")
    val MAINTAIN: Preset get() = named("maintain_stronger")
    val CUSTOM: Preset get() = named("custom")
}
