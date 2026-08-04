package com.example.sulu_read

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.sulu_read.data.ApiClient
import com.example.sulu_read.data.PendingAttemptStore
import com.example.sulu_read.data.PendingAttemptSyncScheduler
import com.example.sulu_read.data.UserPreferences
import com.example.sulu_read.domain.model.AppLanguage
import com.example.sulu_read.domain.repository.SuluReadRepository
import com.example.sulu_read.focus.FocusReaderScreen
import com.example.sulu_read.ui.navigation.SuluReadNavGraph
import com.example.sulu_read.ui.screens.AiHelpState
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import kotlin.math.max

private const val CONNECT_TIMEOUT_MS = 20_000
private const val READ_TIMEOUT_MS = 270_000
private const val ADAPTATION_TIMEOUT_MS = 300_000L
private const val MAX_UPLOAD_IMAGE_SIDE = 1800

// Kept broad on purpose. Providers disagree about the type of an .epub or .fb2 - some report
// application/octet-stream - so the picker offers anything plausible and the backend decides by
// extension. A reader who cannot see their book in the picker has no way round it.
private val BOOK_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/epub+zip",
    "text/plain",
    "text/html",
    "application/octet-stream"
)
private const val UPLOAD_JPEG_QUALITY = 86

private val WarmCream = Color(0xFFFFFDF6)
private val DeepSageGreen = Color(0xFF2E6F40)
private val SoftMint = Color(0xFFEAF5ED)
private val SoftSageBorder = Color(0xFFCFE3D4)
private val TextPrimary = Color(0xFF2B2A24)
private val TextMuted = Color(0xFF6A665D)
private val FieldSurface = Color(0xFFFFFCF4)
private val WelcomeSurface = Color(0xFFFFF7E7)
private val WarningSurface = Color(0xFFFFF3DF)

private val SuluReadColorScheme = lightColorScheme(
    primary = DeepSageGreen,
    onPrimary = Color.White,
    primaryContainer = SoftMint,
    onPrimaryContainer = TextPrimary,
    secondary = Color(0xFF6C8F73),
    onSecondary = Color.White,
    secondaryContainer = SoftMint,
    onSecondaryContainer = TextPrimary,
    background = WarmCream,
    onBackground = TextPrimary,
    surface = WarmCream,
    onSurface = TextPrimary,
    surfaceVariant = SoftMint,
    onSurfaceVariant = TextMuted,
    outline = SoftSageBorder,
    outlineVariant = SoftSageBorder
)

private val SuluReadTypography = Typography(
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 42.sp,
        lineHeight = 50.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 29.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    )
)

private val SuluReadShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private enum class DocumentSource {
    Camera,
    Gallery,
    File
}

/** A book in the ready-made library that ships with the backend. */
data class CatalogBook(
    val id: String,
    val title: String,
    val author: String,
    val language: String,
    val grade: Int,
    val pageCount: Int,
    val wordCount: Int,
    val sourceUrl: String,
    // The works are public domain; the Wikisource editions they came from are CC BY-SA. Both
    // travel with the book so the app can credit them.
    val workLicense: String,
    val editionLicense: String
)

/** A picture for a tapped noun, with the credit its licence requires. */
data class WordPicture(
    val word: String,
    val imageUrl: String,
    val attribution: String,
    val licenseName: String
)

/** One page of an uploaded book, as the backend divided it. */
data class BookPage(
    val pageNumber: Int,
    val text: String,
    val wordCount: Int
)

private sealed interface AppState {
    data object Home : AppState
    data object CameraScan : AppState
    data object Loading : AppState
    data class Reading(
        val adaptedText: String,
        val originalText: String,
        val source: String,
        val wordCount: Int,
        val title: String?,
        val words: List<ReaderWord>
    ) : AppState

    data object Catalog : AppState

    data class ReadingBook(
        val pages: List<BookPage>,
        val title: String?,
        // The file had more pages than the backend was allowed to return. Shown rather than
        // swallowed: a book that stops early with no explanation reads as a broken app.
        val truncated: Boolean,
        val ocrPageNumbers: List<Int>
    ) : AppState
}

private sealed interface PendingAdaptationRequest {
    data class Image(val uri: Uri) : PendingAdaptationRequest
    data class Url(val url: String) : PendingAdaptationRequest
    data class Book(val uri: Uri) : PendingAdaptationRequest
}

private sealed interface ApiResult {
    data class Success(
        val adaptedText: String,
        val originalText: String,
        val source: String,
        val wordCount: Int,
        val title: String?,
        val words: List<ReaderWord>
    ) : ApiResult

    data class Book(
        val pages: List<BookPage>,
        val title: String?,
        val truncated: Boolean,
        val ocrPageNumbers: List<Int>
    ) : ApiResult

    data class Error(val message: String) : ApiResult
}

private data class PreparedImageUpload(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SuluReadTheme {
                SuluReadApp()
            }
        }
    }
}

@Composable
private fun SuluReadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SuluReadColorScheme,
        typography = SuluReadTypography,
        shapes = SuluReadShapes,
        content = content
    )
}

@Composable
private fun SuluReadApp() {
    val baseContext = LocalContext.current
    val appContext = baseContext.applicationContext
    val repository = remember(appContext) {
        val preferences = UserPreferences(appContext)
        PendingAttemptSyncScheduler.schedulePeriodic(appContext)
        SuluReadRepository(
            api = ApiClient,
            preferences = preferences,
            pendingAttemptQueue = PendingAttemptStore(appContext),
            schedulePendingAttemptSync = {
                PendingAttemptSyncScheduler.enqueueImmediate(appContext)
            }
        )
    }
    val languageCode by repository.appLanguageCode.collectAsStateWithLifecycle(
        initialValue = UserPreferences.initialLanguageCode(appContext)
    )
    val localizedConfiguration = remember(languageCode) {
        Configuration(baseContext.resources.configuration).apply {
            setLocale(AppLanguage.localeFor(languageCode))
        }
    }
    val localizedContext = remember(baseContext, languageCode) {
        LocaleAwareContextWrapper(baseContext, AppLanguage.localeFor(languageCode))
    }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        androidx.compose.ui.platform.LocalConfiguration provides localizedConfiguration
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            SuluReadNavGraph(
                repository = repository,
                languageCode = languageCode
            )
        }
    }
}

