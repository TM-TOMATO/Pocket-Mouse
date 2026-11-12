package com.example

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.fusion.FusionTelemetry
import com.example.fusion.SensorFusionController
import com.example.hid.BluetoothHidMouseManager
import com.example.hid.HidConnectionState
import com.example.hid.HidMetrics
import com.example.sensor.ImuMotionState
import com.example.sensor.ImuSensorProcessor
import com.example.vision.OpticalFlowEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * UI State aggregating all subsystem metrics, settings, and navigation.
 */
data class DeskMouseUiState(
    val isSurfaceMode: Boolean = false,
    val dpiScale: Float = 1.5f,
    val kRot: Float = 1.2f,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    val isScrollMode: Boolean = false,
    val hasCameraPermission: Boolean = false,
    val hasBluetoothPermission: Boolean = false,
    val isVolUpPressed: Boolean = false,
    val isVolDownPressed: Boolean = false,
    val isCameraBound: Boolean = false
)

/**
 * Main ViewModel orchestrating CameraX, IMU sensors, Optical Flow, Sensor Fusion, and Bluetooth HID.
 */
class DeskMouseViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DeskMouse_VM"
    }

    private val context: Context get() = getApplication()

    val hidManager = BluetoothHidMouseManager(context, viewModelScope)
    val imuProcessor = ImuSensorProcessor(context)
    val fusionController = SensorFusionController(imuProcessor, hidManager)

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null

    val opticalFlowEngine = OpticalFlowEngine { result ->
        fusionController.processOpticalFlowFrame(result)
    }

    private val _uiState = MutableStateFlow(DeskMouseUiState())
    val uiState: StateFlow<DeskMouseUiState> = _uiState.asStateFlow()

    val hidConnectionState: StateFlow<HidConnectionState> = hidManager.connectionState
    val hidMetrics: StateFlow<HidMetrics> = hidManager.metrics
    val imuMotionState: StateFlow<ImuMotionState> = imuProcessor.motionState
    val fusionTelemetry: StateFlow<FusionTelemetry> = fusionController.telemetry

    init {
        checkPermissions()
        imuProcessor.start()
        hidManager.initialize()
    }

    fun checkPermissions() {
        val cam = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val bt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        _uiState.value = _uiState.value.copy(
            hasCameraPermission = cam,
            hasBluetoothPermission = bt
        )
    }

    /**
     * Binds CameraX ImageAnalysis targeting the front camera with 320x240 resolution.
     */
    fun startCamera(lifecycleOwner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                val provider = cameraProvider ?: return@addListener
                provider.unbindAll()

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(320, 240),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor, opticalFlowEngine)

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
                _uiState.value = _uiState.value.copy(isCameraBound = true)
                Log.i(TAG, "CameraX front camera successfully bound")
            } catch (e: Exception) {
                Log.e(TAG, "Error binding CameraX: ${e.message}", e)
                _uiState.value = _uiState.value.copy(isCameraBound = false)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
        _uiState.value = _uiState.value.copy(isCameraBound = false)
    }

    fun setSurfaceMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isSurfaceMode = enabled)
        if (!enabled) {
            // Reset scroll mode on exiting driving mode
            setScrollMode(false)
        }
        triggerHapticFeedback(if (enabled) 50 else 30)
    }

    fun setDpiScale(dpi: Float) {
        fusionController.dpiScale = dpi
        _uiState.value = _uiState.value.copy(dpiScale = dpi)
    }

    fun setKRot(kRot: Float) {
        fusionController.kRot = kRot
        _uiState.value = _uiState.value.copy(kRot = kRot)
    }

    fun setInvertX(invert: Boolean) {
        fusionController.invertX = invert
        _uiState.value = _uiState.value.copy(invertX = invert)
    }

    fun setInvertY(invert: Boolean) {
        fusionController.invertY = invert
        _uiState.value = _uiState.value.copy(invertY = invert)
    }

    fun setScrollMode(scroll: Boolean) {
        fusionController.isScrollMode = scroll
        _uiState.value = _uiState.value.copy(isScrollMode = scroll)
    }

    /**
     * Intercepts hardware Volume buttons to map to Mouse clicks and Scroll mode.
     * Volume UP = Left click (drag support on hold)
     * Volume DOWN = Right click
     * Volume UP + DOWN = Toggle Scroll Mode
     */
    fun handleVolumeKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        val isDown = (event.action == KeyEvent.ACTION_DOWN)
        val isUp = (event.action == KeyEvent.ACTION_UP)

        if (!isDown && !isUp) return false

        var volUp = _uiState.value.isVolUpPressed
        var volDown = _uiState.value.isVolDownPressed

        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                volUp = isDown
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                volDown = isDown
            }
            else -> return false
        }

        _uiState.value = _uiState.value.copy(
            isVolUpPressed = volUp,
            isVolDownPressed = volDown
        )

        // Simultaneous Volume UP + DOWN check -> Toggle Scroll Mode
        if (volUp && volDown) {
            val currentScroll = _uiState.value.isScrollMode
            setScrollMode(!currentScroll)
            triggerHapticFeedback(100)
            hidManager.setButtons(left = false, right = false)
            return true
        }

        // Single button mapping
        hidManager.setButtons(left = volUp, right = volDown)
        return true
    }

    fun triggerHapticFeedback(durationMs: Long = 40) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (e: Exception) {
            // Haptic optional
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopCamera()
        imuProcessor.stop()
        hidManager.release()
        opticalFlowEngine.release()
        cameraExecutor.shutdown()
    }
}
