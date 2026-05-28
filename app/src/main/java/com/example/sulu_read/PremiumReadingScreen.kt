package com.example.sulu_read

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.roundToInt

data class SyllableWord(
    val original: String,
    val syllables: List<String>
)

private const val DEFAULT_LETTER_SPACING = 1.5f
private const val DEFAULT_LINE_HEIGHT = 34f
private const val NO_PLAYING_WORD = -1

private val DeepBlueBlack = Color(0xFF1A237E)
private val DarkSlateGray = Color(0xFF37474F)
private val PlaybackHighlight = Color(0xFFFFF9C4)
private val RulerOverlay = Color.Black.copy(alpha = 0.58f)
private val RulerEdge = Color.White.copy(alpha = 0.38f)
private val ControlPanelBackground = Color(0xFFFFFCF4)
private val ControlPanelBorder = Color(0xFFCFE3D4)

@Stable
private class ReadingScreenState(
    letterSpacing: Float = DEFAULT_LETTER_SPACING,
    lineHeight: Float = DEFAULT_LINE_HEIGHT,
    isReadingRulerEnabled: Boolean = false,
    rulerYOffset: Float = 0f,
    currentPlayingWordIndex: Int = NO_PLAYING_WORD,
    simplifiedTextSnippet: String? = null
) {
    var letterSpacing by mutableFloatStateOf(letterSpacing)
    var lineHeight by mutableFloatStateOf(lineHeight)
    var isReadingRulerEnabled by mutableStateOf(isReadingRulerEnabled)
    var rulerYOffset by mutableFloatStateOf(rulerYOffset)
    var currentPlayingWordIndex by mutableIntStateOf(currentPlayingWordIndex)
    var simplifiedTextSnippet by mutableStateOf(simplifiedTextSnippet)
}

private val ReadingScreenStateSaver: Saver<ReadingScreenState, Any> = listSaver(
    save = { state ->
        listOf(
            state.letterSpacing,
            state.lineHeight,
            state.isReadingRulerEnabled,
            state.rulerYOffset,
            state.currentPlayingWordIndex,
            state.simplifiedTextSnippet
        )
    },
    restore = { restored ->
        ReadingScreenState(
            letterSpacing = restored[0] as Float,
            lineHeight = restored[1] as Float,
            isReadingRulerEnabled = restored[2] as Boolean,
            rulerYOffset = restored[3] as Float,
            currentPlayingWordIndex = restored[4] as Int,
            simplifiedTextSnippet = restored[5] as String?
        )
    }
)

private data class ReadingParagraph(
    val original: String,
    val words: List<IndexedSyllableWord>
)

private data class IndexedSyllableWord(
    val index: Int,
    val value: SyllableWord
)

@Composable
fun PremiumReadingScreen(
    text: String,
    modifier: Modifier = Modifier
) {
    val state = rememberSaveable(text, saver = ReadingScreenStateSaver) {
        ReadingScreenState()
    }
    val paragraphs = remember(text) { buildReadingParagraphs(text) }
    val words = remember(paragraphs) { paragraphs.flatMap { paragraph -> paragraph.words } }
    val ttsController = rememberTextToSpeechController(
        words = words,
        state = state
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ReadingControls(
            state = state,
            canPlay = words.isNotEmpty() && ttsController.isReady,
            onPlay = {
                val startIndex = state.currentPlayingWordIndex
                    .takeIf { it in words.indices }
                    ?: 0
                ttsController.playFrom(startIndex)
            },
            onStop = { ttsController.stop() }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.74f))
                .padding(18.dp)
        ) {
            ReadingTextFlow(
                paragraphs = paragraphs,
                state = state,
                onWordClick = { index -> ttsController.playFrom(index) },
                onSimplifyRequest = { source ->
                    state.simplifiedTextSnippet = simplifyTextSnippet(source)
                }
            )

            if (state.isReadingRulerEnabled) {
                ReadingRulerOverlay(
                    state = state,
                    modifier = Modifier.matchParentSize()
                )
            }
        }
    }

    val simplifiedText = state.simplifiedTextSnippet
    if (simplifiedText != null) {
        SimplifiedTextSheet(
            text = simplifiedText,
            onDismissRequest = { state.simplifiedTextSnippet = null }
        )
    }
}

