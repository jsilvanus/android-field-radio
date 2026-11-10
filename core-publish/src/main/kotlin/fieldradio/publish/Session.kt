package fieldradio.publish

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the operator sees. Needs-operator states stop automatic recovery. */
enum class LinkStatus { OFFLINE, CONNECTING, LIVE, RECONNECTING, NEEDS_OPERATOR }

fun interface Backoff { fun delayMs(attempt: Int): Long }
val DefaultBackoff = Backoff { n -> minOf(30_000L, 1_000L shl minOf(n, 5)) }

/**
 * Keeps one broadcast alive: opens the route (tunnel if configured), connects the publisher, and on failure
 * retries with backoff. The audio pipeline keeps running (silence/buffer) while this reconnects.
 */
class BroadcastSession(
    private val scope: CoroutineScope,
    private val publisher: Publisher,
    private val tunnel: TunnelTransport?,
    private val backoff: Backoff = DefaultBackoff,
) {
    private val _status = MutableStateFlow(LinkStatus.OFFLINE)
    val status: StateFlow<LinkStatus> = _status
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private var job: Job? = null

    fun start(route: Route, app: String, streamName: String) {
        require(job == null) { "session already running" }   // prevents accidental duplicate sessions
        job = scope.launch {
            var attempt = 0
            while (true) {
                _status.value = if (attempt == 0) LinkStatus.CONNECTING else LinkStatus.RECONNECTING
                try {
                    val (host, port) = when (route) {
                        is Route.Direct -> route.host to route.port
                        is Route.ViaTunnel -> {
                            val t = checkNotNull(tunnel) { "no tunnel transport configured" }
                            t.open(route.spec)
                            val up = t.state.firstUp()
                            up.localHost to up.localPort
                        }
                    }
                    publisher.connect(host, port, app, streamName)
                    _status.value = LinkStatus.LIVE
                    _error.value = null
                    publisher.waitUntilLost()
                    attempt = 1
                } catch (e: NeedsOperator) {
                    _status.value = LinkStatus.NEEDS_OPERATOR; _error.value = e.message; return@launch
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _error.value = e.message ?: e::class.simpleName
                    attempt++
                }
                _status.value = LinkStatus.RECONNECTING
                delay(backoff.delayMs(attempt))
            }
        }
    }

    suspend fun stop() {
        job?.cancel(); job = null
        runCatching { publisher.disconnect() }
        runCatching { tunnel?.close() }
        _status.value = LinkStatus.OFFLINE
    }
}

class NeedsOperator(message: String) : Exception(message)

private suspend fun Flow<TunnelState>.firstUp(): TunnelState.Up {
    var result: TunnelState.Up? = null
    first { s ->
        when (s) {
            is TunnelState.Up -> { result = s; true }
            is TunnelState.HostKeyChanged -> throw NeedsOperator("SSH host key changed (${s.fingerprint})")
            TunnelState.AuthRefused -> throw NeedsOperator("SSH key refused by the server")
            else -> false
        }
    }
    return checkNotNull(result)
}

private suspend fun Publisher.waitUntilLost() {
    state.first { it == PublisherState.RECONNECTING || it == PublisherState.FAILED || it == PublisherState.IDLE }
}
