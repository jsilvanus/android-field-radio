package fieldradio.publish

import kotlinx.coroutines.flow.Flow

/**
 * Seam between this app and the SSH tunnel module being built in aidos (RFC-0108).
 * This repo does NOT implement SSH. The aidos module (in-process sshj/MINA, Ed25519 key in the vault,
 * pinned host key, keepalive, reconnect with capped backoff) will provide an implementation of [TunnelTransport];
 * until it exists the app uses [DirectTransport] (dev) or a fake in tests.
 *
 * Note: RFC-0108 phase 3 is a desktop *reverse* tunnel (-R); phone-side SSH (-L) and Keystore-backed key
 * storage are its phase 4. This seam asks for the local-forward form.
 */
sealed interface TunnelState {
    data object Idle : TunnelState
    data object Connecting : TunnelState
    data class Up(val localHost: String, val localPort: Int) : TunnelState
    data class Reconnecting(val attempt: Int, val reason: String) : TunnelState
    /** Needs the operator: pinned host key changed. */
    data class HostKeyChanged(val fingerprint: String) : TunnelState
    /** Needs the operator: key refused by the server. */
    data object AuthRefused : TunnelState
}

data class ForwardSpec(
    val sshHost: String,
    val sshPort: Int,
    val user: String,
    /** Remote target reached through the server, e.g. 127.0.0.1:1935 (the relay's loopback RTMP ingest). */
    val remoteHost: String,
    val remotePort: Int,
)

interface TunnelTransport {
    val state: Flow<TunnelState>
    /** Starts (or re-uses) the forward; completes when [TunnelState.Up]. Throws if a needs-operator state is reached. */
    suspend fun open(spec: ForwardSpec)
    suspend fun close()
}

/** Where the publisher dials. [Direct] = a host:port reachable as is; [ViaTunnel] = through a [TunnelTransport]. */
sealed interface Route {
    data class Direct(val host: String, val port: Int) : Route
    data class ViaTunnel(val spec: ForwardSpec) : Route
}

/** What the audio pipeline hands to the publisher: AAC-LC frames with a presentation time on the mixer clock. */
data class AacFrame(val data: ByteArray, val ptsUs: Long)

enum class PublisherState { IDLE, CONNECTING, LIVE, RECONNECTING, FAILED }

interface Publisher {
    val state: Flow<PublisherState>
    /** 2-byte AAC AudioSpecificConfig from the encoder; must be set before frames are sent. */
    fun setAudioConfig(asc: ByteArray)
    suspend fun connect(host: String, port: Int, app: String, streamName: String)
    fun send(frame: AacFrame)
    suspend fun disconnect()
}