private class LocaleAwareContextWrapper(
    base: Context,
    locale: Locale
) : ContextWrapper(base) {
    private val localizedContext: Context = base.createConfigurationContext(
        Configuration(base.resources.configuration).apply { setLocale(locale) }
    )

    override fun getResources() = localizedContext.resources
    override fun getAssets() = localizedContext.assets
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuluReadRoute(
    modifier: Modifier = Modifier,
    repository: SuluReadRepository,
    languageCode: String,
    onCreateTrainingFromText: (List<String>) -> Unit,
    aiHelpState: AiHelpState = AiHelpState.Idle,
    onExplainTextWithAi: (String) -> Unit = {},
    onRequestWordHint: (String) -> Unit = {},
    onDismissAiHelp: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var appState by remember { mutableStateOf<AppState>(AppState.Home) }
    var webLink by rememberSaveable { mutableStateOf("") }
    var showDocumentSheet by rememberSaveable { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var selectedDocumentUri by remember { mutableStateOf<Uri?>(null) }
    var selectedDocumentSource by remember { mutableStateOf<DocumentSource?>(null) }
    var documentStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRequest by remember { mutableStateOf<PendingAdaptationRequest?>(null) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var adaptationRunId by remember { mutableIntStateOf(0) }
    var adaptationJob by remember { mutableStateOf<Job?>(null) }
    var catalogBooks by remember { mutableStateOf<List<CatalogBook>>(emptyList()) }
    var isCatalogLoading by remember { mutableStateOf(false) }

    fun cancelAdaptation() {
        adaptationRunId += 1
        adaptationJob?.cancel()
        adaptationJob = null
        pendingRequest = null
        errorMessage = null
        appState = AppState.Home
    }

    fun runAdaptation(request: PendingAdaptationRequest) {
        adaptationRunId += 1
        val runId = adaptationRunId
        adaptationJob?.cancel()
        pendingRequest = request
        errorMessage = null
        appState = AppState.Loading

        adaptationJob = coroutineScope.launch {
            val result = withTimeoutOrNull(ADAPTATION_TIMEOUT_MS) {
                when (request) {
                    is PendingAdaptationRequest.Image -> SuluReadApiClient.adaptImage(
                        context = context,
                        uri = request.uri,
                        languageHint = AppLanguage.backendHintFor(languageCode)
                    )

                    is PendingAdaptationRequest.Url -> SuluReadApiClient.adaptUrl(
                        context = context,
                        url = request.url,
                        languageHint = AppLanguage.backendHintFor(languageCode)
                    )

                    is PendingAdaptationRequest.Book -> SuluReadApiClient.adaptFile(
                        context = context,
                        uri = request.uri,
                        languageHint = AppLanguage.backendHintFor(languageCode)
                    )
                }
            } ?: ApiResult.Error(context.getString(R.string.api_adaptation_timeout))

            if (runId != adaptationRunId) {
                return@launch
            }
            adaptationJob = null

            when (result) {
                is ApiResult.Success -> {
                    pendingRequest = null
                    appState = AppState.Reading(
                        adaptedText = result.adaptedText,
                        originalText = result.originalText,
                        source = result.source,
                        wordCount = result.wordCount,
                        title = result.title,
                        words = result.words
                    )
                }

                is ApiResult.Book -> {
                    pendingRequest = null
                    appState = AppState.ReadingBook(
                        pages = result.pages,
                        title = result.title,
                        truncated = result.truncated,
                        ocrPageNumbers = result.ocrPageNumbers
                    )
                }

                is ApiResult.Error -> {
                    errorMessage = result.message
                    appState = AppState.Home
                }
            }
        }
    }

    fun processImage(uri: Uri, source: DocumentSource) {
        selectedDocumentUri = uri
        selectedDocumentSource = source
        documentStatus = when (source) {
            DocumentSource.Camera -> context.getString(R.string.document_status_camera_ready)
            DocumentSource.Gallery -> context.getString(R.string.document_status_gallery_ready)
            // Book files never reach here: they go straight to runAdaptation from the picker,
            // because they must not be re-encoded as an image.
            DocumentSource.File -> context.getString(R.string.document_status_file_ready)
        }
        runAdaptation(PendingAdaptationRequest.Image(uri))
    }

    fun showHomeMessage(message: String) {
        documentStatus = message
        appState = AppState.Home
    }

    val documentScannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val scannedUri = scanningResult
                ?.pages
                ?.firstOrNull()
                ?.imageUri

            if (scannedUri != null) {
                processImage(scannedUri, DocumentSource.Camera)
            } else {
                showHomeMessage(context.getString(R.string.scanner_no_photo_short))
                errorMessage = context.getString(R.string.scanner_no_photo_error)
            }
        } else {
            showHomeMessage(context.getString(R.string.scanning_cancelled))
        }
    }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            processImage(uri, DocumentSource.Gallery)
        } else {
            showHomeMessage(context.getString(R.string.photo_not_selected))
        }
    }

    val bookPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedDocumentUri = uri
            selectedDocumentSource = DocumentSource.File
            documentStatus = context.getString(R.string.document_status_file_ready)
            runAdaptation(PendingAdaptationRequest.Book(uri))
        } else {
            showHomeMessage(context.getString(R.string.file_not_selected))
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { saved ->
        val uri = pendingCameraUri
        if (saved && uri != null) {
            processImage(uri, DocumentSource.Camera)
        } else {
            showHomeMessage(context.getString(R.string.picture_not_taken))
        }
        pendingCameraUri = null
    }

    fun launchCameraCapture() {
        val uri = runCatching { createTextbookPhotoUri(context) }
            .getOrElse {
                showHomeMessage(context.getString(R.string.camera_file_error))
                errorMessage = context.getString(R.string.camera_file_error)
                return
            }

        pendingCameraUri = uri
        appState = AppState.CameraScan
        takePictureLauncher.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCameraCapture()
        } else {
            showHomeMessage(context.getString(R.string.camera_denied_short))
            errorMessage = context.getString(R.string.camera_denied_error)
        }
    }

    fun launchMediaPicker() {
        appState = AppState.CameraScan
        mediaPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    fun launchDocumentScanner() {
        showDocumentSheet = false
        appState = AppState.CameraScan
        errorMessage = null
        val activity = context.findActivity()
        if (activity == null) {
            showHomeMessage(context.getString(R.string.document_scanner_open_error))
            errorMessage = context.getString(R.string.document_scanner_open_error)
            return
        }

        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                documentScannerLauncher.launch(
                    IntentSenderRequest.Builder(intentSender).build()
                )
            }
            .addOnFailureListener {
                appState = AppState.CameraScan
                if (hasPermission(context, Manifest.permission.CAMERA)) {
                    launchCameraCapture()
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasMediaImageAccess(context)) {
            launchMediaPicker()
        } else {
            showHomeMessage(context.getString(R.string.media_denied_short))
            errorMessage = context.getString(R.string.media_denied_error)
        }
    }

    fun requestCamera() {
        launchDocumentScanner()
    }

    fun requestMedia() {
        showDocumentSheet = false
        errorMessage = null
        val permissions = mediaImagePermissions()
        if (permissions.isEmpty() || hasMediaImageAccess(context)) {
            launchMediaPicker()
        } else {
            appState = AppState.CameraScan
            mediaPermissionLauncher.launch(permissions)
        }
    }

    fun adaptCurrentInput() {
        val link = webLink.trim()
        if (link.isBlank()) {
            errorMessage = context.getString(R.string.input_missing_link_or_scan)
            return
        }

        selectedDocumentUri = null
        selectedDocumentSource = null
        documentStatus = null
        runAdaptation(PendingAdaptationRequest.Url(link))
    }

    when (val currentState = appState) {
        AppState.Home -> {
            SuluReadHomeScreen(
                modifier = modifier,
                webLink = webLink,
                onWebLinkChange = {
                    webLink = it
                    if (it.isNotBlank()) {
                        selectedDocumentUri = null
                        selectedDocumentSource = null
                        documentStatus = null
                    }
                    errorMessage = null
                },
                onClearWebLink = {
                    webLink = ""
                    errorMessage = null
                },
                onScanClick = { showDocumentSheet = true },
                selectedDocumentUri = selectedDocumentUri,
                selectedDocumentSource = selectedDocumentSource,
                documentStatus = documentStatus,
                isLoading = false,
                onAdaptClick = ::adaptCurrentInput
            )
        }

        AppState.CameraScan -> {
            CameraScanScreen(
                modifier = modifier,
                onCancel = {
                    pendingCameraUri = null
                    showDocumentSheet = false
                    appState = AppState.Home
                }
            )
        }

        AppState.Loading -> {
            LoadingScreen(
                modifier = modifier,
                onCancel = ::cancelAdaptation
            )
        }

        AppState.Catalog -> {
            CatalogScreen(
                modifier = modifier,
                books = catalogBooks,
                isLoading = isCatalogLoading,
                onOpenBook = { book ->
                    appState = AppState.Loading
                    coroutineScope.launch {
                        when (val result = SuluReadApiClient.fetchCatalogBook(context, book.id)) {
                            is ApiResult.Book -> appState = AppState.ReadingBook(
                                pages = result.pages,
                                title = book.title,
                                truncated = false,
                                ocrPageNumbers = emptyList()
                            )

                            is ApiResult.Error -> {
                                errorMessage = result.message
                                appState = AppState.Catalog
                            }

                            is ApiResult.Success -> appState = AppState.Catalog
                        }
                    }
                },
                onBackHome = { appState = AppState.Home }
            )
        }

        is AppState.ReadingBook -> {
            BookReadingScreen(
                modifier = modifier,
                state = currentState,
                repository = repository,
                languageCode = languageCode,
                aiHelpState = aiHelpState,
                onExplainTextWithAi = onExplainTextWithAi,
                onRequestWordHint = onRequestWordHint,
                onDismissAiHelp = onDismissAiHelp,
                onCreateTrainingFromText = onCreateTrainingFromText,
                onBackHome = {
                    selectedDocumentUri = null
                    selectedDocumentSource = null
                    documentStatus = null
                    pendingRequest = null
                    errorMessage = null
                    appState = AppState.Home
                }
            )
        }

        is AppState.Reading -> {
            ReadingScreen(
                modifier = modifier,
                state = currentState,
                repository = repository,
                languageCode = languageCode,
                aiHelpState = aiHelpState,
                onExplainTextWithAi = onExplainTextWithAi,
                onRequestWordHint = onRequestWordHint,
                onDismissAiHelp = onDismissAiHelp,
                onCreateTrainingFromText = onCreateTrainingFromText,
                onBackHome = {
                    selectedDocumentUri = null
                    selectedDocumentSource = null
                    documentStatus = null
                    pendingRequest = null
                    errorMessage = null
                    appState = AppState.Home
                }
            )
        }
    }

    if (showDocumentSheet) {
        DocumentSourceSheet(
            onDismissRequest = { showDocumentSheet = false },
            onCameraClick = ::requestCamera,
            onGalleryClick = ::requestMedia,
            onFileClick = {
                showDocumentSheet = false
                bookPickerLauncher.launch(BOOK_MIME_TYPES)
            },
            onCatalogClick = {
                showDocumentSheet = false
                appState = AppState.Catalog
                isCatalogLoading = true
                coroutineScope.launch {
                    catalogBooks = SuluReadApiClient.fetchCatalog(
                        AppLanguage.backendHintFor(languageCode)
                    )
                    isCatalogLoading = false
                }
            }
        )
    }

    val visibleError = errorMessage
    if (visibleError != null) {
        ProcessingErrorDialog(
            message = visibleError,
            canRetry = pendingRequest != null,
            onRetry = {
                val retryRequest = pendingRequest
                if (retryRequest != null) {
                    runAdaptation(retryRequest)
                } else {
                    errorMessage = null
                }
            },
            onCancel = ::cancelAdaptation
        )
    }
}

