package fieldradio.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/** Internal format: 48 kHz mono float PCM in [-1, 1]. */
const val SAMPLE_RATE = 48_000

/** What happens to library playback when the microphone goes live. */
sealed interface TransitionPolicy {
    data object Stop : TransitionPolicy
    data class FadeOut(val ms: Int = 400) : TransitionPolicy
    data class Duck(val db: Float = -18f, val releaseMs: Int = 600) : TransitionPolicy
    data object Keep : TransitionPolicy
}

/** A source of PCM the mixer pulls from. Returns frames written; 0 means nothing available (treated as silence). */
fun interface PcmSource {
    fun read(out: FloatArray, count: Int): Int
}

fun dbToGain(db: Float): Float = 10f.pow(db / 20f)

/**
 * Pulled by a single audio thread; output length is the mixer clock, so the
 * encoder never stalls: with nothing playing it produces silence.
 * Not thread-safe except for the setters, which only write volatile fields.
 */
class Mixer(
    private val mic: PcmSource,
    private val library: PcmSource,
    var policy: TransitionPolicy = TransitionPolicy.FadeOut(),
    var micGainDb: Float = 0f,
    var playbackGainDb: Float = 0f,
) {
    @Volatile var micOn: Boolean = false
        set(value) {
            if (field != value) { field = value; onMicChanged(value) }
        }
    /** Set false by the library layer when a sound is stopped by [TransitionPolicy.Stop]. */
    @Volatile var stopRequested: Boolean = false
        private set

    private val limiter = Limiter()
    private var libGain = 1f          // current library gain factor (fade/duck state)
    private var libGainTarget = 1f
    private var libGainStep = 0f      // per-sample increment toward target
    private var micScratch = FloatArray(0)
    private var libScratch = FloatArray(0)

    /** Last block peak levels for the UI meters (0..1). */
    @Volatile var micPeak = 0f; private set
    @Volatile var outPeak = 0f; private set
    @Volatile var clipCount = 0L; private set

    fun consumeStopRequest(): Boolean {
        val r = stopRequested; stopRequested = false; return r
    }

    private fun onMicChanged(on: Boolean) {
        when (val p = policy) {
            TransitionPolicy.Keep -> setLibTarget(1f, 0)
            TransitionPolicy.Stop -> { if (on) { stopRequested = true; setLibTarget(0f, 0) } else setLibTarget(1f, 0) }
            is TransitionPolicy.FadeOut -> {
                if (on) { setLibTarget(0f, p.ms); stopRequested = true } else setLibTarget(1f, 0)
            }
            is TransitionPolicy.Duck -> setLibTarget(if (on) dbToGain(p.db) else 1f, if (on) 60 else p.releaseMs)
        }
    }

    private fun setLibTarget(target: Float, ms: Int) {
        libGainTarget = target
        val samples = ms * SAMPLE_RATE / 1000
        libGainStep = if (samples <= 0) { libGain = target; 0f } else (target - libGain) / samples
    }

    /** Fills [out] with [count] mixed, limited samples. */
    fun mix(out: FloatArray, count: Int) {
        if (micScratch.size < count) { micScratch = FloatArray(count); libScratch = FloatArray(count) }
        val micGain = dbToGain(micGainDb)
        val playGain = dbToGain(playbackGainDb)

        micScratch.fill(0f, 0, count)
        libScratch.fill(0f, 0, count)
        // The mic is always drained so the capture buffer never backs up; it only enters the mix when on.
        val micRead = mic.read(micScratch, count)
        if (micRead < count) micScratch.fill(0f, max(micRead, 0), count)
        val libRead = library.read(libScratch, count)
        if (libRead < count) libScratch.fill(0f, max(libRead, 0), count)

        var mp = 0f
        for (i in 0 until count) {
            if (libGain != libGainTarget) {
                libGain += libGainStep
                if ((libGainStep > 0f && libGain >= libGainTarget) || (libGainStep < 0f && libGain <= libGainTarget) || libGainStep == 0f) libGain = libGainTarget
            }
            val m = if (micOn) micScratch[i] * micGain else 0f
            mp = max(mp, abs(micScratch[i] * micGain))
            out[i] = m + libScratch[i] * playGain * libGain
        }
        micPeak = mp
        val clipped = limiter.process(out, count)
        clipCount += clipped
        var op = 0f
        for (i in 0 until count) op = max(op, abs(out[i]))
        outPeak = op
    }
}

/** Simple soft limiter: transparent below the knee, tanh-shaped above, hard ceiling at [ceiling]. */
class Limiter(private val ceiling: Float = 0.89f, private val knee: Float = 0.7f) {
    /** Returns the number of samples that exceeded the ceiling before limiting. */
    fun process(buf: FloatArray, count: Int): Int {
        var over = 0
        for (i in 0 until count) {
            val x = buf[i]
            val a = abs(x)
            if (a <= knee) continue
            if (a > ceiling) over++
            val range = ceiling - knee
            val shaped = knee + range * kotlin.math.tanh(((a - knee) / range).toDouble()).toFloat()
            buf[i] = if (x < 0f) -shaped else shaped
        }
        return over
    }
}

/** Float -> little-endian 16-bit PCM. */
fun floatToPcm16(src: FloatArray, count: Int, dst: ByteArray) {
    for (i in 0 until count) {
        val v = (src[i].coerceIn(-1f, 1f) * 32767f).toInt()
        dst[2 * i] = (v and 0xFF).toByte()
        dst[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
    }
}
