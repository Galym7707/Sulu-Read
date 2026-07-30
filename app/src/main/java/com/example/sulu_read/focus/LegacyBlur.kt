package com.example.sulu_read.focus

import android.graphics.Bitmap
import kotlin.math.max

/**
 * Blur for API 24-30, where [androidx.compose.ui.draw.blur] is a documented no-op.
 *
 * Downsample-then-upsample rather than a real Gaussian: bilinear filtering in both
 * directions destroys exactly the high-frequency detail that makes text readable, which is
 * the whole requirement, and it needs no RenderScript (deprecated at API 31 — the very
 * version where the native blur takes over) and no new dependency.
 *
 * ponytail: not a true Gaussian. Letter shapes dissolve, so visual crowding is solved, but
 * a photographer would call the falloff wrong. Swap in a real two-pass Gaussian only if the
 * flat look becomes a complaint.
 */
fun downsampleBlur(source: Bitmap, factor: Int = DEFAULT_DOWNSAMPLE_FACTOR): Bitmap {
    val width = max(1, source.width / factor)
    val height = max(1, source.height / factor)
    val small = Bitmap.createScaledBitmap(source, width, height, true)
    val blurred = Bitmap.createScaledBitmap(small, source.width, source.height, true)
    if (small !== blurred && small !== source) {
        small.recycle()
    }
    return blurred
}

const val DEFAULT_DOWNSAMPLE_FACTOR = 3

/**
 * The text block is rasterised at a fraction of its layout size before being blurred: a
 * full-resolution raster of a long page is tens of megabytes, which a 1 GB device will not
 * survive, and the detail is about to be destroyed anyway.
 */
const val RASTER_SCALE_DIVISOR = 4

/** Longest raster edge tolerated before shrinking further. 2048x2048 ARGB is ~16 MB. */
private const val MAX_RASTER_EDGE = 2048

/**
 * Picks a divisor that keeps the raster inside [MAX_RASTER_EDGE]. A full textbook page is
 * tall enough that [RASTER_SCALE_DIVISOR] alone still leaves a bitmap worth several hundred
 * megabytes on the heap.
 */
fun rasterDivisorFor(width: Int, height: Int): Int {
    var divisor = RASTER_SCALE_DIVISOR
    while (width / divisor > MAX_RASTER_EDGE || height / divisor > MAX_RASTER_EDGE) {
        divisor *= 2
    }
    return divisor
}
