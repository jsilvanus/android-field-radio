package fieldradio.audio

/** Streaming linear resampler (mono). Good enough for speech and library files; swap for a polyphase one later. */
class LinearResampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private var pos = 0.0           // position in input, relative to [prev]
    private var prev = 0f
    private var primed = false

    /** Converts [input] and returns the produced samples. State carries across calls. */
    fun process(input: FloatArray, count: Int): FloatArray {
        if (inRate == outRate) return input.copyOf(count)
        val out = ArrayList<Float>((count * outRate / inRate) + 2)
        var i = 0
        if (!primed && count > 0) { prev = input[0]; primed = true }
        while (true) {
            val idx = pos.toInt()          // 0 => between prev and input[0]
            if (idx >= count) break
            val a = if (idx == 0) prev else input[idx - 1]
            val b = input[idx]
            val frac = (pos - idx).toFloat()
            out.add(a + (b - a) * frac)
            pos += step
        }
        pos -= count
        if (count > 0) prev = input[count - 1]
        return out.toFloatArray()
    }
}
