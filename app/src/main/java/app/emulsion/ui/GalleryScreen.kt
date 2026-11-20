package app.emulsion.ui

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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CameraRoll
import androidx.compose.material.icons.outlined.PhotoLibrary
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
import app.emulsion.data.Album
import app.emulsion.data.MediaImage
import app.emulsion.data.MediaRepository
import app.emulsion.film.Profiles
import app.emulsion.ui.components.Chip
import app.emulsion.ui.components.RoundIconButton
import app.emulsion.ui.components.SegmentedTabs
import app.emulsion.ui.theme.Palette
import app.emulsion.ui.theme.Type
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
) {
    val context = LocalContext.current
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
            Modifier.statusBarsPadding().fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("EMULSION", style = Type.Wordmark)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(Palette.Orange))
                }
                Text("FILM LAB · ${Profiles.all.size - 1} STOCKS & CAMERAS", style = Type.Label.copy(fontSize = 10.sp))
            }
            RoundIconButton(Icons.Outlined.AddPhotoAlternate, "Pick a photo", onClick = { pick() })
        }

        if (accessState == Access.NONE) {
            PermissionCard(onAllow = { permLauncher.launch(permissions()) }, onPick = { pick() })
            return@Column
        }

        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SegmentedTabs(listOf("Library", "Developed"), tab, { tab = it })
        }
        Spacer(Modifier.height(10.dp))

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
            val library = all.filter { !it.isDeveloped }
            val albums = remember(library) { MediaRepository.albums(library) }
            AlbumRow(albums, albumId) { albumId = it }
            val shown = if (albumId == null) library else library.filter { it.bucketId == albumId }
            PhotoGrid(shown, emptyText = "No photos here yet.") { list, i -> onOpen(list[i].uri) }
        } else {
            val developed = remember(all) { all.filter { it.isDeveloped }.sortedByDescending { it.addedMillis }.map { it.copy(dateMillis = it.addedMillis) } }
            PhotoGrid(developed, emptyText = "Nothing developed yet.\nPick a photo from your library and give it some film.") { list, i -> onOpenDeveloped(list, i) }
        }
    }
}

@Composable
private fun AlbumRow(albums: List<Album>, selected: Long?, onSelect: (Long?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 10.dp),
    ) {
        item { Chip("All photos", selected == null, { onSelect(null) }) }
        items(albums, key = { it.id }) { a -> Chip(a.name, selected == a.id, { onSelect(a.id) }) }
    }
}

private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy")

@Composable
private fun PhotoGrid(images: List<MediaImage>, emptyText: String, onClick: (List<MediaImage>, Int) -> Unit) {
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
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = rememberLazyGridState(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = bottom + 16.dp),
        modifier = Modifier.fillMaxSize(),
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
                    model = ImageRequest.Builder(context).data(img.uri).size(384).crossfade(180).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .background(Palette.Surface)
                        .clickable { onClick(images, index) },
                )
            }
        }
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
                "Allow access to browse your library inside Emulsion. Everything is developed on your phone; nothing is uploaded.",
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
