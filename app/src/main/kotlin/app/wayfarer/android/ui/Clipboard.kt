package app.wayfarer.android.ui

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch

/**
 * Putting text on the clipboard, as one call.
 *
 * `LocalClipboardManager` is deprecated in favour of `LocalClipboard`, and the
 * replacement is a suspend API rather than a synchronous one — writing to the
 * system clipboard can genuinely take a moment, and on some platforms it cannot
 * be done from the composition at all. That makes every call site need a
 * coroutine scope and a `ClipEntry`, which is three lines of ceremony repeated
 * at each of the five places this app copies something.
 *
 * So it lives here once. Callers get back a plain `(String) -> Unit` and do not
 * have to know that copying is asynchronous now.
 *
 * The label is what Android shows in the clipboard preview from API 33 onward.
 * It is the app's name rather than a description of the content, because the
 * content is the interesting half and the system already shows that.
 *
 * Nothing sensitive is ever passed through here. The account's secret key is not
 * copyable at all — it is shown on a `FLAG_SECURE` screen and nowhere else — so
 * there is no case for `ClipDescription.EXTRA_IS_SENSITIVE`, which is the flag
 * that would otherwise be needed to keep a copied value out of that preview.
 */
@Composable
fun rememberCopyToClipboard(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    return remember(clipboard, scope) {
        { text: String ->
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(LABEL, text)))
            }
            Unit
        }
    }
}

private const val LABEL = "Wayfarer"
