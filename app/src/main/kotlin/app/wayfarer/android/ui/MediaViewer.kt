package app.wayfarer.android.ui

import android.net.Uri
import android.widget.VideoView
import android.widget.MediaController as VideoControls
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.wayfarer.android.ui.icons.WayfarerIcons

/**
 * One picture or video, filling the window.
 *
 * A post's pictures are drawn small and fitted, which is right in a feed and
 * useless for the thing people actually do with a photograph — look at it. This
 * is that: the same bitmap, at the size the screen can show, pinch-zoomable to
 * six times its fitted size, pannable while zoomed, and double-tap to go back.
 *
 * The gate is unchanged and unweakened. This composable is only ever reached
 * from a picture that was already being drawn, which means its host is
 * [app.wayfarer.android.viewmodel.MediaApproval.Allowed]; the fetch still goes
 * through `ImageLoader`, so `GatedImageRequests` still sees it. Nothing here can
 * open a connection the small version could not.
 */
@Composable
fun MediaViewer(
    url: String,
    video: Boolean,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                // The point of the screen is the picture, so it takes the whole
                // window rather than Material's dialog width.
                usePlatformDefaultWidth = false,
            ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // Its own dark ground in both themes: a photograph is judged
                // against what surrounds it, and parchment is not neutral.
                .background(Color(0xF2000000)),
        ) {
            if (video) VideoSurface(url) else ZoomableImage(url)

            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    url,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xCCFFFFFF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                IconButton(onClick = onDismiss) {
                    Icon(WayfarerIcons.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

/**
 * The picture, with the two gestures anybody expects of one.
 *
 * Zoom is clamped at both ends — never smaller than fitted, never past six
 * times — because a picture that can be pinched away to a speck has no way back
 * except the gesture that lost it. Panning is only live while zoomed in, so a
 * fitted picture cannot be dragged off its own screen.
 */
@Composable
private fun ZoomableImage(url: String) {
    // Loaded much larger than a feed row asks for: this is the one place where
    // the pixels are the point, and the loader still downsamples anything
    // bigger than this on the way in.
    val bitmap = rememberRemoteImageAt(url, VIEWER_PIXELS)

    if (bitmap == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }

    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offsetX by remember(url) { mutableFloatStateOf(0f) }
    var offsetY by remember(url) { mutableFloatStateOf(0f) }

    fun reset() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(url) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }.pointerInput(url) {
                    detectTapGestures(onDoubleTap = { if (scale > 1f) reset() else scale = 2.5f })
                }
                // After the gestures, so a pinch is measured in the untransformed
                // space of the layout rather than in the space it just changed.
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
    )
}

/**
 * A video, played full-window by the platform.
 *
 * `VideoView` rather than a player library: the app carries no media
 * dependency, and the framework's own view is a decode surface that already
 * exists on every device this runs on. The trade is real — no adaptive
 * streaming, and the fetch is `MediaPlayer`'s rather than the gated OkHttp
 * client's — so this is only ever composed for a host the user has already
 * allowed in Pictures, and a video from an undecided host still shows the badge
 * that leads to that decision instead.
 */
@Composable
private fun VideoSurface(url: String) {
    var failed by remember(url) { mutableStateOf(false) }

    if (failed) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Text(
                    "This video could not be played on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                Text(
                    "Wayfarer plays what Android itself can decode, and nothing else.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xB3FFFFFF),
                )
            }
        }
        return
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            VideoView(context).apply {
                setMediaController(VideoControls(context).also { it.setAnchorView(this) })
                setOnPreparedListener { start() }
                setOnErrorListener { _, _, _ ->
                    failed = true
                    // Handled: returning false lets the framework put its own
                    // dialog up, over a dialog of ours.
                    true
                }
                setVideoURI(Uri.parse(url))
            }
        },
        // Stopped rather than left to the garbage collector: a dismissed dialog
        // whose MediaPlayer is still holding the audio focus and the socket is
        // the one way a media view leaks past its own screen.
        onRelease = { it.stopPlayback() },
    )
}

/** A small play triangle over a still, for a video that has not been opened yet. */
@Composable
internal fun PlayBadge(modifier: Modifier = Modifier) {
    Box(
        modifier.size(56.dp).background(Color(0x99000000), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            WayfarerIcons.Play,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * How many pixels the full-screen viewer asks for on the longest edge.
 *
 * Above any phone's short edge and most long edges, so a fitted picture is
 * sharp and a zoom to 2.5x still has something to show, while staying far below
 * the eight megabytes the loader will read for one image.
 */
private const val VIEWER_PIXELS = 1600
