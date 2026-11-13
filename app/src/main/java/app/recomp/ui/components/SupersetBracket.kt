package app.recomp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * RT-05: a bracket down the left of a superset's cards, in a routine or a workout. [position] is the
 * card's place in its superset and the superset's size; [gap] is the list's spacing, which the bracket
 * bridges so it reads as one.
 */
@Composable
fun SupersetBracket(position: Pair<Int, Int>?, gap: Dp, content: @Composable () -> Unit) {
    if (position == null) return content()
    val (place, count) = position
    val color = MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxWidth().drawBehind {
            val x = 4.dp.toPx()
            val stroke = 3.dp.toPx()
            val tick = 8.dp.toPx()
            val top = if (place == 0) 0f else -gap.toPx() / 2
            val bottom = if (place == count - 1) size.height else size.height + gap.toPx() / 2
            drawLine(color, Offset(x, top), Offset(x, bottom), stroke)
            if (place == 0) drawLine(color, Offset(x, 0f), Offset(x + tick, 0f), stroke)
            if (place == count - 1) drawLine(color, Offset(x, size.height), Offset(x + tick, size.height), stroke)
        }.padding(start = 14.dp),
    ) { content() }
}

/** "Superset · 1 of 2", above the exercise's name. */
@Composable
fun SupersetLabel(position: Pair<Int, Int>?) {
    val (place, count) = position ?: return
    Text("Superset · ${place + 1} of $count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}
