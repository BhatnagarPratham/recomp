package app.recomp.ui.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.recomp.data.body.ScanImages
import app.recomp.data.food.PhotoStore
import app.recomp.data.food.TesseractLabelReader
import app.recomp.data.prefs.AiSettings
import app.recomp.data.repo.AiRepository
import app.recomp.data.repo.CameraFoodRepository
import app.recomp.domain.ai.AiKind
import app.recomp.domain.ai.AiState
import app.recomp.ui.camera.PhotoCaptureScreen
import app.recomp.ui.camera.PickedPhoto
import app.recomp.ui.navigation.LabelScanRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Scan label's steps (NU-19): the camera, then reading, then back to New food. */
sealed interface LabelScanPhase {
    data object Camera : LabelScanPhase
    data class Reading(val withAi: Boolean) : LabelScanPhase
    data object Done : LabelScanPhase
    data object Failed : LabelScanPhase
}

@HiltViewModel
class LabelScanViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val photos: PhotoStore,
    private val cameraFood: CameraFoodRepository,
    private val ai: AiRepository,
    images: ScanImages,
) : ViewModel() {
    private val route = savedState.toRoute<LabelScanRoute>()
    private val name = route.name

    /** A label shared to Recomp (M15's picker): read instead of the camera, and its copy deleted once read. */
    val shared: PickedPhoto? = route.sharedImage.takeIf { it.isNotEmpty() }?.let(images::file)?.let { PickedPhoto.Taken(it) }

    private val _phase = MutableStateFlow<LabelScanPhase>(LabelScanPhase.Camera)
    val phase: StateFlow<LabelScanPhase> = _phase

    val settings: StateFlow<AiSettings?> = ai.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Whether AI would read it too, so the screen shows the label notice once first (TRD §6.2). */
    suspend fun aiReady(): Boolean = ai.gate.first().state == AiState.READY

    fun noticeSeen() = viewModelScope.launch { ai.markNoticeSeen(AiKind.LABEL) }

    fun read(photo: PickedPhoto, useAi: Boolean) {
        if (_phase.value !is LabelScanPhase.Camera) return
        _phase.value = LabelScanPhase.Reading(useAi)
        viewModelScope.launch {
            try {
                val ocr = when (photo) {
                    is PickedPhoto.Taken -> photos.decode(photo.file, TesseractLabelReader.OCR_MAX_EDGE)
                    is PickedPhoto.Picked -> photos.decode(photo.uri, TesseractLabelReader.OCR_MAX_EDGE)
                }
                val scan = cameraFood.scanLabel(ocr, photos.image(photos.downscale(ocr)), name, useAi)
                cameraFood.hold(scan.prefill)
                _phase.value = LabelScanPhase.Done
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _phase.value = LabelScanPhase.Failed
            } finally {
                (photo as? PickedPhoto.Taken)?.file?.delete()
            }
        }
    }

    fun retry() {
        _phase.value = LabelScanPhase.Camera
    }
}

/**
 * *Scan label* (NU-19): photograph the nutrition table; the phone reads it, and with AI on your provider checks
 * the reading. New food then opens with the values filled in and marked for you to check.
 */
@Composable
fun LabelScanScreen(onBack: () -> Unit, onScanned: () -> Unit, viewModel: LabelScanViewModel = hiltViewModel()) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var asking by remember { mutableStateOf<PickedPhoto?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(phase) { if (phase == LabelScanPhase.Done) onScanned() }

    fun handle(photo: PickedPhoto) {
        scope.launch {
            if (!viewModel.aiReady()) viewModel.read(photo, useAi = false)
            else if (settings?.noticeSeen(AiKind.LABEL) == true) viewModel.read(photo, useAi = true)
            else asking = photo
        }
    }

    val shared = viewModel.shared
    LaunchedEffect(shared, settings) { if (shared != null && settings != null && phase == LabelScanPhase.Camera && asking == null) handle(shared) }

    when (val p = phase) {
        LabelScanPhase.Camera -> if (shared != null) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
            }
        } else PhotoCaptureScreen(
            title = "Scan label",
            hint = "Fill the picture with the nutrition table, straight on and in good light.",
            onBack = onBack,
        ) { photo -> handle(photo) }
        is LabelScanPhase.Reading, LabelScanPhase.Done -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(
                if (p is LabelScanPhase.Reading && p.withAi) "Reading the label, then checking it with ${settings?.provider?.label ?: "AI"}…" else "Reading the label…",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        LabelScanPhase.Failed -> AlertDialog(
            onDismissRequest = onBack,
            title = { Text("Couldn't read that photo") },
            text = { Text("Try another photo, or type the values into New food.") },
            confirmButton = { TextButton(onClick = viewModel::retry) { Text("Try again") } },
            dismissButton = { TextButton(onClick = onBack) { Text("Type them in") } },
        )
    }

    asking?.let { photo ->
        AlertDialog(
            onDismissRequest = { asking = null; viewModel.read(photo, useAi = false) },
            title = { Text("Check labels with ${settings?.provider?.label ?: "AI"}?") },
            text = { Text(settings?.let { AiText.privacy(it, AiKind.LABEL) }.orEmpty()) },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    viewModel.noticeSeen()
                    viewModel.read(photo, useAi = true)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { asking = null; viewModel.read(photo, useAi = false) }) { Text("Phone only") } },
        )
    }
}
