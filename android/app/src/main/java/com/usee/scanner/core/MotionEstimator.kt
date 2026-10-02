package com.usee.scanner.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class MotionLevel(val label: String) {
    CALIBRATING("Calibrating"),
    NONE("Still"),
    MINIMAL("Minimal"),
    MODERATE("Moderate"),
    HIGH("High"),
}

data class MotionReading(
    /** Smoothed noise-normalised residual. ≈1.0 means "behaves like the learned noise floor". */
    val score: Double,
    val level: MotionLevel,
    /** True when accumulated evidence says the RF channel is being disturbed. */
    val activity: Boolean,
    /** 0..1 confidence in [activity]. Heuristic, not a calibrated probability. */
    val confidence: Double,
    val contributingStreams: Int,
    val framesSeen: Long,
) {
    companion object {
        val EMPTY = MotionReading(0.0, MotionLevel.CALIBRATING, false, 0.0, 0, 0)
    }
}

/**
 * Noise-normalised residual motion estimator.
 *
 * The structure follows the RuView multi-BSSID pipeline
 * (v2/crates/wifi-densepose-wifiscan/src/pipeline): an EMA predictor per
 * stream (alpha 0.3, `predictive_gate.rs`), an EMA-smoothed weighted residual
 * (alpha 0.3, `motion_estimator.rs`) and a decaying evidence accumulator
 * (decay 0.95, `quality_gate.rs`).
 *
 * Difference from the Rust pipeline: residuals are divided by a per-stream,
 * slowly-learned noise floor so that thresholds are unit-free and work for
 * both RSSI in dB and CSI amplitudes. The thresholds below are heuristics
 * chosen for that normalisation (≈1.0 is pure noise); they are not measured
 * accuracy figures.
 */
class MotionEstimator(
    private val noiseAlpha: Double,
    private val initialNoise: Double,
    private val noiseFloor: Double,
    private val warmupFrames: Int,
    private val staleAfterMs: Long = 60_000,
) {
    private class Stream(var prediction: Double, var noise: Double, var lastSeenMs: Long, var samples: Int)

    private val streams = HashMap<String, Stream>()
    private var ema = 1.0
    private var evidence = 0.0
    private var frames = 0L

    fun reset() {
        streams.clear()
        ema = 1.0
        evidence = 0.0
        frames = 0
    }

    /**
     * Feeds one observation frame.
     * @param values stream id -> measurement (dBm for RSSI, normalised amplitude for CSI)
     * @param weights optional per-stream weight in (0, 1]; defaults to 1
     */
    fun update(values: Map<String, Double>, nowMs: Long, weights: Map<String, Double> = emptyMap()): MotionReading {
        frames++
        var weighted = 0.0
        var weightTotal = 0.0
        var contributing = 0
        for ((id, v) in values) {
            if (v.isNaN() || v.isInfinite()) continue
            val s = streams[id]
            if (s == null) {
                streams[id] = Stream(v, initialNoise, nowMs, 1)
                continue
            }
            val residual = v - s.prediction
            s.prediction = PRED_ALPHA * v + (1 - PRED_ALPHA) * s.prediction
            s.lastSeenMs = nowMs
            s.samples++
            val mag = abs(residual)
            val z = mag / max(s.noise, noiseFloor)
            // Asymmetric, outlier-clipped noise tracker: it falls quickly to a
            // quiet floor but rises slowly, so sustained motion is not
            // re-learned as "noise" within seconds.
            val target = min(mag, OUTLIER_CLIP * s.noise)
            val a = if (target > s.noise) noiseAlpha * RISE_FACTOR else noiseAlpha
            s.noise += a * (target - s.noise)
            s.noise = max(s.noise, noiseFloor)
            val w = (weights[id] ?: 1.0).coerceIn(0.05, 1.0)
            weighted += w * z
            weightTotal += w
            if (z > 1.5) contributing++
        }
        streams.entries.removeAll { nowMs - it.value.lastSeenMs > staleAfterMs }

        if (weightTotal > 0) {
            val frameScore = weighted / weightTotal
            ema = SCORE_ALPHA * frameScore + (1 - SCORE_ALPHA) * ema
            evidence = evidence * EVIDENCE_DECAY + max(0.0, ema - T_NONE)
        }
        val level = when {
            frames < warmupFrames -> MotionLevel.CALIBRATING
            ema < T_NONE -> MotionLevel.NONE
            ema < T_MINIMAL -> MotionLevel.MINIMAL
            ema < T_MODERATE -> MotionLevel.MODERATE
            else -> MotionLevel.HIGH
        }
        val warmedUp = frames >= warmupFrames
        return MotionReading(
            score = ema,
            level = level,
            activity = warmedUp && evidence > EVIDENCE_THRESHOLD,
            confidence = if (warmedUp) (evidence / (3 * EVIDENCE_THRESHOLD)).coerceIn(0.0, 1.0) else 0.0,
            contributingStreams = contributing,
            framesSeen = frames,
        )
    }

    companion object {
        const val PRED_ALPHA = 0.3
        const val SCORE_ALPHA = 0.3
        const val EVIDENCE_DECAY = 0.95
        const val EVIDENCE_THRESHOLD = 1.0
        const val OUTLIER_CLIP = 3.0
        const val RISE_FACTOR = 0.15
        const val T_NONE = 1.35
        const val T_MINIMAL = 1.9
        const val T_MODERATE = 3.0

        /** Phone RSSI in dBm, sampled at ~2-4 Hz. Integer dBm quantisation → 0.5 dB floor. */
        fun forRssi() = MotionEstimator(noiseAlpha = 0.03, initialNoise = 1.0, noiseFloor = 0.5, warmupFrames = 12)

        /** ESP32 CSI amplitudes (per-frame mean-normalised), ~20-100 Hz. */
        fun forCsi() = MotionEstimator(noiseAlpha = 0.004, initialNoise = 0.05, noiseFloor = 0.01, warmupFrames = 60)
    }
}
