package app.lumobo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumobo.data.LooksRepository
import app.lumobo.editor.EditorController
import app.lumobo.film.Adjustments
import app.lumobo.film.Curves
import app.lumobo.film.Grade
import app.lumobo.film.Profiles
import app.lumobo.film.Pt
import app.lumobo.ui.components.panelScroll
import app.lumobo.ui.components.PanelColumn
import app.lumobo.ui.components.Chip
import app.lumobo.ui.components.FilmSlider
import app.lumobo.ui.theme.Palette
import app.lumobo.ui.theme.Type
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun ToolHeader(
    title: String, onReset: (() -> Unit)?, action: String? = null, onAction: () -> Unit = {},
    secondary: String? = null, onSecondary: () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().height(30.dp).padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = Type.Label.copy(color = Palette.Text, fontWeight = FontWeight.Bold))
        Spacer(Modifier.weight(1f))
        if (secondary != null) {
            Text(
                secondary, style = Type.Label.copy(color = Palette.Text, fontWeight = FontWeight.Bold),
                modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, Palette.Line, RoundedCornerShape(50))
                    .clickable(onClick = onSecondary).padding(horizontal = 12.dp, vertical = 5.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        if (action != null) {
            Text(
                action, style = Type.Label.copy(color = Palette.Bg, fontWeight = FontWeight.Black),
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.Amber).clickable(onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        if (onReset != null) {
            Text(
                "RESET", style = Type.Label.copy(color = Palette.Amber),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onReset).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
fun Histogram(data: FloatArray?, modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Surface2)) {
        if (data == null) return@Canvas
        val bins = data.size / 3
        val colors = listOf(Color(0xFFFF5A4E), Color(0xFF5BD46A), Color(0xFF5A8DFF))
        for (ch in 0 until 3) {
            val path = Path().apply {
                moveTo(0f, size.height)
                for (i in 0 until bins) lineTo(size.width * i / (bins - 1f), size.height * (1f - data[ch * bins + i] * 0.92f))
                lineTo(size.width, size.height)
                close()
            }
            drawPath(path, colors[ch].copy(alpha = 0.55f), blendMode = BlendMode.Screen)
        }
    }
}

private val pct: (Float) -> String = { v -> val i = (v * 100).roundToInt(); if (i > 0) "+$i" else "$i" }

@Composable
fun LightPanel(editor: EditorController, onCurves: () -> Unit) {
    val a = editor.adj
    Column(Modifier.fillMaxWidth()) {
        ToolHeader("Light", onReset = {
            editor.updateAdj(a.copy(exposure = 0f, contrast = 0f, highlights = 0f, shadows = 0f, whites = 0f, blacks = 0f, fade = 0f))
        }, action = "AUTO", onAction = editor::autoTone, secondary = "CURVES", onSecondary = onCurves)
        PanelColumn(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
            FilmSlider("Exposure", a.exposure, { editor.updateAdj(editor.adj.copy(exposure = it)) }, format = { String.format(Locale.US, "%+.1f EV", it * 2f) })
            FilmSlider("Contrast", a.contrast, { editor.updateAdj(editor.adj.copy(contrast = it)) })
            FilmSlider("Highlights", a.highlights, { editor.updateAdj(editor.adj.copy(highlights = it)) })
            FilmSlider("Shadows", a.shadows, { editor.updateAdj(editor.adj.copy(shadows = it)) })
            FilmSlider("Whites", a.whites, { editor.updateAdj(editor.adj.copy(whites = it)) })
            FilmSlider("Blacks", a.blacks, { editor.updateAdj(editor.adj.copy(blacks = it)) })
            FilmSlider("Fade", a.fade, { editor.updateAdj(editor.adj.copy(fade = it)) }, min = 0f, max = 1f, default = 0f)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                // How much of the photo's real HDR light (its gain map, or an estimate) shows on
                // an HDR screen and goes into the saved Ultra HDR photo. 0 = a plain SDR photo.
                FilmSlider("HDR light", a.hdrLight, { editor.updateAdj(editor.adj.copy(hdrLight = it)) }, min = 0f, max = 1f, default = 1f)
            }
        }
    }
}

@Composable
fun ColorPanel(editor: EditorController) {
    val a = editor.adj
    Column(Modifier.fillMaxWidth()) {
        ToolHeader("Color", onReset = { editor.updateAdj(a.copy(temp = 0f, tint = 0f, vibrance = 0f, saturation = 0f)) })
        PanelColumn(Modifier.padding(bottom = 8.dp)) {
            FilmSlider("Temperature", a.temp, { editor.updateAdj(editor.adj.copy(temp = it)) })
            FilmSlider("Tint", a.tint, { editor.updateAdj(editor.adj.copy(tint = it)) })
            FilmSlider("Vibrance", a.vibrance, { editor.updateAdj(editor.adj.copy(vibrance = it)) })
            FilmSlider("Saturation", a.saturation, { editor.updateAdj(editor.adj.copy(saturation = it)) })
        }
    }
}

private val bandColors = listOf(
    Color(0xFFE5403A), Color(0xFFF08A2C), Color(0xFFF2D23A), Color(0xFF4CC05A),
    Color(0xFF3CC8C8), Color(0xFF3F6BE8), Color(0xFF8B54E0), Color(0xFFE04CB6),
)

@Composable
fun MixerPanel(editor: EditorController) {
    var band by rememberSaveable { mutableIntStateOf(0) }
    val a = editor.adj
    Column(Modifier.fillMaxWidth()) {
        ToolHeader("Color mixer", onReset = { editor.updateAdj(a.copy(mixer = Adjustments.MIXER_ZERO)) })
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            bandColors.forEachIndexed { i, c ->
                val changed = (0..2).any { a.mixerValue(i, it) != 0f }
                Box(
                    Modifier.size(34.dp).clip(CircleShape)
                        .border(if (band == i) 2.5.dp else 0.dp, if (band == i) Color.White else Color.Transparent, CircleShape)
                        .padding(4.dp).clip(CircleShape).background(c).clickable { band = i },
                    contentAlignment = Alignment.Center,
                ) { if (changed) Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White)) }
            }
        }
        Text(
            Adjustments.MIXER_NAMES[band].uppercase(), style = Type.Label.copy(color = bandColors[band]),
            modifier = Modifier.padding(start = 20.dp, top = 6.dp),
        )
        PanelColumn(Modifier.padding(bottom = 8.dp)) {
            FilmSlider("Hue", a.mixerValue(band, 0), { editor.updateAdj(editor.adj.withMixer(band, 0, it)) })
            FilmSlider("Saturation", a.mixerValue(band, 1), { editor.updateAdj(editor.adj.withMixer(band, 1, it)) })
            FilmSlider("Luminance", a.mixerValue(band, 2), { editor.updateAdj(editor.adj.withMixer(band, 2, it)) })
        }
    }
}