@Composable
private fun SuluReadHomeScreen(
    modifier: Modifier = Modifier,
    webLink: String,
    onWebLinkChange: (String) -> Unit,
    onClearWebLink: () -> Unit,
    onScanClick: () -> Unit,
    selectedDocumentUri: Uri?,
    selectedDocumentSource: DocumentSource?,
    documentStatus: String?,
    isLoading: Boolean,
    onAdaptClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HeaderSection()
        ScanTextbookCard(onClick = onScanClick)
        DocumentStatusCard(
            selectedDocumentUri = selectedDocumentUri,
            selectedDocumentSource = selectedDocumentSource,
            documentStatus = documentStatus
        )
        WebLinkAccessibilitySection(
            webLink = webLink,
            onWebLinkChange = onWebLinkChange,
            onClearWebLink = onClearWebLink,
            onAdaptClick = onAdaptClick,
            isLoading = isLoading
        )
    }
}

@Composable
private fun CameraScanScreen(
    modifier: Modifier = Modifier,
    onCancel: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(56.dp),
            color = DeepSageGreen,
            strokeWidth = 5.dp
        )

        Spacer(modifier = Modifier.height(22.dp))

        Text(
            text = stringResource(R.string.camera_scan_instruction),
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(18.dp))

        TextButton(onClick = onCancel) {
            Text(text = stringResource(R.string.common_back))
        }
    }
}

