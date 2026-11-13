package fieldradio.session

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.net.URL
import java.time.Instant

/**
 * Control plane API client for android-field-radio.
 * MVP (P1-P2): minimal credential management (session ID + stream name).
 * P3+: JWT credentials and per-path auth.
 */

@Serializable
data class SessionResponse(
    val sessionId: String,
    val streamName: String,
    val publishUrl: String,
    val publishCredential: String,
    val captionToken: String? = null,
    val expiresAt: String,
    val relay: RelayInfo? = null,
)

@Serializable
data class RelayInfo(
    val apiUrl: String,
    val streamUrl: String? = null,
)

@Serializable
data class SessionRefreshResponse(
    val publishCredential: String,
    val expiresAt: String,
    val captionToken: String? = null,
)

@Serializable
data class StatusResponse(
    val status: String,
    val version: String? = null,
    val mediaUrl: String? = null,
    val relayUrl: String? = null,
    val credentialTtlSeconds: Int = 7200,
)

enum class ControlPlaneError {
    INVALID_INVITE,
    INVITE_EXPIRED,
    INVITE_REVOKED,
    STREAM_NAME_TAKEN,
    UNAUTHORIZED,
    INTERNAL_ERROR,
    NETWORK_ERROR,
}

data class ControlPlaneException(
    val error: ControlPlaneError,
    val message: String,
    val statusCode: Int? = null,
) : Exception("[$error] $message")

/**
 * Low-level HTTP client for control plane. Implementations can use okhttp, ktor, or other.
 * MVP: generated from OpenAPI or hand-written. Non-Android can use standard HTTP libs.
 */
interface ControlPlaneClient {
    /**
     * Create a session with an invite code or admin key.
     * Returns [SessionResponse] or throws [ControlPlaneException].
     */
    suspend fun createSession(
        inviteCode: String? = null,
        adminKey: String? = null,
        profileHint: String? = null,
    ): SessionResponse

    /**
     * Delete a session (end broadcast, revoke credential).
     * Idempotent: succeeds even if the session was already ended.
     */
    suspend fun deleteSession(
        sessionId: String,
        inviteCode: String? = null,
        adminKey: String? = null,
    )

    /**
     * Refresh a credential before expiry.
     */
    suspend fun refreshSession(
        sessionId: String,
        inviteCode: String? = null,
        adminKey: String? = null,
    ): SessionRefreshResponse

    /**
     * Get service status and hints.
     */
    suspend fun getStatus(): StatusResponse
}

/** Noop implementation for MVP testing (does not connect to any server). */
class NoopControlPlaneClient : ControlPlaneClient {
    override suspend fun createSession(
        inviteCode: String?,
        adminKey: String?,
        profileHint: String?,
    ) = SessionResponse(
        sessionId = "550e8400-0000-0000-0000-000000000000",
        streamName = profileHint?.take(32)?.lowercase()?.replace(Regex("[^a-z0-9_-]"), "-") ?: "test",
        publishUrl = "rtmps://localhost/live",
        publishCredential = "test-credential",
        expiresAt = "2099-12-31T23:59:59Z",
        relay = RelayInfo("http://localhost:8080/api/v1"),
    )

    override suspend fun deleteSession(
        sessionId: String,
        inviteCode: String?,
        adminKey: String?,
    ) {}

    override suspend fun refreshSession(
        sessionId: String,
        inviteCode: String?,
        adminKey: String?,
    ) = SessionRefreshResponse(
        publishCredential = "refreshed-credential",
        expiresAt = "2099-12-31T23:59:59Z",
    )

    override suspend fun getStatus() = StatusResponse("ok")
}

/**
 * Higher-level session manager: tracks one active session, handles refresh on expiry.
 * Used by the app's broadcast service.
 */
data class SessionState(
    val sessionId: String,
    val streamName: String,
    val publishUrl: String,
    val publishCredential: String,
    val expiresAt: Instant,
    val relayUrl: String? = null,
)

enum class SessionPhase { IDLE, CREATING, ACTIVE, ENDING, FAILED }

class SessionManager(
    private val client: ControlPlaneClient,
    private val refreshThresholdMs: Long = 5 * 60 * 1000, // refresh 5 min before expiry
) {
    private var current: SessionState? = null
    private var phase: SessionPhase = SessionPhase.IDLE
    
    val state: Flow<SessionState?>
        get() = kotlinx.coroutines.flow.flowOf(current)

    suspend fun create(inviteCode: String?, profileHint: String? = null): SessionState {
        phase = SessionPhase.CREATING
        return try {
            val resp = client.createSession(inviteCode, profileHint = profileHint)
            SessionState(
                sessionId = resp.sessionId,
                streamName = resp.streamName,
                publishUrl = resp.publishUrl,
                publishCredential = resp.publishCredential,
                expiresAt = Instant.parse(resp.expiresAt),
                relayUrl = resp.relay?.apiUrl,
            ).also { current = it; phase = SessionPhase.ACTIVE }
        } catch (e: Exception) {
            phase = SessionPhase.FAILED
            throw e
        }
    }

    suspend fun end() {
        val s = current ?: return
        phase = SessionPhase.ENDING
        try {
            client.deleteSession(s.sessionId)
            current = null
            phase = SessionPhase.IDLE
        } catch (e: Exception) {
            phase = SessionPhase.FAILED
            throw e
        }
    }

    suspend fun refresh(): SessionState {
        val s = current ?: throw ControlPlaneException(ControlPlaneError.UNAUTHORIZED, "No active session")
        return try {
            val resp = client.refreshSession(s.sessionId)
            SessionState(
                sessionId = s.sessionId,
                streamName = s.streamName,
                publishUrl = s.publishUrl,
                publishCredential = resp.publishCredential,
                expiresAt = Instant.parse(resp.expiresAt),
                relayUrl = s.relayUrl,
            ).also { current = it }
        } catch (e: Exception) {
            phase = SessionPhase.FAILED
            throw e
        }
    }

    fun getPhase() = phase
    fun getCurrent() = current
}
