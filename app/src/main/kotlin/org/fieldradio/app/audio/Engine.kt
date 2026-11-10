package org.fieldradio.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import fieldradio.audio.Mixer
import fieldradio.audio.SAMPLE_RATE
import fieldradio.audio.TransitionPolicy
import fieldradio.audio.floatToPcm16
import fieldradio.publish.AacFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Meters(val micPeak: Float = 0f, val outPeak: Float = 0f, val clips: Long = 0, val micOn: Boolean = false, val playing: Boolean = false)

/**
 * One mixer thread paced by the output sample clock: mic + library -> mixer -> limiter -> AAC. With nothing playing it
 * emits silence, so the stream never stalls. The same mixed PCM goes to the local monitor when enabled.
 */
class Engine(
    context: Context,
    private val onFrame: (AacFrame) -> Unit,
    private val onError: (String) -> Unit,
    private val onLibraryEnded: () -> Unit,
    bitrateKbps: Int,
    policy: TransitionPolicy,
    micGainDb: Float,
    playbackGainDb: Float,
    private val monitor: Boolean,
) {
    val mic = MicCapture(onError)
    val player = LibraryPlayer(context) { onLibraryEnded() }
    val encoder = AacEncoder(bitrateKbps) { onFrame(it) }
    val mixer = Mixer(mic.ring, player.ring, policy, micGainDb, playbackGainDb)
    private val _meters = MutableStateFlow(Meters())
    val meters: StateFlow<Meters> = _meters
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    fun start() {
        running = true
        mic.start()
        if (monitor) track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(SAMPLE_RATE / 2 * 2).build().also { it.play() }
        thread = Thread({ loop() }, "mixer").apply { priority = Thread.MAX_PRIORITY; isDaemon = true; start() }
    }

    private fun loop() {
        val block = 1024
        val f = FloatArray(block); val pcm = ByteArray(block * 2)
        val periodNs = block * 1_000_000_000L / SAMPLE_RATE
        var next = System.nanoTime()
        try {
            while (running) {
                mixer.mix(f, block)
                if (mixer.consumeStopRequest() && player.playing) player.stop()   // policy asked the library to stop
                floatToPcm16(f, block, pcm)
                encoder.encode(pcm, pcm.size)
                // local monitor: library only. While the mic is live the monitor gets silence, so the live mic is never
                // played on the phone speaker (feedback). Headset mic monitoring is a later option.
                track?.write(if (mixer.micOn) ByteArray(pcm.size) else pcm, 0, pcm.size)
                _meters.value = Meters(mixer.micPeak, mixer.outPeak, mixer.clipCount, mixer.micOn, player.playing)
                next += periodNs
                val wait = (next - System.nanoTime()) / 1_000_000
                if (wait > 0) Thread.sleep(wait) else if (wait < -500) next = System.nanoTime()
            }
        } catch (e: Exception) { onError("Audio engine stopped: ${e.message}") }
    }

    fun setMic(on: Boolean) { mixer.micOn = on }
    fun play(uri: Uri) = player.play(uri)
    fun stopPlayback() = player.stop()

    fun stop() {
        running = false
        runCatching { thread?.join(1000) }
        mic.stop(); player.stop(); encoder.release()
        runCatching { track?.stop(); track?.release() }
    }
}