@Composable
private fun ReadingControls(
    state: ReadingScreenState,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ControlPanelBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ReadingSlider(
            label = "Расстояние между буквами",
            value = state.letterSpacing,
            valueRange = 0f..10f,
            valueSuffix = "sp",
            onValueChange = { state.letterSpacing = it.coerceIn(0f, 10f) }
        )

        ReadingSlider(
            label = "Высота строки",
            value = state.lineHeight,
            valueRange = 20f..48f,
            valueSuffix = "sp",
            onValueChange = { state.lineHeight = it.coerceIn(20f, 48f) }
        )

        HorizontalDivider(color = ControlPanelBorder)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Чтение вслух",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onPlay,
                    enabled = canPlay,
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Начать чтение",
                        tint = if (canPlay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onStop,
                    enabled = state.currentPlayingWordIndex != NO_PLAYING_WORD,
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Остановить чтение",
                        tint = if (state.currentPlayingWordIndex != NO_PLAYING_WORD) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Линейка чтения",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Switch(
                checked = state.isReadingRulerEnabled,
                onCheckedChange = { enabled ->
                    state.isReadingRulerEnabled = enabled
                }
            )
        }
    }
}

@Composable
private fun ReadingSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueSuffix: String,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${value.roundToInt()} $valueSuffix",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ReadingTextFlow(
    paragraphs: List<ReadingParagraph>,
    state: ReadingScreenState,
    onWordClick: (Int) -> Unit,
    onSimplifyRequest: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        paragraphs.forEach { paragraph ->
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        role = Role.Button,
                        onClick = {},
                        onLongClick = { onSimplifyRequest(paragraph.original) }
                    ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                paragraph.words.forEach { indexedWord ->
                    SyllableWordChip(
                        indexedWord = indexedWord,
                        state = state,
                        paragraphText = paragraph.original,
                        onClick = onWordClick,
                        onLongClick = onSimplifyRequest
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SyllableWordChip(
    indexedWord: IndexedSyllableWord,
    state: ReadingScreenState,
    paragraphText: String,
    onClick: (Int) -> Unit,
    onLongClick: (String) -> Unit
) {
    val isPlaying by remember(indexedWord.index, state) {
        derivedStateOf { state.currentPlayingWordIndex == indexedWord.index }
    }
    val backgroundColor by animateColorAsState(
        targetValue = if (isPlaying) PlaybackHighlight else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "word-highlight"
    )

    Text(
        text = remember(indexedWord.value) { indexedWord.value.toAnnotatedSyllables() },
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .combinedClickable(
                role = Role.Button,
                onClick = { onClick(indexedWord.index) },
                onLongClick = { onLongClick(paragraphText) }
            )
            .semantics {
                contentDescription = indexedWord.value.original
            }
            .padding(horizontal = 5.dp, vertical = 2.dp),
        style = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 22.sp,
            lineHeight = state.lineHeight.sp,
            letterSpacing = state.letterSpacing.sp
        )
    )
}

private fun SyllableWord.toAnnotatedSyllables() = buildAnnotatedString {
    val safeSyllables = syllables.ifEmpty { listOf(original) }
    safeSyllables.forEachIndexed { index, syllable ->
        withStyle(
            SpanStyle(
                color = if (index % 2 == 0) DeepBlueBlack else DarkSlateGray
            )
        ) {
            append(syllable)
        }
        if (index < safeSyllables.lastIndex) {
            withStyle(SpanStyle(color = DarkSlateGray.copy(alpha = 0.58f))) {
                append("·")
            }
        }
    }
}

@Composable
private fun ReadingRulerOverlay(
    state: ReadingScreenState,
    modifier: Modifier = Modifier,
    clearWindowHeight: Dp = 60.dp
) {
    val density = LocalDensity.current
    val clearWindowHeightPx = with(density) { clearWindowHeight.toPx() }

    Canvas(
        modifier = modifier
            .semantics {
                contentDescription = "Линейка чтения"
            }
            .pointerRulerDrag(
                state = state,
                clearWindowHeightPx = clearWindowHeightPx
            )
    ) {
        val centerY = resolveRulerCenterY(
            currentY = state.rulerYOffset,
            height = size.height,
            clearWindowHeightPx = clearWindowHeightPx
        )
        val windowTop = (centerY - clearWindowHeightPx / 2f).coerceAtLeast(0f)
        val windowBottom = (centerY + clearWindowHeightPx / 2f).coerceAtMost(size.height)

        drawRect(
            color = RulerOverlay,
            topLeft = Offset.Zero,
            size = Size(width = size.width, height = windowTop)
        )
        drawRect(
            color = RulerOverlay,
            topLeft = Offset(x = 0f, y = windowBottom),
            size = Size(width = size.width, height = size.height - windowBottom)
        )
        drawLine(
            color = RulerEdge,
            start = Offset(x = 0f, y = windowTop),
            end = Offset(x = size.width, y = windowTop),
            strokeWidth = 2f
        )
        drawLine(
            color = RulerEdge,
            start = Offset(x = 0f, y = windowBottom),
            end = Offset(x = size.width, y = windowBottom),
            strokeWidth = 2f
        )
    }
}

private fun Modifier.pointerRulerDrag(
    state: ReadingScreenState,
    clearWindowHeightPx: Float
): Modifier = then(
    Modifier.pointerInput(clearWindowHeightPx) {
        awaitEachGesture {
            val down = awaitFirstDown()
            state.rulerYOffset = down.position.y.coerceIn(
                clearWindowHeightPx / 2f,
                size.height - clearWindowHeightPx / 2f
            )
            drag(down.id) { change ->
                val updatedY = state.rulerYOffset + change.positionChange().y
                state.rulerYOffset = updatedY.coerceIn(
                    clearWindowHeightPx / 2f,
                    size.height - clearWindowHeightPx / 2f
                )
                change.consume()
            }
        }
    }
)

private fun resolveRulerCenterY(
    currentY: Float,
    height: Float,
    clearWindowHeightPx: Float
): Float {
    if (height <= 0f) {
        return 0f
    }

    val minY = clearWindowHeightPx / 2f
    val maxY = height - clearWindowHeightPx / 2f
    if (maxY <= minY) {
        return height / 2f
    }

    return if (currentY <= 0f) {
        height * 0.36f
    } else {
        currentY
    }.coerceIn(minY, maxY)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimplifiedTextSheet(
    text: String,
    onDismissRequest: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Простыми словами ✨",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismissRequest) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Закрыть",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    lineHeight = 32.sp,
                    letterSpacing = 0.4.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            Button(
                onClick = onDismissRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(text = "Закрыть")
            }
        }
    }
}

@Composable
private fun rememberTextToSpeechController(
    words: List<IndexedSyllableWord>,
    state: ReadingScreenState
): TextToSpeechController {
    val context = LocalContext.current.applicationContext
    var textToSpeech by remember { mutableStateOf<TextToSpeech?>(null) }
    var isReady by remember { mutableStateOf(false) }
    var playbackRequest by remember { mutableIntStateOf(0) }

    DisposableEffect(context) {
        var disposed = false
        var engine: TextToSpeech? = null

        engine = TextToSpeech(context) { status ->
            if (disposed) {
                return@TextToSpeech
            }

            val initialized = status == TextToSpeech.SUCCESS
            if (initialized) {
                engine?.applyPreferredLanguage()
                engine?.setSpeechRate(0.88f)
                engine?.setPitch(1.0f)
            }

            isReady = initialized
        }
        textToSpeech = engine

        onDispose {
            disposed = true
            isReady = false
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
            state.currentPlayingWordIndex = NO_PLAYING_WORD
        }
    }

    val controller = remember(words, textToSpeech, isReady) {
        TextToSpeechController(
            isReady = isReady,
            playFrom = { wordIndex ->
                if (words.isEmpty()) {
                    return@TextToSpeechController
                }
                state.currentPlayingWordIndex = wordIndex.coerceIn(0, words.lastIndex)
                playbackRequest += 1
            },
            stop = {
                playbackRequest += 1
                textToSpeech?.stop()
                state.currentPlayingWordIndex = NO_PLAYING_WORD
            }
        )
    }

    androidx.compose.runtime.LaunchedEffect(playbackRequest, isReady, words) {
        if (playbackRequest == 0 || words.isEmpty()) {
            return@LaunchedEffect
        }

        val startIndex = state.currentPlayingWordIndex
        if (startIndex !in words.indices) {
            textToSpeech?.stop()
            return@LaunchedEffect
        }

        val utteranceText = words
            .drop(startIndex)
            .joinToString(separator = " ") { item -> item.value.original }
            .trim()

        if (utteranceText.isBlank()) {
            state.currentPlayingWordIndex = NO_PLAYING_WORD
            return@LaunchedEffect
        }

        if (isReady) {
            textToSpeech?.stop()
            textToSpeech?.speakCompat(
                text = utteranceText,
                utteranceId = "sulu-read-${System.nanoTime()}"
            )
        }

        for (index in startIndex..words.lastIndex) {
            if (!isActive) {
                return@LaunchedEffect
            }
            state.currentPlayingWordIndex = index
            delay(estimateWordDurationMillis(words[index].value.original))
        }

        state.currentPlayingWordIndex = NO_PLAYING_WORD
    }

    return controller
}

private data class TextToSpeechController(
    val isReady: Boolean,
    val playFrom: (Int) -> Unit,
    val stop: () -> Unit
)

private fun TextToSpeech.applyPreferredLanguage() {
    val preferredLocales = listOf(
        Locale("kk", "KZ"),
        Locale("ru", "RU"),
        Locale.getDefault()
    )
    val supportedLocale = preferredLocales.firstOrNull { locale ->
        isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
    }
    if (supportedLocale != null) {
        language = supportedLocale
    }
}

private fun TextToSpeech.speakCompat(text: String, utteranceId: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
    } else {
        @Suppress("DEPRECATION")
        speak(text, TextToSpeech.QUEUE_FLUSH, null)
    }
}

