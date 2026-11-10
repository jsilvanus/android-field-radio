package fieldradio.audio

import kotlin.math.abs
import kotlin.test.*

private fun const(v: Float) = PcmSource { out, n -> out.fill(v, 0, n); n }
private val silent = PcmSource { _, _ -> 0 }

class MixerTest {
    @Test fun silenceWhenNothingPlays() {
        val m = Mixer(silent, silent)
        val b = FloatArray(480)
        m.mix(b, 480)
        assertTrue(b.all { it == 0f })
    }

    @Test fun micExcludedUntilOn() {
        val m = Mixer(const(0.3f), silent, TransitionPolicy.Keep)
        val b = FloatArray(100); m.mix(b, 100)
        assertTrue(b.all { it == 0f })
        m.micOn = true; m.mix(b, 100)
        assertEquals(0.3f, b[50], 1e-4f)
    }

    @Test fun micPeakMeasuredEvenWhenOff() {
        val m = Mixer(const(0.5f), silent)
        m.mix(FloatArray(10), 10)
        assertEquals(0.5f, m.micPeak, 1e-4f)
    }

    @Test fun fadeOutReachesZeroAfterConfiguredTime() {
        val ms = 100
        val m = Mixer(silent, const(0.4f), TransitionPolicy.FadeOut(ms))
        val n = SAMPLE_RATE * ms / 1000
        val b = FloatArray(n + 480)
        m.micOn = true
        m.mix(b, b.size)
        assertTrue(b[0] > 0.3f, "starts near full level")
        assertTrue(abs(b[n + 100]) < 1e-4f, "silent after fade")
        for (i in 1 until n) assertTrue(b[i] <= b[i - 1] + 1e-6f, "monotonic fade")
        assertTrue(m.consumeStopRequest())
    }

    @Test fun stopPolicySilencesImmediately() {
        val m = Mixer(silent, const(0.4f), TransitionPolicy.Stop)
        m.micOn = true
        val b = FloatArray(10); m.mix(b, 10)
        assertTrue(b.all { it == 0f })
    }

    @Test fun duckLowersAndRestores() {
        val m = Mixer(silent, const(0.4f), TransitionPolicy.Duck(-20f, 10))
        val n = SAMPLE_RATE / 10
        m.micOn = true
        val b = FloatArray(n); m.mix(b, n)
        assertEquals(0.04f, b[n - 1], 0.005f)
        m.micOn = false
        val c = FloatArray(n); m.mix(c, n)
        assertEquals(0.4f, c[n - 1], 0.005f)
        assertFalse(m.consumeStopRequest())
    }

    @Test fun limiterNeverExceedsCeilingAndCountsClips() {
        val m = Mixer(const(0.9f), const(0.9f), TransitionPolicy.Keep)
        m.micOn = true
        val b = FloatArray(1000); m.mix(b, 1000)
        assertTrue(b.all { abs(it) <= 0.8901f })
        assertTrue(m.clipCount > 0)
    }

    @Test fun belowKneeIsTransparent() {
        val b = floatArrayOf(0.1f, -0.5f, 0.69f)
        Limiter().process(b, 3)
        assertContentEquals(floatArrayOf(0.1f, -0.5f, 0.69f), b)
    }

    @Test fun pcm16Conversion() {
        val d = ByteArray(4)
        floatToPcm16(floatArrayOf(1f, -1f), 2, d)
        assertEquals(0x7FFF, ((d[1].toInt() shl 8) or (d[0].toInt() and 0xFF)))
        assertEquals(-32767, ((d[3].toInt() shl 8) or (d[2].toInt() and 0xFF)))
    }
}

class ResamplerTest {
    @Test fun passthroughSameRate() {
        val r = LinearResampler(48000, 48000)
        val x = floatArrayOf(0.1f, 0.2f, 0.3f)
        assertContentEquals(x, r.process(x, 3))
    }
    @Test fun lengthRatio44kTo48k() {
        val r = LinearResampler(44100, 48000)
        var total = 0
        repeat(100) { total += r.process(FloatArray(441) { 0.1f }, 441).size }
        assertTrue(abs(total - 48000) <= 2, "got $total")
    }
}

class RingTest {
    @Test fun fifoAndPartialRead() {
        val r = FloatRing(8)
        r.write(floatArrayOf(1f, 2f, 3f), 3)
        val o = FloatArray(5)
        assertEquals(3, r.read(o, 5))
        assertEquals(listOf(1f, 2f, 3f), o.take(3))
        assertEquals(0, r.read(o, 5))
    }
    @Test fun overflowDropsOldest() {
        val r = FloatRing(4)
        r.write(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), 6)
        val o = FloatArray(4); r.read(o, 4)
        assertEquals(listOf(3f, 4f, 5f, 6f), o.toList())
        assertEquals(2L, r.overruns)
    }
}
