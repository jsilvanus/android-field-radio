package org.fieldradio.app.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * P5 Bluetooth Fallback Manager
 * 
 * Monitors connected audio devices and automatically falls back to built-in microphone
 * when Bluetooth headset disconnects. Provides device change notifications to UI.
 */

class BluetoothFallbackManager(
    private val context: Context,
    private val audioManager: AudioManager,
    private val engine: Engine,
    private val scope: CoroutineScope,
    private val onDeviceChanged: (AudioDeviceState) -> Unit = {},
) {
    
    private var currentDevice: AudioDeviceInfo? = null
    private var isBluetoothActive = false
    private val callback = BluetoothDeviceCallback()
    
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            audioManager.registerAudioDeviceCallback(callback, null)
        }
    }
    
    fun shutdown() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            audioManager.unregisterAudioDeviceCallback(callback)
        }
    }
    
    private inner class BluetoothDeviceCallback : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>?) {
            if (addedDevices == null) return
            
            scope.launch {
                for (device in addedDevices) {
                    if (isBluetoothMic(device)) {
                        // Bluetooth headset connected: consider switching
                        currentDevice = device
                        isBluetoothActive = true
                        
                        val state = AudioDeviceState(
                            micDevice = device,
                            connected = true,
                            isBluetoothHeadset = true
                        )
                        
                        onDeviceChanged(state)
                        android.util.Log.i("BluetoothFallback", "BT headset connected: ${device.productName}")
                    }
                }
            }
        }
        
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>?) {
            if (removedDevices == null) return
            
            scope.launch {
                for (device in removedDevices) {
                    if (isBluetoothMic(device) && device == currentDevice) {
                        // Bluetooth headset disconnected: fallback to built-in
                        android.util.Log.w(
                            "BluetoothFallback",
                            "BT headset disconnected: ${device.productName}, falling back to built-in mic"
                        )
                        
                        isBluetoothActive = false
                        currentDevice = getBuiltInMic()
                        
                        // Notify engine to switch input
                        val fallbackDevice = currentDevice ?: getBuiltInMic()
                        val state = AudioDeviceState(
                            micDevice = fallbackDevice,
                            connected = true,
                            isBluetoothHeadset = false
                        )
                        
                        onDeviceChanged(state)
                        
                        // UI: show fallback notification
                        android.util.Log.i("BluetoothFallback", "Fell back to built-in microphone")
                    }
                }
            }
        }
    }
    
    private fun isBluetoothMic(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
    }
    
    private fun getBuiltInMic(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        }
        return null
    }
}

/**
 * P5 Process Death Recovery
 * 
 * Saves the last active broadcast profile to SharedPreferences.
 * On service restart, can optionally restore (but not auto-start mic).
 */

class ProcessDeathRecovery(private val context: Context) {
    companion object {
        private const val PREF_NAME = "fieldradio_recovery"
        private const val KEY_LAST_PROFILE = "last_profile_json"
        private const val KEY_LAST_PROFILE_TIME = "last_profile_time"
        private const val KEY_RECOVERY_ENABLED = "recovery_enabled"
    }
    
    fun saveLastProfile(profileJson: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_LAST_PROFILE, profileJson)
            putLong(KEY_LAST_PROFILE_TIME, System.currentTimeMillis())
            apply()
        }
    }
    
    fun getLastProfile(): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_PROFILE, null)
    }
    
    fun getLastProfileAge(): Long {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedTime = prefs.getLong(KEY_LAST_PROFILE_TIME, 0L)
        return if (savedTime > 0) System.currentTimeMillis() - savedTime else -1
    }
    
    fun clearLastProfile() {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            remove(KEY_LAST_PROFILE)
            remove(KEY_LAST_PROFILE_TIME)
            apply()
        }
    }
    
    fun isRecoveryEnabled(): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_RECOVERY_ENABLED, true)
    }
    
    fun setRecoveryEnabled(enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_RECOVERY_ENABLED, enabled).apply()
    }
}

/**
 * P5 Permission Revocation Detector
 * 
 * Monitors RECORD_AUDIO permission and detects if it's revoked at runtime.
 * Stops microphone and shuts down service gracefully.
 */

class PermissionRevocationDetector(
    private val context: Context,
    private val engine: Engine,
    private val onPermissionRevoked: () -> Unit,
) {
    
    fun checkPermissionStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val result = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            
            if (result != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                android.util.Log.e("PermissionRevocation", "RECORD_AUDIO permission revoked!")
                
                // Stop recording
                engine.stop()
                
                // Notify app to shut down service
                onPermissionRevoked()
                
                // Log for analytics
                android.util.Log.w(
                    "PermissionRevocation",
                    "Broadcast stopped due to permission revocation"
                )
            }
        }
    }
    
    /**
     * Should be called periodically (e.g., every 30 seconds) or when app comes to foreground.
     */
    fun periodicCheck(intervalMs: Long = 30_000) {
        // In a real implementation, this would use a coroutine or timer
        // For now, callers should invoke checkPermissionStatus() periodically
    }
}

/**
 * P5 Battery & Thermal Monitoring (API 29+)
 * 
 * Displays warnings when battery is low or device is thermally throttled.
 */

class BatteryThermalMonitor(private val context: Context) {
    
    companion object {
        private const val LOW_BATTERY_THRESHOLD = 20 // percent
        private const val CRITICAL_BATTERY_THRESHOLD = 10 // percent
    }
    
    fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        return batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
    }
    
    fun isBatteryLow(): Boolean {
        return getBatteryLevel() <= LOW_BATTERY_THRESHOLD
    }
    
    fun isBatteryCritical(): Boolean {
        return getBatteryLevel() <= CRITICAL_BATTERY_THRESHOLD
    }
    
    @RequiresApi(Build.VERSION_CODES.Q)
    fun getThermalStatus(): Int {
        val powerProfile = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return powerProfile?.currentThermalStatus ?: 0
    }
    
    @RequiresApi(Build.VERSION_CODES.Q)
    fun isThermalThrottling(): Boolean {
        val status = getThermalStatus()
        return status >= android.os.PowerManager.THERMAL_STATUS_MODERATE
    }
    
    @RequiresApi(Build.VERSION_CODES.Q)
    fun getThermalStatusDescription(): String {
        return when (getThermalStatus()) {
            android.os.PowerManager.THERMAL_STATUS_NONE -> "Normal"
            android.os.PowerManager.THERMAL_STATUS_LIGHT -> "Light throttling"
            android.os.PowerManager.THERMAL_STATUS_MODERATE -> "Moderate throttling"
            android.os.PowerManager.THERMAL_STATUS_SEVERE -> "Severe throttling"
            android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "Critical thermal state"
            android.os.PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency thermal state"
            else -> "Unknown"
        }
    }
}
