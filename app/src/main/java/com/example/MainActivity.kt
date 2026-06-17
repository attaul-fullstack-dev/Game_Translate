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
                    background = Color(0xFF0D0D1A),
                    surface = Color(0xFF1A1A2E),
                    primary = Color(0xFFA855F7),
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
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Game Translator",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = "Terjemahkan game secara real-time",
                    fontSize = 16.sp,
                    color = Color.LightGray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 32.dp)
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                            Text(text = "1. Izin Overlay", modifier = Modifier.weight(1f), color = Color.White)
                            if (hasOverlayPerm) {
                                Text("✓", color = Color.Green)
                            } else {
                                Button(onClick = { 
                                    PermissionHelper.requestOverlayPermission(this@MainActivity) 
                                }) {
                                    Text("Izinkan")
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "2. Download Model", modifier = Modifier.weight(1f), color = Color.White)
                            if (isModelDownloaded) {
                                Text("✓", color = Color.Green)
                            } else if (isDownloading) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Button(onClick = {
                                    scope.launch {
                                        isDownloading = true
                                        try {
                                            translateManager.downloadModelsIfNeeded()
                                            isModelDownloaded = true
                                        } catch (e: Exception) {
                                            Toast.makeText(this@MainActivity, "Download failed", Toast.LENGTH_SHORT).show()
                                        } finally {
                                            isDownloading = false
                                        }
                                    }
                                }) {
                                    Text("Download")
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = { startCapture() },
                    enabled = hasOverlayPerm && isModelDownloaded,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Mulai Translate", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                    background = Color(0xFF0D0D1A),
                    surface = Color(0xFF1A1A2E),
                    primary = Color(0xFFA855F7),
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
