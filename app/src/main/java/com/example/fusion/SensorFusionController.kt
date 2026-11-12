package com.example.fusion

import android.graphics.Bitmap
import android.util.Log
import com.example.hid.BluetoothHidMouseManager
import com.example.sensor.ImuSensorProcessor
import com.example.sensor.OneEuroFilter2D
import com.example.vision.OpticalFlowResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Real-time telemetry snapshot of the Sensor Fusion pipeline for UI and debugging.
 */
data class FusionTelemetry(
    val rawOptX: Float = 0f,
    val rawOptY: Float = 0f,
    val gyroCompX: Float = 0f,
    val finalDx: Int = 0,
    val finalDy: Int = 0,
    val wheelDelta: Int = 0,
    val isLiftedOff: Boolean = false,
    val isLowFeature: Boolean = false,
    val featureConfidence: Float = 0f,
    val validFeatures: Int = 0,
    val fps: Float = 0f,
    val isScrollMode: Boolean = false,
    val debugBitmap: Bitmap? = null
)

/**
 * Controller that fuses Visual Optical Flow with IMU data:
 * 1. Cancels out wrist rotation artifacts using Gyro Z-axis integration.
 * 2. Mutes output when the phone is lifted off the desk.
 * 3. Applies short-term damped velocity interpolation (< 150ms) during low-texture zones.
 * 4. Filters jitter via 1€ Filter (One Euro Filter).
 * 5. Dispatches formatted movements to the Bluetooth HID mouse manager.
 */
class SensorFusionController(
    val imuProcessor: ImuSensorProcessor,
    val hidManager: BluetoothHidMouseManager
) {
    companion object {
        private const val TAG = "DeskMouse_Fusion"
        private const val DEFAULT_ROTATION_COEFFICIENT = 1.2f
        private const val DEFAULT_DPI_SCALE = 1.5f
    }

    // Configurable parameters
    var dpiScale: Float = DEFAULT_DPI_SCALE
    var kRot: Float = DEFAULT_ROTATION_COEFFICIENT // Rotation compensation factor
    var invertX: Boolean = false
    var invertY: Boolean = false
    var isScrollMode: Boolean = false

    private val oneEuroFilter = OneEuroFilter2D(minCutoff = 1.2, beta = 0.015, dCutoff = 1.0)

    private val _telemetry = MutableStateFlow(FusionTelemetry())
    val telemetry: StateFlow<FusionTelemetry> = _telemetry.asStateFlow()

    private var lastFrameTimestampNs: Long = 0L
    private var frameCount = 0
    private var lastFpsCalcTimeMs = System.currentTimeMillis()
    private var currentFps = 0f

    /**
     * Resets smoothing filters and timers.
     */
    fun reset() {
        oneEuroFilter.reset()
        lastFrameTimestampNs = 0L
        imuProcessor.reset()
    }

    /**
     * Primary entry point invoked for every optical flow frame from CameraX.
     */
    fun processOpticalFlowFrame(result: OpticalFlowResult) {
        val nowMs = System.currentTimeMillis()
        frameCount++
        if (nowMs - lastFpsCalcTimeMs >= 1000) {
            currentFps = (frameCount * 1000f) / (nowMs - lastFpsCalcTimeMs)
            frameCount = 0
            lastFpsCalcTimeMs = nowMs
        }

        val timestampSec = if (result.timestampNs > 0) result.timestampNs * 1e-9 else nowMs / 1000.0
        val dtSec = if (lastFrameTimestampNs > 0 && result.timestampNs > lastFrameTimestampNs) {
            ((result.timestampNs - lastFrameTimestampNs) * 1e-9f).coerceIn(0.005f, 0.1f)
        } else {
            0.033f // fallback 30fps delta
        }
        lastFrameTimestampNs = result.timestampNs

        // 1. Guardrail: Lift-off instant muting
        if (imuProcessor.isLiftedOff) {
            hidManager.postMovement(0, 0, 0)
            _telemetry.value = _telemetry.value.copy(
                rawOptX = result.rawDeltaX,
                rawOptY = result.rawDeltaY,
                finalDx = 0,
                finalDy = 0,
                wheelDelta = 0,
                isLiftedOff = true,
                isLowFeature = result.isLowFeature,
                featureConfidence = result.featureConfidence,
                validFeatures = result.validFeaturesCount,
                fps = currentFps,
                isScrollMode = isScrollMode,
                debugBitmap = result.debugBitmap ?: _telemetry.value.debugBitmap
            )
            return
        }

        // 2. Guardrail: Gyroscope Z-axis rotational artifact compensation
        // Delta theta_z in radians integrated across inter-frame interval
        val deltaThetaZ = imuProcessor.consumeAccumulatedRotationZ()
        // Convert angular displacement to linear camera pixel offset equivalent
        val rotationCompX = (deltaThetaZ * kRot * 60f)

        var fusedX = result.rawDeltaX - rotationCompX
        var fusedY = result.rawDeltaY

        // 3. Guardrail: Low-feature (< 150ms) damped inertial interpolation
        if (result.isLowFeature) {
            val (interpDx, interpDy) = imuProcessor.computeInertialInterpolation(dtSec, isLowFeature = true)
            fusedX = interpDx
            fusedY = interpDy
        } else {
            // Update known planar velocity for smooth fallback
            val currentVx = if (dtSec > 0) fusedX / dtSec else 0f
            val currentVy = if (dtSec > 0) fusedY / dtSec else 0f
            imuProcessor.updateKnownVelocity(currentVx, currentVy)
            imuProcessor.computeInertialInterpolation(dtSec, isLowFeature = false)
        }

        // 4. Sensitivity & Inversion
        fusedX *= dpiScale
        fusedY *= dpiScale

        if (invertX) fusedX = -fusedX
        if (invertY) fusedY = -fusedY

        // 5. Guardrail: One Euro Filter for jitter reduction
        val (smoothedX, smoothedY) = oneEuroFilter.filter(fusedX, fusedY, timestampSec)

        val finalDx = smoothedX.roundToInt()
        val finalDy = smoothedY.roundToInt()

        // 6. Dispatch to Bluetooth HID Mouse
        var wheelStep = 0
        if (isScrollMode) {
            // Volume Up + Down converts Y displacement to mouse wheel
            wheelStep = (smoothedY / 3f).roundToInt()
            hidManager.postMovement(deltaX = 0, deltaY = 0, wheel = wheelStep)
        } else {
            hidManager.postMovement(deltaX = finalDx, deltaY = finalDy, wheel = 0)
        }

        // 7. Update Telemetry
        _telemetry.value = FusionTelemetry(
            rawOptX = result.rawDeltaX,
            rawOptY = result.rawDeltaY,
            gyroCompX = rotationCompX,
            finalDx = if (isScrollMode) 0 else finalDx,
            finalDy = if (isScrollMode) 0 else finalDy,
            wheelDelta = wheelStep,
            isLiftedOff = false,
            isLowFeature = result.isLowFeature,
            featureConfidence = result.featureConfidence,
            validFeatures = result.validFeaturesCount,
            fps = currentFps,
            isScrollMode = isScrollMode,
            debugBitmap = result.debugBitmap ?: _telemetry.value.debugBitmap
        )
    }
}
