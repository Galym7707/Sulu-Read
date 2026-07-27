package com.example.sulu_read

import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.res.stringResource
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
import com.example.sulu_read.audio.applyNaturalVoice
import com.example.sulu_read.audio.detectSpeechLanguageCode
import com.example.sulu_read.audio.speakCompat
import com.example.sulu_read.domain.model.AppLanguage
import com.example.sulu_read.domain.model.ReaderDisplayPreferences
import com.example.sulu_read.ui.screens.AiHelpState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

data class SyllableWord(
    val original: String,
    val syllables: List<String>,
    val languageHint: String? = null
)

private const val DEFAULT_LETTER_SPACING = 1.5f
private const val DEFAULT_LINE_HEIGHT = 34f
private const val NO_PLAYING_WORD = -1

private val DeepBlueBlack = Color(0xFF1A237E)
private val DarkSlateGray = Color(0xFF37474F)
private val SyllableAlternateColor = Color(0xFF8A5A00)
private val PlaybackHighlight = Color(0xFFFFF9C4)
private val RulerHighlight = Color(0xFFFFD166).copy(alpha = 0.26f)
private val RulerEdge = Color(0xFF8A6D1D).copy(alpha = 0.42f)
private val ControlPanelBackground = Color(0xFFFFFCF4)
private val ControlPanelBorder = Color(0xFFCFE3D4)

@Stable
private class ReadingScreenState(
    letterSpacing: Float = DEFAULT_LETTER_SPACING,
    lineHeight: Float = DEFAULT_LINE_HEIGHT,
    isReadingRulerEnabled: Boolean = false,
    rulerYOffset: Float = 0f,
    currentPlayingWordIndex: Int = NO_PLAYING_WORD,
    simplifiedTextSnippet: String? = null,
    isSimplifyingText: Boolean = false,
    simplificationError: String? = null,
    simplificationSource: String? = null,
    showSyllableBreaks: Boolean = true,
    colorSyllables: Boolean = true,
    useOriginalWords: Boolean = false
) {
    var letterSpacing by mutableFloatStateOf(letterSpacing)
    var lineHeight by mutableFloatStateOf(lineHeight)
    var isReadingRulerEnabled by mutableStateOf(isReadingRulerEnabled)
    var rulerYOffset by mutableFloatStateOf(rulerYOffset)
    var currentPlayingWordIndex by mutableIntStateOf(currentPlayingWordIndex)
    var simplifiedTextSnippet by mutableStateOf(simplifiedTextSnippet)
    var isSimplifyingText by mutableStateOf(isSimplifyingText)
    var simplificationError by mutableStateOf(simplificationError)
    var simplificationSource by mutableStateOf(simplificationSource)
    var showSyllableBreaks by mutableStateOf(showSyllableBreaks)
    var colorSyllables by mutableStateOf(colorSyllables)
    var useOriginalWords by mutableStateOf(useOriginalWords)
}

