package com.usee.scanner

import com.usee.scanner.core.MotionEstimator
import com.usee.scanner.core.MotionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MotionEstimatorTest {

    @Test
    fun calibratesBeforeReporting() {
        val e = MotionEstimator.forRssi()
        val r = e.update(mapOf("a" to -50.0), 0)
        assertEquals(MotionLevel.CALIBRATING, r.level)
        assertFalse(r.activity)
    }

    @Test
    fun quietChannelStaysStill() {
        val e = MotionEstimator.forRssi()
        val rnd = Random(1)
        var last = e.update(mapOf("a" to -50.0), 0)
        for (t in 1..200) {
            // ±1 dB quantised jitter, typical of an idle link
            val v = -50.0 + listOf(-1, 0, 0, 0, 1)[rnd.nextInt(5)]
            last = e.update(mapOf("a" to v, "b" to -70.0, "c" to -80.0), t * 300L)
        }
        assertTrue("score ${last.score}", last.level == MotionLevel.NONE || last.level == MotionLevel.MINIMAL)
        assertFalse(last.activity)
    }

    @Test
    fun largeSwingsRaiseActivity() {
        val e = MotionEstimator.forRssi()
        for (t in 0..60) e.update(mapOf("a" to -50.0, "b" to -65.0), t * 300L)
        val rnd = Random(7)
        var last = e.update(mapOf("a" to -50.0), 0)
        for (t in 61..100) {
            last = e.update(
                mapOf("a" to -50.0 + rnd.nextInt(-8, 9), "b" to -65.0 + rnd.nextInt(-8, 9)),
                t * 300L,
            )
        }
        assertTrue("score ${last.score}", last.score > 3.0)
        assertTrue(last.activity)
        assertEquals(MotionLevel.HIGH, last.level)
    }

    @Test
    fun resetClearsState() {
        val e = MotionEstimator.forRssi()
        for (t in 0..30) e.update(mapOf("a" to -50.0 + (t % 9)), t * 300L)
        e.reset()
        assertEquals(MotionLevel.CALIBRATING, e.update(mapOf("a" to -50.0), 0).level)
    }

    @Test
    fun staleStreamsExpire() {
        val e = MotionEstimator(0.03, 1.0, 0.5, 1, staleAfterMs = 1000)
        e.update(mapOf("a" to -50.0), 0)
        // "a" vanishes; a new stream at a very different level must not be compared to it
        val r = e.update(mapOf("b" to -90.0), 5000)
        assertEquals(0, r.contributingStreams)
    }
}
