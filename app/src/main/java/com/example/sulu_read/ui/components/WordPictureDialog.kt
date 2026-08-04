package com.example.sulu_read.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sulu_read.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val IMAGE_CONNECT_TIMEOUT_MS = 15_000
private const val IMAGE_READ_TIMEOUT_MS = 20_000
private const val IMAGE_MAX_BYTES = 4 * 1024 * 1024
private const val DIALOG_IMAGE_HEIGHT_DP = 240

/**
 * The picture behind a word the reader tapped.
 *
 * The word itself is the heading and is set large: the point of the picture is to attach a
 * meaning to that word, so the two have to be seen together.
 *
 * The credit line is not optional decoration. These images are free to use but almost all of
 * them require attribution, so a picture whose author could not be read says so rather than
 * appearing with no credit at all.
 */
@Composable
fun WordPictureDialog(
    word: String,
    imageUrl: String,
    attribution: String,
    licenseName: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.word_picture_close))
            }
        },
        title = {
            Text(
                text = word,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RemoteImage(
                    url = imageUrl,
                    contentDescription = word,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DIALOG_IMAGE_HEIGHT_DP.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
                Text(
                    text = stringResource(R.string.word_picture_credit, attribution, licenseName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
private fun RemoteImage(
    url: String,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        val loaded = withContext(Dispatchers.IO) { downloadImage(url) }
        if (loaded == null) failed = true else bitmap = loaded
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = DIALOG_IMAGE_HEIGHT_DP.dp)
            )

            failed -> Text(
                text = stringResource(R.string.word_picture_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp)
            )

            else -> CircularProgressIndicator()
        }
    }
}

/**
 * Fetches one image.
 *
 * Deliberately small and dependency-free: the app needs exactly one remote image at a time, in
 * a dialog, and adding an image-loading library for that would be a lot of machinery for one
 * screen. The byte cap is what stops a mis-sized source image from exhausting memory on a
 * cheap phone.
 */
private fun downloadImage(url: String): ImageBitmap? {
    return runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = IMAGE_CONNECT_TIMEOUT_MS
            readTimeout = IMAGE_READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) {
                return@runCatching null
            }
            val bytes = connection.inputStream.use { stream ->
                stream.readBytes(IMAGE_MAX_BYTES)
            } ?: return@runCatching null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

/** Reads at most [limit] bytes, giving up rather than filling memory with an oversized image. */
private fun java.io.InputStream.readBytes(limit: Int): ByteArray? {
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    while (true) {
        val read = read(chunk)
        if (read < 0) break
        if (buffer.size() + read > limit) return null
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}