@Composable
private fun LoadingScreen(
    modifier: Modifier = Modifier,
    onCancel: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(64.dp),
            color = DeepSageGreen,
            strokeWidth = 5.dp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.loading_adaptation),
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(18.dp))
        TextButton(onClick = onCancel) {
            Text(text = stringResource(R.string.cancel))
        }
    }
}

/**
 * The ready-made library.
 *
 * These books ship with the backend already divided into pages, so opening one is a lookup
 * rather than an upload: there is nothing to photograph, no OCR, and no waiting. Everything in
 * here is public domain, and each book carries where its text came from.
 */
@Composable
private fun CatalogScreen(
    modifier: Modifier = Modifier,
    books: List<CatalogBook>,
    isLoading: Boolean,
    onOpenBook: (CatalogBook) -> Unit,
    onBackHome: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.catalog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary
                )
                Text(
                    text = stringResource(R.string.catalog_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            TextButton(onClick = onBackHome) {
                Text(text = stringResource(R.string.reader_back_home))
            }
        }

        when {
            isLoading -> Text(
                text = stringResource(R.string.catalog_loading),
                style = MaterialTheme.typography.bodyLarge,
                color = TextMuted
            )

            books.isEmpty() -> Text(
                text = stringResource(R.string.catalog_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = TextMuted
            )

            else -> books.forEach { book ->
                CatalogBookCard(book = book, onClick = { onOpenBook(book) })
            }
        }
    }
}

@Composable
private fun CatalogBookCard(book: CatalogBook, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = FieldSurface),
        border = BorderStroke(1.dp, SoftSageBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
            Text(
                text = book.author,
                style = MaterialTheme.typography.bodyLarge,
                color = TextMuted
            )
            Text(
                text = stringResource(R.string.catalog_book_meta, book.grade, book.pageCount),
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted
            )
            Text(
                text = stringResource(
                    R.string.catalog_source_note,
                    book.workLicense,
                    book.editionLicense
                ),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
    }
}

/**
 * An uploaded book, one page at a time.
 *
 * The page is the unit the reader moves through, so it is deliberately not one long scroll: a
 * child who loses their place in a 200-page document has no way back to it, whereas a page
 * number is somewhere to return to. Each page is handed to the ordinary reader, so the focus
 * mode, the AI help and the training builder all work on a book exactly as on a photo.
 */
