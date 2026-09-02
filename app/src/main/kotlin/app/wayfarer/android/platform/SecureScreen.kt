package app.wayfarer.android.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Keeps the screen off every capture path for as long as it is composed.
 *
 * `FLAG_SECURE` is what stops a window being screenshotted, recorded through a
 * `MediaProjection` session, read by an accessibility service, or — the one that
 * matters most here — written into the system's recents snapshot, which is
 * persisted outside this app's sandbox. Without it, the keyguard check in front
 * of the secret key protects the key from someone tapping "Show my secret key"
 * and from nobody else: backgrounding the app hands the same key to the task
 * switcher, and that thumbnail passes no lock screen.
 *
 * Applied to the whole screen rather than only while the key is on it. Window
 * flags take effect on the next frame, so switching it on at the moment the key
 * appears leaves a frame in which it has not; and there is nothing on either of
 * these screens worth screenshotting anyway.
 *
 * Cleared on dispose so the rest of the app stays capturable — a reader wanting
 * to screenshot a note should be able to.
 */
@Composable
fun SecureScreen() {
    val view = LocalView.current
    DisposableEffect(view) {
        // Null under a Compose preview, and in any other context with no window
        // to flag. Nothing to protect there either, so this is a quiet no-op.
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/**
 * The activity behind a composable's context.
 *
 * `LocalView.current.context` is not always the activity — Compose may hand back
 * a wrapper — so the chain is walked rather than cast.
 */
private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
