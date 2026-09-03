package app.wayfarer.android.platform

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Drawing a QR code, as one call.
 *
 * The other half of [QrScanBridge], and it costs nothing to add: `zxing-core` is
 * already a dependency for the scanner, and its writer sits on the same
 * classpath as the reader. No camera permission is involved — this only draws.
 *
 * Used for handing somebody an npub. A key is 63 characters that cannot be
 * usefully read aloud or typed, and a code somebody can point a phone at is the
 * one way to pass one across a table.
 */
object QrRender {
    /**
     * A black-and-white bitmap of [text], or null if it could not be encoded.
     *
     * Colours are fixed rather than themed on purpose: a scanner needs real
     * contrast between the modules and the quiet zone, and a code drawn in the
     * app's parchment-on-stone would be a code that some readers refuse. The
     * caller frames it in white so the quiet zone survives on a dark background.
     */
    fun encode(
        text: String,
        sizePx: Int,
    ): ImageBitmap? {
        if (text.isEmpty() || sizePx <= 0) return null
        return try {
            val matrix =
                QRCodeWriter().encode(
                    text,
                    BarcodeFormat.QR_CODE,
                    sizePx,
                    sizePx,
                    mapOf(
                        // An npub is long enough that the default correction
                        // level makes a dense code; L keeps the modules big
                        // enough to scan from a phone held at arm's length.
                        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                        EncodeHintType.MARGIN to 1,
                    ),
                )

            val pixels = IntArray(matrix.width * matrix.height)
            for (y in 0 until matrix.height) {
                val row = y * matrix.width
                for (x in 0 until matrix.width) {
                    pixels[row + x] = if (matrix.get(x, y)) BLACK else WHITE
                }
            }
            Bitmap
                .createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
                .asImageBitmap()
        } catch (e: IllegalArgumentException) {
            // Thrown for input the encoder cannot fit. Nothing to recover, and a
            // missing code is better than a crash on somebody's profile.
            null
        } catch (e: com.google.zxing.WriterException) {
            null
        }
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
