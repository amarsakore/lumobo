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
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Details
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import app.lumobo.film.LensPreset
import app.lumobo.ui.components.panelScroll
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Velocity
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

internal enum class Tool(val label: String, val icon: ImageVector) {
    FILMS("Films", Icons.Outlined.FilterVintage),
    LOOKS("My Looks", Icons.Outlined.Bookmarks),
    LIGHT("Light", Icons.Outlined.WbSunny),
    COLOR("Color", Icons.Outlined.ColorLens),
    MIXER("Mixer", Icons.Outlined.Tune),
    GRADING("Grading", Icons.Outlined.Gradient),
    CURVES("Curves", Icons.AutoMirrored.Outlined.ShowChart),
    DETAIL("Detail", Icons.Outlined.Details),
    EFFECTS("Effects", Icons.Outlined.Grain),
    LENS("Lens", Icons.Outlined.Camera),
    CROP("Crop", Icons.Outlined.Crop),
}

/** Every tool lives in the bottom bar (it scrolls); Curves opens from Light, My Looks and Frame from Films. */
private val BAR = listOf(Tool.FILMS, Tool.LIGHT, Tool.COLOR, Tool.EFFECTS, Tool.CROP, Tool.LENS, Tool.DETAIL, Tool.MIXER, Tool.GRADING)

