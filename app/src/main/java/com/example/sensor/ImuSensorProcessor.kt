package com.example.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.exp

/**
 * Snapshot of IMU motion analysis and state.
 */
data class ImuMotionState(
    val isLiftedOff: Boolean = false,
    val omegaZ: Float = 0f,              // Angular velocity along Z-axis (rad/s)
    val accumulatedDeltaThetaZ: Float = 0f, // Integrated delta rotation angle since last frame (rad)
    val linearAccelZ: Float = 0f,        // Z-axis linear acceleration (m/s^2)
    val proximityDistanceCm: Float = 0f, // Proximity sensor distance (cm)
    val isLowFeatureActive: Boolean = false,
    val lowFeatureDurationMs: Long = 0L,
    val isSensorsAvailable: Boolean = true
)

/**
 * Processes high-frequency Gyroscope, Linear Acceleration, and Proximity sensor streams.
 * Handles Z-axis rotational compensation, instant Lift-off detection & muting,
 * and max 150ms damped inertial velocity interpolation during feature loss.
 */
class ImuSensorProcessor(
    private val context: Context? = null
) : SensorEventListener {

    companion object {
        private const val TAG = "DeskMouse_IMU"

        // Lift-off thresholds
        const val LIFT_OFF_ACCEL_THRESHOLD_Z = 2.5f     // m/s^2 upward acceleration
        const val LIFT_OFF_SETTLE_THRESHOLD_Z = 0.6f    // m/s^2 settling stillness
        const val LIFT_OFF_SETTLE_TIME_MS = 80L         // Required stillness duration to un-mute
        const val PROXIMITY_FAR_THRESHOLD_CM = 1.0f     // cm

        // Feature loss inertial interpolation limit
        const val MAX_LOW_FEATURE_INTERPOLATION_MS = 150L
        const val VELOCITY_DAMPING_LAMBDA = 8.0f        // Decay rate e^(-lambda * t)
    }

    private val sensorManager = context?.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val linearAccelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private val _motionState = MutableStateFlow(ImuMotionState())
    val motionState: StateFlow<ImuMotionState> = _motionState.asStateFlow()

    // Gyroscope tracking
    private var lastGyroTimestampNs: Long = 0L
    @Volatile
    private var accumulatedAngleZ: Float = 0f
    @Volatile
    private var latestOmegaZ: Float = 0f

    // Linear Accel & Lift-off tracking
    private var lastAccelTimestampNs: Long = 0L
    @Volatile
    private var latestLinearAccelZ: Float = 0f
    @Volatile
    private var latestLinearAccelX: Float = 0f
    @Volatile
    private var latestLinearAccelY: Float = 0f
    @Volatile
    private var latestProximityDistance: Float = 0f
    @Volatile
    var isLiftedOff: Boolean = false
        private set
    private var settleStartTimeMs: Long = 0L

    // Known velocity for short-term damped interpolation
    @Volatile
    private var lastKnownVx: Float = 0f // pixels or units per second
    @Volatile
    private var lastKnownVy: Float = 0f
    @Volatile
    private var lowFeatureElapsedMs: Long = 0L

    /**
     * Registers hardware sensor listeners.
     */
    fun start() {
        if (sensorManager == null) {
            Log.w(TAG, "SensorManager unavailable; running in software/synthetic mode")
            _motionState.value = _motionState.value.copy(isSensorsAvailable = false)
            return
        }

        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        linearAccelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        proximitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        _motionState.value = _motionState.value.copy(
            isSensorsAvailable = (gyroSensor != null || linearAccelSensor != null)
        )
    }

    /**
     * Unregisters sensor listeners.
     */
    fun stop() {
        sensorManager?.unregisterListener(this)
        reset()
    }

    fun reset() {
        accumulatedAngleZ = 0f
        latestOmegaZ = 0f
        lastGyroTimestampNs = 0L
        isLiftedOff = false
        settleStartTimeMs = 0L
        lowFeatureElapsedMs = 0L
        lastKnownVx = 0f
        lastKnownVy = 0f
    }

    /**
     * Consumes and resets the accumulated inter-frame rotation angle along the Z-axis.
     * Used by Optical Flow Fusion to calculate rotation artifact displacement.
     * @return Accumulated delta angle in radians
     */
    @Synchronized
    fun consumeAccumulatedRotationZ(): Float {
        val angle = accumulatedAngleZ
        accumulatedAngleZ = 0f
        return angle
    }

    /**
     * Updates the last verified planar velocity from a high-confidence optical flow frame.
     * @param vx Velocity X in units/sec
     * @param vy Velocity Y in units/sec
     */
    fun updateKnownVelocity(vx: Float, vy: Float) {
        lastKnownVx = vx
        lastKnownVy = vy
        lowFeatureElapsedMs = 0L
    }

    /**
     * Computes damped inertial displacement during feature loss.
     * Guardrail: strictly clamped to MAX_LOW_FEATURE_INTERPOLATION_MS (150ms).
     * @param dtSec Time delta in seconds
     * @param isLowFeature Whether current camera frame lacks features
     * @return Pair of (interpDx, interpDy)
     */
    fun computeInertialInterpolation(dtSec: Float, isLowFeature: Boolean): Pair<Float, Float> {
        if (!isLowFeature) {
            lowFeatureElapsedMs = 0L
            return Pair(0f, 0f)
        }

        val stepMs = (dtSec * 1000f).toLong().coerceAtLeast(1L)
        lowFeatureElapsedMs += stepMs

        if (lowFeatureElapsedMs > MAX_LOW_FEATURE_INTERPOLATION_MS) {
            // Cut off immediately after 150ms to prevent double-integration drift
            lastKnownVx = 0f
            lastKnownVy = 0f
            updateState()
            return Pair(0f, 0f)
        }

        // Exponential velocity damping: v(t) = v0 * exp(-lambda * t)
        val elapsedSec = lowFeatureElapsedMs / 1000f
        val dampingFactor = exp(-VELOCITY_DAMPING_LAMBDA * elapsedSec)

        // Include minor linear acceleration contribution with damping
        val currentVx = (lastKnownVx + latestLinearAccelX * dtSec) * dampingFactor
        val currentVy = (lastKnownVy + latestLinearAccelY * dtSec) * dampingFactor

        val dx = currentVx * dtSec
        val dy = currentVy * dtSec

        updateState()
        return Pair(dx, dy)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                handleGyroEvent(event.values[2], event.timestamp)
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                handleLinearAccelEvent(event.values[0], event.values[1], event.values[2], event.timestamp)
            }
            Sensor.TYPE_PROXIMITY -> {
                handleProximityEvent(event.values[0])
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    /**
     * Handles Gyroscope angular velocity on Z-axis (rad/s) with timestamp integration.
     */
    fun handleGyroEvent(omegaZ: Float, timestampNs: Long) {
        latestOmegaZ = omegaZ
        if (lastGyroTimestampNs != 0L && timestampNs > lastGyroTimestampNs) {
            val dtSec = (timestampNs - lastGyroTimestampNs) * 1e-9f
            if (dtSec in 0.0001f..0.2f) {
                synchronized(this) {
                    accumulatedAngleZ += omegaZ * dtSec
                }
            }
        }
        lastGyroTimestampNs = timestampNs
        updateState()
    }

    /**
     * Handles Linear Acceleration and triggers instant Lift-off detection & recovery.
     */
    fun handleLinearAccelEvent(ax: Float, ay: Float, az: Float, timestampNs: Long) {
        latestLinearAccelX = ax
        latestLinearAccelY = ay
        latestLinearAccelZ = az
        lastAccelTimestampNs = timestampNs

        val now = System.currentTimeMillis()

        // 1. Instant Lift-off trigger: sharp upward acceleration or strong vertical jerk
        if (az > LIFT_OFF_ACCEL_THRESHOLD_Z || abs(az) > 4.0f || latestProximityDistance > PROXIMITY_FAR_THRESHOLD_CM) {
            if (!isLiftedOff) {
                isLiftedOff = true
                Log.d(TAG, "Lift-off detected: az=$az, prox=$latestProximityDistance")
            }
            settleStartTimeMs = 0L
        } else if (isLiftedOff) {
            // 2. Settle & place-down check: must remain still on desk for settling duration
            if (abs(az) < LIFT_OFF_SETTLE_THRESHOLD_Z && latestProximityDistance <= PROXIMITY_FAR_THRESHOLD_CM) {
                if (settleStartTimeMs == 0L) {
                    settleStartTimeMs = now
                } else if (now - settleStartTimeMs >= LIFT_OFF_SETTLE_TIME_MS) {
                    isLiftedOff = false
                    settleStartTimeMs = 0L
                    Log.d(TAG, "Phone settled back on desk: Lift-off cleared")
                }
            } else {
                settleStartTimeMs = 0L
            }
        }

        updateState()
    }

    /**
     * Handles proximity sensor readings.
     */
    fun handleProximityEvent(distanceCm: Float) {
        latestProximityDistance = distanceCm
        if (distanceCm > PROXIMITY_FAR_THRESHOLD_CM && !isLiftedOff) {
            isLiftedOff = true
            settleStartTimeMs = 0L
        }
        updateState()
    }

    private fun updateState() {
        _motionState.value = ImuMotionState(
            isLiftedOff = isLiftedOff,
            omegaZ = latestOmegaZ,
            accumulatedDeltaThetaZ = accumulatedAngleZ,
            linearAccelZ = latestLinearAccelZ,
            proximityDistanceCm = latestProximityDistance,
            isLowFeatureActive = (lowFeatureElapsedMs > 0L),
            lowFeatureDurationMs = lowFeatureElapsedMs,
            isSensorsAvailable = _motionState.value.isSensorsAvailable
        )
    }
}
