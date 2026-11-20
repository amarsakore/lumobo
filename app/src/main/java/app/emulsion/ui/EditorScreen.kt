package app.emulsion.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.emulsion.editor.EditorController
import app.emulsion.editor.EditorStatus
import app.emulsion.film.Category
import app.emulsion.film.FilmProfile
import app.emulsion.film.FrameStyle
import app.emulsion.film.Profiles
import app.emulsion.ui.components.BottomTab
import app.emulsion.ui.components.Chip
import app.emulsion.ui.components.FilmSlider
import app.emulsion.ui.components.RoundIconButton
import app.emulsion.ui.components.ZoomableImage
import app.emulsion.ui.theme.Palette
import app.emulsion.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private enum class Panel(val label: String) { FILMS("Films"), ADJUST("Adjust"), EFFECTS("Effects"), FRAME("Frame") }

@Composable
fun EditorScreen(uri: Uri, onBack: () -> Unit, onSaved: () -> Unit, onViewSaved: (Uri) -> Unit) {
    val context = LocalContext.current.applicationContext
    val editor = remember(uri) { EditorController(context, uri) }
    DisposableEffect(editor) {
        editor.start()
        onDispose { editor.dispose() }
    }
    BackHandler(onBack = onBack)

    var panel by rememberSaveable { mutableStateOf(Panel.FILMS) }
    var comparing by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<Pair<String, Uri?>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(4500)
            toast = null
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.Bg)) {
        // ── Top bar
        Row(
            Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Crossfade(editor.profile, label = "title") { p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(p.name, style = Type.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((p.category?.label ?: "No film").uppercase(), style = Type.Label.copy(fontSize = 9.5.sp))
                    }
                }
            }
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (comparing) Palette.Amber else Palette.Surface2)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            comparing = true
                            tryAwaitRelease()
                            comparing = false
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Compare, "Hold to compare", tint = if (comparing) Palette.Bg else Palette.Text, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .height(42.dp)
                    .width(84.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Palette.Amber)
                    .clickable(enabled = !editor.saving && editor.status == EditorStatus.Ready) {
                        scope.launch {
                            val r = editor.save()
                            toast = r.fold(
                                onSuccess = { onSaved(); "Developed · saved to Pictures/Emulsion" to it },
                                onFailure = { ("Couldn't save: " + (it.message ?: "unknown error")) to null },
                            )
                        }
                    }
                    ,
                contentAlignment = Alignment.Center,
            ) {
                if (editor.saving) {
                    CircularProgressIndicator(color = Palette.Bg, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Text("SAVE", color = Palette.Bg, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 1.5.sp)
                }
            }
        }

        // ── Image
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (val s = editor.status) {
                EditorStatus.Loading -> CircularProgressIndicator(color = Palette.Amber, strokeWidth = 2.dp)
                is EditorStatus.Failed -> Text(s.message, style = Type.Body, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
                EditorStatus.Ready -> {
                    val shown: ImageBitmap? = if (comparing) editor.original else (editor.rendered ?: editor.original)
                    if (shown != null) {
                        ZoomableImage(
                            bitmap = shown,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
                            resetKey = editor.fx.frame,
                            onHold = { comparing = it },
                        )
                    }
                    if (comparing) {
                        Text(
                            "ORIGINAL",
                            style = Type.Label.copy(color = Palette.Bg, fontWeight = FontWeight.Black),
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp)
                                .clip(RoundedCornerShape(50)).background(Palette.Amber).padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    if (editor.saving) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Palette.Amber, strokeWidth = 2.dp)
                                Spacer(Modifier.height(12.dp))
                                Text("DEVELOPING AT FULL RESOLUTION", style = Type.Label.copy(color = Palette.Text))
                            }
                        }
                    }
                }
            }
            this@Column.AnimatedVisibility(
                visible = toast != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            ) {
                val t = toast
                Row(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.Surface3).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(t?.first.orEmpty(), style = Type.Body.copy(color = Palette.Text), modifier = Modifier.weight(1f, fill = false))
                    if (t?.second != null) {
                        Text(
                            "VIEW",
                            style = Type.Label.copy(color = Palette.Amber, fontWeight = FontWeight.Black),
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onViewSaved(t.second!!) }.padding(12.dp),
                        )
                    } else Spacer(Modifier.width(10.dp))
                }
            }
        }

        // ── Control panel
        Column(Modifier.fillMaxWidth().background(Palette.Surface).navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(246.dp)) {
                when (panel) {
                    Panel.FILMS -> FilmsPanel(editor)
                    Panel.ADJUST -> AdjustPanel(editor)
                    Panel.EFFECTS -> EffectsPanel(editor)
                    Panel.FRAME -> FramePanel(editor)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                BottomTab(Icons.Outlined.FilterVintage, Panel.FILMS.label, panel == Panel.FILMS, { panel = Panel.FILMS }, Modifier.weight(1f))
                BottomTab(Icons.Outlined.Tune, Panel.ADJUST.label, panel == Panel.ADJUST, { panel = Panel.ADJUST }, Modifier.weight(1f))
                BottomTab(Icons.Outlined.Grain, Panel.EFFECTS.label, panel == Panel.EFFECTS, { panel = Panel.EFFECTS }, Modifier.weight(1f))
                BottomTab(Icons.Outlined.CropFree, Panel.FRAME.label, panel == Panel.FRAME, { panel = Panel.FRAME }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FilmsPanel(editor: EditorController) {
    var category by rememberSaveable { mutableStateOf(editor.profile.category ?: Category.FUJIFILM) }
    val films = remember(category) { listOf(Profiles.ORIGINAL) + Profiles.all.filter { it.category == category } }
    val p = editor.profile
    Column(Modifier.fillMaxSize().padding(top = 10.dp)) {
        // Strength + description
        Box(Modifier.height(64.dp)) {
            if (p.isIdentity) {
                Text(
                    "Pick a film below. Hold the image to compare with the original.",
                    style = Type.Body, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            } else {
                Column {
                    Text(p.blurb, style = Type.Body.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp))
                    FilmSlider(
                        "Strength", editor.adj.intensity, { editor.updateAdj(editor.adj.copy(intensity = it)) },
                        min = 0f, max = 1f, default = 1f, format = { "${(it * 100).roundToInt()}%" },
                    )
                }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Category.entries) { c -> Chip(c.label, c == category, { category = c }) }
        }
        Spacer(Modifier.height(12.dp))
        val listState = rememberLazyListState()
        LaunchedEffect(category) { listState.scrollToItem(0) }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(films, key = { it.id }) { f ->
                FilmCard(f, editor.thumbs[f.id], f.id == p.id) { editor.select(f) }
            }
        }
    }
}

@Composable
private fun FilmCard(profile: FilmProfile, thumb: ImageBitmap?, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(76.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Surface2)
                .border(if (selected) 2.dp else 0.dp, if (selected) Palette.Amber else Color.Transparent, RoundedCornerShape(14.dp)),
        ) {
            if (thumb != null) {
                Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .padding(start = 7.dp, bottom = 5.dp, top = 12.dp),
            ) {
                Text(profile.code, style = Type.Code.copy(color = if (selected) Palette.Amber else Palette.Text))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            profile.name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) Palette.Text else Palette.TextDim,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun PanelHeader(title: String, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = Type.Label.copy(color = Palette.Text, fontWeight = FontWeight.Bold))
        Spacer(Modifier.weight(1f))
        Text(
            "RESET", style = Type.Label.copy(color = Palette.Amber),
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onReset).padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun AdjustPanel(editor: EditorController) {
    val a = editor.adj
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Adjust", editor::resetAdjustments)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            FilmSlider("Exposure", a.exposure, { editor.updateAdj(a.copy(exposure = it)) }, format = { String.format(Locale.US, "%+.1f EV", it * 2f) })
            FilmSlider("Contrast", a.contrast, { editor.updateAdj(a.copy(contrast = it)) })
            FilmSlider("Highlights", a.highlights, { editor.updateAdj(a.copy(highlights = it)) })
            FilmSlider("Shadows", a.shadows, { editor.updateAdj(a.copy(shadows = it)) })
            FilmSlider("Warmth", a.temp, { editor.updateAdj(a.copy(temp = it)) })
            FilmSlider("Tint", a.tint, { editor.updateAdj(a.copy(tint = it)) })
            FilmSlider("Saturation", a.saturation, { editor.updateAdj(a.copy(saturation = it)) })
            FilmSlider("Fade", a.fade, { editor.updateAdj(a.copy(fade = it)) }, min = 0f, max = 1f, default = 0f)
        }
    }
}

@Composable
private fun EffectsPanel(editor: EditorController) {
    val f = editor.fx
    val d = editor.profile.fx
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Film & lens", editor::resetEffects)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            FilmSlider("Grain", f.grain, { editor.updateFx(f.copy(grain = it)) }, min = 0f, max = 1f, default = d.grain)
            FilmSlider("Grain size", f.grainSize, { editor.updateFx(f.copy(grainSize = it)) }, min = 0f, max = 1f, default = d.grainSize, enabled = f.grain > 0f)
            FilmSlider("Colour grain", f.colorGrain, { editor.updateFx(f.copy(colorGrain = it)) }, min = 0f, max = 1f, default = d.colorGrain, enabled = f.grain > 0f && !editor.profile.isMono)
            FilmSlider("Halation", f.halation, { editor.updateFx(f.copy(halation = it)) }, min = 0f, max = 1f, default = d.halation)
            FilmSlider("Bloom", f.bloom, { editor.updateFx(f.copy(bloom = it)) }, min = 0f, max = 1f, default = d.bloom)
            FilmSlider("Vignette", f.vignette, { editor.updateFx(f.copy(vignette = it)) }, min = 0f, max = 1f, default = d.vignette)
            FilmSlider("Aberration", f.aberration, { editor.updateFx(f.copy(aberration = it)) }, min = 0f, max = 1f, default = d.aberration)
            FilmSlider("Light leak", f.leak, { editor.updateFx(f.copy(leak = it)) }, min = 0f, max = 1f, default = d.leak)
        }
    }
}