private val ReadingScreenStateSaver: Saver<ReadingScreenState, Any> = listSaver(
    save = { state ->
        listOf(
            state.letterSpacing,
            state.lineHeight,
            state.isReadingRulerEnabled,
            state.rulerYOffset,
            state.currentPlayingWordIndex,
            state.simplifiedTextSnippet,
            state.showSyllableBreaks,
            state.colorSyllables,
            state.useOriginalWords
        )
    },
    restore = { restored ->
        ReadingScreenState(
            letterSpacing = restored[0] as Float,
            lineHeight = restored[1] as Float,
            isReadingRulerEnabled = restored[2] as Boolean,
            rulerYOffset = restored[3] as Float,
            currentPlayingWordIndex = restored[4] as Int,
            simplifiedTextSnippet = restored[5] as String?,
            showSyllableBreaks = restored[6] as Boolean,
            colorSyllables = restored[7] as Boolean,
            useOriginalWords = restored[8] as Boolean
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
    backendWords: List<SyllableWord> = emptyList(),
    onSimplifyText: suspend (String) -> String,
    aiHelpState: AiHelpState = AiHelpState.Idle,
    onExplainTextWithAi: (String) -> Unit = {},
    onDismissAiHelp: () -> Unit = {},
    languageCode: String,
    readerDisplayPreferences: ReaderDisplayPreferences = ReaderDisplayPreferences(),
    onReaderDisplayPreferencesChange: (ReaderDisplayPreferences) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state = rememberSaveable(text, saver = ReadingScreenStateSaver) {
        ReadingScreenState(
            showSyllableBreaks = readerDisplayPreferences.showSyllableBreaks,
            colorSyllables = readerDisplayPreferences.colorSyllables,
            useOriginalWords = false
        )
    }
    val coroutineScope = rememberCoroutineScope()
    val simplifyErrorMessage = stringResource(R.string.reader_simplify_error)
    val paragraphs = remember(text, backendWords) { buildReadingParagraphs(text, backendWords) }
    val words = remember(paragraphs) { paragraphs.flatMap { paragraph -> paragraph.words } }
    val ttsController = rememberTextToSpeechController(
        words = words,
        state = state,
        languageCode = languageCode
    )

    fun requestSimplification(source: String) {
        state.simplificationSource = source
        state.isSimplifyingText = true
        state.simplifiedTextSnippet = ""
        state.simplificationError = null
        coroutineScope.launch {
            runCatching { onSimplifyText(source) }
                .onSuccess {
                    state.simplifiedTextSnippet = it
                    state.simplificationError = null
                }
                .onFailure {
                    state.simplificationError = simplifyErrorMessage
                }
            state.isSimplifyingText = false
        }
    }

    LaunchedEffect(readerDisplayPreferences) {
        state.showSyllableBreaks = readerDisplayPreferences.showSyllableBreaks
        state.colorSyllables = readerDisplayPreferences.colorSyllables
        state.useOriginalWords = false
    }

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
            onStop = { ttsController.stop() },
            onShowSyllableBreaksChange = { enabled ->
                state.showSyllableBreaks = enabled
                onReaderDisplayPreferencesChange(state.toReaderDisplayPreferences())
            },
            onColorSyllablesChange = { enabled ->
                state.colorSyllables = enabled
                onReaderDisplayPreferencesChange(state.toReaderDisplayPreferences())
            },
            onAiHelpClick = { onExplainTextWithAi(text) }
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
                onSimplifyRequest = ::requestSimplification
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
    if (simplifiedText != null || state.isSimplifyingText || state.simplificationError != null) {
        SimplifiedTextSheet(
            text = simplifiedText.orEmpty(),
            isLoading = state.isSimplifyingText,
            errorMessage = state.simplificationError,
            onRetry = state.simplificationSource?.let { source ->
                { requestSimplification(source) }
            },
            onDismissRequest = {
                state.simplifiedTextSnippet = null
                state.simplificationError = null
                state.simplificationSource = null
                state.isSimplifyingText = false
            }
        )
    }

    if (aiHelpState !is AiHelpState.Idle) {
        AiHelpSheet(
            state = aiHelpState,
            onDismissRequest = onDismissAiHelp
        )
    }
}

@Composable
private fun ReadingControls(
    state: ReadingScreenState,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onShowSyllableBreaksChange: (Boolean) -> Unit,
    onColorSyllablesChange: (Boolean) -> Unit,
    onAiHelpClick: () -> Unit
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
            label = stringResource(R.string.reader_letter_spacing),
            value = state.letterSpacing,
            valueRange = 0f..10f,
            valueSuffix = "sp",
            onValueChange = { state.letterSpacing = it.coerceIn(0f, 10f) }
        )

        ReadingSlider(
            label = stringResource(R.string.reader_line_height),
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
                    text = stringResource(R.string.reader_read_aloud),
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
                        contentDescription = stringResource(R.string.reader_start_reading),
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
                        contentDescription = stringResource(R.string.reader_stop_reading),
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
                text = stringResource(R.string.reader_ruler),
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

        ReadingSwitch(
            label = stringResource(R.string.reader_show_syllable_breaks),
            checked = state.showSyllableBreaks,
            onCheckedChange = onShowSyllableBreaksChange
        )
        ReadingSwitch(
            label = stringResource(R.string.reader_color_syllables),
            checked = state.colorSyllables,
            onCheckedChange = onColorSyllablesChange
        )

        Button(
            onClick = onAiHelpClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = stringResource(R.string.reader_ai_help))
        }
    }
}

