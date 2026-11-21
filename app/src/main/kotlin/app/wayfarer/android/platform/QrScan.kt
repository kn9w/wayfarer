package app.wayfarer.android.platform

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reading a QR code, as one suspending call.
 *
 * Same shape and the same reasoning as [Nip55Bridge]: the UI asks for a string
 * and gets one or null, and nothing above this file knows there is a camera
 * involved. When no scanner is wired up the hook is null, and the button that
 * would have used it is simply not drawn.
 */
class QrScanBridge(
    private val context: Context,
    private val launcher: ActivityResultLauncher<Intent>,
) {
    private val lock = Mutex()

    @Volatile
    private var pending: CompletableDeferred<String?>? = null

    /** Called by the activity's result callback. */
    fun onActivityResult(result: ActivityResult) {
        val text =
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.getStringExtra(QrScanActivity.EXTRA_RESULT)
            } else {
                null
            }
        pending?.complete(text)
    }

    /** Null when the scan was cancelled, refused, or nothing could be read. */
    suspend fun scan(): String? =
        lock.withLock {
            val slot = CompletableDeferred<String?>()
            pending = slot
            try {
                launcher.launch(Intent(context, QrScanActivity::class.java))
                slot.await()
            } catch (failure: Throwable) {
                null
            } finally {
                pending = null
            }
        }

    companion object {
        fun hasCamera(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
}

/**
 * A camera viewfinder that finishes as soon as it reads one QR code.
 *
 * Deliberately its own activity rather than a screen in the app: the camera
 * permission is then asked for at the moment it is used and for a purpose the
 * user can see, and the rest of the UI never touches CameraX.
 *
 * Nothing is stored and no image leaves the process — frames are decoded in
 * memory and dropped.
 */
class QrScanActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else finishWith(null)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        previewView = PreviewView(this)
        val hint =
            TextView(this).apply {
                text = "Point the camera at a nostr QR code"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(140, 0, 0, 0))
                setPadding(32, 24, 32, 24)
            }
        setContentView(
            FrameLayout(this).apply {
                addView(
                    previewView,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                addView(
                    hint,
                    FrameLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { gravity = Gravity.BOTTOM },
                )
            },
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener finishWith(null)

            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis =
                ImageAnalysis
                    .Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
            analysis.setAnalyzer(ContextCompat.getMainExecutor(this), QrCodeAnalyzer(::finishWith))

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { finishWith(null) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun finishWith(text: String?) {
        if (text == null) {
            setResult(Activity.RESULT_CANCELED)
        } else {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT, text))
        }
        finish()
    }

    companion object {
        const val EXTRA_RESULT = "qr.result"
    }
}

/**
 * Decodes the luminance plane of each frame, and reports the first QR code found.
 *
 * The Y plane of a YUV frame *is* a greyscale image, so it can go straight to
 * ZXing with no conversion. Its rows are padded to `rowStride`, which is why the
 * source is built from the stride rather than the image width — using the width
 * as the data width skews the picture and nothing ever decodes.
 */
private class QrCodeAnalyzer(
    private val onFound: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val reader =
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }

    private var found = false

    override fun analyze(image: ImageProxy) {
        if (found) {
            image.close()
            return
        }
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val rowStride = plane.rowStride
            if (rowStride <= 0) return
            val dataHeight = bytes.size / rowStride
            val height = minOf(image.height, dataHeight)
            val width = minOf(image.width, rowStride)
            if (width <= 0 || height <= 0) return

            val source = PlanarYUVLuminanceSource(bytes, rowStride, dataHeight, 0, 0, width, height, false)
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            found = true
            onFound(result.text)
        } catch (failure: Throwable) {
            // Nothing readable in this frame, which is the common case.
        } finally {
            reader.reset()
            image.close()
        }
    }
}
