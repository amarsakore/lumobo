package app.lumobo

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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import app.lumobo.data.AppSettings
import app.lumobo.editor.Recipe
import app.lumobo.ui.EditorScreen
import app.lumobo.ui.GalleryScreen
import app.lumobo.ui.SettingsScreen
import app.lumobo.ui.ViewerScreen
import app.lumobo.ui.theme.LumoboTheme

sealed interface Screen {
    val key: String

    data object Gallery : Screen { override val key = "gallery" }
    data object Settings : Screen { override val key = "settings" }
    data class Editor(val uri: Uri, val recipe: Recipe? = null, val target: Uri? = null, val note: String? = null, val id: Long = next()) : Screen {
        override val key get() = "editor-$id"
    }
    data class Viewer(val uris: List<Uri>, val index: Int, val id: Long = next()) : Screen {
        override val key get() = "viewer-$id"
    }

    companion object {
        private var counter = 0L
        fun next() = ++counter
    }
}

private object NoHaptics : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

class MainActivity : ComponentActivity() {

    private val stack = mutableStateListOf<Screen>(Screen.Gallery)
    private var mediaVersion by mutableIntStateOf(0)
    private val removedKeys = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        val settings = AppSettings.get(this)
        setContent {
            LumoboTheme {
                val holder = rememberSaveableStateHolder()
                // Forget saved UI state of screens that were closed.
                if (removedKeys.isNotEmpty()) {
                    removedKeys.forEach { holder.removeState(it) }
                    removedKeys.clear()
                }
                val haptics = if (settings.haptics) LocalHapticFeedback.current else NoHaptics
                CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                    AnimatedContent(
                        targetState = stack.last(),
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        contentKey = { it.key },
                        label = "nav",
                    ) { screen ->
                        holder.SaveableStateProvider(screen.key) {
                            when (screen) {
                                Screen.Gallery -> GalleryScreen(
                                    refreshKey = mediaVersion,
                                    onOpen = { push(Screen.Editor(it)) },
                                    onOpenDeveloped = { list, i -> push(Screen.Viewer(list.map { it.uri }, i)) },
                                    onOpenSettings = { push(Screen.Settings) },
                                )
                                Screen.Settings -> SettingsScreen(onBack = ::pop)
                                is Screen.Editor -> EditorScreen(
                                    uri = screen.uri,
                                    recipe = screen.recipe,
                                    target = screen.target,
                                    note = screen.note,
                                    onBack = ::pop,
                                    onSaved = { mediaVersion++ },
                                    onViewSaved = { saved ->
                                        // Re-editing from the viewer: go back to it rather than stacking another.
                                        if (stack.size >= 2 && stack[stack.size - 2] is Screen.Viewer) pop()
                                        else push(Screen.Viewer(listOf(saved), 0))
                                    },
                                )
                                is Screen.Viewer -> ViewerScreen(
                                    uris = screen.uris,
                                    start = screen.index,
                                    version = mediaVersion,
                                    onBack = ::pop,
                                    onDeleted = { mediaVersion++ },
                                    onEdit = { plan -> push(Screen.Editor(plan.source, plan.recipe, plan.target, plan.note)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))?.firstOrNull()
            Intent.ACTION_EDIT, Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) push(Screen.Editor(uri))
    }

    private fun push(s: Screen) {
        stack.add(s)
    }

    private fun pop() {
        if (stack.size > 1) {
            val gone = stack.removeAt(stack.lastIndex)
            if (gone != Screen.Gallery) removedKeys.add(gone.key)
        } else {
            finish()
        }
    }
}
