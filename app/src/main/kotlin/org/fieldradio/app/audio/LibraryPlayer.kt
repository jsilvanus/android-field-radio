package org.fieldradio.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import fieldradio.audio.FloatRing
import fieldradio.audio.LinearResampler
import fieldradio.audio.SAMPLE_RATE

/**
 * Decodes one library file at a time to 48 kHz mono float PCM into [ring]. The mixer reads the ring, so the
 * same samples reach the stream and the local monitor (no acoustic capture of the speaker).
 */
class LibraryPlayer(private val context: Context, private val onEnded: () -> Unit) {
    val ring = FloatRing(SAMPLE_RATE * 2)
    @Volatile var playing = false; private set
    @Volatile var paused = false
    @Volatile private var cancel = false
    private var thread: Thread? = null

    fun play(uri: Uri) {
        stop()
        cancel = false; playing = true; paused = false
        thread = Thread({ runCatching { decode(uri) }; playing = false; if (!cancel) onEnded() }, "library-decode").apply { isDaemon = true; start() }
    }

    fun stop() {
        cancel = true
        runCatching { thread?.join(800) }
        ring.clear(); playing = false; paused = false
    }

    private fun decode(uri: Uri) {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: error("no audio track")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var resampler = LinearResampler(rate, SAMPLE_RATE)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (!cancel) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        val nr = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (nr != rate) { rate = nr; resampler = LinearResampler(rate, SAMPLE_RATE) }
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frames = info.size / 2 / channels
                        val mono = FloatArray(frames)
                        for (f in 0 until frames) { var sum = 0f; for (c in 0 until channels) sum += buf.get(f * channels + c) / 32768f; mono[f] = sum / channels }
                        val out = resampler.process(mono, frames)
                        // backpressure: wait while the ring is nearly full or paused
                        while (!cancel && (paused || ring.available > SAMPLE_RATE + out.size)) Thread.sleep(10)
                        if (!cancel) ring.write(out, out.size)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            // let the tail drain before reporting the end
            while (!cancel && ring.available > 0) Thread.sleep(20)
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }; runCatching { ex.release() }
        }
    }
}
