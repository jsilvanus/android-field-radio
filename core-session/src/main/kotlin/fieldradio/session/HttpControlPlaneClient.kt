package fieldradio.session

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration

/**
 * OkHttp-based implementation of ControlPlaneClient.
 * Handles HTTP communication with field-radio-control service.
 * 
 * P3 feature: Credentials stored in EncryptedSharedPreferences on Android.
 */
class HttpControlPlaneClient(
    private val baseUrl: String = "http://127.0.0.1:9100/v1/control",
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(10))
        .build(),
) : ControlPlaneClient {
    
    private val json = Json { ignoreUnknownKeys = true }
    private val contentType = "application/json".toMediaType()

    override suspend fun createSession(
        inviteCode: String?,
        adminKey: String?,
        profileHint: String?,
    ): SessionResponse {
        val body = buildJsonBody(mapOf(
            "profileHint" to profileHint,
        ))
        
        val request = Request.Builder()
            .url("$baseUrl/sessions")
            .post(body)
            .addHeaders(inviteCode, adminKey)
            .build()
        
        return execute<SessionResponse>(request, "create session")
    }

    override suspend fun deleteSession(
        sessionId: String,
        inviteCode: String?,
        adminKey: String?,
    ) {
        val request = Request.Builder()
            .url("$baseUrl/sessions/$sessionId")
            .delete()
            .addHeaders(inviteCode, adminKey)
            .build()
        
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful && response.code != 404) {
            throw ControlPlaneException(
                ControlPlaneError.INTERNAL_ERROR,
                "Delete session failed: ${response.code}",
                response.code
            )
        }
        response.close()
    }

    override suspend fun refreshSession(
        sessionId: String,
        inviteCode: String?,
        adminKey: String?,
    ): SessionRefreshResponse {
        val request = Request.Builder()
            .url("$baseUrl/sessions/$sessionId/refresh")
            .post("{}".toRequestBody(contentType))
            .addHeaders(inviteCode, adminKey)
            .build()
        
        return execute<SessionRefreshResponse>(request, "refresh session")
    }

    override suspend fun getStatus(): StatusResponse {
        val request = Request.Builder()
            .url("$baseUrl/status")
            .get()
            .build()
        
        return try {
            execute<StatusResponse>(request, "get status")
        } catch (e: Exception) {
            throw ControlPlaneException(
                ControlPlaneError.NETWORK_ERROR,
                "Status check failed: ${e.message}",
            )
        }
    }

    // Private helpers

    private fun Request.Builder.addHeaders(
        inviteCode: String?,
        adminKey: String?,
    ): Request.Builder {
        if (inviteCode != null) {
            addHeader("X-Invite-Code", inviteCode)
        }
        if (adminKey != null) {
            addHeader("X-Admin-Key", adminKey)
        }
        return this
    }

    private fun <T> execute(
        request: Request,
        action: String,
        clazz: kotlin.reflect.KClass<T>? = null,
    ): T {
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: Exception) {
            throw ControlPlaneException(
                ControlPlaneError.NETWORK_ERROR,
                "Network error during $action: ${e.message}",
            )
        }

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            val error = parseErrorResponse(errorBody)
            throw ControlPlaneException(
                error.first,
                error.second,
                response.code
            )
        }

        val body = response.body?.string() ?: "{}"
        response.close()

        return @Suppress("UNCHECKED_CAST")
        when (clazz) {
            SessionResponse::class -> json.decodeFromString<SessionResponse>(body) as T
            SessionRefreshResponse::class -> json.decodeFromString<SessionRefreshResponse>(body) as T
            StatusResponse::class -> json.decodeFromString<StatusResponse>(body) as T
            else -> body as T
        }
    }

    private inline fun <reified T> execute(
        request: Request,
        action: String,
    ): T {
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: Exception) {
            throw ControlPlaneException(
                ControlPlaneError.NETWORK_ERROR,
                "Network error during $action: ${e.message}",
            )
        }

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            val error = parseErrorResponse(errorBody)
            throw ControlPlaneException(
                error.first,
                error.second,
                response.code
            )
        }

        val body = response.body?.string() ?: "{}"
        response.close()

        return json.decodeFromString<T>(body)
    }

    private fun parseErrorResponse(body: String): Pair<ControlPlaneError, String> {
        return try {
            val errorObj = json.decodeFromString<Map<String, String>>(body)
            val code = errorObj["code"] ?: "internal_error"
            val message = errorObj["message"] ?: "Unknown error"
            val error = when (code) {
                "invalid_invite" -> ControlPlaneError.INVALID_INVITE
                "invite_expired" -> ControlPlaneError.INVITE_EXPIRED
                "invite_revoked" -> ControlPlaneError.INVITE_REVOKED
                "stream_name_taken" -> ControlPlaneError.STREAM_NAME_TAKEN
                "unauthorized" -> ControlPlaneError.UNAUTHORIZED
                else -> ControlPlaneError.INTERNAL_ERROR
            }
            error to message
        } catch (e: Exception) {
            ControlPlaneError.INTERNAL_ERROR to "Failed to parse error: $body"
        }
    }

    private fun buildJsonBody(map: Map<String, Any?>): okhttp3.RequestBody {
        val filtered = map.filterValues { it != null }
        val json = Json.encodeToString(
            kotlinx.serialization.json.JsonObject(
                filtered.mapValues { (_, v) ->
                    when (v) {
                        is String -> kotlinx.serialization.json.JsonPrimitive(v)
                        is Int -> kotlinx.serialization.json.JsonPrimitive(v)
                        is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                        null -> kotlinx.serialization.json.JsonNull
                        else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
                    }
                }
            )
        )
        return json.toRequestBody(contentType)
    }
}
