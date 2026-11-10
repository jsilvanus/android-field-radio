package fieldradio.profile

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val PROFILE_SCHEMA = 1
val STREAM_NAME_REGEX = Regex("^[a-z0-9_-]{1,32}$")

@Serializable enum class SttEngine {
    @SerialName("none") NONE,
    @SerialName("android-standard") ANDROID_STANDARD,   // may use the network
    @SerialName("android-ondevice") ANDROID_ONDEVICE,
    @SerialName("whisper-bundled") WHISPER_BUNDLED;      // disabled until 2.0

    val available: Boolean get() = this != WHISPER_BUNDLED
}

@Serializable enum class Transition { @SerialName("stop") STOP, @SerialName("fade") FADE, @SerialName("duck") DUCK, @SerialName("keep") KEEP }

@Serializable data class ServerSettings(
    val controlUrl: String = "",
    /** Secret; never exported. */
    val inviteCode: String? = null,
)

@Serializable data class AudioSettings(
    val inputDeviceId: Int? = null,
    val bitrateKbps: Int = 64,
    val micGainDb: Float = 0f,
    val playbackGainDb: Float = 0f,
    val transition: Transition = Transition.FADE,
    val fadeMs: Int = 400,
    val duckDb: Float = -18f,
)

@Serializable data class CaptionSettings(
    val enabled: Boolean = false,
    val lang: String = "fi",
    val engine: SttEngine = SttEngine.NONE,
)

@Serializable data class SoundItem(
    val id: String,
    val title: String,
    val file: String,            // path relative to the profile's library directory
    val folder: String = "",
    val pinned: Boolean = false,
)

@Serializable data class Profile(
    val schema: Int = PROFILE_SCHEMA,
    val name: String,
    val eventName: String = "",
    val streamName: String,
    val server: ServerSettings = ServerSettings(),
    val audio: AudioSettings = AudioSettings(),
    val captions: CaptionSettings = CaptionSettings(),
    val library: List<SoundItem> = emptyList(),
    val monitorEnabled: Boolean = false,
)

fun validate(p: Profile): List<String> = buildList {
    if (p.schema != PROFILE_SCHEMA) add("unsupported schema ${p.schema}")
    if (p.name.isBlank()) add("name is empty")
    if (!STREAM_NAME_REGEX.matches(p.streamName)) add("streamName must match ${STREAM_NAME_REGEX.pattern}")
    if (p.audio.bitrateKbps !in 24..256) add("bitrateKbps out of range")
    if (p.audio.fadeMs !in 0..10_000) add("fadeMs out of range")
    if (p.audio.micGainDb !in -24f..24f) add("micGainDb out of range")
    if (p.audio.playbackGainDb !in -24f..12f) add("playbackGainDb out of range")
    if (!p.captions.engine.available) add("captions engine ${p.captions.engine} is not available before 2.0")
    if (p.captions.enabled && p.captions.engine == SttEngine.NONE) add("captions enabled but no engine selected")
    if (p.library.map { it.id }.toSet().size != p.library.size) add("duplicate sound ids")
    p.library.forEach { if (it.file.contains("..") || it.file.startsWith("/") || it.file.contains(":")) add("unsafe sound path: ${it.file}") }
}

private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

/** Credentials are never exported. */
fun exportJson(p: Profile): String = json.encodeToString(Profile.serializer(), p.copy(server = p.server.copy(inviteCode = null)))
fun storeJson(p: Profile): String = json.encodeToString(Profile.serializer(), p)
fun parseProfile(text: String): Profile = json.decodeFromString(Profile.serializer(), text)
