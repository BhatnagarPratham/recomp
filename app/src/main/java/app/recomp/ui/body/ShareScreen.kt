package app.recomp.ui.body

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import android.graphics.BitmapFactory
import app.recomp.data.body.ScanImages
import app.recomp.ui.navigation.ShareRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Loads a kept image for the screen, downscaled to about [maxPx] on its long edge. Null when it's gone or unreadable. */
suspend fun loadScanImage(images: ScanImages, name: String, maxPx: Int = 1600): ImageBitmap? = withContext(Dispatchers.IO) {
    val file = images.file(name) ?: return@withContext null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

@HiltViewModel
class ShareViewModel @Inject constructor(savedState: SavedStateHandle, private val images: ScanImages) : ViewModel() {
    val image: String = savedState.toRoute<ShareRoute>().image

    private val _bitmap = MutableStateFlow<ImageBitmap?>(null)
    val bitmap: StateFlow<ImageBitmap?> = _bitmap

    init {
        if (image.isNotEmpty()) viewModelScope.launch { _bitmap.value = loadScanImage(images, image, maxPx = 800) }
    }

    /** *Cancel*: the copy goes at once. */
    fun cancel() = images.delete(image)
}

/**
 * "What is this?" for an image shared to Recomp (IB-03, TRD §6.7, PRD §12.11). *InBody result* opens the scan form
 * with the image pinned above it. Nutrition labels and meal photos come with M18. No AI reads an InBody image.
 */
@Composable
fun ShareScreen(onInBody: (String) -> Unit, onDone: () -> Unit, viewModel: ShareViewModel = hiltViewModel()) {
    val bitmap by viewModel.bitmap.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("What is this?", style = MaterialTheme.typography.headlineSmall)
        if (viewModel.image.isEmpty()) {
            Text("This image couldn't be read. Share a screenshot or photo (JPEG, PNG or WebP, up to 20 MB).")
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("OK") }
            return@Column
        }
        bitmap?.let { Image(it, "The shared image", Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit) }
        Button(onClick = { onInBody(viewModel.image) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("InBody result") }
        Text(
            "Opens the scan form with this image above it, to type the numbers from. It stays on this phone and is never sent to AI.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Nutrition labels and meal photos come in a later version.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { viewModel.cancel(); onDone() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
    }
}
