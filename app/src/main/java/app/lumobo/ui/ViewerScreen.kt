package app.lumobo.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumobo.data.MediaRepository
import app.lumobo.editor.ReEdit
import app.lumobo.render.Images
import app.lumobo.ui.components.RoundIconButton
import app.lumobo.ui.components.ZoomState
import app.lumobo.ui.components.zoomable
import app.lumobo.ui.theme.Palette
import app.lumobo.ui.theme.Type
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ViewerScreen(
    uris: List<Uri>,
    start: Int,
    version: Int,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onEdit: (ReEdit.Plan) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    var items by remember { mutableStateOf(uris) }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (uris.size - 1).coerceAtLeast(0))) { items.size }
    val zooms = remember { mutableStateMapOf<Uri, ZoomState>() }
    var pendingDelete by remember { mutableStateOf<Uri?>(null) }
    var opening by remember { mutableStateOf(false) }

    fun removed(uri: Uri) {
        items = items - uri
        onDeleted()
        if (items.isEmpty()) onBack()
    }

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val u = pendingDelete
        if (res.resultCode == Activity.RESULT_OK && u != null) removed(u)
        pendingDelete = null
    }

    val current = items.getOrNull(pager.currentPage)
    val currentZoom = current?.let { zooms.getOrPut(it) { ZoomState() } }
    // Reset zoom on pages we leave.
    LaunchedEffect(pager.currentPage) { zooms.forEach { (u, z) -> if (u != current) z.reset() } }

    var film by remember { mutableStateOf("") }
    LaunchedEffect(current, version) {
        film = if (current == null) "" else withContext(Dispatchers.IO) { Images.readDeveloped(context, current).first.orEmpty() }
    }

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            key = { items[it] },
            userScrollEnabled = currentZoom?.zoomed != true,
        ) { page ->
            val uri = items[page]
            val z = zooms.getOrPut(uri) { ZoomState() }
            Box(Modifier.fillMaxSize().padding(top = 72.dp, bottom = 96.dp)) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(uri).size(3200)
                        .memoryCacheKey("$uri#$version").crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().zoomable(z),
                )
            }
        }

        // Top bar
        Row(
            Modifier.statusBarsPadding().fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (film.isNotBlank()) Text(film.uppercase(), style = Type.Label.copy(color = Palette.Amber))
                if (items.isNotEmpty()) Text("${pager.currentPage + 1} / ${items.size}", style = Type.Mono)
            }
            RoundIconButton(Icons.Outlined.Share, "Share", {
                val uri = current ?: return@RoundIconButton
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Share photo"))
            })
            Spacer(Modifier.width(8.dp))
            RoundIconButton(Icons.Outlined.DeleteOutline, "Delete", {
                val uri = current ?: return@RoundIconButton
                if (Build.VERSION.SDK_INT >= 30) {
                    // The system asks the user to confirm.
                    pendingDelete = uri
                    val pi = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                    deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else if (MediaRepository.delete(context, uri)) {
                    removed(uri)
                }
            }, tint = Palette.Red)
        }

        // Edit
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 18.dp)
                .clip(RoundedCornerShape(50))
                .background(Palette.Amber)
                .clickable(enabled = current != null && !opening) {
                    val uri = current ?: return@clickable
                    opening = true
                    scope.launch {
                        val plan = ReEdit.resolve(context, uri)
                        opening = false
                        onEdit(plan)
                    }
                }
                .padding(horizontal = 26.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (opening) {
                CircularProgressIndicator(color = Palette.Bg, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Icon(Icons.Outlined.Edit, null, tint = Palette.Bg, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text("EDIT AGAIN", color = Palette.Bg, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 1.2.sp)
        }
        Spacer(Modifier.height(0.dp))
    }
}
