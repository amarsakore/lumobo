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
import app.lumobo.ui.components.PanelColumn
import app.lumobo.ui.components.AngleDial
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Flare
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
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.HdrOff
import androidx.compose.material.icons.outlined.HdrOn
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.gestures.detectDragGestures
import app.lumobo.editor.Background
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.ContentCopy
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
    GLOW("Glow", Icons.Outlined.Flare),
    LENS("Lens", Icons.Outlined.Camera),
    CROP("Crop", Icons.Outlined.Crop),
}

/** Every tool lives in the bottom bar (it scrolls); Curves opens from Light, My Looks and Frame from Films. */
private val BAR = listOf(Tool.FILMS, Tool.LIGHT, Tool.COLOR, Tool.EFFECTS, Tool.GLOW, Tool.CROP, Tool.LENS, Tool.DETAIL, Tool.MIXER, Tool.GRADING)

/** Tools whose panel can be pulled up to show every control at once. */
private val FIXED = setOf(Tool.FILMS, Tool.LOOKS, Tool.CROP, Tool.CURVES)

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
    // Show the photo in HDR on HDR screens (Android 14+): the preview carries the edited photo's
    // gain map, which the window draws above SDR white in HDR colour mode.
    val window = (LocalContext.current as? android.app.Activity)?.window
    val appSettings = remember { app.lumobo.data.AppSettings.get(context) }
    val hdrView = appSettings.hdrView
    DisposableEffect(window, hdrView) {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            window?.colorMode = if (hdrView) android.content.pm.ActivityInfo.COLOR_MODE_HDR else android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
        }
        onDispose { window?.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT }
    }
    // Leaving an edited photo develops it into Developed (library photos always reopen fresh;
    // a developed photo is updated in place). The overlay shows while it develops.
    val appContext = LocalContext.current.applicationContext
    val leaveScope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }
    val leave: () -> Unit = leave@{
        if (leaving || editor.saving) return@leave
        if (!editor.isDirty) { onBack(); return@leave }
        leaving = true
        leaveScope.launch {
            val r = editor.save()
            if (r.isSuccess) {
                onSaved()
                android.widget.Toast.makeText(appContext, if (target != null) "Updated in Developed" else "Saved to Developed", android.widget.Toast.LENGTH_SHORT).show()
            }
            leaving = false
            onBack()
        }
    }
    BackHandler(onBack = leave)
    // App closing / going to the background with unsaved edits: develop them too.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, editor) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP && editor.isDirty && !editor.saving && !leaving) {
                Background.scope.launch { if (editor.save().isSuccess) onSaved() }
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    var panel by rememberSaveable { mutableStateOf(Tool.FILMS) }
    val sheetState = remember { app.lumobo.ui.components.PanelSheet() }
    var comparing by remember { mutableStateOf(false) }
    var straightening by remember { mutableStateOf(false) }
    // Preview: hide the panel and tool bar to see the photo as large as possible.
    var preview by remember { mutableStateOf(false) }
    BackHandler(enabled = preview) { preview = false }
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

    LaunchedEffect(panel) { editor.setCropping(panel == Tool.CROP); if (panel != Tool.LENS) editor.starBrush = false; if (panel != Tool.GLOW) editor.previewGlow(false) }
    // No star or streak left to erase: leave brush mode so the photo takes gestures again.
    LaunchedEffect(editor.fx.star > 0f || editor.fx.streak > 0f) { if (editor.fx.star <= 0f && editor.fx.streak <= 0f) editor.starBrush = false }
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
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", leave, Modifier.size(34.dp))
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
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                // HDR view on / off. Off shows the photo at normal brightness; its HDR light is
                // still used for stars and glow, and saving still writes Ultra HDR.
                RoundIconButton(
                    if (hdrView) Icons.Outlined.HdrOn else Icons.Outlined.HdrOff,
                    if (hdrView) "Show without HDR" else "Show in HDR", { appSettings.hdrView = !hdrView },
                    Modifier.size(34.dp), background = if (hdrView) Palette.Amber.copy(alpha = 0.25f) else Color.Transparent,
                )
            }
            RoundIconButton(
                if (preview) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                if (preview) "Show tools" else "Preview photo", { preview = !preview },
                Modifier.size(34.dp), background = if (preview) Palette.Amber.copy(alpha = 0.25f) else Color.Transparent,
            )
            Spacer(Modifier.width(4.dp))
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
            // Save: the first save of a library photo goes straight to Developed; once a developed
            // photo exists (re-editing, or saved earlier this session) it offers Update or a copy.
            var saveMenu by remember { mutableStateOf(false) }
            fun doSave(asCopy: Boolean) {
                val updating = editor.hasSaveTarget && !asCopy
                panel = if (panel == Tool.CROP) Tool.FILMS else panel
                scope.launch {
                    val r = editor.save(asCopy)
                    toast = r.fold(
                        onSuccess = {
                            onSaved()
                            (if (asCopy) "Saved as a copy in Developed" else if (updating) "Updated in Developed" else "Developed · saved to your gallery") to it
                        },
                        onFailure = { ("Couldn't save: " + (it.message ?: "unknown error")) to null },
                    )
                }
            }
            Box {
                Box(
                    Modifier
                        .height(34.dp)
                        .width(68.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Palette.Amber)
                        .clickable(enabled = !editor.saving && editor.status == EditorStatus.Ready) {
                            if (editor.hasSaveTarget) saveMenu = true else doSave(false)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (editor.saving) {
                        CircularProgressIndicator(color = Palette.Bg, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Text(if (target != null) "UPDATE" else "SAVE", color = Palette.Bg, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp)
                    }
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = saveMenu,
                    onDismissRequest = { saveMenu = false },
                    containerColor = Palette.Surface2,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Update", color = Palette.Text, fontWeight = FontWeight.SemiBold) },
                        leadingIcon = { Icon(Icons.Outlined.Save, null, tint = Palette.Text) },
                        onClick = { saveMenu = false; doSave(false) },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Save as copy", color = Palette.Text, fontWeight = FontWeight.SemiBold) },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, tint = Palette.Text) },
                        onClick = { saveMenu = false; doSave(true) },
                    )
                }
            }
        }

        // ── Panel sizing. The panel follows the photo: a tall photo keeps it compact, a wider
        // one gives it more room (never more than for a 3:4 photo). It always fits Color whole.
        val conf = LocalConfiguration.current
        val density = androidx.compose.ui.platform.LocalDensity.current
        val maxPanel = (conf.screenHeightDp * 0.58f).dp
        val (srcW, srcH) = editor.sourceSize
        val g0 = editor.geo
        val photoAspect = if (srcW > 0 && srcH > 0) {
            val (rw, rh) = if (g0.rotation % 2 == 1) srcH to srcW else srcW to srcH
            (rw * g0.width) / (rh * g0.height).coerceAtLeast(1f)
        } else 0.75f
        val photoH = (conf.screenWidthDp - 12) / photoAspect.coerceIn(0.4f, 3f)
        val capH = conf.screenHeightDp - 170 - (conf.screenWidthDp - 12) / 0.75f
        val panelH = (conf.screenHeightDp - 170 - photoH).coerceIn(196f, maxOf(196f, capH)).dp
        val fixedTool = panel in FIXED
        var fullPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        var contentPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        var imageBoxPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        val handlePx = with(density) { 14.dp.toPx() }
        val capPx = with(density) { panelH.toPx() }
        // Collapsed, every tool has the same height (so the photo never moves); inside it, as
        // many controls as fit whole are shown. Crop is the one tool that may be taller.
        val uniformPx = maxOf(handlePx + capPx, if (panel == Tool.CROP) handlePx + contentPx else 0f)
        val snapPx = when {
            contentPx == 0 -> uniformPx
            fixedTool -> handlePx + contentPx
            else -> handlePx + sheetState.snapHeight(capPx, contentPx)
        }
        val overflow = !fixedTool && handlePx + contentPx > snapPx + 1f
        androidx.compose.runtime.SideEffect { sheetState.overflow = overflow }
        val open = sheetState.expanded && overflow
        // The sheet slides on the GPU (a layer offset) and its controls are revealed by a clip,
        // so nothing re-lays out while it moves.
        val spring = androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = 380f)
        val shown by androidx.compose.animation.core.animateFloatAsState(
            if (open) maxOf(fullPx.toFloat(), uniformPx) else uniformPx, spring, label = "sheet",
        )
        val reveal by androidx.compose.animation.core.animateFloatAsState(
            if (open) handlePx + contentPx else snapPx, spring, label = "reveal",
        )
        val padPx by androidx.compose.animation.core.animateFloatAsState(
            if (preview) 0f else uniformPx, androidx.compose.animation.core.tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "pad",
        )
        val imagePad = with(density) { padPx.toDp() }
        val canExpand = overflow
        val sheet = rememberPanelSheet(
            canExpand = { sheetState.overflow },
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
        val sheetDrag = verticalDrag(
            onUp = { if (sheetState.overflow) sheetState.expanded = true },
            onDown = { sheetState.expanded = false },
        )

        // ── Image
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
        Box(
            Modifier.fillMaxSize().padding(bottom = imagePad)
                .onSizeChanged { imageBoxPx = it.height }
                .graphicsLayer {
                    // As the panel slides up, the photo shrinks into the space above it (GPU only).
                    val h = imageBoxPx.toFloat()
                    if (h > 0f) {
                        val k = ((h - (shown - uniformPx).coerceAtLeast(0f)) / h).coerceIn(0.3f, 1f)
                        scaleX = k; scaleY = k
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
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
                        if (panel == Tool.LENS && editor.starBrush && (editor.fx.star > 0f || editor.fx.streak > 0f) && shown != null) {
                            // Brush over the photo: paints where stars are erased.
                            var brushAt by remember { mutableStateOf<Offset?>(null) }
                            // Keyed on nothing that changes while painting: every dab re-renders the
                            // photo, and restarting the gesture would drop the stroke after one dab.
                            val img by rememberUpdatedState(shown)
                            Box(
                                Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp)
                                    .pointerInput(Unit) {
                                        fun paint(p: Offset) {
                                            val iw = img.width.toFloat()
                                            val ih = img.height.toFloat()
                                            val sc = kotlin.math.min(size.width / iw, size.height / ih)
                                            val nx = (p.x - (size.width - iw * sc) / 2f) / (iw * sc)
                                            val ny = (p.y - (size.height - ih * sc) / 2f) / (ih * sc)
                                            if (nx in 0f..1f && ny in 0f..1f) editor.eraseStars(nx, ny, editor.brushRadius)
                                        }
                                        detectDragGestures(
                                            onDragStart = { brushAt = it; paint(it) },
                                            onDragEnd = { brushAt = null },
                                            onDragCancel = { brushAt = null },
                                        ) { ch, _ -> ch.consume(); brushAt = ch.position; paint(ch.position) }
                                    }
                                    .drawWithContent {
                                        drawContent()
                                        brushAt?.let {
                                            val r = kotlin.math.min(size.width, size.height * shown.width / shown.height.toFloat()) * editor.brushRadius
                                            drawCircle(Color.White.copy(alpha = 0.25f), r, it)
                                            drawCircle(Color.White, r, it, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                                        }
                                    },
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
            // ── Tool panel: a sheet over the bottom of the photo area. Pull it up to see every
            // control (it slides over the photo); it never cuts a control in half.
            if (!preview) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .heightIn(min = with(density) { uniformPx.toDp() })
                        .onSizeChanged { fullPx = it.height }
                        .graphicsLayer { translationY = (fullPx - shown).coerceAtLeast(0f) }
                        .background(Palette.Surface),
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(14.dp).then(sheetDrag)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                if (sheetState.overflow) sheetState.expanded = !sheetState.expanded
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
                            .heightIn(max = if (fixedTool && panel != Tool.CROP) panelH else maxPanel)
                            .onSizeChanged { contentPx = it.height }
                            .drawWithContent {
                                // Show whole controls only: hide anything past the reveal line.
                                clipRect(bottom = (reveal - handlePx).coerceAtLeast(0f)) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                            .onGloballyPositioned { sheetState.contentCoords = it; sheetState.updateTop() }
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
                    Tool.GLOW -> GlowPanel(editor)
                    Tool.CROP -> CropPanel(editor) { straightening = it }
                }
                      }
                    }
                }
            }
        }
        if (preview) Spacer(Modifier.navigationBarsPadding())
        this@Column.AnimatedVisibility(
            visible = !preview,
            enter = androidx.compose.animation.expandVertically() + fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + fadeOut(),
        ) {
        Column(Modifier.fillMaxWidth().background(Palette.Surface).navigationBarsPadding()) {
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
        PanelColumn(Modifier.padding(bottom = 8.dp)) {
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
        Row(Modifier.fillMaxWidth().height(38.dp).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LENS", style = Type.Label.copy(color = Palette.Text, fontWeight = FontWeight.Bold))
                Text(selected.blurb, style = Type.Body.copy(fontSize = 11.5.sp, lineHeight = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (f.star > 0f || f.streak > 0f) {
                // Star eraser lives in the header: paint on the photo where stars shouldn't appear.
                if (f.starMask.isNotEmpty()) {
                    Text(
                        "CLEAR", style = Type.Label.copy(color = Palette.Text, fontWeight = FontWeight.Bold),
                        modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, Palette.Line, RoundedCornerShape(50))
                            .clickable { editor.clearStarMask() }.padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (editor.starBrush) "DONE" else "ERASE STARS",
                    style = Type.Label.copy(color = if (editor.starBrush) Palette.Bg else Palette.Text, fontWeight = FontWeight.Bold),
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .then(if (editor.starBrush) Modifier.background(Palette.Amber) else Modifier.border(1.dp, Palette.Line, RoundedCornerShape(50)))
                        .clickable { editor.starBrush = !editor.starBrush }.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(LensPreset.DISPLAY) { preset ->
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
        PanelColumn(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
            if (f.star > 0f || f.streak > 0f) {
                if (editor.starBrush) {
                    FilmSlider("Brush size", editor.brushSize, { editor.brushSize = it }, min = 0f, max = 1f, default = 0.35f)
                }
            }
            if (f.distortion > 0f || selected in listOf(LensPreset.FISHEYE, LensPreset.CIRCLE, LensPreset.WIDE)) {
                FilmSlider("Distortion", f.distortion, { editor.updateFx(f.copy(distortion = it)) }, min = 0f, max = 1f, default = 0f)
            }
            if (f.star > 0f || selected in listOf(LensPreset.TWINKLE, LensPreset.STARBURST, LensPreset.SPARKLE, LensPreset.GLIMMER, LensPreset.DIFFRACTION, LensPreset.SUNBURST)) {
                FilmSlider("Glint", f.star, { editor.updateFx(f.copy(star = it)) }, min = 0f, max = 1f, default = 0f)
                // Higher threshold: only the very brightest points sparkle.
                FilmSlider("Threshold", f.starThreshold, { editor.updateFx(f.copy(starThreshold = it)) }, min = 0.3f, max = 0.95f, default = 0.6f,
                    format = { "${((it - 0.3f) / 0.65f * 100).roundToInt()}" })
                FilmSlider("Star size", f.starLength, { editor.updateFx(f.copy(starLength = it)) }, min = 0f, max = 1f, default = 0.5f)
                FilmSlider("Rainbow", f.starSpectral, { editor.updateFx(f.copy(starSpectral = it)) }, min = 0f, max = 1f, default = 0f)
                FilmSlider("Star angle", f.starAngle, { editor.updateFx(f.copy(starAngle = it)) }, min = 0f, max = 1f, default = 0f,
                    format = { "${(it * 180f / (f.starPoints / 2).coerceAtLeast(2)).roundToInt()}°" })
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    items(listOf(4, 6, 8)) { n -> Chip("$n points", f.starPoints == n, { editor.updateFx(f.copy(starPoints = n)) }) }
                }
            }
            if (f.mist > 0f || selected in listOf(LensPreset.PROMIST, LensPreset.GLIMMER, LensPreset.VELVET)) {
                FilmSlider("Mist", f.mist, { editor.updateFx(f.copy(mist = it)) }, min = 0f, max = 1f, default = 0f)
            }
            if (f.streak > 0f || selected in listOf(LensPreset.ANAMORPHIC, LensPreset.PRISM, LensPreset.AMBER)) {
                FilmSlider("Streak", f.streak, { editor.updateFx(f.copy(streak = it)) }, min = 0f, max = 1f, default = 0f)
                FilmSlider("Streak colour", f.streakWarm, { editor.updateFx(f.copy(streakWarm = it)) }, min = 0f, max = 1f, default = 0f,
                    format = { if (it < 0.34f) "Blue" else if (it < 0.67f) "Neutral" else "Amber" })
                if (f.star <= 0f) FilmSlider("Rainbow", f.starSpectral, { editor.updateFx(f.copy(starSpectral = it)) }, min = 0f, max = 1f, default = 0f)
                if (f.star <= 0f) {
                    FilmSlider("Threshold", f.starThreshold, { editor.updateFx(f.copy(starThreshold = it)) }, min = 0.3f, max = 0.95f, default = 0.6f,
                        format = { "${((it - 0.3f) / 0.65f * 100).roundToInt()}" })
                }
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
            LensPreset.PROMIST -> { for (i in 5 downTo 1) drawCircle(color.copy(alpha = 0.12f * (6 - i)), r * (0.2f + i * 0.15f), c); drawCircle(color, r * 0.18f, c) }
            LensPreset.GLIMMER -> { for (i in 4 downTo 1) drawCircle(color.copy(alpha = 0.1f * (5 - i)), r * i / 4.2f, c); star(4, 0.75f) }
            LensPreset.DIFFRACTION -> { star(8, 0.95f); drawCircle(color, r * 0.55f, c, style = Stroke(1.dp.toPx())) }
            LensPreset.SUNBURST -> { star(8, 1f); drawCircle(color, r * 0.3f, c) }
            LensPreset.HELIOS -> { drawCircle(color, r * 0.9f, c, style = st); for (i in 1..2) drawArc(color, 30f + 60f * i, 200f, false, Offset(c.x - r * i / 2.6f, c.y - r * i / 2.6f), Size(r * 2 * i / 2.6f, r * 2 * i / 2.6f), style = st) }
            LensPreset.HOLGA -> {
                drawRoundRect(color, Offset(c.x - r * 0.85f, c.y - r * 0.85f), Size(r * 1.7f, r * 1.7f), androidx.compose.ui.geometry.CornerRadius(r * 0.25f), style = st)
                drawCircle(color.copy(alpha = 0.35f), r * 0.55f, c)
                drawCircle(color, r * 0.28f, c, style = st)
            }
            LensPreset.VELVET -> { for (i in 4 downTo 1) drawCircle(color.copy(alpha = 0.16f * (5 - i)), r * i / 4f, c); drawCircle(color, r * 0.9f, c, style = Stroke(1.dp.toPx())) }
            LensPreset.PRISM -> {
                val p = androidx.compose.ui.graphics.Path().apply { moveTo(c.x, c.y - r * 0.75f); lineTo(c.x + r * 0.7f, c.y + r * 0.5f); lineTo(c.x - r * 0.7f, c.y + r * 0.5f); close() }
                drawPath(p, color, style = st)
                drawLine(color, Offset(0f, c.y + r * 0.05f), Offset(size.width, c.y - r * 0.2f), 1.6.dp.toPx())
            }
            LensPreset.AMBER -> { drawLine(color, Offset(0f, c.y), Offset(size.width, c.y), 3.dp.toPx()); drawLine(color.copy(alpha = 0.5f), Offset(size.width * 0.15f, c.y - r * 0.35f), Offset(size.width * 0.85f, c.y - r * 0.35f), 1.dp.toPx()); drawCircle(color, r * 0.24f, c) }
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


