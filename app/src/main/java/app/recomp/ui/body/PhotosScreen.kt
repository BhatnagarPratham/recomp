package app.recomp.ui.body

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.content.ActivityNotFoundException
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.recomp.data.db.PhotoPose
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.repo.PhotoItem
import app.recomp.data.repo.PhotoDelete
import app.recomp.data.repo.PhotoRepository
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.ConfirmDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class PhotosViewModel @Inject constructor(
    private val photos: PhotoRepository,
    private val days: DayBoundary,
    private val state: SavedStateHandle,
) : ViewModel() {
    val items: StateFlow<List<PhotoItem>?> = photos.observe().map { photos.items(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val today: StateFlow<LocalDate> = days.observeToday().stateIn(viewModelScope, SharingStarted.Eagerly, LocalDate.now())

    /** A one-off message: a capture with no camera app, or a file that couldn't be deleted. */
    val message = MutableStateFlow<String?>(null)

    init {
        // Files no row points at (a capture whose app was killed before this fix, or left by an import) can't be
        // seen or deleted, so they go; a capture the camera may still be writing is never that old.
        viewModelScope.launch { photos.sweepOrphans() }
    }

    /**
     * The camera app's target for [pose]. The pose and file name are kept in the saved state, so a result that
     * arrives after Android recreated Recomp (memory, or a rotation) still saves the photo.
     */
    fun startCapture(pose: PhotoPose): File = photos.newCaptureFile(pose).also { file ->
        state[CAPTURE_POSE] = pose.name
        state[CAPTURE_FILE] = file.name
    }

    /** The camera's answer: saves the capture, or removes an empty or cancelled one. */
    fun finishCapture(ok: Boolean): Job = viewModelScope.launch {
        val pose = state.get<String>(CAPTURE_POSE)?.let { name -> PhotoPose.entries.firstOrNull { it.name == name } }
        val file = state.get<String>(CAPTURE_FILE)?.let(photos::captureFile)
        state.remove<String>(CAPTURE_POSE)
        state.remove<String>(CAPTURE_FILE)
        if (file == null) return@launch
        val checked = state.get<String>(CAPTURE_CHECKED)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        state.remove<String>(CAPTURE_CHECKED)
        if (ok && pose != null) photos.saveCapture(file, pose, checkedFor = checked) else file.delete()
    }

    /** No app answers the camera intent (the camera is optional in the manifest): nothing is left behind. */
    fun cameraMissing(): Job = finishCapture(false).also { message.value = PhotoText.NO_CAMERA }

    /**
     * Whether a new [pose] photo would replace one from today, so the screen asks first. The day checked is kept with
     * the capture, so a save that lands on the next day replaces nothing (M17 review R2).
     */
    suspend fun replaces(pose: PhotoPose, now: Long = System.currentTimeMillis()): Boolean {
        val today = days.today(now)
        state[CAPTURE_CHECKED] = today.toString()
        return photos.has(pose, today)
    }

    fun delete(id: Long): Job = viewModelScope.launch {
        when (photos.delete(id)) {
            PhotoDelete.DELETED -> Unit
            PhotoDelete.FILE_KEPT -> message.value = PhotoText.NOT_DELETED
            PhotoDelete.SAFETY_COPY_KEPT -> message.value = PhotoText.SAFETY_COPY_KEPT
        }
    }

    companion object {
        const val CAPTURE_POSE = "capture_pose"
        const val CAPTURE_FILE = "capture_file"
        const val CAPTURE_CHECKED = "capture_checked"
    }
}

object PhotoText {
    fun pose(p: PhotoPose) = when (p) {
        PhotoPose.FRONT -> "Front"
        PhotoPose.SIDE -> "Side"
        PhotoPose.BACK -> "Back"
    }

    const val PRIVATE = "Photos stay in Recomp's own storage: not in your gallery, not sent to AI, and not in Android's backup. A backup file holds them only when you include them."
    const val SUGGEST = "It's been 4 weeks: time for new photos. Same spot, same light, same time of day."
    const val FIRST = "Take front, side and back photos to compare later. Same spot, same light, same time of day."
    const val NO_CAMERA = "There's no camera app on this phone to take the photo with."
    const val NOT_DELETED = "Couldn't delete the photo. Try again."
    const val SAFETY_COPY_KEPT = "The photo is deleted from the phone, but Recomp couldn't take it out of the copy it kept before your last import. Restoring that copy would bring it back."

    fun replace(p: PhotoPose) = "Replace today's ${pose(p).lowercase()} photo?"
    const val REPLACE_TEXT = "The one you took earlier today is deleted once the new one is saved."
}

/** A downscaled bitmap of [file], decoded off the main thread; null while loading or when the file is missing. */
@Composable
private fun rememberPhoto(file: File, maxPx: Int, rotation: Int): Bitmap? = produceState<Bitmap?>(null, file, maxPx, rotation) {
    value = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        val bitmap = runCatching { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull()
        // BitmapFactory ignores EXIF orientation, so a portrait shot saved sideways is turned upright here.
        if (bitmap == null || rotation == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    }
}.value

@Composable
private fun PhotoTile(item: PhotoItem?, label: String, modifier: Modifier = Modifier, maxPx: Int = 400, onClick: (() -> Unit)? = null) {
    val bitmap = item?.takeIf { it.present }?.file?.let { rememberPhoto(it, maxPx, item.rotation) }
    Box(
        modifier.aspectRatio(3f / 4f).background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable(role = Role.Image, onClickLabel = "Open $label", onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                when {
                    item == null -> "No $label photo"
                    !item.present -> "Photo not on this phone"
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

/**
 * Body → Photos (BM-04, PRD §12.13): front, side and back, taken with the phone's camera app straight into
 * Recomp's private storage. *Compare* puts two dates side by side for one pose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen(onBack: () -> Unit, viewModel: PhotosViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var replacing by remember { mutableStateOf<PhotoPose?>(null) }
    var askingFor by remember { mutableStateOf<PhotoPose?>(null) }
    var denied by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<PhotoItem?>(null) }
    var deleting by remember { mutableStateOf<PhotoItem?>(null) }
    var comparePose by remember { mutableStateOf(PhotoPose.FRONT) }
    var compare by remember { mutableStateOf(false) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> viewModel.finishCapture(ok) }
    fun launch(pose: PhotoPose) {
        val file = viewModel.startCapture(pose)
        val uri = FileProvider.getUriForFile(context, PhotoCapture.authority(context.packageName), file)
        try {
            camera.launch(uri)
        } catch (e: ActivityNotFoundException) {
            viewModel.cameraMissing()
        }
    }
    fun take(pose: PhotoPose, askedBefore: Boolean = false) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        when (PhotoCapture.next(granted, askedBefore)) {
            PhotoCapture.Step.LAUNCH_CAMERA -> launch(pose)
            PhotoCapture.Step.ASK_PERMISSION -> askingFor = pose
            PhotoCapture.Step.EXPLAIN_DENIED -> denied = true
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        askingFor?.let { pose -> askingFor = null; take(pose, askedBefore = true) }
    }
    LaunchedEffect(askingFor) { if (askingFor != null) permission.launch(Manifest.permission.CAMERA) }

    Scaffold(topBar = { TopAppBar(title = { Text("Photos") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        val list = items ?: return@Scaffold
        val byDate = list.groupBy { it.photo.logDate }.toSortedMap(compareByDescending { it })
        val latest = byDate.keys.firstOrNull()
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (PhotoRepository.suggest(latest, today)) Text(if (latest == null) PhotoText.FIRST else PhotoText.SUGGEST, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PhotoPose.entries.forEach { pose ->
                            OutlinedButton(onClick = { scope.launch { if (viewModel.replaces(pose)) replacing = pose else take(pose) } }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text(PhotoText.pose(pose)) }
                        }
                    }
                    Text(PhotoText.PRIVATE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (byDate.size >= 2) OutlinedButton(onClick = { compare = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Compare") }
                }
            }
            items(byDate.entries.toList(), key = { it.key.toString() }) { (date, photos) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(BodyText.date(date), style = MaterialTheme.typography.titleSmall)
                        // Every photo of the date shows, so each can be opened and deleted; a pose without one gets a placeholder.
                        tiles(photos).chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { (pose, item) ->
                                    PhotoTile(item, PhotoText.pose(pose).lowercase(), Modifier.weight(1f), onClick = item?.let { { open = it } })
                                }
                                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }

    open?.let { item ->
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text("${PhotoText.pose(item.photo.pose)} · ${BodyText.date(item.photo.logDate)}") },
            text = { PhotoTile(item, PhotoText.pose(item.photo.pose).lowercase(), Modifier.fillMaxWidth(), maxPx = 1600) },
            confirmButton = { TextButton(onClick = { open = null }) { Text("Close") } },
            dismissButton = { TextButton(onClick = { deleting = item; open = null }) { Text("Delete") } },
        )
    }
    deleting?.let { item ->
        ConfirmDialog("Delete this photo?", "It's removed from this phone. This can't be undone.", "Delete", onDismiss = { deleting = null }) {
            deleting = null
            scope.launch { viewModel.delete(item.photo.id) }
        }
    }
    replacing?.let { pose ->
        ConfirmDialog(PhotoText.replace(pose), PhotoText.REPLACE_TEXT, "Take a new one", onDismiss = { replacing = null }) {
            replacing = null
            take(pose)
        }
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { viewModel.message.value = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { viewModel.message.value = null }) { Text("OK") } },
        )
    }
    if (denied) {
        AlertDialog(
            onDismissRequest = { denied = false },
            text = { Text(PhotoCapture.DENIED) },
            confirmButton = { TextButton(onClick = { denied = false }) { Text("OK") } },
        )
    }
    if (compare) {
        CompareDialog(items.orEmpty(), comparePose, onPose = { comparePose = it }, onDismiss = { compare = false })
    }
}

/** A date's tiles: each pose's photos, newest first, or one empty placeholder for a pose with none. */
internal fun tiles(photos: List<PhotoItem>): List<Pair<PhotoPose, PhotoItem?>> = PhotoPose.entries.flatMap { pose ->
    photos.filter { it.photo.pose == pose }.sortedByDescending { it.photo.takenAt }.map { pose to it }.ifEmpty { listOf(pose to null) }
}

/** *Compare* (BM-04): one pose, two dates side by side; the earliest and the latest to start with. */
@Composable
private fun CompareDialog(items: List<PhotoItem>, pose: PhotoPose, onPose: (PhotoPose) -> Unit, onDismiss: () -> Unit) {
    val dates = PhotoRepository.datesFor(items.map(PhotoItem::photo), pose)
    var left by remember(pose) { mutableStateOf(dates.lastOrNull()) }
    var right by remember(pose) { mutableStateOf(dates.firstOrNull()) }
    fun at(date: LocalDate?) = items.firstOrNull { it.photo.pose == pose && it.photo.logDate == date }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compare") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChips(PhotoPose.entries, pose, PhotoText::pose, onPose)
                if (dates.size < 2) {
                    Text("Take this pose on two dates to compare it.")
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            PhotoTile(at(left), "earlier", Modifier.fillMaxWidth(), maxPx = 800)
                            ChoiceChips(dates, left, BodyText::date, { left = it })
                        }
                        Column(Modifier.weight(1f)) {
                            PhotoTile(at(right), "later", Modifier.fillMaxWidth(), maxPx = 800)
                            ChoiceChips(dates, right, BodyText::date, { right = it })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

