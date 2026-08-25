package com.example.sulu_read.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.example.sulu_read.ui.theme.FocusHighlight
import com.example.sulu_read.ui.theme.FocusWordColor
import com.example.sulu_read.ui.theme.ReadingSurface
import com.example.sulu_read.ui.theme.RestingWordColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt


// The word being read is set well above the surrounding text rather than a notch above it.
// A difference a reader has to look for is not a cue; at half again the resting size the eye
// lands on the right word without being told where to look.
internal const val FOCUS_FONT_SIZE_SP = 22
internal const val FOCUS_WORD_FONT_SIZE_SP = 34

// Fixed for every line, and tall enough for the enlarged word. Uniform leading means the text
// does not re-space itself as the focus moves, which would be motion in the reading area.
internal const val FOCUS_LINE_HEIGHT_SP = 52

private const val BLOCK_PADDING_DP = 18
private const val HIGHLIGHT_CORNER_DP = 8
private const val HIGHLIGHT_PADDING_DP = 4
private const val HIGHLIGHT_LINE_INSET_DP = 5

// Without a cap the card grows to the height of the whole document and pushes every control
// off-screen, so the reader never discovers that help exists. 280dp rather than 320: measured on
// a 1220x2712 phone, the extra 40dp was the difference between the reader seeing the button that
// moves the focus and having to go looking for it. Five lines of context still fit.
private const val BLOCK_MAX_HEIGHT_DP = 280

// The focus word is parked a third of the way down rather than centred: it leaves the text
// still to be read in view, which is the direction the reader is heading.
private const val FOCUS_VIEWPORT_FRACTION = 3

/**
 * The reading surface: the whole text legible, with the word being read enlarged and
 * highlighted.
 *
 * Everything is drawn as one [AnnotatedString] in a single [Text]. That is what makes the
 * enlarged word reflow its line correctly — a row of individually sized Text nodes cannot wrap
 * as one paragraph — and it lets the highlight be positioned from the real glyph geometry
 * rather than guessed at.
 *
 * This replaces an earlier version that blurred everything except the current word. Obscuring
 * the surrounding text removes the context a reader uses to predict what is coming, and the
 * blur itself had to be rasterised by hand below API 31. Emphasis carries the same "read this
 * one" signal without taking the rest of the sentence away.
 */
@Composable
fun FocusTextBlock(
    words: List<FocusWord>,
    focusIndex: Int,
    isFocusEmphasised: Boolean,
    onWordTapped: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var layout by remember(words) { mutableStateOf<TextLayoutResult?>(null) }
    var viewportHeight by remember(words) { mutableStateOf(0) }

    // Built once per text, not per word: only the span styling depends on the focus index, and
    // the ranges are needed by both the highlight and the scroller.
    val ranges = remember(words) { focusWordRanges(words) }
    val plainText = remember(words) { words.joinToString(" ") { it.display } }
    val focusRange = ranges.getOrNull(focusIndex)

    val annotated = remember(plainText, focusRange, isFocusEmphasised) {
        buildFocusAnnotatedString(plainText, focusRange, isFocusEmphasised)
    }

    // Follow the focus word without animating. Motion inside the reading area is a documented
    // disorientation trigger, so the text jumps to where it needs to be rather than gliding.
    LaunchedEffect(focusIndex, layout, viewportHeight) {
        val result = layout ?: return@LaunchedEffect
        val range = focusRange ?: return@LaunchedEffect
        if (viewportHeight <= 0) {
            return@LaunchedEffect
        }
        val line = result.getLineForOffset(range.first)
        val target = result.getLineTop(line).roundToInt() - viewportHeight / FOCUS_VIEWPORT_FRACTION
        scrollState.scrollTo(target.coerceAtLeast(0))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = BLOCK_MAX_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(18.dp))
            // Not white. A white page under a bright screen is a documented source of visual
            // stress for dyslexic readers, and this is the surface they look at longest.
            .background(ReadingSurface)
            .padding(BLOCK_PADDING_DP.dp)
            .onSizeChanged { viewportHeight = it.height }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
        ) {
            Text(
                text = annotated,
                fontFamily = SuluSerifFontFamily,
                fontSize = FOCUS_FONT_SIZE_SP.sp,
                lineHeight = FOCUS_LINE_HEIGHT_SP.sp,
                color = RestingWordColor,
                onTextLayout = { layout = it },
                modifier = Modifier
                    .fillMaxWidth()
                    // The reader drives the focus. Tapping the text is the direct way to say
                    // "I am here now" — going back to re-read a line, or skipping ahead — and
                    // it needs no control of its own on a screen that is already full.
                    .pointerInput(ranges) {
                        detectTapGestures { position ->
                            val result = layout ?: return@detectTapGestures
                            val offset = result.getOffsetForPosition(position)
                            wordIndexAt(ranges, offset)?.let(onWordTapped)
                        }
                    }
                    .drawBehind {
                        val result = layout ?: return@drawBehind
                        val range = focusRange ?: return@drawBehind
                        if (!isFocusEmphasised) {
                            return@drawBehind
                        }
                        val pad = HIGHLIGHT_PADDING_DP.dp.toPx()
                        val corner = HIGHLIGHT_CORNER_DP.dp.toPx()
                        // Inset vertically because the rects are full line boxes, and the line
                        // box is sized for the tallest thing on the line. Without this the
                        // marker would reach the line above and below the word.
                        val inset = HIGHLIGHT_LINE_INSET_DP.dp.toPx()
                        // Drawn per line so a word broken across a wrap still gets a highlight
                        // on both halves instead of one box spanning the gap between them.
                        highlightRects(result, range).forEach { rect ->
                            drawRoundRect(
                                color = FocusHighlight,
                                topLeft = Offset(rect.left - pad, rect.top + inset),
                                size = Size(
                                    rect.width + pad * 2,
                                    (rect.height - inset * 2).coerceAtLeast(0f)
                                ),
                                cornerRadius = CornerRadius(corner, corner)
                            )
                        }
                    }
            )
        }
    }
}

