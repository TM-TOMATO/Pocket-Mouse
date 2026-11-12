package com.example.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ImuSensorProcessor:
 * 1. Z-axis gyroscope angular integration
 * 2. Instant Lift-off detection and settling recovery
 * 3. Low-feature 150ms damped velocity interpolation
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImuSensorProcessorTest {

    private lateinit var processor: ImuSensorProcessor

    @Before
    fun setUp() {
        processor = ImuSensorProcessor(context = null)
        processor.reset()
    }

    @Test
    fun testGyroscopeIntegration_accumulatesDeltaAngle() {
        val t0 = 1_000_000_000L // 1.00s in ns
        val t1 = 1_010_000_000L // 1.01s in ns (dt = 0.01s = 10ms)
        val omegaZ = 2.0f       // 2 rad/s

        // Initial sample
        processor.handleGyroEvent(omegaZ, t0)

        // Second sample with 10ms interval
        processor.handleGyroEvent(omegaZ, t1)

        // Expected delta angle = omegaZ * dt = 2.0 * 0.01 = 0.02 rad
        val consumed = processor.consumeAccumulatedRotationZ()
        assertEquals(0.02f, consumed, 0.001f)

        // Verify consumption resets the accumulator to 0
        val afterConsumption = processor.consumeAccumulatedRotationZ()
        assertEquals(0.0f, afterConsumption, 0.0001f)
    }

    @Test
    fun testLiftOffDetection_triggersAndRecovers() {
        assertFalse(processor.isLiftedOff)

        // 1. Sharp upward acceleration (phone lifted from desk)
        val t0 = 1_000_000_000L
        processor.handleLinearAccelEvent(ax = 0f, ay = 0f, az = 3.5f, timestampNs = t0)

        assertTrue("Lift-off flag should be set on az > 2.5", processor.isLiftedOff)

        // 2. High acceleration persists while in air
        processor.handleLinearAccelEvent(ax = 0.5f, ay = -0.3f, az = 1.8f, timestampNs = t0 + 20_000_000L)
        assertTrue(processor.isLiftedOff)

        // 3. Settling still on desk (az < 0.6)
        val settleTimeStart = t0 + 50_000_000L
        processor.handleLinearAccelEvent(ax = 0f, ay = 0f, az = 0.1f, timestampNs = settleTimeStart)
        // Still settling...
        assertTrue(processor.isLiftedOff)

        // Fast forward settling time (> 80ms)
        Thread.sleep(90)
        processor.handleLinearAccelEvent(ax = 0f, ay = 0f, az = 0.1f, timestampNs = settleTimeStart + 90_000_000L)
        assertFalse("Lift-off flag should clear after phone settles on desk", processor.isLiftedOff)
    }

    @Test
    fun testLowFeatureInterpolation_dampsAndCutsOffAt150ms() {
        // Known velocity: 100 pixels/sec X, 50 pixels/sec Y
        processor.updateKnownVelocity(100f, 50f)

        // Step 1: 50ms feature loss
        val (dx1, dy1) = processor.computeInertialInterpolation(dtSec = 0.05f, isLowFeature = true)
        assertTrue("dx should be positive and nonzero within 150ms", dx1 > 0f)
        assertTrue("dy should be positive and nonzero within 150ms", dy1 > 0f)

        // Step 2: Another 50ms (total 100ms) -> velocity damping should reduce displacement
        val (dx2, dy2) = processor.computeInertialInterpolation(dtSec = 0.05f, isLowFeature = true)
        assertTrue("Subsequent step should be damped", dx2 < dx1)

        // Step 3: Another 60ms (total 160ms, exceeds 150ms limit!)
        val (dx3, dy3) = processor.computeInertialInterpolation(dtSec = 0.06f, isLowFeature = true)
        assertEquals("Motion should strictly cut off to 0 after 150ms", 0f, dx3, 0.0001f)
        assertEquals("Motion should strictly cut off to 0 after 150ms", 0f, dy3, 0.0001f)
    }

    @Test
    fun testOneEuroFilter_reducesJitter() {
        val filter = OneEuroFilter(minCutoff = 1.0, beta = 0.01)

        var t = 0.0
        val dt = 0.016 // ~60Hz
        val baseValue = 10.0

        // Stationary state with noise (+-0.5)
        var filteredTotalNoise = 0.0
        var rawTotalNoise = 0.0

        for (i in 0 until 50) {
            val noise = if (i % 2 == 0) 0.5 else -0.5
            val raw = baseValue + noise
            val filtered = filter.filter(raw, t)

            if (i > 10) {
                rawTotalNoise += kotlin.math.abs(raw - baseValue)
                filteredTotalNoise += kotlin.math.abs(filtered - baseValue)
            }
            t += dt
        }

        assertTrue("Filtered noise must be less than raw jitter", filteredTotalNoise < rawTotalNoise)
    }
}