private fun estimateWordDurationMillis(word: String): Long {
    val readableLength = word.count { it.isLetterOrDigit() }.coerceAtLeast(1)
    return (280L + readableLength * 42L).coerceIn(460L, 1_350L)
}

private fun buildReadingParagraphs(text: String): List<ReadingParagraph> {
    var nextIndex = 0
    return text
        .split(Regex("(\\r?\\n){2,}|\\r?\\n"))
        .mapNotNull { rawParagraph ->
            val paragraphText = rawParagraph.trim()
            if (paragraphText.isBlank()) {
                return@mapNotNull null
            }

            val words = paragraphText
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .map { token ->
                    IndexedSyllableWord(
                        index = nextIndex++,
                        value = SyllableWord(
                            original = token,
                            syllables = splitIntoSyllables(token)
                        )
                    )
                }

            ReadingParagraph(
                original = paragraphText,
                words = words
            )
        }
}

private fun splitIntoSyllables(token: String): List<String> {
    val trimmed = token.trim()
    if (trimmed.isBlank()) {
        return emptyList()
    }

    val firstCoreIndex = trimmed.indexOfFirst { it.isLetterOrDigit() }
    val lastCoreIndex = trimmed.indexOfLast { it.isLetterOrDigit() }
    if (firstCoreIndex == -1 || lastCoreIndex == -1 || firstCoreIndex > lastCoreIndex) {
        return listOf(trimmed)
    }

    val prefix = trimmed.substring(0, firstCoreIndex)
    val core = trimmed.substring(firstCoreIndex, lastCoreIndex + 1)
    val suffix = trimmed.substring(lastCoreIndex + 1)
    val baseSyllables = splitCoreIntoSyllables(core)
    if (baseSyllables.isEmpty()) {
        return listOf(trimmed)
    }

    return baseSyllables.mapIndexed { index, syllable ->
        buildString {
            if (index == 0) {
                append(prefix)
            }
            append(syllable)
            if (index == baseSyllables.lastIndex) {
                append(suffix)
            }
        }
    }
}

