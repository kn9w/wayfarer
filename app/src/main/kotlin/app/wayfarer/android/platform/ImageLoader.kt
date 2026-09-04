package app.wayfarer.android.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.wayfarer.core.model.MediaHost
import app.wayfarer.core.relay.MediaAccessPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Turning an image URL into a host, with real URL parsing.
 *
 * The core deliberately does not do this — see [MediaHost]. OkHttp's `HttpUrl`
 * is the parser the request itself will use, so the host the user is asked about
 * is by construction the host the request would go to. Doing it any other way
 * invites the one bug that would matter here: approving `image.example` and then
 * fetching from somewhere else because two parsers disagreed about a string like
 * `https://image.example@evil.example/x.jpg`.
 */
object MediaUrls {
    /** Null when [raw] is not an `https` URL this app is willing to fetch. */
    fun hostOf(raw: String?): MediaHost? {
        val url = raw?.trim()?.toHttpUrlOrNull() ?: return null
        // https only. There is nothing to decide about a picture that would
        // arrive in clear text, so it is refused rather than made approvable.
        if (url.scheme != "https") return null
        return MediaHost.parseOrNull(url.host)
    }

    /**
     * The pictures and video a post's text points at, in the order they appear.
     *
     * Nostr has no attachment. An image in a note is a bare URL sitting in the
     * note's own words, so finding one means reading the text — and reading it
     * is all this does: no request is made, which is the point, because whether
     * the host may be contacted at all is still an open question at this stage.
     *
     * Judged by file extension, which is a guess. It is the only evidence
     * available before the request, and it errs the safe way: a link this misses
     * is a picture not shown, while the alternative — treating every link as
     * media — would queue a host for every URL anybody posts.
     */
    fun mediaIn(content: String): List<PostMedia> {
        if (content.isEmpty()) return emptyList()

        val found = LinkedHashMap<String, PostMedia>()
        for (candidate in urlsIn(content)) {
            val url = candidate.toHttpUrlOrNull() ?: continue
            if (url.scheme != "https") continue
            val extension = url.encodedPath.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
            val video = extension in VIDEO_EXTENSIONS
            if (!video && extension !in IMAGE_EXTENSIONS) continue
            if (MediaHost.parseOrNull(url.host) == null) continue
            if (candidate !in found) found[candidate] = PostMedia(candidate, video)
        }
        return found.values.toList()
    }

    /**
     * Every `https://…` run in [content], with sentence punctuation trimmed off
     * the end.
     *
     * Deliberately crude and deliberately narrow: `http` is not looked for at
     * all, because a picture that would arrive in clear text is refused rather
     * than made approvable, and there is no point queueing a host for one.
     */
    private fun urlsIn(content: String): List<String> {
        val results = mutableListOf<String>()
        var index = 0
        while (true) {
            val start = content.indexOf(SCHEME, index)
            if (start < 0) break
            // Only at a word boundary: "xhttps://" is somebody's typo.
            val before = content.getOrNull(start - 1)
            if (before != null && !before.isWhitespace() && before !in "([{<\"'") {
                index = start + SCHEME.length
                continue
            }
            var end = start
            while (end < content.length && !content[end].isWhitespace() && content[end] !in "<>\"") end++
            val raw = content.substring(start, end).trimEnd { it in TRAILING_PUNCTUATION }
            if (raw.length > SCHEME.length) results += raw
            index = end + 1
        }
        return results
    }

    private const val SCHEME = "https://"

    /** Characters that end a sentence rather than a URL. */
    private const val TRAILING_PUNCTUATION = ".,;:!?'\"\u201d\u2019)]}\u00bb"

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "heic", "heif")

    private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mov", "m4v", "mkv", "ogv")
}

/**
 * One picture or video a post links to.
 *
 * [video] is carried rather than resolved because the two are shown differently
 * and neither can be fetched to find out which it is: a video is named and left
 * alone — Wayfarer has no player, and pretending otherwise would be a broken
 * frame where an honest line belongs.
 */
data class PostMedia(
    val url: String,
    val video: Boolean,
)

/**
 * The media approval gate, enforced at the request.
 *
 * The exact counterpart of `GatedWebsocketBuilder` in the `nostr-quartz` module,
 * and it exists for the same reason. The composables that draw pictures already
 * refuse to ask for one from a host without a grant, so in normal operation this
 * interceptor never rejects anything. It is here because "no request is made to
 * a media server the user did not approve" is a promise, and a promise enforced
 * only by the code that decides what to draw is one bug away from being false.
 * Here it is enforced by the one object that can actually open the connection.
 *
 * It reads the host from the request that is about to be sent, not from whatever
 * string produced it, so there is no gap between what was checked and what is
 * fetched.
 *
 * Registered twice, and that is load bearing. As an *application* interceptor it
 * runs once per call, before the cache is consulted, so a host whose grant was
 * revoked cannot still be served from disk. As a *network* interceptor it runs
 * once per actual network request — which is the only way a redirect is seen at
 * all: OkHttp follows redirects below the application interceptors, so a
 * `302` from an approved host to an unapproved one would otherwise be fetched
 * without this ever being consulted a second time.
 */