@Composable
private fun BookReadingScreen(
    modifier: Modifier = Modifier,
    state: AppState.ReadingBook,
    repository: SuluReadRepository,
    languageCode: String,
    aiHelpState: AiHelpState,
    onExplainTextWithAi: (String) -> Unit,
    onRequestWordHint: (String) -> Unit,
    onDismissAiHelp: () -> Unit,
    onCreateTrainingFromText: (List<String>) -> Unit,
    onBackHome: () -> Unit
) {
    var pageIndex by rememberSaveable(state.pages.size) { mutableIntStateOf(0) }
    val page = state.pages.getOrNull(pageIndex) ?: return
    val isOcrPage = page.pageNumber in state.ocrPageNumbers

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title ?: stringResource(R.string.book_reader_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    maxLines = 2
                )
                Text(
                    text = stringResource(
                        R.string.book_page_of,
                        page.pageNumber,
                        state.pages.size
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            TextButton(onClick = onBackHome) {
                Text(text = stringResource(R.string.reader_back_home))
            }
        }

        if (isOcrPage) {
            Text(
                text = stringResource(R.string.book_page_from_scan),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
        if (state.truncated) {
            Text(
                text = stringResource(R.string.book_truncated_notice, state.pages.size),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            ReadingScreen(
                state = AppState.Reading(
                    adaptedText = page.text,
                    originalText = page.text,
                    source = "file",
                    wordCount = page.wordCount,
                    title = null,
                    words = emptyList()
                ),
                repository = repository,
                languageCode = languageCode,
                aiHelpState = aiHelpState,
                onExplainTextWithAi = onExplainTextWithAi,
                onRequestWordHint = onRequestWordHint,
                onDismissAiHelp = onDismissAiHelp,
                onCreateTrainingFromText = onCreateTrainingFromText,
                onBackHome = onBackHome,
                showHeader = false
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { pageIndex = max(0, pageIndex - 1) },
                enabled = pageIndex > 0,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(text = stringResource(R.string.book_previous_page))
            }
            Button(
                onClick = { pageIndex = minOf(state.pages.lastIndex, pageIndex + 1) },
                enabled = pageIndex < state.pages.lastIndex,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(text = stringResource(R.string.book_next_page))
            }
        }
    }
}

@Composable
private fun ReadingScreen(
    modifier: Modifier = Modifier,
    state: AppState.Reading,
    repository: SuluReadRepository,
    languageCode: String,
    aiHelpState: AiHelpState,
    onExplainTextWithAi: (String) -> Unit,
    onRequestWordHint: (String) -> Unit,
    onDismissAiHelp: () -> Unit,
    onCreateTrainingFromText: (List<String>) -> Unit,
    onBackHome: () -> Unit,
    // A page of a book already has its own title and page counter above it, so the reader's own
    // header would be a second one saying almost the same thing.
    showHeader: Boolean = true
) {
    val coroutineScope = rememberCoroutineScope()
    var isFocusMode by rememberSaveable(state.adaptedText) { mutableStateOf(false) }
    val sourceText = when (state.source) {
        "image" -> stringResource(R.string.reading_source_image)
        "url" -> stringResource(R.string.reading_source_url)
        else -> stringResource(R.string.reading_source_material)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (showHeader) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = state.title ?: stringResource(R.string.reader_adapted_text_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.reader_source_summary, sourceText, state.wordCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                TextButton(onClick = onBackHome) {
                    Text(text = stringResource(R.string.reader_back_home))
                }
            }
        }

        Button(
            onClick = { onCreateTrainingFromText(extractTrainingWords(state.originalText.ifBlank { state.adaptedText })) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(text = stringResource(R.string.reader_create_training_from_text))
        }

        TextButton(onClick = { isFocusMode = !isFocusMode }) {
            Text(
                text = stringResource(
                    if (isFocusMode) R.string.focus_mode_exit else R.string.focus_mode_enter
                )
            )
        }

        if (isFocusMode) {
            // Focus mode is fed the original text, not the adapted one: scene splitting needs
            // real punctuation, and only the original is guaranteed to carry it.
            FocusReaderScreen(
                text = state.originalText.ifBlank { state.adaptedText },
                languageCode = languageCode,
                aiHelpState = aiHelpState,
                onRequestMeaningHint = onRequestWordHint,
                onDismissHint = onDismissAiHelp,
                onCollectTriggerWords = onCreateTrainingFromText
            )
        } else {
            PremiumReadingScreen(
                text = state.adaptedText,
                backendWords = state.words,
                onSimplifyText = { source -> repository.simplify(source, languageCode) },
                aiHelpState = aiHelpState,
                onExplainTextWithAi = onExplainTextWithAi,
                onDismissAiHelp = onDismissAiHelp,
                languageCode = languageCode,
                onLookUpWordPicture = { word ->
                    SuluReadApiClient.fetchWordPicture(
                        word = word,
                        languageHint = AppLanguage.backendHintFor(languageCode)
                    )
                }
            )
        }
    }
}

@Composable
private fun ProcessingErrorDialog(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(text = stringResource(R.string.processing_error_title))
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onRetry) {
                Text(text = if (canRetry) stringResource(R.string.try_again) else stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = stringResource(R.string.cancel))
            }
        },
        containerColor = WarmCream,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary
    )
}

@Composable
private fun HeaderSection() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displayMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.home_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = TextMuted,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = WelcomeSurface),
            border = BorderStroke(1.dp, Color(0xFFF1DFC1)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(SoftMint),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = DeepSageGreen,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Text(
                    text = stringResource(R.string.home_support_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
            }
        }
    }
}

