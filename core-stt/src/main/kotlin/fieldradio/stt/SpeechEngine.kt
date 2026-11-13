package fieldradio.stt

import kotlinx.coroutines.flow.Flow

/**
 * Speech-to-text abstraction for MVP (noop) and post-MVP engines.
 * MVP ships with [NoopSpeechEngine]; real engines (android-standard, on-device, whisper.cpp) added post-MVP.
 *
 * The engine is fed only from the microphone tap (never from the mixed stream).
 * Produces utterance-level events: Partial (intermediate recognition) and Final (confirmed text).
 */

enum class SttEventType { PARTIAL, FINAL }

data class SttEvent(
    val type: SttEventType,
    val text: String,
    /** Capture time (nanoseconds since some reference), synced to mixer clock. */
    val captureTimeNs: Long,
    /** End time of this segment (nanoseconds), optional for partials. */
    val endTimeNs: Long? = null,
    /** Language code (e.g., "fi", "en"). */
    val language: String? = null,
)

interface SpeechEngine {
    /** Start recognition for the given language and sample rate. */
    suspend fun start(language: String, sampleRate: Int)
    
    /** Feed PCM samples (16-bit, mono). The engine should not block; queue internally. */
    fun feed(pcm16: ShortArray, captureTimeNs: Long)
    
    /** Events: Partial and Final utterances. */
    val events: Flow<SttEvent>
    
    /** Stop recognition and close resources. */
    suspend fun stop()
}

/** MVP noop engine: ignores input, produces no events. */
class NoopSpeechEngine : SpeechEngine {
    override suspend fun start(language: String, sampleRate: Int) {}
    override fun feed(pcm16: ShortArray, captureTimeNs: Long) {}
    override val events: Flow<SttEvent>
        get() = kotlinx.coroutines.flow.emptyFlow()
    override suspend fun stop() {}
}
