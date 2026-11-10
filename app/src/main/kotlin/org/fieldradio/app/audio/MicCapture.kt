package org.fieldradio.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import fieldradio.audio.FloatRing
import fieldradio.audio.SAMPLE_RATE

/**
 * The single microphone capture. Everything that needs microphone PCM (the mixer now, STT later) reads from here;
 * nothing else opens the microphone, so there is no contention.
 */
class MicCapture(private val onError: (String) -> Unit) {
    val ring = FloatRing(SAMPLE_RATE * 2)                 // 2 s
    /** Mic-only tap for a later speech engine; filled only when [sttTapEnabled]. */
    val sttRing = FloatRing(SAMPLE_RATE * 10)
    @Volatile var sttTapEnabled = false
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")   // checked by the caller before the service starts
    fun start(source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION) {
        if (running) return
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 4096))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); onError("Microphone could not be opened"); return }
        record = rec; running = true
        rec.startRecording()
        thread = Thread({
            val s = ShortArray(960); val f = FloatArray(960)
            while (running) {
                val n = rec.read(s, 0, s.size)
                if (n < 0) { onError("Microphone read error $n"); break }
                for (i in 0 until n) f[i] = s[i] / 32768f
                ring.write(f, n)
                if (sttTapEnabled) sttRing.write(f, n)
            }
        }, "mic-capture").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { thread?.join(500) }
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null; thread = null
    }
}
