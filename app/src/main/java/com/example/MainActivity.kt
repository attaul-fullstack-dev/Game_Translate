package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private data class ModelDownloadNetworkStatus(
    val hasInternet: Boolean,
    val label: String,
    val isMetered: Boolean,
)

private fun currentModelDownloadNetworkStatus(context: Context): ModelDownloadNetworkStatus {
    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork
        ?: return ModelDownloadNetworkStatus(
            hasInternet = false,
            label = "Offline",
            isMetered = false,
        )
    val capabilities = connectivityManager.getNetworkCapabilities(network)
        ?: return ModelDownloadNetworkStatus(
            hasInternet = false,
            label = "Offline",
            isMetered = false,
        )
    val transport = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Data seluler"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "Jaringan aktif"
    }
    val hasInternet =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    return ModelDownloadNetworkStatus(
        hasInternet = hasInternet,
        label = if (hasInternet) transport else "$transport tanpa internet",
        isMetered = connectivityManager.isActiveNetworkMetered,
    )
}

class MainActivity : ComponentActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private val translateManager = TranslateManager()
    private val overlayPermissionState = mutableStateOf(false)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    "Notifikasi ditolak; translator tetap dijalankan.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            launchScreenCaptureConsent()
        }

    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startOverlayService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        overlayPermissionState.value = PermissionHelper.hasOverlayPermission(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0F0F1A),
                    surface = Color(0xFF1E1E2E), // lighter than the old 0x1A1A2E roughly
                    primary = Color(0xFFA855F7), // purple
                    onPrimary = Color.White
                )
            ) {
                MainScreen()
            }
        }
    }

    @Composable
    fun MainScreen() {
        val scope = rememberCoroutineScope()
        val hasOverlayPerm by overlayPermissionState
        val serviceState by DebugStore.serviceState
        var modelProgress by remember { mutableStateOf(ModelDownloadProgress()) }
        var isDownloading by remember { mutableStateOf(false) }
        var modelError by remember { mutableStateOf("") }
        var downloadRequest by remember { mutableIntStateOf(0) }
        var downloadStartedAt by remember { mutableLongStateOf(0L) }
        var downloadElapsedSeconds by remember { mutableLongStateOf(0L) }
        var networkStatus by remember {
            mutableStateOf(currentModelDownloadNetworkStatus(this@MainActivity))
        }
        var selectedTab by remember { mutableIntStateOf(0) }
        var testResult by remember { mutableStateOf("") }
        var testError by remember { mutableStateOf("") }
        val modelsReady = modelProgress.allReady

        LaunchedEffect(Unit) {
            while (isActive) {
                networkStatus = currentModelDownloadNetworkStatus(this@MainActivity)
                delay(if (isDownloading) 1_000L else 5_000L)
            }
        }

        LaunchedEffect(downloadRequest) {
            modelError = ""
            downloadElapsedSeconds = 0L
            downloadStartedAt = SystemClock.elapsedRealtime()
            isDownloading = true
            try {
                translateManager.prepareModels { progress ->
                    modelProgress = progress
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val latestNetworkStatus =
                    currentModelDownloadNetworkStatus(this@MainActivity)
                networkStatus = latestNetworkStatus
                modelProgress = modelProgress.copy(phase = ModelDownloadPhase.FAILED)
                modelError = if (!latestNetworkStatus.hasInternet) {
                    "Tidak ada koneksi internet. Sambungkan perangkat lalu coba lagi."
                } else {
                    e.localizedMessage
                        ?.takeIf { it.isNotBlank() }
                        ?: "Model terjemahan gagal disiapkan."
                }
            } finally {
                downloadElapsedSeconds =
                    ((SystemClock.elapsedRealtime() - downloadStartedAt) / 1_000L)
                        .coerceAtLeast(0L)
                isDownloading = false
            }
        }

        LaunchedEffect(isDownloading, downloadStartedAt) {
            while (isActive && isDownloading) {
                downloadElapsedSeconds =
                    ((SystemClock.elapsedRealtime() - downloadStartedAt) / 1_000L)
                        .coerceAtLeast(0L)
                delay(1_000L)
            }
        }

        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = Color.White
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Beranda") },
                        label = { Text("Beranda") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = Color.Transparent
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Translate, contentDescription = "Translate") },
                        label = { Text("Translate") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = Color.Transparent
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Pengaturan") },
                        label = { Text("Pengaturan") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Default.BugReport, contentDescription = "Debug") },
                        label = { Text("Debug") }
                    )
                }
            }
        ) { paddingValues ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Game Translator",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Text(
                        text = "Terjemahkan game secara real-time",
                        fontSize = 14.sp,
                        color = Color.LightGray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 32.dp)
                    )

                    if (selectedTab == 0) {
                        // Cards Layout
                        // Status Overlay
                    StatusCard(
                        title = "Status Overlay",
                        subtitle = if (hasOverlayPerm) "Aktif" else "Tidak Aktif",
                        isActive = hasOverlayPerm,
                        onClick = {
                            if (!hasOverlayPerm) PermissionHelper.requestOverlayPermission(this@MainActivity)
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))

                    // Bahasa Terjemahan
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "Bahasa Terjemahan", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(text = "Inggris → Indonesia", fontSize = 14.sp, color = Color.White)
                                Text(text = "Utama", fontSize = 12.sp, color = Color.LightGray)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    ModelDownloadCard(
                        progress = modelProgress,
                        isDownloading = isDownloading,
                        elapsedSeconds = downloadElapsedSeconds,
                        networkStatus = networkStatus,
                        error = modelError,
                        onRetry = { downloadRequest++ },
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = { startCapture() },
                        enabled = hasOverlayPerm && modelsReady && serviceState != "RUNNING",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Text(
                            if (serviceState == "RUNNING") "Translator Aktif" else "Mulai Translate",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "Ketuk bubble aktif untuk jeda, lalu ketuk lagi untuk memilih ulang area.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                    } else if (selectedTab == 1) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = "Test Terjemahan (Inggris -> Indonesia)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                translateManager.resetLastText()
                                                val res = translateManager.translate("Hello")
                                                testResult = res ?: "Tidak ada hasil"
                                                testError = ""
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                testResult = ""
                                                testError = e.localizedMessage ?: "Unknown Error"
                                            }
                                        }
                                    },
                                    enabled = modelsReady && !isDownloading,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Test Translation", color = Color.White)
                                }
                                
                                if (testResult.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Hasil: $testResult", color = Color.Green, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                                if (testError.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Error: $testError", color = Color.Red, fontSize = 14.sp)
                                }
                            }
                        }
                    } else if (selectedTab == 2) {
                        SettingsPanel(serviceState == "RUNNING")
                    } else if (selectedTab == 3) {
                        DiagnosticPanel()
                    }
                }
            }
        }
    }

    @Composable
    fun SettingsPanel(isServiceRunning: Boolean) {
        val translationEnabled by DebugStore.enableTranslation

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Pengaturan Translator",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Terjemahan", color = Color.White)
                        Text(
                            "Matikan untuk menguji hasil OCR mentah.",
                            color = Color.LightGray,
                            fontSize = 12.sp,
                        )
                    }
                    Switch(
                        checked = translationEnabled,
                        onCheckedChange = { DebugStore.enableTranslation.value = it },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { stopService(Intent(this@MainActivity, OverlayService::class.java)) },
                    enabled = isServiceRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (isServiceRunning) "Hentikan Translator" else "Translator Tidak Aktif")
                }
            }
        }
    }

    @Composable
    fun DiagnosticPanel() {
        val captureStatus by DebugStore.captureStatus
        val bitmapCaptured by DebugStore.bitmapCaptured
        val lastBitmap by DebugStore.lastBitmap
        val ocrRawText by DebugStore.ocrRawText
        val ocrTextLength by DebugStore.ocrTextLength
        val translationResult by DebugStore.translationResult
        val serviceState by DebugStore.serviceState
        val ocrOverlayState by DebugStore.ocrOverlayState
        val lastError by DebugStore.lastError
        val detectedBlocks by DebugStore.detectedBlocks
        val detectedLines by DebugStore.detectedLines
        val selX by DebugStore.selectedAreaX
        val selY by DebugStore.selectedAreaY
        val selW by DebugStore.selectedAreaW
        val selH by DebugStore.selectedAreaH
        val panelX by DebugStore.translationPanelX
        val panelY by DebugStore.translationPanelY
        val panelW by DebugStore.translationPanelW
        val panelH by DebugStore.translationPanelH
        val selectedDisplayWidth by DebugStore.selectedDisplayWidth
        val selectedDisplayHeight by DebugStore.selectedDisplayHeight
        val selectedDisplayRotation by DebugStore.selectedDisplayRotation
        val captureFrameWidth by DebugStore.captureFrameWidth
        val captureFrameHeight by DebugStore.captureFrameHeight
        val enableTrans = DebugStore.enableTranslation.value

        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Text("System Diagnostics", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Enable Translation (Disable for OCR Test)", color = Color.White, modifier = Modifier.weight(1f))
                Switch(
                    checked = enableTrans,
                    onCheckedChange = { DebugStore.enableTranslation.value = it }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            DiagnosticCard("Service State", serviceState)
            DiagnosticCard("Overlay Mode", ocrOverlayState)
            DiagnosticCard("Screen Capture", captureStatus)
            DiagnosticCard("Bitmap Captured", if (bitmapCaptured) "YES" else "NO")
            DiagnosticCard("OCR Result", if (ocrRawText.isNotBlank()) ocrRawText else "NO_TEXT")
            DiagnosticCard("OCR Text Length", "$ocrTextLength characters")
            DiagnosticCard("Detected Blocks/Lines", "$detectedBlocks Blocks, $detectedLines Lines")
            DiagnosticCard("Translation Result", if (translationResult.isNotBlank()) translationResult else "NO_RESULT")
            
            Spacer(modifier = Modifier.height(8.dp))
            DiagnosticCard("OCR Input Area", "X:$selX Y:$selY W:$selW H:$selH")
            DiagnosticCard(
                "Translation Panel",
                "X:$panelX Y:$panelY W:$panelW H:$panelH",
            )
            DiagnosticCard(
                "Selection Space",
                "${selectedDisplayWidth}x$selectedDisplayHeight @ ${selectedDisplayRotation}°",
            )
            DiagnosticCard("Capture Frame", "${captureFrameWidth}x$captureFrameHeight")

            if (lastError.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color(0x33FF0000)), modifier = Modifier.fillMaxWidth()) {
                    Text("Last Error:\n$lastError", color = Color.Red, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                }
            }

            if (lastBitmap != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Last Captured Bitmap:", color = Color.LightGray, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Image(
                    bitmap = lastBitmap!!.asImageBitmap(),
                    contentDescription = "Last captured screenshot",
                    modifier = Modifier.fillMaxWidth().height(200.dp).background(Color.Black)
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    @Composable
    fun DiagnosticCard(label: String, value: String) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Row(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                Text(label, color = Color.LightGray, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    @Composable
    fun StatusCard(title: String, subtitle: String, isActive: Boolean, statusText: String? = null, onClick: () -> Unit) {
        Card(
            onClick = onClick,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (statusText != null) {
                            Text(text = subtitle, fontSize = 14.sp, color = Color.LightGray)
                        } else {
                            Box(modifier = Modifier.size(8.dp).background(if(isActive) Color.Green else Color.Red, shape = RoundedCornerShape(4.dp)))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = subtitle, fontSize = 14.sp, color = Color.LightGray)
                        }
                    }
                }
                if (statusText != null) {
                    Box(modifier = Modifier.size(8.dp).background(if(isActive) Color.Green else Color.Red, shape = RoundedCornerShape(4.dp)))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = statusText, fontSize = 12.sp, color = if(isActive) Color.Green else Color.Red)
                }
            }
        }
    }

    @Composable
    private fun ModelDownloadCard(
        progress: ModelDownloadProgress,
        isDownloading: Boolean,
        elapsedSeconds: Long,
        networkStatus: ModelDownloadNetworkStatus,
        error: String,
        onRetry: () -> Unit,
    ) {
        val isSlow =
            isDownloading && elapsedSeconds >= SLOW_MODEL_DOWNLOAD_SECONDS
        val phaseText = when (progress.phase) {
            ModelDownloadPhase.IDLE -> "Menunggu pemeriksaan model"
            ModelDownloadPhase.CHECKING -> progress.currentModel?.let {
                "Memeriksa model ${it.displayName}…"
            } ?: "Memeriksa model yang tersimpan…"
            ModelDownloadPhase.DOWNLOADING -> progress.currentModel?.let {
                "Mengunduh model ${it.displayName}…"
            } ?: "Mengunduh model terjemahan…"
            ModelDownloadPhase.VERIFYING -> progress.currentModel?.let {
                "Memverifikasi model ${it.displayName}…"
            } ?: "Memverifikasi semua model…"
            ModelDownloadPhase.READY -> "Semua model siap digunakan"
            ModelDownloadPhase.FAILED -> "Persiapan model gagal"
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Status Model",
                        fontSize = 14.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${progress.completedCount}/${progress.totalCount} siap",
                        fontSize = 12.sp,
                        color = if (progress.allReady) Color.Green else Color.LightGray,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = phaseText,
                    fontSize = 13.sp,
                    color = if (progress.phase == ModelDownloadPhase.FAILED) {
                        Color(0xFFFF6B6B)
                    } else {
                        Color.White
                    },
                )

                if (isDownloading) {
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Heartbeat ${formatElapsedTime(elapsedSeconds)} • ${networkStatus.label}",
                        color = if (networkStatus.hasInternet) {
                            Color(0xFF81C784)
                        } else {
                            Color(0xFFFFB74D)
                        },
                        fontSize = 12.sp,
                    )
                    if (networkStatus.isMetered) {
                        Text(
                            text = "Jaringan ini dapat memakai kuota data.",
                            color = Color.LightGray,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    Text(
                        text = "ML Kit tidak menyediakan progres byte/persen; " +
                            "tahap dan waktu di atas diperbarui secara langsung.",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                } else if (progress.allReady) {
                    Text(
                        text = "Selesai dalam ${formatElapsedTime(elapsedSeconds)}.",
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }

                if (isDownloading && !networkStatus.hasInternet) {
                    Text(
                        text = "Tidak ada akses internet tervalidasi. Download sedang menunggu jaringan.",
                        color = Color(0xFFFFB74D),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

                if (isSlow) {
                    Text(
                        text = "Download lebih dari 3 menit. Tugas masih aktif; " +
                            "gunakan Periksa Ulang untuk membaca status model lagi.",
                        color = Color(0xFFFFB74D),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

                if (error.isNotBlank()) {
                    Text(
                        text = error,
                        fontSize = 12.sp,
                        color = Color(0xFFFF6B6B),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(12.dp))

                TranslationModel.entries.forEachIndexed { index, model ->
                    ModelItem(
                        model = model,
                        progress = progress,
                        networkStatus = networkStatus,
                    )
                    if (index != TranslationModel.entries.lastIndex) {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                val showRetry = !progress.allReady &&
                    (!isDownloading || isSlow || !networkStatus.hasInternet)
                if (showRetry) {
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (isDownloading) "Periksa Ulang" else "Coba Lagi",
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ModelItem(
        model: TranslationModel,
        progress: ModelDownloadProgress,
        networkStatus: ModelDownloadNetworkStatus,
    ) {
        val isReady = model in progress.readyModels
        val isCurrent = progress.currentModel == model
        val isBusy = isCurrent &&
            progress.phase in setOf(
                ModelDownloadPhase.CHECKING,
                ModelDownloadPhase.DOWNLOADING,
                ModelDownloadPhase.VERIFYING,
            ) &&
            (progress.phase != ModelDownloadPhase.DOWNLOADING || networkStatus.hasInternet)
        val statusText = when {
            isReady -> "Siap"
            isCurrent &&
                progress.phase == ModelDownloadPhase.DOWNLOADING &&
                !networkStatus.hasInternet -> "Menunggu jaringan"
            isCurrent && progress.phase == ModelDownloadPhase.CHECKING -> "Memeriksa"
            isCurrent && progress.phase == ModelDownloadPhase.DOWNLOADING -> "Sedang diunduh"
            isCurrent && progress.phase == ModelDownloadPhase.VERIFYING -> "Memverifikasi"
            isCurrent && progress.phase == ModelDownloadPhase.FAILED -> "Gagal"
            progress.phase == ModelDownloadPhase.FAILED -> "Belum siap"
            else -> "Menunggu"
        }
        val statusColor = when {
            isReady -> Color.Green
            isCurrent &&
                progress.phase == ModelDownloadPhase.DOWNLOADING &&
                !networkStatus.hasInternet -> Color(0xFFFFB74D)
            progress.phase == ModelDownloadPhase.FAILED -> Color(0xFFFF6B6B)
            isCurrent -> MaterialTheme.colorScheme.primary
            else -> Color.Gray
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.displayName,
                    fontSize = 14.sp,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = model.description,
                    fontSize = 12.sp,
                    color = Color.LightGray,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            color = statusColor,
                            strokeWidth = 1.5.dp,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    statusColor,
                                    shape = RoundedCornerShape(4.dp),
                                ),
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        color = statusColor,
                    )
                }
            }
            Text(
                text = model.sizeLabel,
                fontSize = 14.sp,
                color = Color.LightGray,
            )
        }
    }

    @Composable
    fun AreaTranslasiCard() {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Area Translasi", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "Pilih area teks yang ingin diterjemahkan", fontSize = 12.sp, color = Color.LightGray)
                }
                Box(
                    modifier = Modifier
                        .background(Color(0xFF2A2A3E), shape = RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text("[ ]", color = Color(0xFFA855F7), fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayPermissionState.value = PermissionHelper.hasOverlayPermission(this)
    }

    override fun onStart() {
        DebugStore.isActivityVisible = true
        super.onStart()
    }

    override fun onStop() {
        DebugStore.isActivityVisible = false
        super.onStop()
    }

    override fun onDestroy() {
        DebugStore.isActivityVisible = false
        translateManager.close()
        super.onDestroy()
    }

    private fun startCapture() {
        if (!PermissionHelper.hasOverlayPermission(this)) {
            PermissionHelper.requestOverlayPermission(this)
            return
        }
        if (DebugStore.serviceState.value == "RUNNING") {
            Toast.makeText(this, "Translator sudah aktif.", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionHelper.hasNotificationPermission(this)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        launchScreenCaptureConsent()
    }

    private fun launchScreenCaptureConsent() {
        val intent = projectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }

    private fun startOverlayService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(OverlayService.EXTRA_DATA, data)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            // Keep the capture grant's Activity task alive, but put the game back
            // in front so MediaProjection does not immediately OCR this app.
            moveTaskToBack(true)
        } catch (failure: RuntimeException) {
            DebugStore.logError(failure)
            Toast.makeText(
                this,
                "Translator gagal dijalankan: " +
                    (failure.localizedMessage ?: "layanan diblokir sistem"),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
