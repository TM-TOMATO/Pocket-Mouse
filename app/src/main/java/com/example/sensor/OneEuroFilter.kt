package com.example.sensor

import kotlin.math.PI
import kotlin.math.abs

/**
 * 1€ Filter (One Euro Filter) for noise filtering with dynamic cutoff frequency.
 * Reference: Casiez, G., Roussel, N. and Vogel, D. (CHI 2012)
 *
 * Provides smooth output at low speeds (jitter elimination) while minimizing lag
 * during rapid flicks/motions.
 */
class OneEuroFilter(
    private var minCutoff: Double = 1.0,  // f_c_min: Minimum cutoff frequency (Hz)
    private var beta: Double = 0.02,       // beta: Speed coefficient
    private var dCutoff: Double = 1.0      // f_c_d: Cutoff frequency for derivative (Hz)
) {
    private var xPrev: Double? = null
    private var dxPrev: Double = 0.0
    private var tPrev: Double? = null

    /**
     * Resets filter internal state.
     */
    fun reset() {
        xPrev = null
        dxPrev = 0.0
        tPrev = null
    }

    fun setParameters(minCutoff: Double, beta: Double) {
        this.minCutoff = minCutoff
        this.beta = beta
    }

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    /**
     * Filters a single scalar sample.
     * @param x Raw sensor or optical measurement
     * @param timestampSec Timestamp in seconds (must be strictly increasing)
     */
    fun filter(x: Double, timestampSec: Double): Double {
        val prevT = tPrev
        val prevX = xPrev

        if (prevT == null || prevX == null) {
            xPrev = x
            dxPrev = 0.0
            tPrev = timestampSec
            return x
        }

        val dt = (timestampSec - prevT).coerceAtLeast(1e-4)
        tPrev = timestampSec

        // Calculate derivative and filter it
        val dx = (x - prevX) / dt
        val aD = alpha(dCutoff, dt)
        val dxHat = aD * dx + (1.0 - aD) * dxPrev
        dxPrev = dxHat

        // Dynamic cutoff based on speed
        val cutoff = minCutoff + beta * abs(dxHat)
        val a = alpha(cutoff, dt)
        val xHat = a * x + (1.0 - a) * prevX
        xPrev = xHat

        return xHat
    }
}

/**
 * 2D vector wrapper around OneEuroFilter for smoothing planar (X, Y) displacements.
 */
class OneEuroFilter2D(
    minCutoff: Double = 1.0,
    beta: Double = 0.02,
    dCutoff: Double = 1.0
) {
    private val filterX = OneEuroFilter(minCutoff, beta, dCutoff)
    private val filterY = OneEuroFilter(minCutoff, beta, dCutoff)

    fun reset() {
        filterX.reset()
        filterY.reset()
    }

    fun setParameters(minCutoff: Double, beta: Double) {
        filterX.setParameters(minCutoff, beta)
        filterY.setParameters(minCutoff, beta)
    }

    fun filter(x: Float, y: Float, timestampSec: Double): Pair<Float, Float> {
        val fx = filterX.filter(x.toDouble(), timestampSec).toFloat()
        val fy = filterY.filter(y.toDouble(), timestampSec).toFloat()
        return Pair(fx, fy)
    }
}
