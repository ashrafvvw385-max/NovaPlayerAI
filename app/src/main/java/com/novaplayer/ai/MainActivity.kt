package com.novaplayer.ai

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VideoFile(
    val id: Long,
    val title: String,
    val uri: Uri,
    val duration: Long,
    val folder: String,
    val size: Long,
    val date: Long
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                NovaPlayerScreen()
            }
        }
    }
}

fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remaining = seconds % 60

    return if (hours > 0) {
        "%02d:%02d:%02d".format(
            hours, minutes, remaining
        )
    } else {
        "%02d:%02d".format(minutes, remaining)
    }
}

@Composable
fun NovaPlayerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val modelManager = remember(context) {
        WhisperModelManager(context.applicationContext)
    }

    var modelInstalled by remember {
        mutableStateOf(modelManager.isInstalled())
    }

    var downloading by remember {
        mutableStateOf(false)
    }

    var downloadProgress by remember {
        mutableIntStateOf(0)
    }

    var modelError by remember {
        mutableStateOf<String?>(null)
    }

    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var videos by remember {
        mutableStateOf<List<VideoFile>>(emptyList())
    }

    var search by remember {
        mutableStateOf("")
    }

    var sortMode by remember {
        mutableStateOf("Newest")
    }

    var selectedFolder by remember {
        mutableStateOf<String?>(null)
    }

    var selectedUri by remember {
        mutableStateOf<Uri?>(null)
    }

    var menuExpanded by remember {
        mutableStateOf(false)
    }

    var loading by remember {
        mutableStateOf(false)
    }

    var error by remember {
        mutableStateOf<String?>(null)
    }

    val player = remember {
        ExoPlayer.Builder(context).build()
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    fun playVideo(uri: Uri) {
        selectedUri = uri
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.play()
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { allowed ->
            granted = allowed
        }

    val picker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri ->
            uri?.let { playVideo(it) }
        }

    LaunchedEffect(granted) {
        if (granted) {
            loading = true
            error = null

            try {
                videos = withContext(Dispatchers.IO) {
                    loadVideos(context)
                }
            } catch (e: Exception) {
                error = e.localizedMessage
                    ?: "Unable to load videos"
            } finally {
                loading = false
            }
        } else {
            videos = emptyList()
        }
    }

    val sortedVideos = remember(
        videos, search, sortMode
    ) {
        val filtered = videos.filter {
            it.title.contains(
                search,
                ignoreCase = true
            )
        }

        when (sortMode) {
            "Name" -> filtered.sortedBy {
                it.title.lowercase()
            }
            "Size" -> filtered.sortedByDescending {
                it.size
            }
            "Duration" -> filtered.sortedByDescending {
                it.duration
            }
            else -> filtered.sortedByDescending {
                it.date
            }
        }
    }

    val folders = remember(sortedVideos) {
        sortedVideos.groupBy { it.folder }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }

    BackHandler(
        enabled = selectedUri == null &&
            selectedFolder != null
    ) {
        selectedFolder = null
    }

    if (selectedUri != null) {
        FullscreenPlayer(
            player = player,
            onBack = {
                player.stop()
                selectedUri = null
            }
        )
    } else {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = "Nova Player AI",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(16.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "AI Speech Recognition",
                        style = MaterialTheme.typography.titleMedium
                    )

                    if (modelInstalled) {
                        Text("Whisper model downloaded")

                        Text(
                            "Ready for speech recognition integration",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            "Download Whisper multilingual model"
                        )

                        Button(
                            enabled = !downloading,
                            onClick = {
                                downloading = true
                                downloadProgress = 0
                                modelError = null

                                scope.launch {
                                    try {
                                        modelManager.downloadModel {
                                            progress ->
                                            scope.launch {
                                                downloadProgress = progress
                                            }
                                        }

                                        modelInstalled =
                                            modelManager.isInstalled()

                                        if (!modelInstalled) {
                                            modelError =
                                                "Model verification failed"
                                        }
                                    } catch (e: Exception) {
                                        modelError =
                                            e.localizedMessage
                                                ?: "Download failed"
                                    } finally {
                                        downloading = false
                                    }
                                }
                            }
                        ) {
                            Text(
                                if (downloading) {
                                    "Downloading..."
                                } else {
                                    "Download AI Model"
                                }
                            )
                        }

                        if (downloading) {
                            LinearProgressIndicator(
                                progress = {
                                    downloadProgress / 100f
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text("$downloadProgress%")
                        }
                    }

                    modelError?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.padding(12.dp)
            ) {
                Button(
                    onClick = {
                        picker.launch("video/*")
                    }
                ) {
                    Text("Open Video")
                }

                Spacer(Modifier.width(8.dp))

                if (!granted) {
                    Button(
                        onClick = {
                            permissionLauncher.launch(permission)
                        }
                    ) {
                        Text("Allow Videos")
                    }
                }
            }

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search videos") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            )

            Row(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    "Videos: ${sortedVideos.size}",
                    modifier = Modifier.weight(1f)
                )

                Box {
                    TextButton(
                        onClick = {
                            menuExpanded = true
                        }
                    ) {
                        Text("Sort: $sortMode")
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = {
                            menuExpanded = false
                        }
                    ) {
                        listOf(
                            "Newest",
                            "Name",
                            "Size",
                            "Duration"
                        ).forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode) },
                                onClick = {
                                    sortMode = mode
                                    menuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp)
                )
            }

            if (selectedFolder != null) {
                TextButton(
                    onClick = {
                        selectedFolder = null
                    }
                ) {
                    Text("Back to folders")
                }
            }

            if (selectedFolder == null) {
                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    folders.forEach { (folder, files) ->
                        item(key = folder) {
                            ListItem(
                                headlineContent = {
                                    Text(folder)
                                },
                                supportingContent = {
                                    Text("${files.size} videos")
                                },
                                leadingContent = {
                                    VideoThumbnail(
                                        uri = files.first().uri,
                                        modifier = Modifier.size(
                                            width = 112.dp,
                                            height = 64.dp
                                        )
                                    )
                                },
                                modifier = Modifier.clickable {
                                    selectedFolder = folder
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            } else {
                val folderVideos = sortedVideos.filter {
                    it.folder == selectedFolder
                }

                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    items(
                        items = folderVideos,
                        key = { it.id }
                    ) { video ->
                        ListItem(
                            headlineContent = {
                                Text(video.title)
                            },
                            supportingContent = {
                                Text(
                                    formatDuration(
                                        video.duration
                                    )
                                )
                            },
                            leadingContent = {
                                VideoThumbnail(
                                    uri = video.uri,
                                    modifier = Modifier.size(
                                        width = 112.dp,
                                        height = 64.dp
                                    )
                                )
                            },
                            modifier = Modifier.clickable {
                                playVideo(video.uri)
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

fun loadVideos(context: Context): List<VideoFile> {
    val result = mutableListOf<VideoFile>()

    val collection =
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATE_ADDED
    )

    context.contentResolver.query(
        collection,
        projection,
        null,
        null,
        "${MediaStore.Video.Media.DATE_ADDED} DESC"
    )?.use { cursor ->

        val idIndex = cursor.getColumnIndexOrThrow(
            MediaStore.Video.Media._ID
        )
        val nameIndex = cursor.getColumnIndexOrThrow(
            MediaStore.Video.Media.DISPLAY_NAME
        )
        val durationIndex = cursor.getColumnIndexOrThrow(
            MediaStore.Video.Media.DURATION
        )
        val folderIndex = cursor.getColumnIndexOrThrow(
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )
        val sizeIndex = cursor.getColumnIndexOrThrow(
            MediaStore.Video.Media.SIZE
        )
        val dateIndex = cursor.getColumnIndexOrThrow(
            MediaStore.Video.Media.DATE_ADDED
        )

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idIndex)

            val title = cursor.getString(nameIndex)
                ?: "Unknown Video"

            val duration = if (
                cursor.isNull(durationIndex)
            ) 0L else cursor.getLong(durationIndex)

            val folder = cursor.getString(folderIndex)
                ?: "Other Videos"

            val size = if (
                cursor.isNull(sizeIndex)
            ) 0L else cursor.getLong(sizeIndex)

            val date = if (
                cursor.isNull(dateIndex)
            ) 0L else cursor.getLong(dateIndex)

            val uri = ContentUris.withAppendedId(
                collection,
                id
            )

            result.add(
                VideoFile(
                    id = id,
                    title = title,
                    uri = uri,
                    duration = duration,
                    folder = folder,
                    size = size,
                    date = date
                )
            )
        }
    }

    return result
}