/**
 * Character ranges of each word inside the joined text, in word order.
 *
 * The reader's word list and the rendered string have to agree on where word N starts, so the
 * offsets are derived from the same join that produces the string rather than searched for
 * afterwards — a search would find the wrong instance of any word that repeats.
 */
internal fun focusWordRanges(words: List<FocusWord>): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var cursor = 0
    words.forEachIndexed { index, word ->
        if (index > 0) {
            cursor += 1
        }
        ranges += cursor until (cursor + word.display.length)
        cursor += word.display.length
    }
    return ranges
}

/**
 * Which word a character offset belongs to, or the word just before it.
 *
 * A tap lands on a space or on the gap at the end of a line as often as it lands on a glyph, and
 * a tap that does nothing reads as the app being broken. Falling back to the word that started
 * before the offset means every tap inside the block moves the focus somewhere sensible.
 */
internal fun wordIndexAt(ranges: List<IntRange>, offset: Int): Int? {
    val exact = ranges.indexOfFirst { offset in it }
    if (exact >= 0) {
        return exact
    }
    val preceding = ranges.indexOfLast { it.first <= offset }
    return preceding.takeIf { it >= 0 }
}

private fun buildFocusAnnotatedString(
    plainText: String,
    focusRange: IntRange?,
    isFocusEmphasised: Boolean
): AnnotatedString {
    if (focusRange == null || !isFocusEmphasised) {
        return AnnotatedString(plainText)
    }
    val start = focusRange.first.coerceIn(0, plainText.length)
    val end = (focusRange.last + 1).coerceIn(start, plainText.length)
    return buildAnnotatedString {
        append(plainText.substring(0, start))
        withStyle(
            SpanStyle(
                fontSize = FOCUS_WORD_FONT_SIZE_SP.sp,
                fontWeight = FontWeight.Bold,
                color = FocusWordColor
            )
        ) {
            append(plainText.substring(start, end))
        }
        append(plainText.substring(end))
    }
}

private fun highlightRects(
    result: TextLayoutResult,
    range: IntRange
): List<Rect> {
    val start = range.first.coerceIn(0, result.layoutInput.text.length)
    val end = (range.last + 1).coerceIn(start, result.layoutInput.text.length)
    if (start >= end) {
        return emptyList()
    }
    val firstLine = result.getLineForOffset(start)
    val lastLine = result.getLineForOffset(end - 1)
    return (firstLine..lastLine).map { line ->
        val lineStart = maxOf(start, result.getLineStart(line))
        val lineEnd = minOf(end, result.getLineEnd(line, visibleEnd = true))
        val left = result.getHorizontalPosition(lineStart, usePrimaryDirection = true)
        val right = result.getHorizontalPosition(lineEnd, usePrimaryDirection = true)
        Rect(
            left = minOf(left, right),
            top = result.getLineTop(line),
            right = maxOf(left, right),
            bottom = result.getLineBottom(line)
        )
    }
}