private fun splitCoreIntoSyllables(core: String): List<String> {
    if (core.length <= 4) {
        return listOf(core)
    }

    val vowelIndices = core.indices.filter { index -> core[index].isKazakhRussianVowel() }
    if (vowelIndices.size <= 1) {
        return listOf(core)
    }

    val breakPoints = buildList {
        for (vowelPosition in 0 until vowelIndices.lastIndex) {
            val currentVowel = vowelIndices[vowelPosition]
            val nextVowel = vowelIndices[vowelPosition + 1]
            val consonantsBetween = nextVowel - currentVowel - 1
            val breakPoint = when {
                consonantsBetween <= 1 -> currentVowel + 1
                consonantsBetween == 2 -> currentVowel + 2
                else -> currentVowel + 2
            }.coerceIn(1, core.lastIndex)
            add(breakPoint)
        }
    }.distinct().sorted()

    val result = mutableListOf<String>()
    var start = 0
    breakPoints.forEach { end ->
        if (end > start) {
            result += core.substring(start, end)
            start = end
        }
    }
    if (start < core.length) {
        result += core.substring(start)
    }

    return result.filter { it.isNotBlank() }.ifEmpty { listOf(core) }
}

private fun Char.isKazakhRussianVowel(): Boolean {
    return lowercaseChar() in setOf(
        'а',
        'ә',
        'е',
        'ё',
        'и',
        'і',
        'о',
        'ө',
        'ұ',
        'ү',
        'у',
        'ы',
        'э',
        'ю',
        'я'
    )
}

private fun simplifyTextSnippet(source: String): String {
    val normalized = source
        .replace(Regex("\\([^)]*\\)"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    if (normalized.isBlank()) {
        return "Текст пустой."
    }

    val sentences = Regex("[^.!?]+[.!?]?")
        .findAll(normalized)
        .map { it.value.trim() }
        .filter { it.isNotBlank() }
        .toList()

    val selectedText = sentences
        .take(2)
        .joinToString(separator = " ")
        .ifBlank { normalized }

    val words = selectedText.split(Regex("\\s+")).filter { it.isNotBlank() }
    val shortened = if (words.size > 42) {
        words.take(42).joinToString(separator = " ") + "..."
    } else {
        selectedText
    }

    return "Коротко: $shortened"
}
