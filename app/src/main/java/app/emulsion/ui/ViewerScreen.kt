package app.emulsion.ui

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.LaunchedEffect
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.emulsion.data.MediaRepository
import app.emulsion.ui.components.RoundIconButton
import app.emulsion.ui.theme.Palette
import app.emulsion.ui.theme.Type
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun ViewerScreen(uris: List<Uri>, start: Int, onBack: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    var items by remember { mutableStateOf(uris) }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (uris.size - 1).coerceAtLeast(0))) { items.size }
    var pendingDelete by remember { mutableStateOf<Uri?>(null) }

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

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { items[it] }) { page ->
            AsyncImage(
                model = ImageRequest.Builder(context).data(items[page]).size(2400).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(vertical = 64.dp),
            )
        }
        Row(
            Modifier.statusBarsPadding().fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
            Spacer(Modifier.weight(1f))
            val current = items.getOrNull(pager.currentPage)
            var film by remember { mutableStateOf("") }
            LaunchedEffect(current) {
                film = if (current == null) "" else withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(current)?.use { ExifInterface(it).getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION) }
                    }.getOrNull().orEmpty()
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (film.isNotBlank()) Text(film.uppercase(), style = Type.Label.copy(color = Palette.Amber))
                if (items.isNotEmpty()) Text("${pager.currentPage + 1} / ${items.size}", style = Type.Mono)
            }
            Spacer(Modifier.weight(1f))
            RoundIconButton(Icons.Outlined.Share, "Share", {
                val uri = items.getOrNull(pager.currentPage) ?: return@RoundIconButton
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Share photo"))
            })
            Spacer(Modifier.width(8.dp))
            RoundIconButton(Icons.Outlined.DeleteOutline, "Delete", {
                val uri = items.getOrNull(pager.currentPage) ?: return@RoundIconButton
                if (Build.VERSION.SDK_INT >= 30) {
                    // System dialog asks the user to confirm; the photo goes to the system trash flow.
                    pendingDelete = uri
                    val pi = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                    deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else if (MediaRepository.delete(context, uri)) {
                    removed(uri)
                }
            }, tint = Palette.Red)
        }
    }
}
