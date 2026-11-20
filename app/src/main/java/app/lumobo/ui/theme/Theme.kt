package app.lumobo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Bg = Color(0xFF0B0B0C)
    val Surface = Color(0xFF141416)
    val Surface2 = Color(0xFF1C1C1F)
    val Surface3 = Color(0xFF26262A)
    val Line = Color(0xFF2E2E33)
    val Text = Color(0xFFF2EFE9)
    val TextDim = Color(0xFF9C978F)
    val TextFaint = Color(0xFF5F5B56)
    val Amber = Color(0xFFF5B841)
    val Orange = Color(0xFFFF7A3D)
    val Red = Color(0xFFE5534B)
}

object Type {
    val Wordmark = TextStyle(fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = 5.sp, color = Palette.Text)
    val Label = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.4.sp, color = Palette.TextDim)
    val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = Palette.TextDim)
    val Code = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp, color = Palette.Text)
    val Title = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 0.2.sp, color = Palette.Text)
    val Body = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = Palette.TextDim)
}

@Composable
fun LumoboTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Amber,
            onPrimary = Color(0xFF1A1300),
            secondary = Palette.Orange,
            background = Palette.Bg,
            onBackground = Palette.Text,
            surface = Palette.Surface,
            onSurface = Palette.Text,
            surfaceVariant = Palette.Surface2,
            onSurfaceVariant = Palette.TextDim,
            outline = Palette.Line,
            error = Palette.Red,
        ),
        typography = Typography(),
    ) {
        // Compact type: every text in the app renders ~10% smaller, on top of the system font size.
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * 0.9f), content = content)
    }
}
