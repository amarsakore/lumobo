package app.lumobo.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CameraRoll
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Cloud
import app.lumobo.data.AppSettings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.lumobo.data.Album
import app.lumobo.data.MediaImage
import app.lumobo.data.MediaRepository
import app.lumobo.data.MediaThumb
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import app.lumobo.film.Profiles
import app.lumobo.ui.components.Chip
import app.lumobo.ui.components.RoundIconButton
import app.lumobo.ui.components.SegmentedTabs
import app.lumobo.ui.theme.Palette
import app.lumobo.ui.theme.Type
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Access { NONE, PARTIAL, FULL }

private fun permissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun access(context: Context): Access {
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> Access.FULL
        Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.FULL
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
        else -> Access.NONE
    }
}

@Composable
fun GalleryScreen(
    refreshKey: Int,
    onOpen: (Uri) -> Unit,
    onOpenDeveloped: (List<MediaImage>, Int) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    var accessState by remember { mutableStateOf(access(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) accessState = access(context) }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { accessState = access(context) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) onOpen(uri) }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    var images by remember { mutableStateOf<List<MediaImage>?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var albumId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(accessState, refreshKey) {
        if (accessState != Access.NONE) images = runCatching { MediaRepository.loadImages(context) }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize().background(Palette.Bg)) {
        // Header
        Row(
            Modifier.statusBarsPadding().fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("LUMOBO", style = Type.Wordmark)
                }
            }
            RoundIconButton(Icons.Outlined.AddPhotoAlternate, "Pick from Google Photos or files", onClick = { pick() }, modifier = Modifier.size(38.dp))
            Spacer(Modifier.width(8.dp))
            RoundIconButton(Icons.Outlined.Settings, "Settings", onClick = onOpenSettings, modifier = Modifier.size(38.dp))
        }

        if (accessState == Access.NONE) {
            PermissionCard(onAllow = { permLauncher.launch(permissions()) }, onPick = { pick() })
            return@Column
        }

        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SegmentedTabs(listOf("Library", "Developed"), tab, { tab = it })
        }
        Spacer(Modifier.height(6.dp))

        val all = images
        if (all == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Amber, strokeWidth = 2.dp) }
            return@Column
        }

        if (accessState == Access.PARTIAL) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Surface2)
                    .clickable { permLauncher.launch(permissions()) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Showing only photos you selected.", style = Type.Body, modifier = Modifier.weight(1f))
                Text("MANAGE", style = Type.Label.copy(color = Palette.Amber))
            }
        }

        if (tab == 0) {
            val showShots = settings.showScreenshots
            val library = remember(all, showShots) {
                all.filter { !it.isDeveloped && (showShots || !(it.bucket.contains("screenshot", true) || it.relativePath.contains("Screenshots"))) }
            }
            val albums = remember(library) { MediaRepository.albums(library) }
            AlbumRow(albums, albumId, onCloud = { pick() }) { albumId = it }
            val shown = if (albumId == null) library else library.filter { it.bucketId == albumId }
            PhotoGrid(shown, settings.gridColumns, emptyText = "No photos here yet.") { list, i -> onOpen(list[i].uri) }
        } else {
            val developed = remember(all) { all.filter { it.isDeveloped }.sortedByDescending { it.addedMillis }.map { it.copy(dateMillis = it.addedMillis) } }
            PhotoGrid(developed, settings.gridColumns, emptyText = "Nothing developed yet.\nPick a photo from your library and give it some film.") { list, i -> onOpenDeveloped(list, i) }
        }
    }
}

@Composable
private fun AlbumRow(albums: List<Album>, selected: Long?, onCloud: () -> Unit, onSelect: (Long?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 10.dp),
    ) {
        item {
            // Android's photo picker includes your Google Photos cloud library.
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Palette.Surface2).clickable(onClick = onCloud)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Cloud, null, tint = Palette.Amber, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("Google Photos", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = Palette.Text)
            }
        }
        item { Chip("All photos", selected == null, { onSelect(null) }) }
        items(albums, key = { it.id }) { a -> Chip(a.name, selected == a.id, { onSelect(a.id) }) }
    }
}

private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy")