@Composable
private fun ScanTextbookCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 164.dp)
            .clickable(
                role = Role.Button,
                onClick = onClick
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SoftMint),
        border = BorderStroke(1.5.dp, DeepSageGreen.copy(alpha = 0.22f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(DeepSageGreen),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = stringResource(R.string.home_scan_textbook),
                    tint = Color.White,
                    modifier = Modifier.size(38.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.home_scan_textbook),
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.home_scan_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun DocumentStatusCard(
    selectedDocumentUri: Uri?,
    selectedDocumentSource: DocumentSource?,
    documentStatus: String?
) {
    if (selectedDocumentUri == null && documentStatus == null) {
        return
    }

    val isReady = selectedDocumentUri != null
    val containerColor = if (isReady) SoftMint else WarningSurface
    val borderColor = if (isReady) SoftSageBorder else Color(0xFFE9C98D)
    val iconColor = if (isReady) DeepSageGreen else Color(0xFF946B2D)
    val sourceText = when (selectedDocumentSource) {
        DocumentSource.Camera -> stringResource(R.string.document_status_source_camera)
        DocumentSource.Gallery -> stringResource(R.string.document_status_source_gallery)
        DocumentSource.File -> stringResource(R.string.document_status_source_file)
        null -> stringResource(R.string.document_status_no_photo)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(25.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = documentStatus.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    text = sourceText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentSourceSheet(
    onDismissRequest: () -> Unit,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onFileClick: () -> Unit,
    onCatalogClick: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = WarmCream,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.document_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary
            )
            Text(
                text = stringResource(R.string.document_sheet_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted
            )
            DocumentSourceAction(
                icon = Icons.Default.CameraAlt,
                title = stringResource(R.string.document_sheet_camera_title),
                subtitle = stringResource(R.string.document_sheet_camera_subtitle),
                onClick = onCameraClick
            )
            DocumentSourceAction(
                icon = Icons.Default.PhotoLibrary,
                title = stringResource(R.string.document_sheet_gallery_title),
                subtitle = stringResource(R.string.document_sheet_gallery_subtitle),
                onClick = onGalleryClick
            )
            DocumentSourceAction(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                title = stringResource(R.string.document_sheet_file_title),
                subtitle = stringResource(R.string.document_sheet_file_subtitle),
                onClick = onFileClick
            )
            DocumentSourceAction(
                icon = Icons.Default.AutoAwesome,
                title = stringResource(R.string.catalog_title),
                subtitle = stringResource(R.string.catalog_subtitle),
                onClick = onCatalogClick
            )
        }
    }
}

@Composable
private fun DocumentSourceAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClick = onClick
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SoftMint),
        border = BorderStroke(1.dp, SoftSageBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(DeepSageGreen),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
private fun WebLinkAccessibilitySection(
    webLink: String,
    onWebLinkChange: (String) -> Unit,
    onClearWebLink: () -> Unit,
    onAdaptClick: () -> Unit,
    isLoading: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        OutlinedTextField(
            value = webLink,
            onValueChange = onWebLinkChange,
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(text = stringResource(R.string.web_link_label))
            },
            placeholder = {
                Text(text = "https://")
            },
            trailingIcon = {
                if (webLink.isNotEmpty()) {
                    IconButton(onClick = onClearWebLink) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.web_link_clear),
                            tint = TextMuted
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = DeepSageGreen,
                unfocusedBorderColor = SoftSageBorder,
                focusedContainerColor = FieldSurface,
                unfocusedContainerColor = FieldSurface,
                cursorColor = DeepSageGreen,
                focusedLabelColor = DeepSageGreen,
                unfocusedLabelColor = TextMuted,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedPlaceholderColor = TextMuted,
                unfocusedPlaceholderColor = TextMuted
            )
        )

        Button(
            onClick = onAdaptClick,
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = DeepSageGreen,
                contentColor = Color.White,
                disabledContainerColor = DeepSageGreen.copy(alpha = 0.62f),
                disabledContentColor = Color.White
            ),
            contentPadding = PaddingValues(horizontal = 22.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = if (isLoading) stringResource(R.string.adapt_loading_short) else stringResource(R.string.adapt_text_button),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

private object SuluReadApiClient {
    suspend fun adaptUrl(context: Context, url: String, languageHint: String): ApiResult = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("url", url)
            .put("language_hint", languageHint)
            .toString()
            .toByteArray(Charsets.UTF_8)

        for (baseUrl in ApiClient.backendBaseUrls) {
            val result = runCatching {
                val connection = openPostConnection("$baseUrl/v1/adapt-url").apply {
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }

                try {
                    connection.outputStream.use { output ->
                        output.write(payload)
                    }
                    parseApiResponse(context, connection.responseCode, readResponseBody(connection))
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()

            if (result != null) {
                return@withContext result
            }
        }

        ApiResult.Error(connectionErrorMessage(context))
    }

    suspend fun adaptImage(context: Context, uri: Uri, languageHint: String): ApiResult = withContext(Dispatchers.IO) {
        val upload = runCatching { prepareImageUpload(context, uri) }
            .getOrElse {
                return@withContext ApiResult.Error(context.getString(R.string.image_prepare_error))
            }

        for (baseUrl in ApiClient.backendBaseUrls) {
            val result = runCatching {
                val boundary = "SuluReadBoundary-${UUID.randomUUID()}"
                val connection = openPostConnection("$baseUrl/v1/adapt-image").apply {
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    setRequestProperty("Accept", "application/json")
                }

                try {
                    BufferedOutputStream(connection.outputStream).use { output ->
                        output.writeUtf8("--$boundary\r\n")
                        output.writeUtf8(
                            "Content-Disposition: form-data; name=\"file\"; filename=\"${upload.fileName}\"\r\n"
                        )
                        output.writeUtf8("Content-Type: ${upload.mimeType}\r\n\r\n")
                        output.write(upload.bytes)
                        output.writeUtf8("\r\n")
                        output.writeUtf8("--$boundary\r\n")
                        output.writeUtf8("Content-Disposition: form-data; name=\"language_hint\"\r\n\r\n")
                        output.writeUtf8(languageHint)
                        output.writeUtf8("\r\n--$boundary--\r\n")
                        output.flush()
                    }

                    parseApiResponse(context, connection.responseCode, readResponseBody(connection))
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()

            if (result != null) {
                return@withContext result
            }
        }

        ApiResult.Error(connectionErrorMessage(context))
    }

    /**
     * Uploads a book file untouched.
     *
     * Deliberately not routed through [prepareImageUpload]: that re-encodes and downscales, which
     * is right for a photo and would corrupt a PDF. The bytes go up exactly as they are on disk.
     */
    suspend fun adaptFile(context: Context, uri: Uri, languageHint: String): ApiResult =
        withContext(Dispatchers.IO) {
            val fileName = runCatching { resolveFileName(context, uri) }.getOrElse { "book" }
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()

            if (bytes == null || bytes.isEmpty()) {
                return@withContext ApiResult.Error(context.getString(R.string.file_prepare_error))
            }

            for (baseUrl in ApiClient.backendBaseUrls) {
                val result = runCatching {
                    val boundary = "SuluReadBoundary-${UUID.randomUUID()}"
                    val connection = openPostConnection("$baseUrl/v1/adapt-file").apply {
                        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                        setRequestProperty("Accept", "application/json")
                    }

                    try {
                        BufferedOutputStream(connection.outputStream).use { output ->
                            output.writeUtf8("--$boundary\r\n")
                            output.writeUtf8(
                                "Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n"
                            )
                            output.writeUtf8("Content-Type: application/octet-stream\r\n\r\n")
                            output.write(bytes)
                            output.writeUtf8("\r\n")
                            output.writeUtf8("--$boundary\r\n")
                            output.writeUtf8("Content-Disposition: form-data; name=\"language_hint\"\r\n\r\n")
                            output.writeUtf8(languageHint)
                            output.writeUtf8("\r\n--$boundary--\r\n")
                            output.flush()
                        }

                        parseBookResponse(context, connection.responseCode, readResponseBody(connection))
                    } finally {
                        connection.disconnect()
                    }
                }.getOrNull()

                if (result != null) {
                    return@withContext result
                }
            }

            ApiResult.Error(connectionErrorMessage(context))
        }

    /** The ready-made library. Its books ship with the backend, so this is a plain GET. */
    suspend fun fetchCatalog(languageHint: String): List<CatalogBook> = withContext(Dispatchers.IO) {
        for (baseUrl in ApiClient.backendBaseUrls) {
            val books = runCatching {
                val connection = openGetConnection("$baseUrl/v1/catalog?language_hint=$languageHint")
                try {
                    val json = JSONObject(readResponseBody(connection))
                    val array = json.optJSONArray("books") ?: return@runCatching emptyList()
                    (0 until array.length()).mapNotNull { index ->
                        val item = array.optJSONObject(index) ?: return@mapNotNull null
                        CatalogBook(
                            id = item.optString("id").ifBlank { return@mapNotNull null },
                            title = item.optString("title"),
                            author = item.optString("author"),
                            language = item.optString("language"),
                            grade = item.optInt("grade"),
                            pageCount = item.optInt("page_count"),
                            wordCount = item.optInt("word_count"),
                            sourceUrl = item.optString("source_url"),
                            workLicense = item.optString("work_license"),
                            editionLicense = item.optString("edition_license")
                        )
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()

            if (books != null) {
                return@withContext books
            }
        }
        emptyList()
    }

    suspend fun fetchCatalogBook(context: Context, bookId: String): ApiResult = withContext(Dispatchers.IO) {
        for (baseUrl in ApiClient.backendBaseUrls) {
            val result = runCatching {
                val connection = openGetConnection("$baseUrl/v1/catalog/$bookId")
                try {
                    val json = JSONObject(readResponseBody(connection))
                    val array = json.optJSONArray("pages")
                    val pages = (0 until (array?.length() ?: 0)).mapNotNull { index ->
                        val page = array?.optJSONObject(index) ?: return@mapNotNull null
                        val text = page.optString("text").ifBlank { return@mapNotNull null }
                        BookPage(
                            pageNumber = page.optInt("page_number", index + 1),
                            text = text,
                            wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() }
                        )
                    }
                    if (pages.isEmpty()) {
                        null
                    } else {
                        ApiResult.Book(
                            pages = pages,
                            title = json.optString("title").ifBlank { null },
                            truncated = false,
                            ocrPageNumbers = emptyList()
                        )
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()

            if (result != null) {
                return@withContext result
            }
        }
        ApiResult.Error(connectionErrorMessage(context))
    }

    /**
     * A picture for a tapped word.
     *
     * Returns null for anything that is not a concrete noun, which is most words in any
     * sentence. That is an ordinary answer and the reader is shown nothing, rather than an
     * error they did not cause.
     */
    suspend fun fetchWordPicture(word: String, languageHint: String): WordPicture? =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(word, "UTF-8")
            for (baseUrl in ApiClient.backendBaseUrls) {
                val picture = runCatching {
                    val connection = openGetConnection(
                        "$baseUrl/v1/word-picture?word=$encoded&language_hint=$languageHint"
                    )
                    try {
                        val json = JSONObject(readResponseBody(connection))
                        if (!json.optBoolean("found")) {
                            return@runCatching null
                        }
                        WordPicture(
                            word = json.optString("word", word),
                            imageUrl = json.optString("image_url"),
                            attribution = json.optString("attribution"),
                            licenseName = json.optString("license_name")
                        )
                    } finally {
                        connection.disconnect()
                    }
                }.getOrNull()

                if (picture != null) {
                    return@withContext picture
                }
            }
            null
        }

    private fun openGetConnection(endpoint: String): HttpURLConnection {
        return (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
    }

    private fun parseBookResponse(context: Context, httpCode: Int, body: String): ApiResult {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return ApiResult.Error(connectionErrorMessage(context))

        if (json.optString("status") == "success") {
            val pagesJson = json.optJSONArray("pages")
            val pages = (0 until (pagesJson?.length() ?: 0)).mapNotNull { index ->
                val page = pagesJson?.optJSONObject(index) ?: return@mapNotNull null
                val text = page.optString("original_text").ifBlank { return@mapNotNull null }
                BookPage(
                    pageNumber = page.optInt("page_number", index + 1),
                    text = text,
                    wordCount = page.optInt("word_count", 0)
                )
            }
            if (pages.isNotEmpty()) {
                val ocrJson = json.optJSONArray("ocr_page_numbers")
                return ApiResult.Book(
                    pages = pages,
                    title = json.optString("title").ifBlank { null },
                    truncated = json.optBoolean("truncated", false),
                    ocrPageNumbers = (0 until (ocrJson?.length() ?: 0)).map { ocrJson!!.optInt(it) }
                )
            }
        }

        val serverMessage = json.optString("message").ifBlank {
            if (httpCode in 200..299) {
                context.getString(R.string.file_adaptation_failed)
            } else {
                connectionErrorMessage(context)
            }
        }
        return ApiResult.Error(serverMessage)
    }

    private fun prepareImageUpload(context: Context, uri: Uri): PreparedImageUpload {
        val fileName = sanitizeFileName(resolveFileName(context, uri)).let { name ->
            if (name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true)) {
                name
            } else {
                "${name.substringBeforeLast('.', name)}.jpg"
            }
        }

        val bitmap = decodeScaledBitmap(context, uri)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, UPLOAD_JPEG_QUALITY, output)
        bitmap.recycle()

        return PreparedImageUpload(
            fileName = fileName,
            mimeType = "image/jpeg",
            bytes = output.toByteArray()
        )
    }

    private fun decodeScaledBitmap(context: Context, uri: Uri): Bitmap {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, boundsOptions)
        }

        val sampleSize = calculateInSampleSize(
            width = boundsOptions.outWidth,
            height = boundsOptions.outHeight,
            maxSide = MAX_UPLOAD_IMAGE_SIDE
        )

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        }

        return decoded ?: throw IllegalArgumentException("Selected image could not be decoded")
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxSide: Int): Int {
        if (width <= 0 || height <= 0) {
            return 1
        }

        var sampleSize = 1
        var longestSide = max(width, height)
        while (longestSide / sampleSize > maxSide) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun openPostConnection(endpoint: String): HttpURLConnection {
        return (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doInput = true
            doOutput = true
            useCaches = false
        }
    }

    private fun parseApiResponse(context: Context, httpCode: Int, body: String): ApiResult {
        if (body.isBlank()) {
            return ApiResult.Error(connectionErrorMessage(context))
        }

        val json = runCatching { JSONObject(body) }
            .getOrElse { return ApiResult.Error(connectionErrorMessage(context)) }

        val status = json.optString("status")
        if (status == "success") {
            val adaptedText = json.optString("adapted_text")
            if (adaptedText.isBlank()) {
                return ApiResult.Error(context.getString(R.string.api_empty_adapted_text))
            }

            return ApiResult.Success(
                adaptedText = adaptedText,
                originalText = json.optString("original_text"),
                source = json.optString("source", "material"),
                wordCount = json.optInt("word_count", 0),
                title = json.optString("title").ifBlank { null },
                words = parseBackendReaderWords(json)
            )
        }

        val serverMessage = json.optString("message").ifBlank {
            if (httpCode in 200..299) {
                context.getString(R.string.api_adaptation_failed)
            } else {
                connectionErrorMessage(context)
            }
        }
        return ApiResult.Error(serverMessage)
    }

    private fun readResponseBody(connection: HttpURLConnection): String {
        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }

        return stream.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readText()
        }
    }

    private fun parseBackendReaderWords(json: JSONObject): List<ReaderWord> {
        val wordsJson = json.optJSONArray("words") ?: return emptyList()
        return (0 until wordsJson.length()).mapNotNull { index ->
            val item = wordsJson.optJSONObject(index) ?: return@mapNotNull null
            val original = item.optString("original").ifBlank { return@mapNotNull null }

            ReaderWord(
                original = original,
                languageHint = item.optString("language_hint").ifBlank { null }
            )
        }
    }

    private fun resolveFileName(context: Context, uri: Uri): String {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                val displayName = cursor.getString(index)
                if (!displayName.isNullOrBlank()) {
                    return displayName
                }
            }
        }
        return "textbook-page.jpg"
    }

    private fun sanitizeFileName(fileName: String): String {
        return fileName.replace(Regex("[\\r\\n\"\\\\]"), "_")
    }

    private fun BufferedOutputStream.writeUtf8(value: String) {
        write(value.toByteArray(Charsets.UTF_8))
    }

    private fun connectionErrorMessage(context: Context): String {
        return context.getString(R.string.api_connection_error)
    }
}

private fun hasPermission(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private fun mediaImagePermissions(): Array<String> {
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES
        )

        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private fun hasMediaImageAccess(context: Context): Boolean {
    return mediaImagePermissions().any { permission ->
        hasPermission(context, permission)
    }
}

private fun extractTrainingWords(text: String): List<String> {
    return Regex("[A-Za-zА-Яа-яЁёӘәҒғҚқҢңӨөҰұҮүҺһІі]+")
        .findAll(text)
        .map { it.value }
        .filter { it.length >= 4 }
        .distinctBy { it.lowercase() }
        .take(40)
        .toList()
}

private fun createTextbookPhotoUri(context: Context): Uri {
    val imageDirectory = File(context.cacheDir, "textbook_images").apply {
        mkdirs()
    }
    val imageFile = File.createTempFile(
        "sulu_read_textbook_",
        ".jpg",
        imageDirectory
    )
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        imageFile
    )
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFDF6)
@Composable
private fun SuluReadHomeScreenPreview() {
    SuluReadTheme {
        SuluReadHomeScreen(
            webLink = "",
            onWebLinkChange = {},
            onClearWebLink = {},
            onScanClick = {},
            selectedDocumentUri = null,
            selectedDocumentSource = null,
            documentStatus = null,
            isLoading = false,
            onAdaptClick = {}
        )
    }
}