@Composable
private fun ReadingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
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
        text = remember(
            indexedWord.value,
            state.showSyllableBreaks,
            state.colorSyllables,
            state.useOriginalWords
        ) {
            indexedWord.value.toAnnotatedSyllables(
                showSyllableBreaks = state.showSyllableBreaks,
                colorSyllables = state.colorSyllables,
                useOriginalWords = state.useOriginalWords
            )
        },
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

private fun SyllableWord.toAnnotatedSyllables(
    showSyllableBreaks: Boolean,
    colorSyllables: Boolean,
    useOriginalWords: Boolean
) = buildAnnotatedString {
    if (useOriginalWords || (!showSyllableBreaks && !colorSyllables)) {
        withStyle(SpanStyle(color = DeepBlueBlack)) {
            append(original)
        }
        return@buildAnnotatedString
    }

    val safeSyllables = syllables.ifEmpty { listOf(original) }
    safeSyllables.forEachIndexed { index, syllable ->
        withStyle(
            SpanStyle(
                color = if (!colorSyllables || index % 2 == 0) DeepBlueBlack else SyllableAlternateColor
            )
        ) {
            append(syllable)
        }
        if (showSyllableBreaks && index < safeSyllables.lastIndex) {
            withStyle(SpanStyle(color = DarkSlateGray.copy(alpha = 0.58f))) {
                append("-")
            }
        }
    }
}

private fun ReadingScreenState.toReaderDisplayPreferences(): ReaderDisplayPreferences {
    return ReaderDisplayPreferences(
        showSyllableBreaks = showSyllableBreaks,
        colorSyllables = colorSyllables,
        useOriginalWords = false
    )
}

