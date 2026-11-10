package fieldradio.audio

/** Thread-safe float ring buffer used between capture/decoder threads and the mixer thread. Drops oldest on overflow. */
class FloatRing(private val capacity: Int) : PcmSource {
    private val buf = FloatArray(capacity)
    private var head = 0   // read index
    private var size = 0
    @get:Synchronized val available: Int get() = size
    @get:Synchronized var overruns = 0L; private set

    @Synchronized fun write(src: FloatArray, count: Int) {
        for (i in 0 until count) {
            if (size == capacity) { head = (head + 1) % capacity; size--; overruns++ }
            buf[(head + size) % capacity] = src[i]; size++
        }
    }

    /** Returns samples read (may be fewer than [count]; the mixer pads with silence). */
    @Synchronized override fun read(out: FloatArray, count: Int): Int {
        val n = minOf(count, size)
        for (i in 0 until n) { out[i] = buf[head]; head = (head + 1) % capacity }
        size -= n
        return n
    }

    @Synchronized fun clear() { head = 0; size = 0 }
}
