package org.fieldradio.app.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import fieldradio.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * P3 Secure Credential Storage
 * 
 * Uses EncryptedSharedPreferences to store:
 * - Invite codes (for control plane authentication)
 * - Session credentials (never logged or exported)
 * - Credential expiry times
 * 
 * Sensitive data is encrypted at rest using Android Keystore.
 */

class SecureCredentialStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "fieldradio_credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    suspend fun saveInviteCode(code: String, expiresAt: Instant) = withContext(Dispatchers.IO) {
        encryptedPrefs.edit().apply {
            putString("invite_code", code)
            putLong("invite_expires_at", expiresAt.toEpochMilli())
            apply()
        }
    }
    
    suspend fun getInviteCode(): String? = withContext(Dispatchers.IO) {
        val code = encryptedPrefs.getString("invite_code", null) ?: return@withContext null
        val expiresAt = encryptedPrefs.getLong("invite_expires_at", 0L)
        
        // Check if expired
        if (Instant.now().toEpochMilli() > expiresAt) {
            clearInviteCode()
            return@withContext null
        }
        
        return@withContext code
    }
    
    suspend fun clearInviteCode() = withContext(Dispatchers.IO) {
        encryptedPrefs.edit().apply {
            remove("invite_code")
            remove("invite_expires_at")
            apply()
        }
    }
    
    suspend fun saveSessionCredential(
        sessionId: String,
        credential: String,
        expiresAt: Instant
    ) = withContext(Dispatchers.IO) {
        encryptedPrefs.edit().apply {
            putString("session_credential_$sessionId", credential)
            putLong("session_expires_at_$sessionId", expiresAt.toEpochMilli())
            apply()
        }
    }
    
    suspend fun getSessionCredential(sessionId: String): String? = withContext(Dispatchers.IO) {
        val credential = encryptedPrefs.getString("session_credential_$sessionId", null)
            ?: return@withContext null
        
        val expiresAt = encryptedPrefs.getLong("session_expires_at_$sessionId", 0L)
        
        // Check if expired
        if (Instant.now().toEpochMilli() > expiresAt) {
            clearSessionCredential(sessionId)
            return@withContext null
        }
        
        return@withContext credential
    }
    
    suspend fun clearSessionCredential(sessionId: String) = withContext(Dispatchers.IO) {
        encryptedPrefs.edit().apply {
            remove("session_credential_$sessionId")
            remove("session_expires_at_$sessionId")
            apply()
        }
    }
    
    suspend fun clearAllCredentials() = withContext(Dispatchers.IO) {
        encryptedPrefs.edit().clear().apply()
    }
}

/**
 * Extension function to check if credential is valid (not expired).
 */
fun SessionState.isCredentialValid(): Boolean {
    return Instant.now() < this.expiresAt
}

/**
 * Extension function to check if credential needs refresh (within 5 minutes of expiry).
 */
fun SessionState.needsRefresh(thresholdMs: Long = 5 * 60 * 1000): Boolean {
    val now = Instant.now().toEpochMilli()
    val expiry = this.expiresAt.toEpochMilli()
    return (expiry - now) <= thresholdMs
}
