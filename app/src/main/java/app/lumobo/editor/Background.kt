package app.lumobo.editor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** App-wide scope for work that must outlive a screen, like developing a photo as the app closes. */
object Background {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
