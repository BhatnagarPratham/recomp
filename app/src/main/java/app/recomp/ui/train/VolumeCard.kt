package app.recomp.ui.train

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.recomp.data.prefs.VolumeBand
import app.recomp.data.repo.WeekVolume
import app.recomp.domain.training.MuscleSets
import app.recomp.domain.training.MuscleVolume
import app.recomp.ui.components.Labels

/**
 * Weekly sets per muscle (PO-10, PRD §12.6): this week's working sets against the reference band.
 * Information only, so every bar is the same neutral colour whether it's under, in or over the band.
 */
@Composable
fun VolumeCard(volume: WeekVolume, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Sets this week", style = MaterialTheme.typography.titleMedium)
            Text(
                "Working sets per muscle: 1 for an exercise's main muscle, ½ for each helper. The shaded band is ${volume.band.min}–${volume.band.max} sets, for reference.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (volume.muscles.isEmpty()) {
                Text("Nothing logged yet this week.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val scale = volumeScale(volume.muscles, volume.band)
            volume.muscles.forEach { sets -> MuscleRow(sets, volume.band, scale) }
        }
    }
}

/** The bar's full width in half sets: the band's top, or the biggest muscle if it went past it. */
internal fun volumeScale(muscles: List<MuscleSets>, band: VolumeBand): Int =
    maxOf(band.max * 2, muscles.maxOfOrNull { it.halfSets } ?: 0).coerceAtLeast(1)

@Composable
private fun MuscleRow(sets: MuscleSets, band: VolumeBand, scale: Int) {
    val bandColor = MaterialTheme.colorScheme.surfaceVariant
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val barColor = MaterialTheme.colorScheme.onSurfaceVariant
    val count = MuscleVolume.format(sets)
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "${Labels.muscle(sets.muscle)}: $count sets this week" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(Labels.muscle(sets.muscle), Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Canvas(Modifier.weight(1f).height(14.dp)) {
            val radius = CornerRadius(size.height / 2)
            fun x(halfSets: Int) = size.width * halfSets / scale
            drawRoundRect(trackColor, size = Size(size.width, 2.dp.toPx()), topLeft = Offset(0f, size.height / 2 - 1.dp.toPx()))
            drawRect(bandColor, topLeft = Offset(x(band.min * 2), 0f), size = Size(x(band.max * 2) - x(band.min * 2), size.height))
            if (sets.halfSets > 0) {
                drawRoundRect(barColor, topLeft = Offset(0f, size.height / 4), size = Size(x(sets.halfSets).coerceAtLeast(size.height / 2), size.height / 2), cornerRadius = radius)
            }
        }
        Text(count, Modifier.width(40.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}
