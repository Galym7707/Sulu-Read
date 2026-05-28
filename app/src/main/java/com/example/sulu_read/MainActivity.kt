package com.example.sulu_read

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
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
import androidx.compose.runtime.getValue
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
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.max

private val SULU_READ_API_BASE_URLS = listOf(
    "https://galym7707-sulu-read-backend.hf.space",
    "http://10.0.2.2:8000",
    "http://192.168.0.100:8000",
    "http://192.168.0.103:8000"
)
private const val CONNECT_TIMEOUT_MS = 20_000
private const val READ_TIMEOUT_MS = 240_000
private const val MAX_UPLOAD_IMAGE_SIDE = 1800
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
    Gallery
}

private sealed interface AppState {
    data object Home : AppState
    data object CameraScan : AppState
    data object Loading : AppState
    data class Reading(
        val adaptedText: String,
        val originalText: String,
        val source: String,
        val wordCount: Int,
        val title: String?
    ) : AppState
}

private sealed interface PendingAdaptationRequest {
    data class Image(val uri: Uri) : PendingAdaptationRequest
    data class Url(val url: String) : PendingAdaptationRequest
}

private sealed interface ApiResult {
    data class Success(
        val adaptedText: String,
        val originalText: String,
        val source: String,
        val wordCount: Int,
        val title: String?
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
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing
        ) { innerPadding ->
            SuluReadRoute(
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuluReadRoute(modifier: Modifier = Modifier) {
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

    fun runAdaptation(request: PendingAdaptationRequest) {
        pendingRequest = request
        errorMessage = null
        appState = AppState.Loading

        coroutineScope.launch {
            val result = when (request) {
                is PendingAdaptationRequest.Image -> SuluReadApiClient.adaptImage(
                    context = context.applicationContext,
                    uri = request.uri
                )

                is PendingAdaptationRequest.Url -> SuluReadApiClient.adaptUrl(request.url)
            }

            when (result) {
                is ApiResult.Success -> {
                    pendingRequest = null
                    appState = AppState.Reading(
                        adaptedText = result.adaptedText,
                        originalText = result.originalText,
                        source = result.source,
                        wordCount = result.wordCount,
                        title = result.title
                    )
                }

                is ApiResult.Error -> {
                    errorMessage = result.message
                    appState = AppState.Loading
                }
            }
        }
    }

    fun processImage(uri: Uri, source: DocumentSource) {
        selectedDocumentUri = uri
        selectedDocumentSource = source
        documentStatus = when (source) {
            DocumentSource.Camera -> "Страница учебника готова. Sulu-Read отправляет ее на обработку."
            DocumentSource.Gallery -> "Фото учебника загружено. Sulu-Read отправляет его на обработку."
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
                showHomeMessage("Сканер не вернул фото страницы.")
                errorMessage = "Сканер не вернул фото страницы. Попробуйте сделать снимок еще раз."
            }
        } else {
            showHomeMessage("Сканирование отменено.")
        }
    }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            processImage(uri, DocumentSource.Gallery)
        } else {
            showHomeMessage("Фото не выбрано.")
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { saved ->
        val uri = pendingCameraUri
        if (saved && uri != null) {
            processImage(uri, DocumentSource.Camera)
        } else {
            showHomeMessage("Снимок не был сделан.")
        }
        pendingCameraUri = null
    }

    fun launchCameraCapture() {
        val uri = runCatching { createTextbookPhotoUri(context) }
            .getOrElse {
                showHomeMessage("Не удалось подготовить файл для снимка.")
                errorMessage = "Не удалось подготовить файл для снимка."
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
            showHomeMessage("Доступ к камере не предоставлен.")
            errorMessage = "Разрешите доступ к камере, чтобы сфотографировать страницу учебника."
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
            showHomeMessage("Не удалось открыть сканер документа.")
            errorMessage = "Не удалось открыть сканер документа."
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
            showHomeMessage("Доступ к медиа не предоставлен.")
            errorMessage = "Разрешите доступ к фото, чтобы загрузить страницу учебника."
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
            errorMessage = "Вставьте ссылку или отсканируйте страницу учебника."
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
            LoadingScreen(modifier = modifier)
        }

        is AppState.Reading -> {
            ReadingScreen(
                modifier = modifier,
                state = currentState,
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
            onGalleryClick = ::requestMedia
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
            }
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
            text = "Откройте сканер, обрежьте страницу и нажмите Next.",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(18.dp))

        TextButton(onClick = onCancel) {
            Text(text = "Вернуться")
        }
    }
}

@Composable
private fun LoadingScreen(modifier: Modifier = Modifier) {
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
            text = "Sulu-Read адаптирует текст учебника, пожалуйста подождите...",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ReadingScreen(
    modifier: Modifier = Modifier,
    state: AppState.Reading,
    onBackHome: () -> Unit
) {
    val sourceText = when (state.source) {
        "image" -> "Источник: фото учебника"
        "url" -> "Источник: ссылка"
        else -> "Источник: материал"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
                    text = state.title ?: "Текст адаптирован",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                Text(
                    text = "$sourceText · слов: ${state.wordCount}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            TextButton(onClick = onBackHome) {
                Text(text = "На главный")
            }
        }

        PremiumReadingScreen(text = state.adaptedText)
    }
}

@Composable
private fun ProcessingErrorDialog(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(text = "Не удалось обработать материал")
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onRetry) {
                Text(text = if (canRetry) "Попробовать снова" else "Понятно")
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
            text = "Sulu-Read",
            style = MaterialTheme.typography.displayMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Видеть смысл, а не просто буквы",
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
                    text = "Ты можешь читать спокойно, в своем темпе. Sulu-Read поможет сделать текст мягче для глаз.",
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
                    contentDescription = "Сканировать учебник",
                    tint = Color.White,
                    modifier = Modifier.size(38.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Сканировать учебник",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Сделать фото или выбрать готовый снимок",
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
        DocumentSource.Camera -> "Источник: камера"
        DocumentSource.Gallery -> "Источник: медиа"
        null -> "Фото пока не выбрано"
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
    onGalleryClick: () -> Unit
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
                text = "Добавить фото учебника",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary
            )
            Text(
                text = "Сфотографируйте страницу или загрузите готовый снимок документа.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted
            )
            DocumentSourceAction(
                icon = Icons.Default.CameraAlt,
                title = "Сфотографировать документ",
                subtitle = "Приложение запросит доступ к камере",
                onClick = onCameraClick
            )
            DocumentSourceAction(
                icon = Icons.Default.PhotoLibrary,
                title = "Загрузить из медиа",
                subtitle = "Приложение запросит доступ к фото",
                onClick = onGalleryClick
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
                Text(text = "Вставьте ссылку на статью или учебник")
            },
            placeholder = {
                Text(text = "https://")
            },
            trailingIcon = {
                if (webLink.isNotEmpty()) {
                    IconButton(onClick = onClearWebLink) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Очистить ссылку",
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
                text = if (isLoading) "Адаптируем..." else "Адаптировать текст",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

private object SuluReadApiClient {
    suspend fun adaptUrl(url: String): ApiResult = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("url", url)
            .toString()
            .toByteArray(Charsets.UTF_8)

        for (baseUrl in SULU_READ_API_BASE_URLS) {
            val result = runCatching {
                val connection = openPostConnection("$baseUrl/v1/adapt-url").apply {
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }

                try {
                    connection.outputStream.use { output ->
                        output.write(payload)
                    }
                    parseApiResponse(connection.responseCode, readResponseBody(connection))
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()

            if (result != null) {
                return@withContext result
            }
        }

        ApiResult.Error(connectionErrorMessage())
    }

    suspend fun adaptImage(context: Context, uri: Uri): ApiResult = withContext(Dispatchers.IO) {
        val upload = runCatching { prepareImageUpload(context, uri) }
            .getOrElse {
                return@withContext ApiResult.Error("Не удалось подготовить фото для отправки.")
            }

        for (baseUrl in SULU_READ_API_BASE_URLS) {
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
                        output.writeUtf8("\r\n--$boundary--\r\n")
                        output.flush()
                    }

                    parseApiResponse(connection.responseCode, readResponseBody(connection))
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()

            if (result != null) {
                return@withContext result
            }
        }

        ApiResult.Error(connectionErrorMessage())
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

    private fun parseApiResponse(httpCode: Int, body: String): ApiResult {
        if (body.isBlank()) {
            return ApiResult.Error(connectionErrorMessage())
        }

        val json = runCatching { JSONObject(body) }
            .getOrElse { return ApiResult.Error(connectionErrorMessage()) }

        val status = json.optString("status")
        if (status == "success") {
            val adaptedText = json.optString("adapted_text")
            if (adaptedText.isBlank()) {
                return ApiResult.Error("Сервер вернул пустой адаптированный текст.")
            }

            return ApiResult.Success(
                adaptedText = adaptedText,
                originalText = json.optString("original_text"),
                source = json.optString("source", "material"),
                wordCount = json.optInt("word_count", 0),
                title = json.optString("title").ifBlank { null }
            )
        }

        val serverMessage = json.optString("message").ifBlank {
            if (httpCode in 200..299) {
                "Сервер не смог адаптировать текст."
            } else {
                connectionErrorMessage()
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

    private fun connectionErrorMessage(): String {
        return "Не удалось получить ответ от сервера Sulu-Read. Проверьте интернет и попробуйте еще раз. Первое распознавание на Hugging Face может занять несколько минут."
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