/** Tools whose panel can be pulled up to show every control at once. */
private val FIXED = setOf(Tool.FILMS, Tool.LOOKS, Tool.CROP)
private val PANEL_H = 216.dp

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

    var panel by rememberSaveable { mutableStateOf(Tool.FILMS) }
    val sheetState = remember { app.lumobo.ui.components.PanelSheet() }
    var comparing by remember { mutableStateOf(false) }
    var straightening by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<Pair<String, Uri?>?>(null) }
    // What a just-picked film does, shown briefly over the photo.
    var filmNote by remember { mutableStateOf<FilmProfile?>(null) }
    LaunchedEffect(filmNote) {
        if (filmNote != null) {
            delay(2400)
            filmNote = null
        }
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(panel) { editor.setCropping(panel == Tool.CROP) }
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
            Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack, Modifier.size(34.dp))
            Spacer(Modifier.width(4.dp))
            RoundIconButton(
                Icons.AutoMirrored.Outlined.Undo, "Undo", { editor.undo() },
                Modifier.size(34.dp).alpha(if (editor.canUndo) 1f else 0.35f), background = Color.Transparent,
            )
            RoundIconButton(
                Icons.AutoMirrored.Outlined.Redo, "Redo", { editor.redo() },
                Modifier.size(34.dp).alpha(if (editor.canRedo) 1f else 0.35f), background = Color.Transparent,
            )
            RoundIconButton(
                Icons.Outlined.RestartAlt, "Reset all changes", { editor.resetAll() },
                Modifier.size(34.dp).alpha(if (editor.canUndo || editor.profile.id != "original") 1f else 0.35f), background = Color.Transparent,
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
                    .size(34.dp)
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
                Icon(Icons.Outlined.Compare, "Hold to compare", tint = if (comparing) Palette.Bg else Palette.Text, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .height(34.dp)
                    .width(68.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Palette.Amber)
                    .clickable(enabled = !editor.saving && editor.status == EditorStatus.Ready) {
                        panel = if (panel == Tool.CROP) Tool.FILMS else panel
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
                    Text(if (target != null) "UPDATE" else "SAVE", color = Palette.Bg, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp)
                }
            }
        }

        // ── Image
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (val s = editor.status) {
                EditorStatus.Loading -> CircularProgressIndicator(color = Palette.Amber, strokeWidth = 2.dp)
                is EditorStatus.Failed -> Text(s.message, style = Type.Body, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
                EditorStatus.Ready -> {
                    if (panel == Tool.CROP) {
                        val (sw, sh) = editor.sourceSize
                        val expected = GlRenderer.outputSize(sw, sh, editor.geo.uncropped())
                        val img = editor.rendered
                        if (img != null && img.width == expected.first && img.height == expected.second) {
                            CropOverlay(img, editor.geo, editor::updateGeo, Modifier.fillMaxSize(), fineGrid = straightening)
                        } else {
                            CircularProgressIndicator(color = Palette.Amber, strokeWidth = 2.dp)
                        }
                    } else {
                        val shown: ImageBitmap? = when {
                            comparing -> editor.original
                            else -> editor.rendered ?: editor.original
                        }
                        if (shown != null) {
                            ZoomableImage(
                                bitmap = shown,
                                modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp),
                                resetKey = Triple(editor.fx.frame, editor.geo, panel),
                                onHold = { comparing = it },
                            )
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = filmNote != null && !comparing,
                            enter = fadeIn(), exit = fadeOut(),
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp, start = 24.dp, end = 24.dp),
                        ) {
                            val n = filmNote
                            Column(
                                Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.Surface.copy(alpha = 0.92f))
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(n?.name.orEmpty(), style = Type.Title.copy(fontSize = 13.sp))
                                Text(
                                    n?.blurb.orEmpty(), style = Type.Body.copy(fontSize = 11.5.sp, lineHeight = 15.sp),
                                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
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

        // ── Control panel. Pull a tool's panel up to see all of its controls; pull the
        // handle on Films (or the tab bar) up for every tool.
        // Panels are as tall as their controls (up to PANEL_H). One that has more controls
        // than fit can be pulled up until all of them show; pull down to shrink it again.
        val canExpand = panel !in FIXED && sheetState.overflow
        val open = sheetState.open
        val sheet = rememberPanelSheet(
            canExpand = { panel !in FIXED && sheetState.overflow },
            isExpanded = { sheetState.open },
            onExpand = { sheetState.expanded = it },
        )
        val dragAcc = remember { floatArrayOf(0f) }
        fun verticalDrag(onUp: () -> Unit, onDown: () -> Unit) = Modifier.pointerInput(Unit) {
            detectVerticalDragGestures(onDragStart = { dragAcc[0] = 0f }) { ch, dy ->
                dragAcc[0] += dy
                if (dragAcc[0] < -36f) { onUp(); dragAcc[0] = 0f; ch.consume() }
                else if (dragAcc[0] > 36f) { onDown(); dragAcc[0] = 0f; ch.consume() }
            }
        }
        fun expandable() = panel !in FIXED && sheetState.overflow
        val sheetDrag = verticalDrag(
            onUp = { if (expandable()) sheetState.expanded = true },
            onDown = { sheetState.expanded = false },
        )
        val maxPanel = (LocalConfiguration.current.screenHeightDp * 0.58f).dp
        Column(Modifier.fillMaxWidth().background(Palette.Surface).navigationBarsPadding()) {
            Box(
                Modifier.fillMaxWidth().height(14.dp).then(sheetDrag)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (expandable()) sheetState.expanded = !sheetState.open
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.width(32.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (open) Palette.Amber else if (canExpand) Palette.TextFaint else Palette.Surface3),
                )
            }
            Box(
                Modifier.fillMaxWidth()
                    // Same height for every tool, so the photo doesn't jump when switching;
                    // a pulled-up panel grows (smoothly) to fit all of its controls.
                    .then(if (open) Modifier.heightIn(min = PANEL_H, max = maxPanel) else Modifier.height(PANEL_H))
                    .animateContentSize(androidx.compose.animation.core.tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                    .nestedScroll(sheet)
                    .then(sheetDrag),
            ) {
              CompositionLocalProvider(app.lumobo.ui.components.LocalPanelSheet provides sheetState) {
                when (panel) {
                    Tool.FILMS -> FilmsPanel(editor, onPicked = { filmNote = if (it.isIdentity) null else it }) { panel = Tool.LOOKS }
                    Tool.LOOKS -> LooksPanel(editor)
                    Tool.LIGHT -> LightPanel(editor) { panel = Tool.CURVES }
                    Tool.COLOR -> ColorPanel(editor)
                    Tool.MIXER -> MixerPanel(editor)
                    Tool.GRADING -> GradingPanel(editor)
                    Tool.CURVES -> CurvesPanel(editor) { panel = Tool.LIGHT }
                    Tool.DETAIL -> DetailPanel(editor)
                    Tool.EFFECTS -> EffectsPanel(editor)
                    Tool.LENS -> LensPanel(editor)
                    Tool.CROP -> CropPanel(editor) { straightening = it }
                }
              }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
            // Scrolling tool bar: the tab for a sub-tool (Curves, My Looks) stays lit on its parent.
            val active = when (panel) { Tool.CURVES -> Tool.LIGHT; Tool.LOOKS -> Tool.FILMS; else -> panel }
            val barState = rememberLazyListState()
            LaunchedEffect(active) {
                val i = BAR.indexOf(active)
                if (i >= 0 && barState.layoutInfo.visibleItemsInfo.none { it.index == i && it.offset >= 0 && it.offset + it.size <= barState.layoutInfo.viewportEndOffset }) {
                    barState.animateScrollToItem(maxOf(0, i - 2))
                }
            }
            LazyRow(state = barState, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 4.dp)) {
                items(BAR) { t ->
                    BottomTab(t.icon, t.label, active == t, { panel = t }, Modifier.width(66.dp))
                }
            }
        }
    }
}

/**
 * Bottom-sheet behaviour for tool panels: dragging the controls up first grows the panel
 * (until every slider shows), and pulling down at the top of the list shrinks it again.
 */
@Composable
private fun rememberPanelSheet(
    canExpand: () -> Boolean,
    isExpanded: () -> Boolean,
    onExpand: (Boolean) -> Unit,
): NestedScrollConnection {
    val can by rememberUpdatedState(canExpand)
    val exp by rememberUpdatedState(isExpanded)
    val set by rememberUpdatedState(onExpand)
    return remember {
        object : NestedScrollConnection {
            var acc = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y >= 0f || !can() || exp()) return Offset.Zero
                acc = minOf(acc, 0f) + available.y
                if (acc < -36f) { set(true); acc = 0f }
                return Offset(0f, available.y)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f || !exp()) return Offset.Zero
                acc = maxOf(acc, 0f) + available.y
                if (acc > 48f) { set(false); acc = 0f }
                return Offset(0f, available.y)
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                acc = 0f
                return Velocity.Zero
            }
        }
    }
}

