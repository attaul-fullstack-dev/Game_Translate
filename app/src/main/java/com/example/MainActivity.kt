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

        LaunchedEffect(Unit) {
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
        }

        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = Color.White
                ) {
                    NavigationBarItem(
                        selected = true,
                        onClick = { },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Beranda") },
                        label = { Text("Beranda") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = Color.Transparent
                        )
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = { },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Pengaturan") },
                        label = { Text("Pengaturan") }
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = { },
                        icon = { Icon(Icons.Default.Folder, contentDescription = "Model") },
                        label = { Text("Model") }
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
                                size = "1.18 GB",
                                isReady = isModelDownloaded
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Model Item: Indonesia
                            ModelItem(
                                name = "Indonesia",
                                desc = "Model Bahasa Indonesia",
                                size = "1.23 GB",
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
                }
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
        finish() // Close main app to show game
    }
}