@Composable
private fun FramePanel(editor: EditorController) {
    val f = editor.fx
    Column(Modifier.fillMaxSize().padding(top = 6.dp)) {
        Text("FRAME", style = Type.Label.copy(color = Palette.Text, fontWeight = FontWeight.Bold), modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FrameStyle.entries.forEach { style ->
                FrameOption(style, f.frame == style, Modifier.weight(1f)) { editor.updateFx(f.copy(frame = style)) }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Surface2)
                .clickable { editor.updateFx(f.copy(dateStamp = !f.dateStamp)) }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("'26 11 20", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.Orange, letterSpacing = 1.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Date stamp", style = Type.Title.copy(fontSize = 14.sp))
                Text("LED data-back imprint from the capture date", style = Type.Body.copy(fontSize = 11.sp))
            }
            Switch(
                checked = f.dateStamp, onCheckedChange = { editor.updateFx(f.copy(dateStamp = it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.Amber, checkedThumbColor = Palette.Bg),
            )
        }
    }
}

@Composable
private fun FrameOption(style: FrameStyle, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Surface2)
                .border(if (selected) 2.dp else 0.dp, if (selected) Palette.Amber else Color.Transparent, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(width = 40.dp, height = 48.dp)) {
                val photo = Color(0xFF5E7C8C)
                when (style) {
                    FrameStyle.NONE -> drawRect(photo, size = size)
                    FrameStyle.WHITE -> {
                        drawRect(Color(0xFFF4F2EC), size = size)
                        val b = size.width * 0.09f
                        drawRect(photo, Offset(b, b), Size(size.width - 2 * b, size.height - 2 * b))
                    }
                    FrameStyle.INSTANT -> {
                        drawRect(Color(0xFFF4F2EC), size = size)
                        val b = size.width * 0.09f
                        drawRect(photo, Offset(b, b), Size(size.width - 2 * b, size.height * 0.68f))
                    }
                    FrameStyle.FILM -> {
                        drawRect(Color(0xFF0C0B0A), size = size)
                        val r = size.width * 0.22f
                        drawRect(photo, Offset(r, size.height * 0.06f), Size(size.width - 2 * r, size.height * 0.88f))
                        var y = size.height * 0.05f
                        while (y < size.height - 4f) {
                            drawRect(Color(0xFFE8E4DA), Offset(size.width * 0.05f, y), Size(size.width * 0.09f, size.height * 0.05f))
                            drawRect(Color(0xFFE8E4DA), Offset(size.width * 0.86f, y), Size(size.width * 0.09f, size.height * 0.05f))
                            y += size.height * 0.11f
                        }
                    }
                }
                if (style == FrameStyle.NONE) drawRect(Color.White.copy(alpha = 0.15f), size = size, style = Stroke(1.dp.toPx()))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(style.label, fontSize = 11.sp, color = if (selected) Palette.Text else Palette.TextDim)
    }
}
