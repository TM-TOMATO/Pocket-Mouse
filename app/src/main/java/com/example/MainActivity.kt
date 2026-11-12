package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.DashboardScreen
import com.example.ui.SurfaceDrivingScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DeskMouseViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        viewModel.checkPermissions()
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            viewModel.startCamera(this)
        }
        viewModel.hidManager.initialize()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestAppPermissions()

        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DeskMouseApp(
                        viewModel = viewModel,
                        onRequestPermissions = { requestAppPermissions() },
                        onApplyBrightness = { enableFullBrightness ->
                            setDrivingBrightness(enableFullBrightness)
                        }
                    )
                }
            }
        }
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    /**
     * Intercepts physical Volume buttons so the phone operates as a 3-button mouse.
     * Consumes key event by returning true so system volume bar never appears.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val consumed = viewModel.handleVolumeKeyEvent(event.keyCode, event)
            if (consumed) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setDrivingBrightness(enable: Boolean) {
        val layoutParams = window.attributes
        if (enable) {
            layoutParams.screenBrightness = 1.0f
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        window.attributes = layoutParams
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.uiState.value.hasCameraPermission) {
            viewModel.startCamera(this)
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopCamera()
    }
}

@Composable
fun DeskMouseApp(
    viewModel: DeskMouseViewModel,
    onRequestPermissions: () -> Unit,
    onApplyBrightness: (Boolean) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()
    val hidState by viewModel.hidConnectionState.collectAsState()
    val hidMetrics by viewModel.hidMetrics.collectAsState()
    val imuState by viewModel.imuMotionState.collectAsState()
    val telemetry by viewModel.fusionTelemetry.collectAsState()

    LaunchedEffect(uiState.isSurfaceMode) {
        onApplyBrightness(uiState.isSurfaceMode)
    }

    DisposableEffect(lifecycleOwner) {
        if (uiState.hasCameraPermission) {
            viewModel.startCamera(lifecycleOwner)
        }
        onDispose {
            viewModel.stopCamera()
        }
    }

    Crossfade(targetState = uiState.isSurfaceMode, label = "ScreenTransition") { isSurfaceMode ->
        if (isSurfaceMode) {
            SurfaceDrivingScreen(
                uiState = uiState,
                hidState = hidState,
                hidMetrics = hidMetrics,
                telemetry = telemetry,
                onExit = { viewModel.setSurfaceMode(false) }
            )
        } else {
            DashboardScreen(
                uiState = uiState,
                hidState = hidState,
                hidMetrics = hidMetrics,
                imuState = imuState,
                telemetry = telemetry,
                onRequestPermissions = onRequestPermissions,
                onStartSurfaceMode = { viewModel.setSurfaceMode(true) },
                onDpiChange = { viewModel.setDpiScale(it) },
                onKRotChange = { viewModel.setKRot(it) },
                onInvertXChange = { viewModel.setInvertX(it) },
                onInvertYChange = { viewModel.setInvertY(it) },
                onScrollModeChange = { viewModel.setScrollMode(it) }
            )
        }
    }
}
