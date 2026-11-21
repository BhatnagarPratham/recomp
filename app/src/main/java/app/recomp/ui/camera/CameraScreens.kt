package app.recomp.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.recomp.domain.food.Barcodes
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.File
import java.util.concurrent.Executors

/** A photo for camera food: one taken with our camera, or one picked from the gallery. */
sealed interface PickedPhoto {
    data class Taken(val file: File) : PickedPhoto
    data class Picked(val uri: Uri) : PickedPhoto
}

/**
 * Asks for the camera the first time (M18). Refused, it explains and offers to ask again, and [fallback] (the
 * gallery, or typing) still works without it. Nothing here records; frames stay on the phone.
 */
@Composable
fun CameraPermissionGate(why: String, fallback: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    if (granted) {
        content()
    } else {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
            Text(if (asked) "The camera is off for Recomp." else "Recomp needs the camera.", style = MaterialTheme.typography.titleLarge)
            Text(why, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = { ask.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Allow the camera") }
            fallback?.invoke()
        }
    }
}

/** CameraX's preview, bound to the screen's lifecycle, with whichever use cases the caller adds. */
@Composable
private fun CameraPreview(modifier: Modifier, vararg useCases: androidx.camera.core.UseCase) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            provider = future.get().also { p ->
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, *useCases)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }
    AndroidView({ view }, modifier)
}

/**
 * *Scan barcode* (NU-21): the camera's frames go to ZXing on the phone, and the first retail barcode with a right
 * check digit ends the scan. Only that code goes anywhere (to Open Food Facts, if it isn't one of your foods).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarcodeScanScreen(onBack: () -> Unit, onCode: (String) -> Unit) {
    val done = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val latest by rememberUpdatedState(onCode)
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    val analysis = remember {
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E),
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
        }
        ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also { a ->
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            a.setAnalyzer(executor) { frame ->
                val code = frame.use { decode(reader, it) }
                if (code != null && Barcodes.valid(code) && done.compareAndSet(false, true)) main.post { latest(code) }
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Scan barcode") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            CameraPermissionGate("To read a pack's barcode. The camera's picture stays on your phone; only the barcode number is looked up.") {
                CameraPreview(Modifier.fillMaxSize(), analysis)
                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    Text(
                        "Hold the barcode flat in the middle of the picture.",
                        modifier = Modifier.padding(16.dp).navigationBarsPadding(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

/** ZXing on the frame's luminance plane; null when no barcode is in this frame. */
private fun decode(reader: MultiFormatReader, frame: ImageProxy): String? {
    val plane = frame.planes.firstOrNull() ?: return null
    val buffer = plane.buffer.duplicate().apply { rewind() }
    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val source = PlanarYUVLuminanceSource(data, plane.rowStride, frame.height, 0, 0, frame.width, frame.height, false)
    fun read(bitmap: BinaryBitmap) = try { reader.decodeWithState(bitmap).text } catch (e: NotFoundException) { null } finally { reader.reset() }
    // Barcodes are read along rows, so a pack held sideways is tried rotated too.
    return read(BinaryBitmap(HybridBinarizer(source))) ?: if (source.isRotateSupported) read(BinaryBitmap(HybridBinarizer(source.rotateCounterClockwise()))) else null
}

/**
 * A still photo for camera food: a label (NU-19) or a meal (NU-20). [Take photo] captures with CameraX into app
 * storage; *Choose a photo* opens Android's photo picker, which needs no permission and shows only what you pick.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoCaptureScreen(title: String, hint: String, onBack: () -> Unit, onPhoto: (PickedPhoto) -> Unit) {
    val context = LocalContext.current
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { onPhoto(PickedPhoto.Picked(it)) } }
    val choose = @Composable {
        OutlinedButton(
            onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Choose a photo") }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            CameraPermissionGate("To take the photo. It stays on your phone unless AI is on, and then only this photo goes to your AI provider.", fallback = choose) {
                CameraPreview(Modifier.fillMaxSize(), capture)
                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    Column(Modifier.padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (failed) "That didn't work. Try again, or choose a photo." else hint, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { choose() }
                            Button(
                                onClick = {
                                    busy = true
                                    val file = File(context.cacheDir, "capture-${System.currentTimeMillis()}.jpg")
                                    capture.takePicture(
                                        ImageCapture.OutputFileOptions.Builder(file).build(),
                                        ContextCompat.getMainExecutor(context),
                                        object : ImageCapture.OnImageSavedCallback {
                                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                                busy = false
                                                onPhoto(PickedPhoto.Taken(file))
                                            }

                                            override fun onError(exception: ImageCaptureException) {
                                                busy = false
                                                failed = true
                                                file.delete()
                                            }
                                        },
                                    )
                                },
                                enabled = !busy,
                                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                            ) { Text(if (busy) "Taking…" else "Take photo") }
                        }
                    }
                }
            }
        }
    }
}
