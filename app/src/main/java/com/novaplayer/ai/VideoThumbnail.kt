package com.novaplayer.ai

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val thumbnailCache =
    object : LruCache<String, Bitmap>(24) {}

private fun loadThumbnail(
    context: Context,
    uri: Uri
): Bitmap? {
    val key = uri.toString()

    thumbnailCache.get(key)?.let {
        return it
    }

    val retriever = MediaMetadataRetriever()

    return try {
        retriever.setDataSource(context, uri)

        val bitmap = retriever.getScaledFrameAtTime(
            1000000L,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            240,
            135
        )

        if (bitmap != null) {
            thumbnailCache.put(key, bitmap)
        }

        bitmap
    } catch (_: Exception) {
        null
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
        }
    }
}

@Composable
fun VideoThumbnail(
    uri: Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val bitmap by produceState<Bitmap?>(
        initialValue = thumbnailCache.get(uri.toString()),
        key1 = uri
    ) {
        value = withContext(Dispatchers.IO) {
            loadThumbnail(context, uri)
        }
    }

    Box(
        modifier = modifier.background(
            MaterialTheme.colorScheme.surfaceVariant
        ),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Video thumbnail",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("▶")
        }
    }
}
