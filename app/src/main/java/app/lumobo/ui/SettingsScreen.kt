package app.lumobo.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumobo.data.AppSettings
import app.lumobo.film.FrameStyle
import app.lumobo.film.Profiles
import app.lumobo.ui.components.Chip
import app.lumobo.ui.components.FilmSlider
import app.lumobo.ui.components.RoundIconButton
import app.lumobo.ui.theme.Palette
import app.lumobo.ui.theme.Type
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var s by remember { mutableStateOf(AppSettings.get(context)) }
    var confirmReset by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(Modifier.fillMaxSize().background(Palette.Bg)) {
        Row(
            Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(14.dp))
            Text("Settings", style = Type.Title.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold))
        }

        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = bottom + 24.dp)) {
            Section("Film character") {
                SwitchRow(
                    "Film texture on every photo",
                    "Adds grain, halation and bloom in each film's own flavour, even on Original.",
                    s.characterOn,
                ) { s.characterOn = it }
                val pct: (Float) -> String = { "${(it * 100).roundToInt()}%" }
                FilmSlider("Grain", s.grainLevel, { s.grainLevel = it }, min = 0f, max = 2f, default = 1f, enabled = s.characterOn, format = pct)
                FilmSlider("Halation", s.halationLevel, { s.halationLevel = it }, min = 0f, max = 2f, default = 1f, enabled = s.characterOn, format = pct)
                FilmSlider("Bloom", s.bloomLevel, { s.bloomLevel = it }, min = 0f, max = 2f, default = 1f, enabled = s.characterOn, format = pct)
                Spacer(Modifier.height(8.dp))
            }

            Section("New edits") {
                SwitchRow("Start with your last film", "New photos open with the film you used most recently.", s.rememberLastFilm) { s.rememberLastFilm = it }
                SwitchRow("Date stamp on every photo", "Turns the LED date imprint on by default.", s.dateStampDefault) { s.dateStampDefault = it }
                ChoiceRow("Date stamp colour", listOf("Orange", "Red", "Yellow"), s.dateColor, dots = listOf(Palette.Orange, Color(0xFFFF3B2F), Color(0xFFFFC94D))) { s.dateColor = it }
                ChoiceRow("Date format", listOf("'26 11 20", "11 20 '26", "20 11 '26"), s.dateFormat) { s.dateFormat = it }
                val frames = FrameStyle.entries
                ChoiceRow("Default frame", frames.map { it.label }, frames.indexOfFirst { it.name == s.defaultFrame }.coerceAtLeast(0)) {
                    s.defaultFrame = frames[it].name
                }
            }

            Section("Export") {
                val qualities = listOf(92, 96, 100)
                ChoiceRow("JPEG quality", listOf("High · 92", "Very high · 96", "Maximum · 100"), qualities.indexOf(s.jpegQuality).coerceAtLeast(1)) {
                    s.jpegQuality = qualities[it]
                }
                ChoiceRow("Photo size", listOf("Full resolution", "12 MP", "8 MP", "4 MP"), s.exportSize) { s.exportSize = it }
                SwitchRow("Keep camera details", "Camera, lens and exposure info from the original.", s.keepCameraInfo) { s.keepCameraInfo = it }
                SwitchRow("Keep location", "GPS position from the original. Turn off before sharing publicly.", s.keepLocation) { s.keepLocation = it }
            }

            Section("Gallery & app") {
                ChoiceRow("Grid columns", listOf("3", "4", "5"), (s.gridColumns - 3).coerceIn(0, 2)) { s.gridColumns = it + 3 }
                SwitchRow("Show screenshots", "Include screenshots in the Library tab.", s.showScreenshots) { s.showScreenshots = it }
                SwitchRow("Haptic feedback", "Gentle ticks on sliders and controls.", s.haptics) { s.haptics = it }
            }

            Section("About") {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/amarsakore/lumobo")))
                    }.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Lumobo 1.1", style = Type.Title.copy(fontSize = 15.sp))
                        Text("${Profiles.all.size - 1} films and camera looks · source code on GitHub", style = Type.Body.copy(fontSize = 12.sp))
                    }
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = Palette.TextDim, modifier = Modifier.size(18.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
                Text(
                    "Reset all settings",
                    color = Palette.Red, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().clickable { confirmReset = true }.padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset settings?") },
            text = { Text("All settings go back to their defaults. Your photos are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    s.resetAll()
                    s = AppSettings.get(context)
                    confirmReset = false
                }) { Text("Reset", color = Palette.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel", color = Palette.TextDim) } },
            containerColor = Palette.Surface2,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title.uppercase(),
        style = Type.Label.copy(color = Palette.Amber, fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(start = 24.dp, top = 22.dp, bottom = 8.dp),
    )
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(18.dp)).background(Palette.Surface),
        content = content,
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Type.Title.copy(fontSize = 15.sp))
            Text(subtitle, style = Type.Body.copy(fontSize = 12.sp, lineHeight = 16.sp))
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Amber, checkedThumbColor = Palette.Bg),
        )
    }
}

@Composable
private fun ChoiceRow(title: String, options: List<String>, selected: Int, dots: List<Color>? = null, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, style = Type.Title.copy(fontSize = 15.sp), modifier = Modifier.padding(horizontal = 18.dp))
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(options) { i, label ->
                if (dots == null) {
                    Chip(label, i == selected, { onSelect(i) })
                } else {
                    val sel = i == selected
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(if (sel) Palette.Text else Palette.Surface2)
                            .clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(dots[i]))
                        Spacer(Modifier.width(8.dp))
                        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (sel) Palette.Bg else Palette.TextDim)
                    }
                }
            }
        }
    }
}