@Composable
private fun PhotoGrid(images: List<MediaImage>, columns: Int, emptyText: String, onClick: (List<MediaImage>, Int) -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { AppSettings.get(ctx) }
    if (images.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.CameraRoll, null, tint = Palette.TextFaint, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(12.dp))
                Text(emptyText, style = Type.Body, textAlign = TextAlign.Center)
            }
        }
        return
    }
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val sections = remember(images) {
        images.withIndex().groupBy { Instant.ofEpochMilli(it.value.dateMillis).atZone(zone).format(monthFmt) }
    }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val cellPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx() / columns.coerceIn(2, 6)).toInt().coerceIn(160, 720)
    }
    val gridState = rememberLazyGridState()
    // Month of every grid item (headers included), for the date shown while scrolling.
    val itemMonths = remember(sections) { sections.flatMap { (m, l) -> List(l.size + 1) { m } } }
    // Live pinch scale: the grid follows your fingers, then settles on the new column count.
    var pinchScale by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var pinchOrigin by remember { mutableStateOf(androidx.compose.ui.graphics.TransformOrigin.Center) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns.coerceIn(2, 6)),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = bottom + 16.dp),
        modifier = Modifier.fillMaxSize()
            .graphicsLayer { scaleX = pinchScale; scaleY = pinchScale; transformOrigin = pinchOrigin }
            .pointerInput(Unit) {
                // Pinch to change photos per row, like Google Photos: the grid switches
                // columns as soon as the pinch is clear, keeps the photo under your fingers
                // in place, and eases in with a small zoom.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var acc = 1f
                    var switched = false
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.changes.count { it.pressed } >= 2) {
                            ev.changes.forEach { it.consume() }
                            if (switched) continue
                            acc *= ev.calculateZoom()
                            val cols = settings.gridColumns
                            val next = when {
                                acc > 1.18f && cols > 2 -> cols - 1
                                acc < 0.85f && cols < 6 -> cols + 1
                                else -> continue
                            }
                            switched = true
                            val c = ev.calculateCentroid()
                            // The photo under the fingers, and where inside it they are.
                            val hitItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull {
                                c.x >= it.offset.x && c.x < it.offset.x + it.size.width &&
                                    c.y >= it.offset.y && c.y < it.offset.y + it.size.height
                            }
                            val fy = hitItem?.let { (c.y - it.offset.y) / it.size.height.toFloat() } ?: 0f
                            pinchOrigin = androidx.compose.ui.graphics.TransformOrigin(
                                (c.x / size.width).coerceIn(0f, 1f), (c.y / size.height).coerceIn(0f, 1f),
                            )
                            settings.gridColumns = next
                            scope.launch {
                                if (hitItem != null) {
                                    val newCell = size.width / next.toFloat()
                                    gridState.scrollToItem(hitItem.index)
                                    gridState.scrollBy(-(c.y - fy * newCell))
                                }
                                val from = if (next < cols) 0.92f else 1.08f
                                androidx.compose.animation.core.animate(from, 1f, animationSpec = androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { v, _ -> pinchScale = v }
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            },
    ) {
        sections.forEach { (month, list) ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "h_$month", contentType = "header") {
                Text(
                    month.uppercase(),
                    style = Type.Label.copy(color = Palette.Text, fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 8.dp),
                )
            }
            items(list, key = { it.value.id }, contentType = { "photo" }) { (index, img) ->
                AsyncImage(
                    model = ImageRequest.Builder(context).data(MediaThumb(img.uri, img.modified)).size(cellPx).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .background(Palette.Surface2)
                        .clickable { onClick(images, index) },
                )
            }
        }
    }
    DateScroller(gridState, itemMonths, Modifier.align(Alignment.TopEnd).padding(bottom = bottom + 8.dp))
    }
}

/** Google Photos-style fast scroller: a handle with the month you're at, shown while scrolling. */
@Composable
private fun DateScroller(state: androidx.compose.foundation.lazy.grid.LazyGridState, months: List<String>, modifier: Modifier) {
    if (months.size < 40) return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val scrolling = state.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) visible = true
        else { kotlinx.coroutines.delay(2000); visible = false }
    }
    val total = months.size
    val first = state.firstVisibleItemIndex.coerceIn(0, total - 1)
    val frac = first.toFloat() / (total - 1).coerceAtLeast(1)
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (visible) 1f else 0f, label = "scroller")
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxHeight().width(180.dp).graphicsLayer { this.alpha = alpha }) {
        val trackPx = constraints.maxHeight.toFloat()
        val handleH = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
        val y = (trackPx - handleH) * frac
        Row(
            Modifier.align(Alignment.TopEnd).offset { androidx.compose.ui.unit.IntOffset(0, y.toInt()) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dragging || scrolling) {
                Text(
                    months[first].substringBefore(" ").take(3) + " " + months[first].substringAfter(" "),
                    style = Type.Label.copy(color = Palette.Text, fontWeight = FontWeight.Bold),
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.Surface2).padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Box(
                Modifier.padding(end = 4.dp).width(30.dp).height(56.dp).clip(RoundedCornerShape(15.dp))
                    .background(if (dragging) Palette.Amber else Palette.Surface3),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.UnfoldMore, null, tint = if (dragging) Palette.Bg else Palette.Text, modifier = Modifier.size(20.dp))
            }
        }
        // Touch strip: 28dp at the right edge, active only while the handle shows.
        Box(
            Modifier.align(Alignment.TopEnd).fillMaxHeight().width(48.dp).pointerInput(total, visible) {
                if (total == 0 || !visible) return@pointerInput
                fun jump(py: Float) {
                    val pos = (py - handleH / 2f).coerceIn(0f, trackPx - handleH)
                    val target = ((pos / (trackPx - handleH)) * (total - 1)).toInt()
                    scope.launch { state.scrollToItem(target) }
                }
                detectVerticalDragGestures(
                    onDragStart = { dragging = true; jump(it.y) },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { ch, _ -> ch.consume(); jump(ch.position.y) }
            },
        )
    }
}

@Composable
private fun PermissionCard(onAllow: () -> Unit, onPick: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(84.dp).clip(RoundedCornerShape(24.dp)).background(Palette.Surface2),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.PhotoLibrary, null, tint = Palette.Amber, modifier = Modifier.size(38.dp)) }
            Spacer(Modifier.height(22.dp))
            Text("Your photos, on film.", style = Type.Title.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(10.dp))
            Text(
                "Allow access to browse your library inside Lumobo. Everything is developed on your phone; nothing is uploaded.",
                style = Type.Body, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(26.dp))
            Button(
                onClick = onAllow,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Amber, contentColor = Palette.Bg),
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("Allow photo access", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onPick) { Text("Or pick a single photo", color = Palette.TextDim) }
        }
    }
}
