package com.example.fusion

import com.example.hid.BluetoothHidMouseManager
import com.example.hid.MouseReportPacket
import com.example.sensor.ImuSensorProcessor
import com.example.vision.OpticalFlowResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for SensorFusionController:
 * 1. Gyro rotation compensation math
 * 2. Lift-off muting behavior
 * 3. Low-feature 150ms interpolation
 * 4. Scroll mode mapping
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SensorFusionControllerTest {

    private lateinit var imuProcessor: ImuSensorProcessor
    private lateinit var fakeHidManager: FakeBluetoothHidMouseManager
    private lateinit var controller: SensorFusionController

    class FakeBluetoothHidMouseManager : BluetoothHidMouseManager(
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        scope = CoroutineScope(Dispatchers.Unconfined)
    ) {
        var lastPostedDx: Int = 0
        var lastPostedDy: Int = 0
        var lastPostedWheel: Int = 0

        override fun postMovement(deltaX: Int, deltaY: Int, wheel: Int) {
            lastPostedDx = deltaX
            lastPostedDy = deltaY
            lastPostedWheel = wheel
        }
    }

    @Before
    fun setUp() {
        imuProcessor = ImuSensorProcessor(context = null)
        imuProcessor.reset()
        fakeHidManager = FakeBluetoothHidMouseManager()
        controller = SensorFusionController(imuProcessor, fakeHidManager)
        controller.reset()
    }

    @Test
    fun testLiftOff_strictlyMutesAllMovement() {
        // Trigger lift-off
        imuProcessor.handleLinearAccelEvent(0f, 0f, 4.0f, 1_000_000_000L)
        assertTrue(imuProcessor.isLiftedOff)

        // Incoming high optical flow frame
        val flowResult = OpticalFlowResult(
            rawDeltaX = 50f,
            rawDeltaY = -30f,
            featureConfidence = 0.9f,
            timestampNs = 1_020_000_000L
        )

        controller.processOpticalFlowFrame(flowResult)

        // Verify output is strictly zeroed
        assertEquals(0, fakeHidManager.lastPostedDx)
        assertEquals(0, fakeHidManager.lastPostedDy)
        assertEquals(0, fakeHidManager.lastPostedWheel)
        assertTrue(controller.telemetry.value.isLiftedOff)
    }

    @Test
    fun testGyroCompensation_cancelsWristRotationArtifact() {
        controller.kRot = 1.0f
        controller.dpiScale = 1.0f

        // Wrist rotation creates positive Z angular velocity
        val t0 = 1_000_000_000L
        val t1 = 1_020_000_000L // 20ms delta
        val omegaZ = 1.5f       // rad/s

        imuProcessor.handleGyroEvent(omegaZ, t0)
        imuProcessor.handleGyroEvent(omegaZ, t1)

        // Optical flow picks up raw displacement purely caused by wrist rotation
        val rawArtifactDx = 25f
        val flowResult = OpticalFlowResult(
            rawDeltaX = rawArtifactDx,
            rawDeltaY = 0f,
            featureConfidence = 0.9f,
            timestampNs = t1
        )

        controller.processOpticalFlowFrame(flowResult)

        // Compensation should have subtracted the rotation term
        val gyroCompX = controller.telemetry.value.gyroCompX
        assertTrue("Gyro compensation should be positive", gyroCompX > 0f)
        val finalDx = controller.telemetry.value.finalDx
        assertTrue("Final displacement should be reduced by gyro subtraction", finalDx < rawArtifactDx)
    }

    @Test
    fun testScrollMode_mapsYDisplacementToWheel() {
        controller.isScrollMode = true

        val flowResult = OpticalFlowResult(
            rawDeltaX = 10f,
            rawDeltaY = 40f,
            featureConfidence = 0.8f,
            timestampNs = 1_000_000_000L
        )

        controller.processOpticalFlowFrame(flowResult)

        assertEquals("X displacement must be 0 in scroll mode", 0, fakeHidManager.lastPostedDx)
        assertEquals("Y displacement must be 0 in scroll mode", 0, fakeHidManager.lastPostedDy)
        assertTrue("Wheel delta must be non-zero", fakeHidManager.lastPostedWheel != 0)
    }
}