@Composable
private fun ColorWheel(label: String, hue: Float, sat: Float, onChange: (Float, Float) -> Unit, modifier: Modifier = Modifier) {
    val change by rememberUpdatedState(onChange)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1f)
                .pointerInput(Unit) {
                    fun set(p: Offset) {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val r = size.width / 2f
                        val d = p - c
                        val h = ((Math.toDegrees(atan2(d.y, d.x).toDouble()).toFloat() + 360f) % 360f)
                        change(h, (hypot(d.x, d.y) / r).coerceIn(0f, 1f))
                    }
                    detectDragGestures(onDragStart = { set(it) }) { ch, _ -> ch.consume(); set(ch.position) }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { change(hue, 0f) }, onTap = { p ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val d = p - c
                        change(((Math.toDegrees(atan2(d.y, d.x).toDouble()).toFloat() + 360f) % 360f), (hypot(d.x, d.y) / (size.width / 2f)).coerceIn(0f, 1f))
                    })
                },
        ) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Brush.sweepGradient(List(13) { Color.hsv((it * 30f) % 360f, 0.85f, 0.95f) }, c), r, c)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF8A8A8A), Color(0x008A8A8A)), c, r), r, c)
            drawCircle(Color.Black.copy(alpha = 0.25f), r, c, style = Stroke(1.dp.toPx()))
            val pr = sat * r
            val p = Offset(c.x + cos(Math.toRadians(hue.toDouble())).toFloat() * pr, c.y + sin(Math.toRadians(hue.toDouble())).toFloat() * pr)
            drawCircle(Color.White, 6.dp.toPx(), p)
            drawCircle(Color.Hsv(hue, sat.coerceAtLeast(0.05f), 1f), 4.dp.toPx(), p)
        }
        Text(label, style = Type.Label.copy(fontSize = 10.sp, color = if (sat > 0f) Palette.Amber else Palette.TextDim), modifier = Modifier.padding(top = 4.dp))
    }
}

private fun Color.Companion.Hsv(h: Float, s: Float, v: Float) = hsv(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), v)

