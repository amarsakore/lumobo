package app.lumobo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** App colours. Reads [dark], so everything recolours when the theme changes. */
object Palette {
    var dark by mutableStateOf(true)
    val Bg get() = if (dark) Color(0xFF0B0B0C) else Color(0xFFF4F2EE)
    val Surface get() = if (dark) Color(0xFF141416) else Color(0xFFFFFFFF)
    val Surface2 get() = if (dark) Color(0xFF1C1C1F) else Color(0xFFEFECE7)
    val Surface3 get() = if (dark) Color(0xFF26262A) else Color(0xFFE3DFD8)
    val Line get() = if (dark) Color(0xFF2E2E33) else Color(0xFFDAD5CD)
    val Text get() = if (dark) Color(0xFFF2EFE9) else Color(0xFF1B1A18)
    val TextDim get() = if (dark) Color(0xFF9C978F) else Color(0xFF6A655E)
    val TextFaint get() = if (dark) Color(0xFF5F5B56) else Color(0xFFA8A29A)
    val Amber get() = if (dark) Color(0xFFF5B841) else Color(0xFFE09B12)
    val Orange = Color(0xFFFF7A3D)
    val Red = Color(0xFFE5534B)
    /** Slider and dial knobs. */
    val Thumb get() = if (dark) Color.White else Color(0xFF1B1A18)
}

object Type {
    val Wordmark get() = TextStyle(fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = 5.sp, color = Palette.Text)
    val Label get() = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.4.sp, color = Palette.TextDim)
    val Mono get() = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = Palette.TextDim)
    val Code get() = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp, color = Palette.Text)
    val Title get() = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 0.2.sp, color = Palette.Text)
    val Body get() = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = Palette.TextDim)
}

@Composable
fun LumoboTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val mode = remember { app.lumobo.data.AppSettings.get(ctx) }.themeMode
    val dark = when (mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    Palette.dark = dark
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val c = WindowCompat.getInsetsController(window, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }
    val scheme = if (dark) darkColorScheme(
        primary = Palette.Amber, onPrimary = Color(0xFF1A1300), secondary = Palette.Orange,
        background = Palette.Bg, onBackground = Palette.Text, surface = Palette.Surface, onSurface = Palette.Text,
        surfaceVariant = Palette.Surface2, onSurfaceVariant = Palette.TextDim, outline = Palette.Line, error = Palette.Red,
    ) else lightColorScheme(
        primary = Palette.Amber, onPrimary = Color(0xFF1A1300), secondary = Palette.Orange,
        background = Palette.Bg, onBackground = Palette.Text, surface = Palette.Surface, onSurface = Palette.Text,
        surfaceVariant = Palette.Surface2, onSurfaceVariant = Palette.TextDim, outline = Palette.Line, error = Palette.Red,
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(),
    ) {
        // Compact type: every text in the app renders ~10% smaller, on top of the system font size.
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * 0.9f), content = content)
    }
}
