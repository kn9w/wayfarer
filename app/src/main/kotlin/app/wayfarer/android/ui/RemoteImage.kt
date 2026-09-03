package app.wayfarer.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wayfarer.android.platform.ImageLoader
import app.wayfarer.android.platform.MediaUrls
import app.wayfarer.android.ui.theme.markColorsFor
import app.wayfarer.android.viewmodel.AppController
import app.wayfarer.android.viewmodel.MediaApproval
import app.wayfarer.android.viewmodel.initialsOf
import app.wayfarer.core.model.MediaHost
import app.wayfarer.core.model.PubKey

/**
 * The loader, or null where there is none.
 *
 * A composition local rather than a parameter threaded through every screen: an
 * avatar appears in bylines, follow rows and profile headers, and passing a
 * loader down to each of them would put a plumbing argument on a dozen
 * signatures that have nothing else to do with pictures.
 */
val LocalImageLoader = staticCompositionLocalOf<ImageLoader?> { null }

@Composable
fun ProvideImageLoader(
    loader: ImageLoader?,
    content: @Composable () -> Unit,
) = CompositionLocalProvider(LocalImageLoader provides loader, content = content)

/**
 * Somebody's face, or the mark that stands in for one.
 *
 * The mark is the default rather than a fallback, and that distinction is the
 * whole design. With no approved media host — which is every account on the day
 * it is installed, because nothing is suggested — this is what the entire app
 * shows, so it has to be good enough to live with rather than an apology for a
 * missing photograph.
 *
 * A picture is requested only when its host is [MediaApproval.Allowed]. In every
 * other state this composable draws and asks for nothing; the badge in the
 * corner is the way to change that, and `GatedImageRequests` is the second lock
 * that makes the claim true even if this code is wrong.
 */
@Composable
fun Avatar(
    pubKey: PubKey,
    controller: AppController,
    size: Dp = 40.dp,
    onClick: (() -> Unit)? = null,
    /**
     * Draws a dot when a picture exists but its host has no decision yet.
     *
     * Off by default and switched on only by the profile header. A feed of forty
     * bylines would carry forty of these, which would turn a quiet prompt into
     * nagging — and the media screen is one tap from every screen anyway.
     */
    markUndecided: Boolean = false,
) {
    // From the reactive map, not profileFor: a face has to appear when the
    // kind 0 lands, and a synchronous peek never recomposes.
    val profiles by controller.profiles.collectAsStateWithLifecycle()
    val profile = profiles[pubKey]
    val name = controller.displayName(pubKey)
    val host = remember(profile?.picture) { MediaUrls.hostOf(profile?.picture) }
    val media by controller.media.state.collectAsStateWithLifecycle()
    val approval = host?.let { media.approvalOf(it) } ?: MediaApproval.Unknown

    val base = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier

    Box(base.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        Mark(seed = pubKey.hex, name = name, size = size)

        if (host != null && approval == MediaApproval.Allowed) {
            val bitmap = rememberRemoteImage(profile?.picture, size)
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else if (markUndecided && host != null && approval != MediaApproval.Blocked) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.28f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary),
            )
        }
    }
}

/**
 * The wide strip across the top of a profile.
 *
 * With nothing to show it is a plain band rather than an empty gap, so the
 * header is the same shape whether or not a picture was allowed — a layout that
 * jumps when a permission changes makes the permission feel like a malfunction.
 */
@Composable
fun BannerImage(
    pubKey: PubKey,
    controller: AppController,
    height: Dp = 132.dp,
    onOpenMedia: ((MediaHost) -> Unit)? = null,
) {
    val profiles by controller.profiles.collectAsStateWithLifecycle()
    val profile = profiles[pubKey]
    val host = remember(profile?.banner) { MediaUrls.hostOf(profile?.banner) }
    val media by controller.media.state.collectAsStateWithLifecycle()
    val approval = host?.let { media.approvalOf(it) } ?: MediaApproval.Unknown
    val (tone, _) = markColorsFor(pubKey.hex)

    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(tone),
    ) {
        if (host != null && approval == MediaApproval.Allowed) {
            val bitmap = rememberRemoteImage(profile?.banner, height)
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else if (host != null && approval != MediaApproval.Blocked && onOpenMedia != null) {
            MediaBadge(
                host = host.display(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                onClick = { onOpenMedia(host) },
            )
        }
    }
}

/**
 * The drawn mark: one or two letters on a tone chosen by the key.
 *
 * Initials from the name this app already shows, so it agrees with the byline
 * beside it. For somebody with no profile at all `displayName` is a shortened
 * npub, and the letters that produces are still stable and still theirs.
 */
@Composable
private fun Mark(
    seed: String,
    name: String,
    size: Dp,
) {
    val (background, ink) = markColorsFor(seed)
    Box(
        Modifier.fillMaxSize().background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initialsOf(name),
            color = ink,
            fontWeight = FontWeight.SemiBold,
            // Proportional to the circle rather than a type style: this is
            // drawn to fill a shape, and a fixed style would rattle around in a
            // 88dp header avatar and overflow a 28dp one in a byline.
            fontSize = (size.value * 0.4f).sp,
            lineHeight = (size.value * 0.4f).sp,
        )
    }
}

/** A small "this could be a picture" affordance, on the media screen's behalf. */
@Composable
private fun MediaBadge(
    host: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        "picture on $host",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * Loads [url] at a size suited to [target], or null.
 *
 * Keyed on the URL, so scrolling a feed does not re-fetch what is already in the
 * loader's cache and a recomposition for any other reason does not start a
 * second request.
 */
@Composable
private fun rememberRemoteImage(
    url: String?,
    target: Dp,
): ImageBitmap? {
    val loader = LocalImageLoader.current
    if (url == null || loader == null) return null
    // 3x the drawn size: enough for the densest screens without decoding a
    // 4000px original to fill a 40dp circle.
    val pixels = (target.value * 3).toInt().coerceAtLeast(64)
    val state = produceState<ImageBitmap?>(initialValue = null, url, pixels) { value = loader.load(url, pixels) }
    return state.value
}
