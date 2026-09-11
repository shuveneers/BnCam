package com.bncam

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.bncam.core.debug.DiagnosticsAggregator
import com.bncam.core.debug.Phase0PerformanceTrace
import com.bncam.data.settings.SettingsRepository
import com.bncam.ui.navigation.AppNavigation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object CameraEventBus {
    val captureRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    // +1 = zoom in, -1 = zoom out. Events are intentionally non-replayed so a volume press on a
    // non-camera destination cannot be applied later when returning to the viewfinder.
    val zoomRequests = MutableSharedFlow<Int>(extraBufferCapacity = 4)
}
class MainActivity : ComponentActivity() {

    private lateinit var repository: SettingsRepository
    private var volumeButtonAction: String = "Take Photo"
    private var benchmarkReceiver: android.content.BroadcastReceiver? = null
    private var cameraButtonReceiver: BroadcastReceiver? = null
    private var lastHardwareCaptureElapsedMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        val activityOnCreateNs = SystemClock.elapsedRealtimeNanos()
        super.onCreate(savedInstanceState)
        Phase0PerformanceTrace.activityOnCreate(activityOnCreateNs)
        repository = SettingsRepository(this)
        lifecycleScope.launch {
            repository.volumeButtonActionFlow.collectLatest { action ->
                volumeButtonAction = action
            }
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        benchmarkReceiver = BenchmarkDebugReceiverController.register(this, repository)

        setContent {
            val context = LocalContext.current
            val forceMaxBrightness by repository.forceMaxBrightnessFlow.collectAsState(initial = false)

            LaunchedEffect(forceMaxBrightness) {
                val activityWindow = (context as? ComponentActivity)?.window
                val params = activityWindow?.attributes
                params?.screenBrightness = if (forceMaxBrightness) 1.0f else -1.0f
                activityWindow?.attributes = params
            }

            var hasCameraPermission by remember {
                mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { isGranted -> hasCameraPermission = isGranted }
            )

            if (hasCameraPermission) {
                AppNavigation()
            } else {
                PermissionRequestScreen(onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Phase0PerformanceTrace.activityOnResume()
    }

    override fun onPause() {
        Phase0PerformanceTrace.activityOnPause()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        if (cameraButtonReceiver == null) {
            cameraButtonReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != Intent.ACTION_CAMERA_BUTTON) return
                    @Suppress("DEPRECATION")
                    val keyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
                    if (keyEvent != null &&
                        keyEvent.keyCode == KeyEvent.KEYCODE_CAMERA &&
                        keyEvent.action == KeyEvent.ACTION_DOWN &&
                        keyEvent.repeatCount == 0
                    ) {
                        emitHardwareCapture("ACTION_CAMERA_BUTTON", keyEvent)
                    }
                }
            }
            ContextCompat.registerReceiver(
                this,
                cameraButtonReceiver,
                IntentFilter(Intent.ACTION_CAMERA_BUTTON),
                ContextCompat.RECEIVER_EXPORTED
            )
        }
    }

    override fun onStop() {
        cameraButtonReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        cameraButtonReceiver = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA && hasWindowFocus()) {
            emitHardwareCapture("STILL_IMAGE_CAMERA_INTENT", null)
        }
    }

    private fun emitHardwareCapture(source: String, event: KeyEvent?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHardwareCaptureElapsedMs < 250L) return
        lastHardwareCaptureElapsedMs = now
        CameraEventBus.captureRequests.tryEmit(Unit)
        DiagnosticsAggregator.recordKeyValues(
            stream = DiagnosticsAggregator.Stream.CAMERA,
            scope = "APPLICATION",
            section = "HARDWARE SHUTTER INPUT",
            values = listOf(
                "source" to source,
                "keyCode" to event?.keyCode,
                "scanCode" to event?.scanCode,
                "deviceId" to event?.deviceId,
                "inputSource" to event?.source
            )
        )
    }

    override fun onDestroy() {
        benchmarkReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        benchmarkReceiver = null
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Input dispatch must never wait on DataStore/disk-backed Flow collection.
        val action = volumeButtonAction

        return when (keyCode) {
            KeyEvent.KEYCODE_CAMERA -> {
                if (event?.repeatCount == 0) {
                    emitHardwareCapture("KEYCODE_CAMERA", event)
                }
                true
            }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                when (action) {
                    "Do Nothing" -> true
                    "Take Photo" -> {
                        // Vuur een signaal af naar CameraScreen!
                        CameraEventBus.captureRequests.tryEmit(Unit)
                        true
                    }
                    "Zoom" -> {
                        CameraEventBus.zoomRequests.tryEmit(
                            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
                        )
                        true
                    }
                    "Device Volume" -> super.onKeyDown(keyCode, event)
                    else -> super.onKeyDown(keyCode, event)
                }
            }
            else -> {
                if (event?.repeatCount == 0 && keyCode != KeyEvent.KEYCODE_BACK) {
                    DiagnosticsAggregator.recordKeyValues(
                        stream = DiagnosticsAggregator.Stream.CAMERA,
                        scope = "APPLICATION",
                        section = "UNHANDLED HARDWARE KEY",
                        values = listOf(
                            "keyCode" to keyCode,
                            "scanCode" to event.scanCode,
                            "deviceId" to event.deviceId,
                            "inputSource" to event.source
                        )
                    )
                }
                super.onKeyDown(keyCode, event)
            }
        }
    }
}

/**
 * Een minimalistisch scherm in de "BnCam" stijl om de gebruiker uit te leggen
 * waarom we de camera nodig hebben.
 */
@Composable
fun PermissionRequestScreen(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Hardware Access",
            color = Color.White,
            fontSize = 36.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Text(
            text = "BnCam needs direct access to your camera hardware to bypass standard ISP processing and deliver raw sensor data.",
            color = Color.Gray,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 48.dp)
        )
        Button(
            onClick = onRequestPermission,
            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
            shape = androidx.compose.foundation.shape.CircleShape,
            modifier = Modifier.height(56.dp).fillMaxWidth(0.8f)
        ) {
            Text("Grant Permission", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
