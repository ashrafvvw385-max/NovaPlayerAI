
package com.novaplayer.ai

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

data class VideoFile(
    val id: Long,
    val title: String,
    val uri: Uri,
    val duration: Long
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                NovaPlayerScreen()
            }
        }
    }
}

@Composable
fun NovaPlayerScreen() {
    val context = LocalContext.current

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

    var selectedUri by remember {
        mutableStateOf<Uri?>(null)
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
            videos = loadVideos(context)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            text = "Nova Player AI",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(16.dp)
        )

        Row(
            modifier = Modifier.padding(8.dp)
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

        if (selectedUri != null) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        this.player = player
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
            )
        }

        Text(
            text = "My Videos (${videos.size})",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp)
        )

        LazyColumn {
            items(videos) { video ->
                ListItem(
                    headlineContent = {
                        Text(video.title)
                    },
                    supportingContent = {
                        Text(
                            "${video.duration / 1000} seconds"
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

fun loadVideos(
    context: android.content.Context
): List<VideoFile> {
    val result = mutableListOf<VideoFile>()

    val collection =
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION
    )

    val sortOrder =
        "${MediaStore.Video.Media.DATE_ADDED} DESC"

    context.contentResolver.query(
        collection,
        projection,
        null,
        null,
        sortOrder
    )?.use { cursor ->

        val idColumn = cursor.getColumnIndexOrThrow(
            MediaStore.Video.Media._ID
        )
        val nameColumn = cursor.getColumnIndexOrThrow(
            MediaStore.Video.Media.DISPLAY_NAME
        )
        val durationColumn = cursor.getColumnIndexOrThrow(
            MediaStore.Video.Media.DURATION
        )

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val name = cursor.getString(nameColumn) ?: "Video"
            val duration = if (cursor.isNull(durationColumn)) {
                0L
            } else {
                cursor.getLong(durationColumn)
            }

            val uri = ContentUris.withAppendedId(
                collection,
                id
            )

            result.add(
                VideoFile(
                    id = id,
                    title = name,
                    uri = uri,
                    duration = duration
                )
            )
        }
    }

    return result
}
