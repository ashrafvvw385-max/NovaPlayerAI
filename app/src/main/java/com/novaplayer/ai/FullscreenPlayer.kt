
package com.novaplayer.ai

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@Composable
fun FullscreenPlayer(
    player: ExoPlayer,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as Activity
    val audio = remember {
        context.getSystemService(Context.AUDIO_SERVICE)
            as AudioManager
    }

    var locked by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var speedMenu by remember { mutableStateOf(false) }
    var subtitleMenu by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var subtitleUri by remember { mutableStateOf<Uri?>(null) }

    val maxVolume = remember {
        audio.getStreamMaxVolume(
            AudioManager.STREAM_MUSIC
        ).coerceAtLeast(1)
    }

    val originalOrientation = remember {
        activity.requestedOrientation
    }

    val subtitlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            subtitleUri = uri

            val currentItem = player.currentMediaItem
            if (currentItem != null) {
                val position = player.currentPosition
                val wasPlaying = player.playWhenReady

                val subtitle = MediaItem.SubtitleConfiguration.Builder(uri)
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage("ar")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()

                val newItem = currentItem.buildUpon()
                    .setSubtitleConfigurations(listOf(subtitle))
                    .build()

                player.setMediaItem(newItem, position)
                player.prepare()
                player.playWhenReady = wasPlaying
            }
        }
    }

    DisposableEffect(activity) {
        val window = activity.window
        val oldBrightness = window.attributes.screenBrightness
        val wasFullscreen =
            window.attributes.flags and
                WindowManager.LayoutParams.FLAG_FULLSCREEN != 0

        activity.requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        onDispose {
            activity.requestedOrientation = originalOrientation

            val params = window.attributes
            params.screenBrightness = oldBrightness
            window.attributes = params

            if (!wasFullscreen) {
                window.clearFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
                )
            }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    BackHandler {
        if (locked) {
            locked = false
            showControls = true
        } else {
            onBack()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val screenWidth = constraints.maxWidth.toFloat()
        var totalDrag by remember { mutableFloatStateOf(0f) }
        var startVolume by remember { mutableIntStateOf(0) }
        var startBrightness by remember {
            mutableFloatStateOf(0.5f)
        }
        var gestureSide by remember {
            mutableStateOf("volume")
        }

        AndroidView(
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    useController = false
                    keepScreenOn = true
                }
            },
            update = {
                it.player = player
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(locked, screenWidth) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            totalDrag = 0f
                            gestureSide = if (
                                offset.x < screenWidth / 2f
                            ) "brightness" else "volume"

                            startVolume = audio.getStreamVolume(
                                AudioManager.STREAM_MUSIC
                            )

                            val current =
                                activity.window.attributes.screenBrightness

                            startBrightness = if (current < 0f) {
                                0.5f
                            } else {
                                current
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (!locked) {
                                totalDrag += dragAmount.y

                                if (gestureSide == "volume") {
                                    val delta = (
                                        -totalDrag / 35f
                                    ).toInt()

                                    val volume = (
                                        startVolume + delta
                                    ).coerceIn(0, maxVolume)

                                    audio.setStreamVolume(
                                        AudioManager.STREAM_MUSIC,
                                        volume,
                                        0
                                    )
                                } else {
                                    val brightness = (
                                        startBrightness -
                                            totalDrag / 900f
                                    ).coerceIn(0.05f, 1f)

                                    val params =
                                        activity.window.attributes

                                    params.screenBrightness = brightness
                                    activity.window.attributes = params
                                }
                            }
                        }
                    )
                }
                .pointerInput(locked, screenWidth) {
                    detectTapGestures(
                        onTap = {
                            showControls = !showControls
                        },
                        onDoubleTap = { offset ->
                            if (!locked) {
                                val delta = if (
                                    offset.x < screenWidth / 2f
                                ) -10000L else 10000L

                                val duration = player.duration
                                val target = (
                                    player.currentPosition + delta
                                ).coerceAtLeast(0L)

                                player.seekTo(
                                    if (duration > 0) {
                                        target.coerceAtMost(duration)
                                    } else {
                                        target
                                    }
                                )
                            }
                        }
                    )
                }
        )

        if (showControls) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    if (locked) {
                        locked = false
                    } else {
                        onBack()
                    }
                }) {
                    Text(
                        if (locked) "Unlock" else "Back",
                        color = Color.White
                    )
                }

                Text(
                    "Nova Player AI",
                    color = Color.White
                )

                TextButton(
                    onClick = { locked = !locked }
                ) {
                    Text(
                        if (locked) "Unlock" else "Lock",
                        color = Color.White
                    )
                }
            }

            if (!locked) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Color.Black.copy(alpha = 0.7f)
                        )
                        .padding(8.dp),
                    horizontalArrangement =
                        Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        player.seekTo(
                            (player.currentPosition - 10000L)
                                .coerceAtLeast(0L)
                        )
                    }) {
                        Text("-10s")
                    }

                    TextButton(onClick = {
                        if (player.isPlaying) {
                            player.pause()
                        } else {
                            player.play()
                        }
                    }) {
                        Text(if (playing) "Pause" else "Play")
                    }

                    TextButton(onClick = {
                        val target =
                            player.currentPosition + 10000L
                        val duration = player.duration
                        player.seekTo(
                            if (duration > 0L) {
                                target.coerceAtMost(duration)
                            } else {
                                target
                            }
                        )
                    }) {
                        Text("+10s")
                    }

                    Box {
                        TextButton(
                            onClick = { speedMenu = true }
                        ) {
                            Text("${player.playbackParameters.speed}x")
                        }

                        DropdownMenu(
                            expanded = speedMenu,
                            onDismissRequest = {
                                speedMenu = false
                            }
                        ) {
                            listOf(
                                0.5f, 0.75f, 1f,
                                1.25f, 1.5f, 2f
                            ).forEach { speed ->
                                DropdownMenuItem(
                                    text = { Text("${speed}x") },
                                    onClick = {
                                        player.setPlaybackSpeed(speed)
                                        speedMenu = false
                                    }
                                )
                            }
                        }
                    }

                    Box {
                        TextButton(
                            onClick = { subtitleMenu = true }
                        ) {
                            Text("Subtitles")
                        }

                        DropdownMenu(
                            expanded = subtitleMenu,
                            onDismissRequest = {
                                subtitleMenu = false
                            }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Open SRT file") },
                                onClick = {
                                    subtitleMenu = false
                                    subtitlePicker.launch(
                                        arrayOf(
                                            "application/x-subrip",
                                            "text/plain",
                                            "application/octet-stream",
                                            "*/*"
                                        )
                                    )
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Online subtitles - Coming soon") },
                                enabled = false,
                                onClick = {}
                            )
                        }
                    }
                }
            }
        }
    }
}
