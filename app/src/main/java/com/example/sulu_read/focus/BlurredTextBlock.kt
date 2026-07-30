package com.example.sulu_read.focus

import android.graphics.Bitmap
import android.graphics.Picture
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt

internal val FocusHighlight = Color(0xFFFFE0B2)
internal val FocusWordColor = Color(0xFF1A1A1A)
internal val BlurredWordColor = Color(0xFF6B6B6B)
internal const val FOCUS_FONT_SIZE_SP = 22
internal const val FOCUS_LINE_HEIGHT_SP = 38
private const val BLUR_RADIUS_DP = 7
private const val WORD_GAP_DP = 8
private const val BLOCK_PADDING_DP = 18

/**
 * The reading surface: the whole text blurred as a single layer, with the current word drawn
 * sharp on top of it.
 *
 * One raster, not one blur per word. The blurred layer is identical no matter which word is
 * in focus, so it is rasterised once per text and reused; only the cheap overlay moves. The
 * earlier version put a blur modifier on every one of a few hundred Text nodes, which is
 * both slower and, below API 31, not a blur at all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlurredTextBlock(
    words: List<FocusWord>,
    focusIndex: Int,
    isFocusSharp: Boolean,
    modifier: Modifier = Modifier
) {
    var blockOrigin by remember(words) { mutableStateOf(Offset.Zero) }
    var focusOrigin by remember(words) { mutableStateOf<Offset?>(null) }
    val focusWord = words.getOrNull(focusIndex)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .padding(BLOCK_PADDING_DP.dp)
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(WORD_GAP_DP.dp),
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates -> blockOrigin = coordinates.positionInRoot() }
                .obscuringBlur(cacheKey = words)
        ) {
            words.forEachIndexed { index, word ->
                val isFocus = index == focusIndex
                Text(
                    text = word.display,
                    fontFamily = SuluSerifFontFamily,
                    fontSize = FOCUS_FONT_SIZE_SP.sp,
                    lineHeight = FOCUS_LINE_HEIGHT_SP.sp,
                    // The focus word keeps its weight here too so the sharp overlay drawn on
                    // top of it lands on identical glyph metrics.
                    fontWeight = if (isFocus) FontWeight.Bold else FontWeight.Normal,
                    color = BlurredWordColor,
                    modifier = if (isFocus) {
                        Modifier.onGloballyPositioned { coordinates ->
                            focusOrigin = coordinates.positionInRoot()
                        }
                    } else {
                        Modifier
                    }
                )
            }
        }

        val origin = focusOrigin
        if (focusWord != null && isFocusSharp && origin != null) {
            val offsetX = (origin.x - blockOrigin.x).roundToInt()
            val offsetY = (origin.y - blockOrigin.y).roundToInt()
            Text(
                text = focusWord.display,
                fontFamily = SuluSerifFontFamily,
                fontSize = FOCUS_FONT_SIZE_SP.sp,
                lineHeight = FOCUS_LINE_HEIGHT_SP.sp,
                fontWeight = FontWeight.Bold,
                color = FocusWordColor,
                modifier = Modifier
                    .offset { IntOffset(offsetX, offsetY) }
                    .background(FocusHighlight, RoundedCornerShape(6.dp))
            )
        }
    }
}

/**
 * Makes the content unreadable while leaving its shape visible.
 *
 * API 31+ uses the platform blur. Below that [blur] is a no-op, so the content is rasterised
 * once, blurred by downsampling, and the result is drawn in its place. [cacheKey] must be the
 * content itself, never the focus index — the blurred layer does not change as the reader
 * moves through the text, and rebuilding it per word would rebuild it per keystroke of speech.
 */
@Composable
private fun Modifier.obscuringBlur(cacheKey: Any): Modifier {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return this.blur(radius = BLUR_RADIUS_DP.dp)
    }

    val picture = remember(cacheKey) { Picture() }
    val blurred = remember(cacheKey) { mutableStateOf<ImageBitmap?>(null) }

    return this.drawWithContent {
        val cached = blurred.value
        if (cached != null) {
            drawImage(
                image = cached,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
            )
            return@drawWithContent
        }

        val width = size.width.roundToInt()
        val height = size.height.roundToInt()
        if (width <= 0 || height <= 0) {
            drawContent()
            return@drawWithContent
        }

        val recordingCanvas = picture.beginRecording(width, height)
        draw(this, layoutDirection, Canvas(recordingCanvas), size) {
            this@drawWithContent.drawContent()
        }
        picture.endRecording()

        // Rasterise below layout resolution: the detail is about to be destroyed anyway, and
        // a full-resolution raster of a long page is tens of megabytes.
        val rasterWidth = max(1, width / RASTER_SCALE_DIVISOR)
        val rasterHeight = max(1, height / RASTER_SCALE_DIVISOR)
        val raster = Bitmap.createBitmap(rasterWidth, rasterHeight, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(raster).apply {
            val scale = 1f / RASTER_SCALE_DIVISOR
            scale(scale, scale)
            drawPicture(picture)
        }

        val softened = downsampleBlur(raster)
        if (softened !== raster) {
            raster.recycle()
        }

        // Assigning during draw schedules one extra frame. It happens once per text, and the
        // alternative is re-rasterising on every frame.
        blurred.value = softened.asImageBitmap()
        drawImage(image = blurred.value!!, dstSize = IntSize(width, height))
    }
}
