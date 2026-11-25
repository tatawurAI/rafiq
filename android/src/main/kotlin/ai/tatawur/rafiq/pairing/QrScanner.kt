package ai.tatawur.rafiq.pairing

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.protocol.api.PairingLink
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.tooling.preview.Preview as ComposePreview

/**
 * The camera, looking for the pairing QR code that `rafiq-bridge pair` or `rafiq-bridge setup` prints. It calls
 * [onScanned] once, with the first Rafiq pairing link it sees. A QR code that is not one only shows a line saying so,
 * and scanning goes on. The caller asks for the camera permission before showing this.
 */
@Composable
fun QrScanner(
    onScanned: (PairingLink.Parsed) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    var status by remember { mutableStateOf(ScanStatus.LOOKING) }
    val latestOnScanned by rememberUpdatedState(onScanned)
    BackHandler(onBack = onCancel)
    LaunchedEffect(lifecycleOwner) {
        bindCamera(context, lifecycleOwner, previewView, onStatus = { status = it }, onLink = { latestOnScanned(it) })
    }
    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        ScannerOverlay(status, onCancel, Modifier.align(Alignment.BottomCenter))
    }
}

/** What the scanner shows under the camera image. */
internal enum class ScanStatus {
    LOOKING,
    NOT_RAFIQ,
    CAMERA_FAILED,
}

/** The text and the way out, drawn over the camera image. Stateless, so each status can be previewed. */
@Composable
internal fun ScannerOverlay(
    status: ScanStatus,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            Text(
                text =
                    stringResource(
                        when (status) {
                            ScanStatus.LOOKING -> R.string.setup_scan_hint
                            ScanStatus.NOT_RAFIQ -> R.string.setup_scan_not_rafiq
                            ScanStatus.CAMERA_FAILED -> R.string.setup_scan_camera_failed
                        },
                    ),
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (status == ScanStatus.LOOKING) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )
            Button(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = spacing.minTouchTarget)) {
                Text(stringResource(R.string.setup_scan_type_instead))
            }
        }
    }
}

/**
 * Binds the preview and a frame analyser to [lifecycleOwner] until the calling coroutine is cancelled, then unbinds
 * them. Frames are decoded on one background thread. Only the newest frame is kept, so a slow decode never queues up.
 */
@Suppress("TooGenericExceptionCaught") // CameraX reports a missing or busy camera with several unchecked types.
private suspend fun bindCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    onStatus: (ScanStatus) -> Unit,
    onLink: (PairingLink.Parsed) -> Unit,
) {
    val executor = Executors.newSingleThreadExecutor()
    val main = ContextCompat.getMainExecutor(context)
    val found = AtomicBoolean(false)
    val decoder = QrFrameDecoder()
    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
    val analysis =
        ImageAnalysis
            .Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    analysis.setAnalyzer(executor) { image ->
        val text = image.use { if (found.get()) null else decoder.decode(it) }
        if (text != null) {
            val link = PairingLink.parse(text)
            when {
                link == null -> main.execute { onStatus(ScanStatus.NOT_RAFIQ) }
                found.compareAndSet(false, true) -> main.execute { onLink(link) }
            }
        }
    }
    var provider: ProcessCameraProvider? = null
    try {
        val ready = ProcessCameraProvider.awaitInstance(context)
        provider = ready
        ready.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        awaitCancellation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        onStatus(ScanStatus.CAMERA_FAILED)
        awaitCancellation()
    } finally {
        provider?.unbind(preview, analysis)
        analysis.clearAnalyzer()
        executor.shutdown()
    }
}

/** Decodes the frame's brightness plane, the first plane of the YUV frames ImageAnalysis delivers. */
private fun QrFrameDecoder.decode(image: ImageProxy): String? {
    val plane = image.planes.first()
    val buffer = plane.buffer
    // The last row can stop short of the full stride, so the copy is padded to whole rows.
    val luma = ByteArray(plane.rowStride * image.height)
    buffer.get(luma, 0, minOf(buffer.remaining(), luma.size))
    return decode(luma, image.width, image.height, plane.rowStride)
}

@ComposePreview(name = "Scanner, looking")
@Composable
private fun ScannerLookingPreview() {
    RafiqTheme { ScannerOverlay(ScanStatus.LOOKING, onCancel = {}) }
}

@ComposePreview(name = "Scanner, not a Rafiq code")
@Composable
private fun ScannerNotRafiqPreview() {
    RafiqTheme { ScannerOverlay(ScanStatus.NOT_RAFIQ, onCancel = {}) }
}

@ComposePreview(name = "Scanner, camera failed")
@Composable
private fun ScannerFailedPreview() {
    RafiqTheme { ScannerOverlay(ScanStatus.CAMERA_FAILED, onCancel = {}) }
}
