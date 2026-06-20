package com.example

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private val translateManager = TranslateManager()

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
        var hasOverlayPerm by remember { mutableStateOf(PermissionHelper.hasOverlayPermission(this)) }
        var isModelDownloaded by remember { mutableStateOf(false) }
        var isDownloading by remember { mutableStateOf(false) }
        var selectedTab by remember { mutableIntStateOf(0) }
        var testResult by remember { mutableStateOf("") }
        var testError by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            while (true) {
                hasOverlayPerm = PermissionHelper.hasOverlayPermission(this@MainActivity)
                isModelDownloaded = translateManager.isModelDownloaded()
                if (!isModelDownloaded && !isDownloading && hasOverlayPerm) {
                     isDownloading = true
                     try {
                         translateManager.downloadModelsIfNeeded()
                         isModelDownloaded = true
                     } catch (e: Exception) {
                         // ignore silently on auto download
                     } finally {
                         isDownloading = false
                     }
                }
                delay(2000)
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

                    // Model Terjemahan Detailed Status
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "Status Model", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.weight(1f))
                                if (isDownloading) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                                } else if (!isModelDownloaded) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                isDownloading = true
                                                try {
                                                    translateManager.downloadModelsIfNeeded()
                                                    isModelDownloaded = true
                                                } catch (e: Exception) {
                                                    Toast.makeText(this@MainActivity, "Download gagal", Toast.LENGTH_SHORT).show()
                                                } finally {
                                                    isDownloading = false
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Download", fontSize = 12.sp)
                                    }
                                }
                            }
                            
                            if (isModelDownloaded) {
                                Text(
                                    text = "Semua model sudah terdownload. Akurasi terjemahan optimal.",
                                    fontSize = 12.sp,
                                    color = Color.LightGray,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                                )
                            } else {
                                Text(
                                    text = "Model bahasa diperlukan untuk menerjemahkan.",
                                    fontSize = 12.sp,
                                    color = Color.LightGray,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                                )
                            }
                            
                            HorizontalDivider(color = Color.DarkGray)
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Model Item: Inggris
                            ModelItem(
                                name = "Inggris",
                                desc = "Model Bahasa Inggris",
                                size = "~31 MB",
                                isReady = isModelDownloaded
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Model Item: Indonesia
                            ModelItem(
                                name = "Indonesia",
                                desc = "Model Bahasa Indonesia",
                                size = "~31 MB",
                                isReady = isModelDownloaded
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = { startCapture() },
                        enabled = hasOverlayPerm,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Text("Mulai Translate", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "Overlay akan muncul saat game berjalan",
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
                                                testResult = res ?: "Tidak ada hasil (atau teks sama)"
                                                testError = ""
                                            } catch (e: Exception) {
                                                testResult = ""
                                                testError = e.localizedMessage ?: "Unknown Error"
                                            }
                                        }
                                    },
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
                    } else if (selectedTab == 3) {
                        DiagnosticPanel()
                    }
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
        val enableTrans = DebugStore.enableTranslation.value

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
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
            DiagnosticCard("Selected Area", "X:$selX Y:$selY W:$selW H:$selH")

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
    fun ModelItem(name: String, desc: String, size: String, isReady: Boolean) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = name, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(text = desc, fontSize = 12.sp, color = Color.LightGray)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Box(modifier = Modifier.size(8.dp).background(if(isReady) Color.Green else Color.Red, shape = RoundedCornerShape(4.dp)))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = if(isReady) "Terdownload" else "Belum", fontSize = 12.sp, color = if(isReady) Color.Green else Color.Red)
                }
            }
            Text(text = size, fontSize = 14.sp, color = Color.LightGray)
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
        // Refresh permission state on resume
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

    private fun startCapture() {
        if (!PermissionHelper.hasNotificationPermission(this)) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        val intent = projectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }

    private fun startOverlayService(resultCode: Int, data: Intent) {
        val metrics = resources.displayMetrics
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            putExtra("RESULT_CODE", resultCode)
            putExtra("DATA", data)
            putExtra("WIDTH", metrics.widthPixels)
            putExtra("HEIGHT", metrics.heightPixels)
            putExtra("DENSITY", metrics.densityDpi)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        // Move to background instead of finish() so the activity (and its
        // MediaProjection result) is not torn down — finishing here can cause the
        // projection to capture our own (now closing) task instead of the game.
        moveTaskToBack(true)
    }
}
