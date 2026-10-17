package app.recomp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark by default (PRD §7.4, TRD §7): high contrast for a dim gym and a sweaty glance between sets.
private val RecompDark = darkColorScheme(
    primary = Color(0xFF7CD992),
    onPrimary = Color(0xFF00391A),
    secondary = Color(0xFFFFB86B),
    onSecondary = Color(0xFF442B00),
    background = Color(0xFF101214),
    onBackground = Color(0xFFE6E8EA),
    surface = Color(0xFF181B1E),
    onSurface = Color(0xFFE6E8EA),
    surfaceVariant = Color(0xFF23272B),
    onSurfaceVariant = Color(0xFFB9BEC3),
    error = Color(0xFFFF8A80),
)

@Composable
fun RecompTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RecompDark, content = content)
}
