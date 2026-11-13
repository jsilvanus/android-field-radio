package org.fieldradio.app.ui

import android.media.AudioDeviceInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.fieldradio.app.audio.AudioDeviceState

/**
 * P2 Audio Device Selection UI Component
 * 
 * Displays available input devices (built-in mic, Bluetooth headsets, USB mics)
 * with warnings for narrowband devices (8 kHz sample rate).
 * Allows operator to select preferred audio input device.
 */

data class DeviceSelectionState(
    val availableDevices: List<AudioDeviceInfo> = emptyList(),
    val selectedDevice: AudioDeviceInfo? = null,
    val showNarrowbandWarning: Boolean = false,
    val isBluetoothHeadset: Boolean = false,
    val isUsbDevice: Boolean = false,
)

enum class AudioDeviceType {
    BUILT_IN_MIC,
    BLUETOOTH_HEADSET,
    USB_MICROPHONE,
    UNKNOWN,
}

fun getDeviceType(device: AudioDeviceInfo): AudioDeviceType {
    return when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioDeviceType.BUILT_IN_MIC
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioDeviceType.BLUETOOTH_HEADSET
        AudioDeviceInfo.TYPE_USB_DEVICE -> AudioDeviceType.USB_MICROPHONE
        else -> AudioDeviceType.UNKNOWN
    }
}

fun getDeviceIcon(type: AudioDeviceType): ImageVector {
    return when (type) {
        AudioDeviceType.BUILT_IN_MIC -> Icons.Filled.Mic
        AudioDeviceType.BLUETOOTH_HEADSET -> Icons.Filled.Headphones
        AudioDeviceType.USB_MICROPHONE -> Icons.Filled.Headphones
        AudioDeviceType.UNKNOWN -> Icons.Filled.Mic
    }
}

fun getDeviceLabel(type: AudioDeviceType): String {
    return when (type) {
        AudioDeviceType.BUILT_IN_MIC -> "Built-in Microphone"
        AudioDeviceType.BLUETOOTH_HEADSET -> "Bluetooth Headset"
        AudioDeviceType.USB_MICROPHONE -> "USB Microphone"
        AudioDeviceType.UNKNOWN -> "Audio Device"
    }
}

@Composable
fun DeviceSelectionDialog(
    state: DeviceSelectionState,
    onDeviceSelected: (AudioDeviceInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Audio Input Device") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(state.availableDevices) { device ->
                    val deviceType = getDeviceType(device)
                    val isSelected = device == state.selectedDevice
                    val isBluetooth = deviceType == AudioDeviceType.BLUETOOTH_HEADSET
                    val isNarrowband = device.sampleRates.any { it == 8000 }
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeviceSelected(device) }
                            .padding(12.dp)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = getDeviceIcon(deviceType),
                                contentDescription = getDeviceLabel(deviceType),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = device.productName.ifEmpty { getDeviceLabel(deviceType) },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = getDeviceLabel(deviceType),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (isNarrowband && isBluetooth) {
                                    Text(
                                        text = "⚠️ Narrowband (8 kHz) — audio quality reduced",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
fun DeviceSelectionButton(
    state: DeviceSelectionState,
    onShowDialog: () -> Unit,
) {
    val deviceType = state.selectedDevice?.let { getDeviceType(it) } ?: AudioDeviceType.BUILT_IN_MIC
    val deviceLabel = state.selectedDevice?.productName.ifEmpty { getDeviceLabel(deviceType) }
    
    Button(
        onClick = onShowDialog,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = getDeviceIcon(deviceType),
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text("Audio Input Device", style = MaterialTheme.typography.labelSmall)
            Text(deviceLabel, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun NarrowbandWarningBanner(show: Boolean, onDismiss: () -> Unit) {
    if (show) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "⚠️ Narrowband Audio Detected",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "Connected Bluetooth device uses 8 kHz sample rate. Audio quality will be reduced.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.size(40.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text("OK", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