@Composable
private fun FilmsPanel(editor: EditorController, onPicked: (FilmProfile) -> Unit, onLooks: () -> Unit) {
    var category by rememberSaveable { mutableStateOf(editor.profile.category ?: editor.lastCategory ?: Category.VIBES) }
    LaunchedEffect(category) { editor.prefetch(category) }
    val films = remember(category) { listOf(Profiles.ORIGINAL) + Profiles.all.filter { it.category == category } }
    val p = editor.profile
    // Tap the selected film again to dial its strength (in place of the category row).
    var strengthOpen by remember { mutableStateOf(false) }
    val strength = strengthOpen && !p.isIdentity
    Column(Modifier.fillMaxSize().padding(top = 4.dp, bottom = 4.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Crossfade(strength, label = "strength") { dial ->
                if (dial) {
                    val fx = editor.fx
                    Column {
                        FilmSlider(
                            "${p.name} strength", editor.adj.intensity, { editor.updateAdj(editor.adj.copy(intensity = it)) },
                            min = 0f, max = 1f, default = 1f, format = { "${(it * 100).roundToInt()}%" },
                        )
                        Spacer(Modifier.height(4.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                Text("FRAME", style = Type.Label, modifier = Modifier.padding(top = 6.dp, end = 2.dp))
                            }
                            items(FrameStyle.entries) { st ->
                                Chip(if (st == FrameStyle.NONE) "No frame" else st.label, fx.frame == st, { editor.updateFx(editor.fx.copy(frame = st)) })
                            }
                            item { Chip("Date stamp", fx.dateStamp, { editor.updateFx(editor.fx.copy(dateStamp = !editor.fx.dateStamp)) }) }
                        }
                    }
                } else {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { AddLookChip(onLooks) }
                        items(Category.entries) { c -> Chip(c.label, c == category, { category = c }) }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val listState = rememberLazyListState()
        val (sw, sh) = editor.sourceSize
        val aspect = if (sw > 0 && sh > 0) (sw.toFloat() / sh).coerceIn(0.56f, 1.8f) else 1f
        LaunchedEffect(category) {
            val i = films.indexOfFirst { it.id == p.id }
            listState.scrollToItem(if (i > 1) i - 1 else 0)
        }
        // Previews fill the panel's spare height and take the photo's shape.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        val thumbH = (maxHeight - 22.dp).coerceIn(64.dp, 150.dp)
        val thumbW = (thumbH * aspect).coerceAtMost(170.dp)
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.align(Alignment.Center),
        ) {
            items(films, key = { it.id }) { f ->
                val selected = f.id == p.id
                FilmCard(f, editor.thumbs[f.id], selected, adjustable = selected && !f.isIdentity && !strength, thumbW, thumbH) {
                    if (selected) {
                        if (!f.isIdentity) strengthOpen = !strength
                    } else {
                        editor.select(f)
                        strengthOpen = false
                        onPicked(f)
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun FilmCard(
    profile: FilmProfile, thumb: ImageBitmap?, selected: Boolean, adjustable: Boolean,
    width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, onClick: () -> Unit,
) {
    Column(Modifier.width(maxOf(width, 72.dp)).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width, height)
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
            if (adjustable) {
                // Hint: tap again for strength.
                Box(
                    Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Tune, "Adjust strength", tint = Palette.Amber, modifier = Modifier.size(13.dp)) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            profile.name, fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
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
private fun EffectsPanel(editor: EditorController) {
    val f = editor.fx
    val context = LocalContext.current
    val d = remember(editor.profile) { AppSettings.get(context).effectsFor(editor.profile) }
    Column(Modifier.fillMaxWidth()) {
        PanelHeader("Film & lens", editor::resetEffects)
        Column(Modifier.panelScroll().padding(bottom = 8.dp)) {
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
    Column(Modifier.fillMaxWidth().panelScroll().padding(top = 2.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CROP", style = Type.Label.copy(color = Palette.Text, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            RoundIconButton(Icons.Outlined.Rotate90DegreesCw, "Rotate 90°", {
                val rot = (g.rotation + 1) % 4
                val (rw, rh) = rotated(rot)
                editor.updateGeo(CropMath.centered(rot, g.aspect, rw, rh, g.copy(rotation = rot, left = 0f, top = 0f, right = 1f, bottom = 1f)))
            }, Modifier.size(32.dp))
            Spacer(Modifier.width(6.dp))
            RoundIconButton(Icons.Outlined.Flip, "Flip horizontal", { editor.updateGeo(g.copy(flipH = !g.flipH)) }, Modifier.size(32.dp),
                tint = if (g.flipH) Palette.Amber else Palette.Text)
            Spacer(Modifier.width(6.dp))
            RoundIconButton(Icons.Outlined.Flip, "Flip vertical", { editor.updateGeo(g.copy(flipV = !g.flipV)) }, Modifier.size(32.dp).rotate(90f),
                tint = if (g.flipV) Palette.Amber else Palette.Text)
            Spacer(Modifier.width(6.dp))
            RoundIconButton(Icons.Outlined.RestartAlt, "Reset", { editor.updateGeo(Geometry()) }, Modifier.size(32.dp))
        }
        var mode by rememberSaveable { mutableStateOf(0) }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Straighten", "Vertical", "Horizontal").forEachIndexed { i, label ->
                val changed = when (i) { 0 -> g.angle != 0f; 1 -> g.perspV != 0f; else -> g.perspH != 0f }
                Chip(if (changed && mode != i) "$label •" else label, mode == i, { mode = i })
            }
        }
        val pctFmt: (Float) -> String = { v -> val r = v.roundToInt(); if (r > 0) "+$r" else "$r" }
        AngleDial(
            value = when (mode) { 0 -> g.angle; 1 -> g.perspV * 100f; else -> g.perspH * 100f },
            onChange = { v ->
                val cur = editor.geo
                editor.updateGeo(when (mode) { 0 -> cur.copy(angle = v); 1 -> cur.copy(perspV = v / 100f); else -> cur.copy(perspH = v / 100f) })
            },
            onActive = onStraightening,
            range = if (mode == 0) 45f else 100f,
            unitDp = if (mode == 0) 9f else 3f,
            format = if (mode == 0) { v -> String.format(Locale.US, "%+.1f°", v).replace("+0.0°", "0°").replace("-0.0°", "0°") } else pctFmt,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        )
        Spacer(Modifier.height(2.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
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
    Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        // Fixed height so switching recipes never moves the row below.
        Column(Modifier.fillMaxWidth().height(38.dp).padding(start = 20.dp, end = 16.dp)) {
            Text("LENS", style = Type.Label.copy(color = Palette.Text, fontWeight = FontWeight.Bold))
            Text(selected.blurb, style = Type.Body.copy(fontSize = 11.5.sp, lineHeight = 14.sp), maxLines = 2)
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(LensPreset.entries) { preset ->
                Column(Modifier.width(56.dp).clickable { editor.updateFx(preset.applyTo(f)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    val sel = preset == selected
                    Box(
                        Modifier.size(50.dp).clip(RoundedCornerShape(13.dp)).background(Palette.Surface2)
                            .border(if (sel) 2.dp else 0.dp, if (sel) Palette.Amber else Color.Transparent, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) { LensIcon(preset, if (sel) Palette.Amber else Palette.TextDim) }
                    Spacer(Modifier.height(5.dp))
                    Text(preset.label, fontSize = 10.5.sp, maxLines = 1, color = if (sel) Palette.Text else Palette.TextDim,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        Column(Modifier.panelScroll().padding(top = 4.dp, bottom = 8.dp)) {
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
    Column(Modifier.width(54.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Surface2)
                .border(if (selected) 2.dp else 0.dp, if (selected) Palette.Amber else Color.Transparent, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(32.dp)) {
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
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, maxLines = 1, color = if (selected) Palette.Text else Palette.TextDim, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}