class GatedImageRequests(
    private val policy: MediaAccessPolicy,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = MediaHost.parseOrNull(request.url.host)
        if (request.url.scheme != "https" || host == null || !policy.isApproved(host)) {
            throw IOException("Media host ${request.url.host} is not approved by the user")
        }
        return chain.proceed(request)
    }
}

/**
 * Fetching and decoding pictures, small enough to read in one sitting.
 *
 * Hand-written rather than a library, and that is a deliberate trade rather than
 * an oversight. An image loader is exactly the kind of component that owns its
 * own HTTP client, its own prefetch and its own disk layer — every one of them a
 * place a request could originate that the gate above never sees. This one takes
 * the client it is given, and the only two network calls in the file are the one
 * in [load] and the one in [download], which share it. That makes "nothing is
 * fetched from an unapproved host" a claim a reader can check by reading one
 * page.
 *
 * The cost is real and worth naming: no animated GIF or WebP, no transformations
 * beyond a downscale, and no cross-fade. For avatars and banners that is enough.
 */
class ImageLoader(
    private val client: OkHttpClient,
    maxCacheBytes: Int = defaultCacheBytes(),
    /**
     * Where a video is put so the platform can play it from disk.
     *
     * Null disables video entirely, and the viewer says so rather than falling
     * back to streaming — see [video] for why there is no fallback.
     */
    private val videoDir: File? = null,
) {
    private val cache =
        object : LruCache<String, ImageBitmap>(maxCacheBytes) {
            override fun sizeOf(
                key: String,
                value: ImageBitmap,
            ): Int = value.width * value.height * 4
        }

    /** The decoded picture, or null if it could not be had for any reason. */
    suspend fun load(
        url: String,
        maxPixels: Int,
    ): ImageBitmap? {
        val key = "$url@$maxPixels"
        cache.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            val decoded =
                try {
                    fetch(url, maxPixels)
                } catch (e: IOException) {
                    // A refused host arrives here as an ordinary IO failure, and
                    // is treated as one: the caller draws its fallback. Nothing
                    // is retried and nothing is reported to the user, because
                    // "this picture did not load" is not news on a screen that
                    // already says which hosts are allowed.
                    null
                }
            decoded?.also { cache.put(key, it) }
        }
    }

    /**
     * A local copy of a video, downloaded through the gated client, or null if
     * it could not be had.
     *
     * This exists because handing a remote URL to the platform's `VideoView` is
     * a hole in the media gate, and a large one. `MediaPlayer` does its own
     * networking: it never passes through [GatedImageRequests], so the
     * interceptor that is deliberately registered twice — once so the disk
     * cache cannot route around it, once so a *redirect* cannot — sees nothing
     * at all. The gate was reduced to the composable that decides whether to
     * draw a play button, which is exactly the single-layer arrangement the
     * rest of this app refuses to rely on. Worse, `MediaPlayer` follows
     * redirects on its own, so an allowed host could bounce the request, and
     * the user's IP address, to anyone it liked — the very thing the NIP-11
     * fetcher turns redirects off to prevent.
     *
     * So the bytes are fetched here, by the same client and through the same
     * gate as every picture, and the player is handed a file. It is not a
     * streaming player any more and it is not meant to be: the whole video is
     * read before anything plays, and one too large to hold is refused rather
     * than partially played. That is the honest trade for an app whose promise
     * is that nothing is fetched from a server the user has not allowed.
     */
    suspend fun video(url: String): File? {
        val dir = videoDir ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                val target = File(dir, cacheName(url))
                if (target.isFile && target.length() > 0) return@runCatching target
                if (download(url, target)) target else null
            }.getOrNull()
        }
    }

    /**
     * Streams [url] to [target], refusing anything over [MAX_VIDEO_BYTES].
     *
     * Written through a `.part` file and renamed, so an interrupted download can
     * never be picked up as a complete one by the next tap. The size is checked
     * as the bytes arrive rather than only against `Content-Length`, because a
     * header is a claim and this is the number that has to be true.
     */
    private fun download(
        url: String,
        target: File,
    ): Boolean {
        val request =
            Request
                .Builder()
                .url(url)
                // Not through OkHttp's response cache: that one is sized for
                // avatars, and a single video would evict every picture in it.
                .cacheControl(CacheControl.Builder().noStore().build())
                .build()

        videoClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            if (response.body.contentLength() > MAX_VIDEO_BYTES) return false

            val partial = File(target.absolutePath + ".part")
            try {
                var written = 0L
                response.body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > MAX_VIDEO_BYTES) return false
                            output.write(buffer, 0, read)
                        }
                    }
                }
                return written > 0 && partial.renameTo(target)
            } finally {
                // A no-op after a successful rename; the cleanup that matters is
                // the failure path, which must not leave a stub behind.
                partial.delete()
            }
        }
    }

    /**
     * The same gate and the same connection pool, with room to finish.
     *
     * `newBuilder` carries both interceptors across — that is the whole point of
     * deriving it rather than building a second client — while lifting the read
     * timeout, which is set for a small picture and is too tight for a file that
     * takes a while to arrive.
     */
    private val videoClient: OkHttpClient by lazy {
        client
            .newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** A filesystem-safe, collision-free name for a URL. */
    private fun cacheName(url: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    /**
     * Throws away every picture held, in memory and on disk.
     *
     * Called when an account logs out. The pictures a session drew are the
     * faces, banners and photographs of the people that account was reading —
     * so leaving them cached would leave a legible trace of who somebody read on
     * a phone they have just signed out of, and would let the next session be
     * shown a picture from a server it never allowed, since a cache hit is
     * answered before the gate is consulted.
     *
     * Both layers, because they fail differently: the memory cache is what the
     * next composition would hit, and the disk cache is what survives the
     * process.
     */
    suspend fun clear() {
        withContext(Dispatchers.IO) {
            cache.evictAll()
            // The disk cache belongs to the client, and evicting it is IO that
            // can fail on a device with a wedged filesystem. A cache that would
            // not clear is not worth taking the app down over — nothing is
            // served from it that the gate would not also allow.
            runCatching { client.cache?.evictAll() }
            // Downloaded videos are the same kind of trace as the pictures, and
            // a much heavier one: a file per video the departing account chose
            // to watch, sitting in the cache directory under a name derived from
            // its URL.
            runCatching { videoDir?.listFiles()?.forEach { it.delete() } }
        }
    }

    private fun fetch(
        url: String,
        maxPixels: Int,
    ): ImageBitmap? {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            // Read a bounded prefix rather than the whole body. `bytes()` would
            // buffer whatever arrives entirely into heap before anything gets a
            // chance to look at it, so a host that has been approved — or one
            // serving somebody else's upload — could answer an avatar request
            // with a gigabyte and take the app down. The downscale below only
            // helps after this point, which is too late.
            val body = response.body.source()
            body.request(MAX_IMAGE_BYTES + 1L)
            if (body.buffer.size > MAX_IMAGE_BYTES) return null
            val bytes = body.buffer.readByteArray()
            if (bytes.isEmpty()) return null
            return decode(bytes, maxPixels)?.asImageBitmap()
        }
    }

    /**
     * Decodes at no more than [maxPixels] on the longest edge.
     *
     * Two passes, the first with `inJustDecodeBounds` so the dimensions are read
     * without allocating the full bitmap: a 4000px banner decoded at full size to
     * fill a 400px strip is ~64MB of heap for something about to be thrown away,
     * and a handful of those in a scrolling feed is an OutOfMemoryError.
     *
     * `BitmapFactory` rather than `ImageDecoder`, which is API 28 and this app's
     * minSdk is 26.
     */
    private fun decode(
        bytes: ByteArray,
        maxPixels: Int,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        var sample = 1
        while (longest / (sample * 2) >= maxPixels) sample *= 2

        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    companion object {
        /**
         * The most this will read for one picture.
         *
         * Generous for an avatar or a banner and far below anything that
         * threatens the heap, so it bounds a hostile answer without rejecting a
         * real photograph.
         */
        private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024

        /**
         * The most this will download for one video.
         *
         * Bounded for the same reason as a picture, and the number is larger
         * because the thing is: enough for the short clips people actually post
         * inline, and far short of a file that would fill a phone. Anything over
         * is refused outright and named as too large, rather than played from a
         * stream this app cannot gate.
         */
        private const val MAX_VIDEO_BYTES = 64L * 1024 * 1024

        /** An eighth of the heap, the conventional share for a bitmap cache. */
        fun defaultCacheBytes(): Int = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        /**
         * The client every picture is fetched through, with the gate on it.
         *
         * Its own client rather than the transport's: that one is built for
         * websockets held open indefinitely (`readTimeout(0)`, a ping interval),
         * which is the wrong shape for a short request, and sharing it would put
         * this interceptor on the relay sockets too.
         */
        fun client(
            policy: MediaAccessPolicy,
            cacheDir: File,
            cacheBytes: Long = 32L * 1024 * 1024,
        ): OkHttpClient =
            OkHttpClient
                .Builder()
                // Outermost: runs once per call, before the cache is consulted,
                // so a revoked host is not served from disk either.
                .addInterceptor(GatedImageRequests(policy))
                // And again per network request. Redirects are followed *below*
                // the application interceptors, so this is the only one of the
                // two that sees a hop to a different host — without it, an
                // approved host could redirect the request, and the user's IP,
                // anywhere it liked.
                .addNetworkInterceptor(GatedImageRequests(policy))
                .cache(okhttp3.Cache(File(cacheDir, "media"), cacheBytes))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
    }
}
