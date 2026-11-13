package org.fieldradio.app.service

import android.content.Context
import fieldradio.session.SessionManager
import fieldradio.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fieldradio.app.prefs.SecureCredentialStore
import org.fieldradio.app.prefs.needsRefresh
import java.time.Instant

/**
 * P3 Credential Refresh Management
 * 
 * Automatically refreshes session credentials before expiry.
 * Monitors credential TTL and calls SessionManager.refresh() at appropriate times.
 * Stores refreshed credentials in EncryptedSharedPreferences.
 */

class CredentialRefreshManager(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val scope: CoroutineScope,
) {
    private val credentialStore = SecureCredentialStore(context)
    
    /**
     * Start monitoring session credential and auto-refresh before expiry.
     * Refresh happens 5 minutes before credential expiry.
     */
    fun startRefreshLoop(sessionState: SessionState) {
        scope.launch {
            val refreshThresholdMs = 5 * 60 * 1000 // 5 minutes
            
            while (scope.isActive && sessionManager.getCurrent() != null) {
                val current = sessionManager.getCurrent()
                
                if (current != null && current.needsRefresh(refreshThresholdMs)) {
                    try {
                        val refreshed = sessionManager.refresh()
                        
                        // Store refreshed credential securely
                        credentialStore.saveSessionCredential(
                            refreshed.sessionId,
                            refreshed.publishCredential,
                            refreshed.expiresAt
                        )
                        
                        // Log refresh (without exposing credential)
                        android.util.Log.d(
                            "CredentialRefresh",
                            "Session ${refreshed.sessionId} refreshed, expires ${refreshed.expiresAt}"
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("CredentialRefresh", "Failed to refresh credential", e)
                        // Continue monitoring, will retry on next check
                    }
                }
                
                // Check every 60 seconds
                delay(60_000)
            }
        }
    }
    
    /**
     * Store session credential when first created.
     */
    suspend fun storeInitialCredential(sessionState: SessionState) {
        credentialStore.saveSessionCredential(
            sessionState.sessionId,
            sessionState.publishCredential,
            sessionState.expiresAt
        )
    }
    
    /**
     * Revoke credential on session end.
     */
    suspend fun clearCredential(sessionId: String) {
        credentialStore.clearSessionCredential(sessionId)
    }
    
    /**
     * Get stored credential if still valid.
     */
    suspend fun getStoredCredential(sessionId: String): String? {
        return credentialStore.getSessionCredential(sessionId)
    }
}

/**
 * Extension function for BroadcastService to get credential refresh manager.
 * Call this during service initialization.
 */
fun BroadcastService.createCredentialRefreshManager(
    sessionManager: SessionManager,
    scope: CoroutineScope,
): CredentialRefreshManager {
    return CredentialRefreshManager(this, sessionManager, scope)
}
