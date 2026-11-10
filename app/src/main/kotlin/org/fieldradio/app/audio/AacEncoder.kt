package org.fieldradio.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import fieldradio.audio.SAMPLE_RATE
import fieldradio.publish.AacFrame

/** AAC-LC, 48 kHz mono. Input is 16-bit little-endian PCM; output are raw AAC frames (no ADTS). */
class AacEncoder(bitrateKbps: Int, private val onFrame: (AacFrame) -> Unit) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    /** AudioSpecificConfig for AAC-LC 48 kHz mono: object type 2, frequency index 3, 1 channel. */
    val audioSpecificConfig: ByteArray = byteArrayOf(0x11, 0x88.toByte())
    private var pts = 0L
    private val info = MediaCodec.BufferInfo()

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Feeds one PCM block and delivers any finished frames. Called from the engine thread. */
    fun encode(pcm: ByteArray, bytes: Int) {
        var off = 0
        while (off < bytes) {
            val i = codec.dequeueInputBuffer(20_000)
            if (i >= 0) {
                val b = codec.getInputBuffer(i)!!; b.clear()
                val n = minOf(b.capacity(), bytes - off)
                b.put(pcm, off, n); off += n
                codec.queueInputBuffer(i, 0, n, pts, 0)
                pts += n / 2L * 1_000_000L / SAMPLE_RATE
            }
            drain()
        }
        drain()
    }

    private fun drain() {
        while (true) {
            val o = codec.dequeueOutputBuffer(info, 0)
            if (o < 0) return
            if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                val out = ByteArray(info.size)
                val b = codec.getOutputBuffer(o)!!; b.position(info.offset); b.get(out)
                onFrame(AacFrame(out, info.presentationTimeUs))
            }
            codec.releaseOutputBuffer(o, false)
        }
    }

    fun release() { runCatching { codec.stop() }; runCatching { codec.release() } }
}
