package app.wayfarer.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import app.wayfarer.core.model.Article
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
 * other state this composable draws and asks for nothing; [onOpenMedia] is the
 * way to change that, and `GatedImageRequests` is the second lock that makes the
 * claim true even if this code is wrong.
 */
@Composable
fun Avatar(
    pubKey: PubKey,
    controller: AppController,
    size: Dp = 40.dp,
    onClick: (() -> Unit)? = null,
    /**
     * Where an undrawn picture leads.
     *
     * The fix for a mark that could not be pressed: with a picture waiting on a
     * decision, tapping the face is a question about a server, not a request to
     * open the profile the reader is already looking at. Given only by the
     * profile header — the one place an avatar now appears — and when it is
     * absent the mark falls back to [onClick].
     */
    onOpenMedia: ((MediaHost) -> Unit)? = null,
) {
    // From the reactive map, not profileFor: a face has to appear when the
    // kind 0 lands, and a synchronous peek never recomposes.
    val profiles by controller.profiles.collectAsStateWithLifecycle()
    val profile = profiles[pubKey]
    val name = controller.displayName(pubKey)
    val host = remember(profile?.picture) { MediaUrls.hostOf(profile?.picture) }
    val media by controller.media.state.collectAsStateWithLifecycle()
    val approval = host?.let { media.approvalOf(it) } ?: MediaApproval.Unknown

    // A picture that could be shown and is not is the one case where the tap
    // means something else. Blocked is a decision already made, so it keeps the
    // ordinary tap rather than sending the reader back to reverse it.
    val undecided = host != null && (approval == MediaApproval.Waiting || approval == MediaApproval.Unknown)
    val tap =
        when {
            undecided && onOpenMedia != null -> ({ onOpenMedia(host!!) })
            else -> onClick
        }

    val base = if (tap != null) Modifier.clickable(onClick = tap) else Modifier

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
        } else if (undecided && onOpenMedia != null) {
            // The dot says the mark is standing in for something, and the tap it
            // advertises is the one wired up above.
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
    val undecided = host != null && (approval == MediaApproval.Waiting || approval == MediaApproval.Unknown)

    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(tone)
            // The whole band, not the small badge in its corner. A 132dp strip
            // that does nothing except in one corner reads as a strip that does
            // nothing, which is how the badge came to be the only route to a
            // decision anybody could find.
            .then(if (undecided && onOpenMedia != null) Modifier.clickable { onOpenMedia(host!!) } else Modifier),
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
        } else if (undecided && onOpenMedia != null) {
            MediaBadge(
                label = "picture on ${host!!.display()}",
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                onClick = { onOpenMedia(host) },
            )
        }
    }
}

/**
 * A long-form article's own header picture.
 *
 * On the article's page only. In a feed of mixed notes and articles a full-width
 * photograph per row would make one kind of post shout over the other, and the
 * kicker above the title already says which is which.
 */
@Composable
fun ArticleHeaderImage(
    article: Article,
    controller: AppController,
    modifier: Modifier = Modifier,
) {
    val url = article.image?.takeIf { it.isNotBlank() } ?: return
    val host = remember(url) { MediaUrls.hostOf(url) } ?: return
    val permissions by controller.media.state.collectAsStateWithLifecycle()

    when (permissions.approvalOf(host)) {
        MediaApproval.Allowed -> {
            val bitmap = rememberRemoteImage(url, ArticleImageHeight) ?: return
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier.fillMaxWidth().height(ArticleImageHeight).clip(RoundedCornerShape(8.dp)),
            )
        }
        MediaApproval.Blocked -> Unit
        else ->
            MediaBadge(
                label = "picture on ${host.display()}",
                modifier = modifier,
                onClick = { controller.openMediaHost(host, "the header picture of \"${article.title}\"") },
            )
    }
}

private val ArticleImageHeight = 180.dp

/**
 * The pictures a post links to, under its text.
 *
 * Nostr has no attachment: an image in a note is a bare URL in the note's own
 * words. Their hosts are already in the waiting list by the time this draws —
 * `MediaController` records them as posts arrive, not when something is pressed
 * — so this only has two jobs: show the picture where the host is allowed, and
 * where it is not, say which server it would have come from and lead to the
 * decision.
 *
 * A blocked host draws nothing at all. That was a decision the reader already
 * made, and repeating it under every post would be an app arguing with them.
 */
@Composable
fun PostPictures(
    content: String,
    controller: AppController,
    modifier: Modifier = Modifier,
    onOpenMedia: (MediaHost) -> Unit,
) {
    val media = remember(content) { controller.media.mediaIn(content) }
    if (media.isEmpty()) return

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (item in media) {
            PostImage(url = item.url, controller = controller, video = item.video, onOpenMedia = onOpenMedia)
        }
    }
}

/**
 * One picture a post points at, in whichever of its four states it is in.
 *
 * Split out from [PostPictures] because an article body needs a single picture
 * placed where the author put it, rather than a gallery of everything the text
 * mentions — and the states are the same either way.
 */
@Composable
fun PostImage(
    url: String,
    controller: AppController,
    video: Boolean = false,
    modifier: Modifier = Modifier,
    onOpenMedia: (MediaHost) -> Unit,
) {
    val host = remember(url) { MediaUrls.hostOf(url) } ?: return
    val permissions by controller.media.state.collectAsStateWithLifecycle()

    when {
        permissions.approvalOf(host) == MediaApproval.Blocked -> Unit

        // Named rather than played. A video player is a decode surface and a
        // dependency this app does not carry, and an honest line is better than
        // a frame that never starts.
        video ->
            MediaBadge(
                label = "video on ${host.display()}",
                modifier = modifier,
                onClick = { onOpenMedia(host) },
            )

        permissions.approvalOf(host) == MediaApproval.Allowed ->
            PostPicture(url = url, modifier = modifier, onOpenMedia = { onOpenMedia(host) })

        else ->
            MediaBadge(
                label = "picture on ${host.display()}",
                modifier = modifier,
                onClick = { onOpenMedia(host) },
            )
    }
}

/**
 * One allowed picture, at the width of the post.
 *
 * Bounded in height rather than given its own aspect ratio: a portrait
 * photograph left to size itself pushes the rest of the post — the replies, the
 * provenance line — off the screen entirely.
 */
@Composable
private fun PostPicture(
    url: String,
    modifier: Modifier = Modifier,
    onOpenMedia: () -> Unit,
) {
    val bitmap = rememberRemoteImage(url, PostPictureHeight)
    if (bitmap == null) {
        Box(
            modifier
                .fillMaxWidth()
                .height(PostPictureHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onOpenMedia),
        )
        return
    }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(max = PostPictureHeight)
                .clip(RoundedCornerShape(8.dp)),
    )
}

/** How much of the screen one picture in a post may take before it is fitted. */
private val PostPictureHeight = 260.dp

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
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        label,
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
