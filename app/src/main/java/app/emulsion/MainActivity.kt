package app.emulsion

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import app.emulsion.ui.EditorScreen
import app.emulsion.ui.GalleryScreen
import app.emulsion.ui.ViewerScreen
import app.emulsion.ui.theme.EmulsionTheme

sealed interface Screen {
    data object Gallery : Screen
    data class Editor(val uri: Uri) : Screen
    data class Viewer(val uris: List<Uri>, val index: Int) : Screen
}

class MainActivity : ComponentActivity() {

    private val stack = mutableStateListOf<Screen>(Screen.Gallery)
    private var galleryRefresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        android.util.Log.i("Emulsion", "onCreate restored=${savedInstanceState != null} action=${intent?.action}")
        handleIntent(intent)
        setContent {
            EmulsionTheme {
                val top = stack.last()
                AnimatedContent(
                    targetState = top,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    contentKey = { it::class },
                    label = "nav",
                ) { screen ->
                    when (screen) {
                        Screen.Gallery -> GalleryScreen(
                            refreshKey = galleryRefresh,
                            onOpen = { push(Screen.Editor(it)) },
                            onOpenDeveloped = { list, i -> push(Screen.Viewer(list.map { it.uri }, i)) },
                        )
                        is Screen.Editor -> EditorScreen(
                            uri = screen.uri,
                            onBack = ::pop,
                            onSaved = { galleryRefresh++ },
                            onViewSaved = { push(Screen.Viewer(listOf(it), 0)) },
                        )
                        is Screen.Viewer -> ViewerScreen(
                            uris = screen.uris,
                            start = screen.index,
                            onBack = ::pop,
                            onDeleted = { galleryRefresh++ },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        android.util.Log.i("Emulsion", "onNewIntent action=${intent.action}")
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_EDIT -> intent.data
            else -> null
        }
        if (uri != null) push(Screen.Editor(uri))
    }

    private fun push(s: Screen) {
        stack.add(s)
    }

    private fun pop() {
        android.util.Log.i("Emulsion", "pop from ${stack.size}")
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish()
    }
}