@Composable
fun GradingPanel(editor: EditorController) {
    val g = editor.adj.grade
    fun set(n: Grade) = editor.updateAdj(editor.adj.copy(grade = n))
    Column(Modifier.fillMaxWidth()) {
        ToolHeader("Color grading", onReset = { set(Grade()) })
        Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            ColorWheel("SHADOWS", g.shadowHue, g.shadowSat, { h, s -> set(editor.adj.grade.copy(shadowHue = h, shadowSat = s)) }, Modifier.weight(1f))
            ColorWheel("MIDTONES", g.midHue, g.midSat, { h, s -> set(editor.adj.grade.copy(midHue = h, midSat = s)) }, Modifier.weight(1f))
            ColorWheel("HIGHLIGHTS", g.highHue, g.highSat, { h, s -> set(editor.adj.grade.copy(highHue = h, highSat = s)) }, Modifier.weight(1f))
        }
        PanelColumn(Modifier.padding(top = 2.dp, bottom = 8.dp)) {
            FilmSlider("Blending", g.blending, { set(editor.adj.grade.copy(blending = it)) }, min = 0f, max = 1f, default = 0.5f)
            FilmSlider("Balance", g.balance, { set(editor.adj.grade.copy(balance = it)) })
        }
    }
}

@Composable
fun CurvesPanel(editor: EditorController, onBack: () -> Unit) {
    DisposableEffect(Unit) {
        editor.wantHistogram = true
        onDispose { editor.wantHistogram = false }
    }
    var ch by rememberSaveable { mutableIntStateOf(0) }
    val curves = editor.adj.curves
    val chColors = listOf(Color.White, Color(0xFFFF5A4E), Color(0xFF5BD46A), Color(0xFF5A8DFF))
    Column(Modifier.fillMaxSize()) {
        ToolHeader(
            "Curves", onReset = { editor.updateAdj(editor.adj.copy(curves = curves.with(ch, Curves.IDENTITY))) },
            secondary = "‹ LIGHT", onSecondary = onBack,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("RGB", "Red", "Green", "Blue").forEachIndexed { i, name -> Chip(name, ch == i, { ch = i }) }
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            CurveEditor(
                points = curves.channel(ch),
                color = chColors[ch],
                histogram = editor.histogram,
                onChange = { pts -> editor.updateAdj(editor.adj.copy(curves = editor.adj.curves.with(ch, pts))) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Point curve: drag points, drag the line to add one, double-tap a point to remove it. */
@Composable
private fun CurveEditor(points: List<Pt>, color: Color, histogram: FloatArray?, onChange: (List<Pt>) -> Unit, modifier: Modifier) {
    val pts by rememberUpdatedState(points)
    val change by rememberUpdatedState(onChange)
    val active = remember { intArrayOf(-1) }
    val work = remember { arrayOf(points) }
    Canvas(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Surface2)
            .pointerInput(Unit) {
                fun toPt(o: Offset) = Pt((o.x / size.width).coerceIn(0f, 1f), (1f - o.y / size.height).coerceIn(0f, 1f))
                fun nearest(o: Offset): Int {
                    var best = -1
                    var bestD = 36.dp.toPx()
                    pts.forEachIndexed { i, p ->
                        val d = hypot(p.x * size.width - o.x, (1f - p.y) * size.height - o.y)
                        if (d < bestD) { bestD = d; best = i }
                    }
                    return best
                }
                detectDragGestures(
                    onDragStart = { o ->
                        var i = nearest(o)
                        var list = pts
                        if (i < 0) {
                            val p = toPt(o)
                            list = (pts + Pt(p.x, Curves.sample(pts, p.x))).sortedBy { it.x }
                            i = list.indexOfFirst { it.x == p.x }
                            change(list)
                        }
                        work[0] = list
                        active[0] = i
                    },
                    onDragEnd = { active[0] = -1 },
                    onDragCancel = { active[0] = -1 },
                ) { c, _ ->
                    c.consume()
                    val i = active[0]
                    val list = work[0].toMutableList()
                    if (i !in list.indices) return@detectDragGestures
                    val p = toPt(c.position)
                    val x = when (i) {
                        0 -> 0f
                        list.lastIndex -> 1f
                        else -> p.x.coerceIn(list[i - 1].x + 0.02f, list[i + 1].x - 0.02f)
                    }
                    list[i] = Pt(x, p.y)
                    work[0] = list
                    change(list)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { o ->
                    val list = pts
                    var best = -1
                    var bestD = 36.dp.toPx()
                    for (i in 1 until list.lastIndex) {
                        val d = hypot(list[i].x * size.width - o.x, (1f - list[i].y) * size.height - o.y)
                        if (d < bestD) { bestD = d; best = i }
                    }
                    if (best > 0) change(list.filterIndexed { i, _ -> i != best })
                })
            },
    ) {
        val w = size.width
        val h = size.height
        if (histogram != null) {
            val bins = histogram.size / 3
            val path = Path().apply {
                moveTo(0f, h)
                for (i in 0 until bins) {
                    val v = (histogram[i] + histogram[bins + i] + histogram[2 * bins + i]) / 3f
                    lineTo(w * i / (bins - 1f), h * (1f - v * 0.9f))
                }
                lineTo(w, h); close()
            }
            drawPath(path, Color.White.copy(alpha = 0.08f))
        }
        for (i in 1..3) {
            drawLine(Palette.Line, Offset(w * i / 4f, 0f), Offset(w * i / 4f, h), 1.dp.toPx())
            drawLine(Palette.Line, Offset(0f, h * i / 4f), Offset(w, h * i / 4f), 1.dp.toPx())
        }
        drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, h), Offset(w, 0f), 1.dp.toPx())
        val path = Path()
        for (i in 0..100) {
            val x = i / 100f
            val y = Curves.sample(points, x)
            if (i == 0) path.moveTo(0f, h * (1f - y)) else path.lineTo(w * x, h * (1f - y))
        }
        drawPath(path, color, style = Stroke(2.dp.toPx()))
        points.forEach { p ->
            drawCircle(Palette.Bg, 7.dp.toPx(), Offset(p.x * w, (1f - p.y) * h))
            drawCircle(color, 5.dp.toPx(), Offset(p.x * w, (1f - p.y) * h))
        }
    }
}

@Composable
fun DetailPanel(editor: EditorController) {
    val a = editor.adj
    Column(Modifier.fillMaxWidth()) {
        ToolHeader("Detail", onReset = {
            editor.updateAdj(a.copy(texture = 0f, clarity = 0f, sharpen = 0f, dehaze = 0f, hdr = 0f))
        })
        PanelColumn(Modifier.padding(bottom = 8.dp)) {
            FilmSlider("Texture", a.texture, { editor.updateAdj(editor.adj.copy(texture = it)) })
            FilmSlider("Clarity", a.clarity, { editor.updateAdj(editor.adj.copy(clarity = it)) })
            FilmSlider("Sharpen", a.sharpen, { editor.updateAdj(editor.adj.copy(sharpen = it)) }, min = 0f, max = 1f, default = 0f)
            FilmSlider("Dehaze", a.dehaze, { editor.updateAdj(editor.adj.copy(dehaze = it)) })
            FilmSlider("HDR", a.hdr, { editor.updateAdj(editor.adj.copy(hdr = it)) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LooksPanel(editor: EditorController) {
    val context = LocalContext.current
    val repo = remember { LooksRepository.get(context) }
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Long?>(null) }
    Column(Modifier.fillMaxSize()) {
        ToolHeader("My looks", onReset = null, action = "SAVE CURRENT", onAction = { naming = true })
        if (repo.looks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Save your favourite combination of film, sliders, effects and lens as a look, then apply it to any photo with one tap.",
                    style = Type.Body, textAlign = TextAlign.Center,
                )
            }
        } else {
            Text("Tap to apply · long-press to delete", style = Type.Body.copy(fontSize = 11.5.sp), modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(10.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(repo.looks, key = { it.id }) { look ->
                    Column(
                        Modifier.width(84.dp).combinedClickable(onClick = { editor.applyLook(look) }, onLongClick = { deleting = look.id }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Surface2)) {
                            editor.thumbs[look.filmId]?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(look.name, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2, textAlign = TextAlign.Center, color = Palette.Text)
                        Text(Profiles.byId(look.filmId).name, fontSize = 9.5.sp, maxLines = 1, color = Palette.TextDim)
                    }
                }
            }
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("${editor.profile.name} look") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save look") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(32) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Amber, cursorColor = Palette.Amber),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    repo.add(name, editor.profile.id, editor.adj, editor.fx.copy(frame = app.lumobo.film.FrameStyle.NONE, dateStamp = false))
                    naming = false
                }) { Text("Save", color = Palette.Amber) }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel", color = Palette.TextDim) } },
            containerColor = Palette.Surface2,
        )
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete look?") },
            text = { Text("\"${repo.looks.firstOrNull { it.id == id }?.name}\" will be removed from My Looks. Photos you developed with it don't change.") },
            confirmButton = { TextButton(onClick = { repo.remove(id); deleting = null }) { Text("Delete", color = Palette.Red) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = Palette.TextDim) } },
            containerColor = Palette.Surface2,
        )
    }
}

@Composable
fun AddLookChip(onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).border(1.dp, Palette.Line, RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Add, null, tint = Palette.Amber, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("My looks", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Palette.Amber)
    }
}