@Composable
private fun ReadingRulerOverlay(
    state: ReadingScreenState,
    modifier: Modifier = Modifier,
    clearWindowHeight: Dp = 44.dp
) {
    val density = LocalDensity.current
    val clearWindowHeightPx = with(density) { clearWindowHeight.toPx() }
    val contentDescription = stringResource(R.string.reader_ruler)

    Canvas(
        modifier = modifier
            .semantics {
                this.contentDescription = contentDescription
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
        val bandTop = (centerY - clearWindowHeightPx / 2f).coerceAtLeast(0f)
        val bandBottom = (centerY + clearWindowHeightPx / 2f).coerceAtMost(size.height)

        drawRect(
            color = RulerHighlight,
            topLeft = Offset(x = 0f, y = bandTop),
            size = Size(width = size.width, height = bandBottom - bandTop)
        )
        drawLine(
            color = RulerEdge,
            start = Offset(x = 0f, y = bandTop),
            end = Offset(x = size.width, y = bandTop),
            strokeWidth = 2f
        )
        drawLine(
            color = RulerEdge,
            start = Offset(x = 0f, y = bandBottom),
            end = Offset(x = size.width, y = bandBottom),
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
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: (() -> Unit)?,
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
                    text = stringResource(R.string.reader_simplify_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismissRequest) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.reader_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when {
                isLoading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(
                            text = stringResource(R.string.reader_simplify_loading),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                errorMessage != null -> {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 30.sp),
                        color = MaterialTheme.colorScheme.error
                    )
                    if (onRetry != null) {
                        Button(
                            onClick = onRetry,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(text = stringResource(R.string.try_again))
                        }
                    }
                }

                else -> {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            lineHeight = 32.sp,
                            letterSpacing = 0.4.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

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
                Text(text = stringResource(R.string.reader_close))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiHelpSheet(
    state: AiHelpState,
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
                    text = stringResource(R.string.reader_ai_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismissRequest) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.reader_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when (state) {
                AiHelpState.Idle -> Unit
                AiHelpState.Loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(
                            text = stringResource(R.string.reader_ai_loading),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                is AiHelpState.Error -> {
                    Text(
                        text = state.message.ifBlank { stringResource(R.string.reader_ai_error) },
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 30.sp),
                        color = MaterialTheme.colorScheme.error
                    )
                }

                is AiHelpState.Success -> {
                    Text(
                        text = state.result,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            lineHeight = 32.sp,
                            letterSpacing = 0.4.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

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
                Text(text = stringResource(R.string.reader_close))
            }
        }
    }
}

@Composable
private fun rememberTextToSpeechController(
    words: List<IndexedSyllableWord>,
    state: ReadingScreenState,
    languageCode: String
): TextToSpeechController {
    val context = LocalContext.current.applicationContext
    var textToSpeech by remember { mutableStateOf<TextToSpeech?>(null) }
    var isReady by remember { mutableStateOf(false) }
    var playbackRequest by remember { mutableIntStateOf(0) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val activeUtterance = remember { AtomicReference<UtteranceTracking?>(null) }
    val rangeCallbackSeen = remember { AtomicBoolean(false) }

    DisposableEffect(context, languageCode) {
        var disposed = false
        var engine: TextToSpeech? = null

        engine = TextToSpeech(context) { status ->
            if (disposed) {
                return@TextToSpeech
            }

            val initialized = status == TextToSpeech.SUCCESS
            if (initialized) {
                engine?.applyNaturalVoice(languageCode)
                engine?.setSpeechRate(0.88f)
                engine?.setPitch(1.0f)
                engine?.setOnUtteranceProgressListener(
                    buildUtteranceProgressListener(
                        activeUtterance = activeUtterance,
                        rangeCallbackSeen = rangeCallbackSeen,
                        mainHandler = mainHandler,
                        state = state
                    )
                )
            }

            isReady = initialized
        }
        textToSpeech = engine

        onDispose {
            disposed = true
            isReady = false
            activeUtterance.set(null)
            rangeCallbackSeen.set(false)
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
                activeUtterance.set(null)
                rangeCallbackSeen.set(false)
                textToSpeech?.stop()
                state.currentPlayingWordIndex = NO_PLAYING_WORD
            }
        )
    }

    LaunchedEffect(playbackRequest, isReady, words) {
        if (playbackRequest == 0 || words.isEmpty()) {
            return@LaunchedEffect
        }

        val startIndex = state.currentPlayingWordIndex
        if (startIndex !in words.indices) {
            textToSpeech?.stop()
            return@LaunchedEffect
        }

        if (!isReady) {
            return@LaunchedEffect
        }

        val tracking = buildUtteranceTracking(words, startIndex, languageCode)
        if (tracking.isEmpty) {
            state.currentPlayingWordIndex = NO_PLAYING_WORD
            return@LaunchedEffect
        }

        activeUtterance.set(tracking)
        rangeCallbackSeen.set(false)
        textToSpeech?.stop()
        textToSpeech?.speakTracking(tracking)

        // Not every Android TTS engine emits onRangeStart for every language or voice.
        // This timed loop keeps highlighting usable when exact per-word callbacks are unavailable.
        for (range in tracking.allRanges) {
            if (!isActive) {
                return@LaunchedEffect
            }
            if (activeUtterance.get() !== tracking) {
                return@LaunchedEffect
            }
            if (!rangeCallbackSeen.get()) {
                state.currentPlayingWordIndex = range.wordIndex
            }
            val word = words.getOrNull(range.wordIndex)?.value?.original.orEmpty()
            delay(estimateWordDurationMillis(word))
        }

        if (activeUtterance.compareAndSet(tracking, null)) {
            state.currentPlayingWordIndex = NO_PLAYING_WORD
        }
    }

    return controller
}

private data class TextToSpeechController(
    val isReady: Boolean,
    val playFrom: (Int) -> Unit,
    val stop: () -> Unit
)

private data class UtteranceTracking(
    val chunks: List<SpeechChunk>
) {
    val isEmpty: Boolean get() = chunks.all { it.text.isBlank() }
    val allRanges: List<WordTextRange> get() = chunks.flatMap { it.ranges }
    fun firstWordIndex(): Int? = allRanges.firstOrNull()?.wordIndex
    fun isLastUtterance(utteranceId: String?): Boolean {
        return utteranceId != null && chunks.lastOrNull()?.utteranceId == utteranceId
    }
}

private data class SpeechChunk(
    val utteranceId: String,
    val text: String,
    val languageCode: String,
    val ranges: List<WordTextRange>
)

private data class WordTextRange(
    val wordIndex: Int,
    val startInclusive: Int,
    val endExclusive: Int
)

private fun buildUtteranceTracking(
    words: List<IndexedSyllableWord>,
    startIndex: Int,
    fallbackLanguageCode: String
): UtteranceTracking {
    val chunks = mutableListOf<SpeechChunk>()
    val builder = StringBuilder()
    val ranges = mutableListOf<WordTextRange>()
    var currentLanguageCode: String? = null

    fun flushChunk() {
        val languageCode = currentLanguageCode
        val text = builder.toString().trim()
        if (!languageCode.isNullOrBlank() && text.isNotBlank()) {
            chunks += SpeechChunk(
                utteranceId = "sulu-read-${System.nanoTime()}-${chunks.size}",
                text = text,
                languageCode = languageCode,
                ranges = ranges.toList()
            )
        }
        builder.clear()
        ranges.clear()
    }

    words.drop(startIndex).forEach { word ->
        val wordLanguageCode = word.value.languageHint
            ?.takeIf { it in AppLanguage.supportedCodes }
            ?: detectSpeechLanguageCode(word.value.original, currentLanguageCode ?: fallbackLanguageCode)
        if (currentLanguageCode != null && currentLanguageCode != wordLanguageCode) {
            flushChunk()
        }
        currentLanguageCode = wordLanguageCode
        if (builder.isNotEmpty()) {
            builder.append(' ')
        }
        val start = builder.length
        builder.append(word.value.original)
        ranges += WordTextRange(
            wordIndex = word.index,
            startInclusive = start,
            endExclusive = builder.length
        )
    }
    flushChunk()

    return UtteranceTracking(chunks)
}

private fun buildUtteranceProgressListener(
    activeUtterance: AtomicReference<UtteranceTracking?>,
    rangeCallbackSeen: AtomicBoolean,
    mainHandler: Handler,
    state: ReadingScreenState
): UtteranceProgressListener {
    return object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val firstWord = activeUtterance.getMatchingChunk(utteranceId)
                ?.ranges
                ?.firstOrNull()
                ?.wordIndex
                ?: return
            mainHandler.postPlayingWord(state, firstWord)
        }

        override fun onDone(utteranceId: String?) {
            finishUtterance(utteranceId)
        }

        @Deprecated("Deprecated in Android framework, but still called by older TTS engines.")
        override fun onError(utteranceId: String?) {
            finishUtterance(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            finishUtterance(utteranceId)
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val chunk = activeUtterance.getMatchingChunk(utteranceId) ?: return
            val wordIndex = chunk.findWordIndex(start) ?: return
            rangeCallbackSeen.set(true)
            mainHandler.postPlayingWord(state, wordIndex)
        }

        private fun finishUtterance(utteranceId: String?) {
            val tracking = activeUtterance.get() ?: return
            if (!tracking.isLastUtterance(utteranceId)) {
                return
            }
            activeUtterance.compareAndSet(tracking, null)
            rangeCallbackSeen.set(false)
            mainHandler.postPlayingWord(state, NO_PLAYING_WORD)
        }
    }
}

private fun AtomicReference<UtteranceTracking?>.getMatchingChunk(utteranceId: String?): SpeechChunk? {
    if (utteranceId == null) {
        return null
    }
    return get()?.chunks?.firstOrNull { it.utteranceId == utteranceId }
}

private fun SpeechChunk.findWordIndex(rangeStart: Int): Int? {
    return ranges.firstOrNull { range ->
        rangeStart >= range.startInclusive && rangeStart < range.endExclusive
    }?.wordIndex ?: ranges.lastOrNull { range ->
        rangeStart >= range.startInclusive
    }?.wordIndex
}

private fun Handler.postPlayingWord(state: ReadingScreenState, wordIndex: Int) {
    post {
        state.currentPlayingWordIndex = wordIndex
    }
}

private fun TextToSpeech.speakTracking(tracking: UtteranceTracking) {
    tracking.chunks.forEachIndexed { index, chunk ->
        applyNaturalVoice(chunk.languageCode)
        speakCompat(
            text = chunk.text,
            queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            utteranceId = chunk.utteranceId
        )
    }
}

private fun estimateWordDurationMillis(word: String): Long {
    val readableLength = word.count { it.isLetterOrDigit() }.coerceAtLeast(1)
    return (280L + readableLength * 42L).coerceIn(460L, 1_350L)
}

private fun buildReadingParagraphs(
    text: String,
    backendWords: List<SyllableWord> = emptyList()
): List<ReadingParagraph> {
    if (backendWords.isNotEmpty()) {
        return listOf(
            ReadingParagraph(
                original = text,
                words = backendWords.mapIndexed { index, word ->
                    IndexedSyllableWord(index = index, value = word)
                }
            )
        )
    }

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
