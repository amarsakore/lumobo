package app.lumobo.ui

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
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import app.lumobo.film.LensPreset
import app.lumobo.ui.components.AngleDial
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumobo.data.AppSettings
import app.lumobo.editor.EditorController
import app.lumobo.editor.EditorStatus
import app.lumobo.editor.Recipe
import app.lumobo.film.Category
import app.lumobo.film.FilmProfile
import app.lumobo.film.FrameStyle
import app.lumobo.film.Geometry
import app.lumobo.film.LeakStyle
import app.lumobo.film.Profiles
import app.lumobo.render.GlRenderer
import app.lumobo.ui.components.BottomTab
import app.lumobo.ui.components.Chip
import app.lumobo.ui.components.CropMath
import app.lumobo.ui.components.CropOverlay
import app.lumobo.ui.components.FilmSlider
import app.lumobo.ui.components.RoundIconButton
import app.lumobo.ui.components.ZoomableImage
import app.lumobo.ui.theme.Palette
import app.lumobo.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private enum class Panel(val label: String) { FILMS("Films"), ADJUST("Adjust"), EFFECTS("Effects"), LENS("Lens"), CROP("Crop"), FRAME("Frame") }

@Composable
fun EditorScreen(
    uri: Uri,
    recipe: Recipe?,
    target: Uri?,
    note: String? = null,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onViewSaved: (Uri) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val editor = remember(uri, recipe, target) { EditorController(context, uri, recipe, target) }
    DisposableEffect(editor) {
        editor.start()
        onDispose { editor.dispose() }
    }
    BackHandler(onBack = onBack)

    var panel by rememberSaveable { mutableStateOf(Panel.FILMS) }
    var comparing by remember { mutableStateOf(false) }
    var straightening by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<Pair<String, Uri?>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(panel) { editor.setCropping(panel == Panel.CROP) }
    LaunchedEffect(Unit) { if (note != null) toast = note to null }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(6000)
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
            Spacer(Modifier.width(6.dp))
            RoundIconButton(
                Icons.AutoMirrored.Outlined.Undo, "Undo", { editor.undo() },
                Modifier.size(36.dp).alpha(if (editor.canUndo) 1f else 0.35f),
            )
            Spacer(Modifier.width(4.dp))
            RoundIconButton(
                Icons.AutoMirrored.Outlined.Redo, "Redo", { editor.redo() },
                Modifier.size(36.dp).alpha(if (editor.canRedo) 1f else 0.35f),
            )
            Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Crossfade(editor.profile, label = "title") { p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(p.name, style = Type.Title.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (target != null) "RE-EDIT · " else "") + (p.category?.label ?: "No film").uppercase(),
                            style = Type.Label.copy(fontSize = 9.5.sp),
                        )
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
                    .width(80.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Palette.Amber)
                    .clickable(enabled = !editor.saving && editor.status == EditorStatus.Ready) {
                        panel = if (panel == Panel.CROP) Panel.FILMS else panel
                        scope.launch {
                            val r = editor.save()
                            toast = r.fold(
                                onSuccess = {
                                    onSaved()
                                    (if (it == target) "Updated in Developed" else "Developed · saved to your gallery") to it
                                },
                                onFailure = { ("Couldn't save: " + (it.message ?: "unknown error")) to null },
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (editor.saving) {
                    CircularProgressIndicator(color = Palette.Bg, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Text(if (target != null) "UPDATE" else "SAVE", color = Palette.Bg, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 1.2.sp)
                }
            }
        }

        // ── Image
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (val s = editor.status) {
                EditorStatus.Loading -> CircularProgressIndicator(color = Palette.Amber, strokeWidth = 2.dp)
                is EditorStatus.Failed -> Text(s.message, style = Type.Body, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
                EditorStatus.Ready -> {
                    if (panel == Panel.CROP) {
                        val (sw, sh) = editor.sourceSize
                        val expected = GlRenderer.outputSize(sw, sh, editor.geo.uncropped())
                        val img = editor.rendered
                        if (img != null && img.width == expected.first && img.height == expected.second) {
                            CropOverlay(img, editor.geo, editor::updateGeo, Modifier.fillMaxSize(), fineGrid = straightening)
                        } else {
                            CircularProgressIndicator(color = Palette.Amber, strokeWidth = 2.dp)
                        }
                    } else {
                        val shown: ImageBitmap? = if (comparing) editor.original else (editor.rendered ?: editor.original)
                        if (shown != null) {
                            ZoomableImage(
                                bitmap = shown,
                                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
                                resetKey = Triple(editor.fx.frame, editor.geo, panel),
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
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { toast = null; onViewSaved(t.second!!) }.padding(12.dp),
                        )
                    } else Spacer(Modifier.width(10.dp))
                }
            }
        }

        // ── Control panel
        Column(Modifier.fillMaxWidth().background(Palette.Surface).navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(268.dp)) {
                when (panel) {
                    Panel.FILMS -> FilmsPanel(editor)
                    Panel.ADJUST -> AdjustPanel(editor)
                    Panel.EFFECTS -> EffectsPanel(editor)
                    Panel.LENS -> LensPanel(editor)
                    Panel.CROP -> CropPanel(editor) { straightening = it }
                    Panel.FRAME -> FramePanel(editor)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                BottomTab(Icons.Outlined.FilterVintage, Panel.FILMS.label, panel == Panel.FILMS, { panel = Panel.FILMS }, Modifier.weight(1f))
                BottomTab(Icons.Outlined.Tune, Panel.ADJUST.label, panel == Panel.ADJUST, { panel = Panel.ADJUST }, Modifier.weight(1f))
                BottomTab(Icons.Outlined.Grain, Panel.EFFECTS.label, panel == Panel.EFFECTS, { panel = Panel.EFFECTS }, Modifier.weight(1f))
                BottomTab(Icons.Outlined.Camera, Panel.LENS.label, panel == Panel.LENS, { panel = Panel.LENS }, Modifier.weight(1f))
                BottomTab(Icons.Outlined.Crop, Panel.CROP.label, panel == Panel.CROP, { panel = Panel.CROP }, Modifier.weight(1f))
                BottomTab(Icons.Outlined.CropFree, Panel.FRAME.label, panel == Panel.FRAME, { panel = Panel.FRAME }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FilmsPanel(editor: EditorController) {
    var category by rememberSaveable { mutableStateOf(editor.profile.category ?: Category.VIBES) }
    val films = remember(category) { listOf(Profiles.ORIGINAL) + Profiles.all.filter { it.category == category } }
    val p = editor.profile
    Column(Modifier.fillMaxSize().padding(top = 10.dp)) {
        // Description (always shown in full) + strength
        Column(Modifier.height(86.dp)) {
            Text(
                if (p.isIdentity) "Pick a film below. Hold the image to compare with the original." else p.blurb,
                style = Type.Body.copy(fontSize = 12.5.sp, lineHeight = 16.sp),
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (!p.isIdentity) {
                FilmSlider(
                    "Strength", editor.adj.intensity, { editor.updateAdj(editor.adj.copy(intensity = it)) },
                    min = 0f, max = 1f, default = 1f, format = { "${(it * 100).roundToInt()}%" },
                )
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Category.entries) { c -> Chip(c.label, c == category, { category = c }) }
        }
        Spacer(Modifier.height(12.dp))
        val listState = rememberLazyListState()
        LaunchedEffect(category) {
            val i = films.indexOfFirst { it.id == p.id }
            listState.scrollToItem(if (i > 1) i - 1 else 0)
        }
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
        Spacer(Modifier.height(5.dp))
        Text(
            profile.name, fontSize = 10.5.sp, lineHeight = 12.5.sp, maxLines = 2, textAlign = TextAlign.Center,
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
    val context = LocalContext.current
    val d = remember(editor.profile) { AppSettings.get(context).effectsFor(editor.profile) }
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
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            ) {
                items(LeakStyle.entries) { s ->
                    Chip(s.label, f.leakStyle == s.ordinal, {
                        editor.updateFx(f.copy(leakStyle = s.ordinal, leak = if (f.leak == 0f) 0.35f else f.leak))
                    })
                }
            }
        }
    }
}

@Composable
private fun CropPanel(editor: EditorController, onStraightening: (Boolean) -> Unit) {
    val g = editor.geo
    val (sw, sh) = editor.sourceSize
    fun rotated(rot: Int) = CropMath.rotatedSize(sw, sh, rot)
    Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CROP & STRAIGHTEN", style = Type.Label.copy(color = Palette.Text, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            RoundIconButton(Icons.Outlined.Rotate90DegreesCw, "Rotate 90°", {
                val rot = (g.rotation + 1) % 4
                val (rw, rh) = rotated(rot)
                editor.updateGeo(CropMath.centered(rot, g.aspect, rw, rh, g.copy(rotation = rot, left = 0f, top = 0f, right = 1f, bottom = 1f)))
            }, Modifier.size(38.dp))
            Spacer(Modifier.width(6.dp))
            RoundIconButton(Icons.Outlined.Flip, "Flip horizontal", { editor.updateGeo(g.copy(flipH = !g.flipH)) }, Modifier.size(38.dp),
                tint = if (g.flipH) Palette.Amber else Palette.Text)
            Spacer(Modifier.width(6.dp))
            RoundIconButton(Icons.Outlined.Flip, "Flip vertical", { editor.updateGeo(g.copy(flipV = !g.flipV)) }, Modifier.size(38.dp).rotate(90f),
                tint = if (g.flipV) Palette.Amber else Palette.Text)
            Spacer(Modifier.width(6.dp))
            RoundIconButton(Icons.Outlined.RestartAlt, "Reset", { editor.updateGeo(Geometry()) }, Modifier.size(38.dp))
        }
        AngleDial(
            value = g.angle,
            onChange = { editor.updateGeo(g.copy(angle = it)) },
            onActive = onStraightening,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        )
        Spacer(Modifier.height(6.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(CropMath.PRESETS) { preset ->
                AspectCard(preset.label, preset.ratio ?: if (preset.id == "original") sw.toFloat() / sh else null, g.aspect == preset.id) {
                    val (rw, rh) = rotated(g.rotation)
                    editor.updateGeo(CropMath.centered(g.rotation, preset.id, rw, rh, g))
                }
            }
        }
    }
}

@Composable
private fun LensPanel(editor: EditorController) {
    val f = editor.fx
    val selected = LensPreset.entries.getOrElse(f.lens) { LensPreset.NONE }
    Column(Modifier.fillMaxSize().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LENS", style = Type.Label.copy(color = Palette.Text, fontWeight = FontWeight.Bold))
                Text(selected.blurb, style = Type.Body.copy(fontSize = 11.5.sp), maxLines = 2)
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(LensPreset.entries) { preset ->
                Column(Modifier.width(64.dp).clickable { editor.updateFx(preset.applyTo(f)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    val sel = preset == selected
                    Box(
                        Modifier.size(58.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Surface2)
                            .border(if (sel) 2.dp else 0.dp, if (sel) Palette.Amber else Color.Transparent, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) { LensIcon(preset, if (sel) Palette.Amber else Palette.TextDim) }
                    Spacer(Modifier.height(5.dp))
                    Text(preset.label, fontSize = 10.5.sp, maxLines = 1, color = if (sel) Palette.Text else Palette.TextDim,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 8.dp)) {
            if (f.distortion > 0f || selected in listOf(LensPreset.FISHEYE, LensPreset.CIRCLE, LensPreset.WIDE)) {
                FilmSlider("Distortion", f.distortion, { editor.updateFx(f.copy(distortion = it)) }, min = 0f, max = 1f, default = 0f)
            }
            if (f.star > 0f || selected in listOf(LensPreset.TWINKLE, LensPreset.STARBURST, LensPreset.SPARKLE)) {
                FilmSlider("Glint", f.star, { editor.updateFx(f.copy(star = it)) }, min = 0f, max = 1f, default = 0f)
                FilmSlider("Star size", f.starLength, { editor.updateFx(f.copy(starLength = it)) }, min = 0f, max = 1f, default = 0.5f)
                FilmSlider("Star angle", f.starAngle, { editor.updateFx(f.copy(starAngle = it)) }, min = 0f, max = 1f, default = 0f,
                    format = { "${(it * 180f / (f.starPoints / 2).coerceAtLeast(2)).roundToInt()}°" })
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    items(listOf(4, 6, 8)) { n -> Chip("$n points", f.starPoints == n, { editor.updateFx(f.copy(starPoints = n)) }) }
                }
            }
            if (f.streak > 0f || selected == LensPreset.ANAMORPHIC) {
                FilmSlider("Streak", f.streak, { editor.updateFx(f.copy(streak = it)) }, min = 0f, max = 1f, default = 0f)
            }
            if (f.tiltShift > 0f || selected == LensPreset.TILT) {
                FilmSlider("Blur", f.tiltShift, { editor.updateFx(f.copy(tiltShift = it)) }, min = 0f, max = 1f, default = 0f)
                FilmSlider("Focus position", f.focusY, { editor.updateFx(f.copy(focusY = it)) }, min = 0f, max = 1f, default = 0.55f)
                FilmSlider("Focus size", f.focusSize, { editor.updateFx(f.copy(focusSize = it)) }, min = 0.05f, max = 0.7f, default = 0.22f)
            }
            if (f.softFocus > 0f || selected == LensPreset.SOFT) {
                FilmSlider("Softness", f.softFocus, { editor.updateFx(f.copy(softFocus = it)) }, min = 0f, max = 1f, default = 0f)
            }
            if (f.swirl > 0f || selected == LensPreset.SWIRL) {
                FilmSlider("Swirl", f.swirl, { editor.updateFx(f.copy(swirl = it)) }, min = 0f, max = 1f, default = 0f)
            }
        }
    }
}

@Composable
private fun LensIcon(preset: LensPreset, color: Color) {
    Canvas(Modifier.size(30.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        val st = Stroke(1.8.dp.toPx())
        fun star(points: Int, len: Float) {
            for (i in 0 until points / 2) {
                val a = Math.PI * i / (points / 2) + Math.PI / 8 * (if (points == 4) 1 else 0)
                val dx = (kotlin.math.cos(a) * r * len).toFloat()
                val dy = (kotlin.math.sin(a) * r * len).toFloat()
                drawLine(color, Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), 1.6.dp.toPx())
            }
            drawCircle(color, r * 0.16f, c)
        }
        when (preset) {
            LensPreset.NONE -> { drawCircle(color, r * 0.8f, c, style = st); drawLine(color, Offset(c.x - r * 0.55f, c.y + r * 0.55f), Offset(c.x + r * 0.55f, c.y - r * 0.55f), 1.8.dp.toPx()) }
            LensPreset.FISHEYE -> { drawCircle(color, r * 0.9f, c, style = st); drawOval(color, Offset(c.x - r * 0.45f, c.y - r * 0.9f), Size(r * 0.9f, r * 1.8f), style = st); drawLine(color, Offset(c.x - r * 0.9f, c.y), Offset(c.x + r * 0.9f, c.y), 1.6.dp.toPx()) }
            LensPreset.CIRCLE -> { drawCircle(color, r * 0.95f, c, style = Stroke(4.dp.toPx())); drawCircle(color, r * 0.35f, c) }
            LensPreset.WIDE -> { drawRoundRect(color, Offset(c.x - r * 0.9f, c.y - r * 0.6f), Size(r * 1.8f, r * 1.2f), androidx.compose.ui.geometry.CornerRadius(r * 0.5f), style = st) }
            LensPreset.TWINKLE -> star(4, 1f)
            LensPreset.STARBURST -> star(6, 0.95f)
            LensPreset.SPARKLE -> { star(8, 0.6f); drawCircle(color, r * 0.1f, Offset(c.x + r * 0.7f, c.y - r * 0.7f)); drawCircle(color, r * 0.08f, Offset(c.x - r * 0.7f, c.y + r * 0.6f)) }
            LensPreset.ANAMORPHIC -> { drawLine(color, Offset(0f, c.y), Offset(size.width, c.y), 2.dp.toPx()); drawCircle(color, r * 0.22f, c) }
            LensPreset.TILT -> { for (i in 0..4) { val y = size.height * (0.1f + i * 0.2f); drawLine(color.copy(alpha = if (i == 2) 1f else 0.35f), Offset(0f, y), Offset(size.width, y), if (i == 2) 2.5.dp.toPx() else 4.dp.toPx()) } }
            LensPreset.SOFT -> { for (i in 4 downTo 1) drawCircle(color.copy(alpha = 0.18f * (5 - i)), r * i / 4.5f, c) }
            LensPreset.SWIRL -> { for (i in 1..3) drawArc(color, 40f * i, 220f, false, Offset(c.x - r * i / 3.2f, c.y - r * i / 3.2f), Size(r * 2 * i / 3.2f, r * 2 * i / 3.2f), style = st) }
        }
    }
}

@Composable
private fun AspectCard(label: String, ratio: Float?, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(64.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Surface2)
                .border(if (selected) 2.dp else 0.dp, if (selected) Palette.Amber else Color.Transparent, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(38.dp)) {
                val c = if (selected) Palette.Amber else Palette.TextDim
                if (ratio == null) {
                    val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 5f))
                    drawRect(c, Offset(size.width * 0.1f, size.height * 0.2f), Size(size.width * 0.8f, size.height * 0.6f), style = Stroke(2.dp.toPx(), pathEffect = dash))
                } else {
                    val (w, h) = if (ratio >= 1f) size.width to size.width / ratio else size.height * ratio to size.height
                    drawRect(c, Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h), style = Stroke(2.dp.toPx()))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 11.sp, maxLines = 1, color = if (selected) Palette.Text else Palette.TextDim, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
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
            Text("'26 11 20", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.Orange, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Date stamp", style = Type.Title.copy(fontSize = 14.sp))
                Text("LED imprint of the capture date. Colour and format in Settings.", style = Type.Body.copy(fontSize = 11.sp))
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
