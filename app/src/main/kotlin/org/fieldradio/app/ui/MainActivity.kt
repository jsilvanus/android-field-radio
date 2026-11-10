package org.fieldradio.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fieldradio.profile.*
import fieldradio.publish.LinkStatus
import org.fieldradio.app.service.BroadcastController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }
}

private fun prefs(c: Context) = c.getSharedPreferences("fieldradio", Context.MODE_PRIVATE)
private fun loadProfile(c: Context): Profile =
    prefs(c).getString("profile", null)?.let { runCatching { parseProfile(it) }.getOrNull() }
        ?: Profile(name = "Event", streamName = "event")
private fun saveProfile(c: Context, p: Profile) = prefs(c).edit().putString("profile", storeJson(p)).apply()

private fun displayName(c: Context, uri: Uri): String =
    c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
        val i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cur.moveToFirst() && i >= 0) cur.getString(i) else null
    } ?: uri.lastPathSegment ?: "sound"

@Composable
private fun Screen() {
    val ctx = LocalContext.current
    val ui by BroadcastController.state.collectAsStateWithLifecycle()
    var profile by remember { mutableStateOf(loadProfile(ctx)) }
    var showSettings by remember { mutableStateOf(false) }
    var micOn by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true && pendingStart) BroadcastController.start(ctx, profile)
        pendingStart = false
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val item = SoundItem(id = java.util.UUID.randomUUID().toString(), title = displayName(ctx, uri), file = uri.toString())
            profile = profile.copy(library = profile.library + item); saveProfile(ctx, profile)
        }
    }

    val problems = remember(profile) { validate(profile).filterNot { "unsafe sound path" in it } }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // header: event + status
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profile.eventName.ifBlank { profile.name }, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("stream: ${profile.streamName}", color = Color.Gray)
            }
            StatusChip(ui.link)
        }
        ui.error?.let { Text(it, color = Color(0xFFFF8A80)) }
        problems.firstOrNull()?.let { Text(it, color = Color(0xFFFFD180)) }

        // MIC (prominent)
        val micEnabled = ui.running
        Button(
            onClick = { micOn = !micOn; BroadcastController.setMic(micOn) },
            enabled = micEnabled,
            colors = ButtonDefaults.buttonColors(containerColor = if (micOn) Color(0xFFD32F2F) else Color(0xFF424242)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().height(140.dp),
        ) { Text(if (micOn) "MIC ON" else "MIC OFF", fontSize = 40.sp, fontWeight = FontWeight.Bold) }
        Meter("Input", ui.meters.micPeak); Meter("Output", ui.meters.outPeak)
        if (ui.meters.clips > 0) Text("clipped samples: ${ui.meters.clips}", color = Color(0xFFFFD180), fontSize = 12.sp)

        // now playing + library
        Text(ui.nowPlaying?.let { "Playing: $it" } ?: "Nothing playing", fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { BroadcastController.stopPlayback() }, enabled = ui.nowPlaying != null) { Text("Stop sound") }
            OutlinedButton(onClick = { pick.launch(arrayOf("audio/*")) }) { Text("Add sound") }
            OutlinedButton(onClick = { showSettings = !showSettings }, enabled = !ui.running) { Text("Settings") }
        }
        if (showSettings && !ui.running) SettingsPanel(profile) { profile = it; saveProfile(ctx, it) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(profile.library, key = { it.id }) { item ->
                Row(Modifier.fillMaxWidth().background(Color(0xFF263238), RoundedCornerShape(12.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, Modifier.weight(1f), maxLines = 1)
                    Button(onClick = { BroadcastController.play(Uri.parse(item.file), item.title) }, enabled = ui.running) { Text("Play") }
                    TextButton(onClick = { profile = profile.copy(library = profile.library - item); saveProfile(ctx, profile) }, enabled = !ui.running) { Text("✕") }
                }
            }
        }

        // start / stop
        if (!ui.running) {
            Button(
                onClick = {
                    saveProfile(ctx, profile); pendingStart = true
                    permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                },
                enabled = validate(profile).none { "unsafe" !in it },
                modifier = Modifier.fillMaxWidth().height(72.dp), shape = RoundedCornerShape(16.dp),
            ) { Text("START BROADCAST", fontSize = 22.sp) }
        } else {
            var confirm by remember { mutableStateOf(false) }
            Button(
                onClick = { if (confirm) { BroadcastController.stop(ctx); micOn = false; confirm = false } else confirm = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF616161)),
                modifier = Modifier.fillMaxWidth().height(72.dp), shape = RoundedCornerShape(16.dp),
            ) { Text(if (confirm) "TAP AGAIN TO END" else "END BROADCAST", fontSize = 22.sp) }
        }
    }
}

@Composable
private fun StatusChip(s: LinkStatus) {
    val (txt, col) = when (s) {
        LinkStatus.OFFLINE -> "OFFLINE" to Color(0xFF616161)
        LinkStatus.CONNECTING -> "CONNECTING" to Color(0xFFF9A825)
        LinkStatus.LIVE -> "LIVE" to Color(0xFF2E7D32)
        LinkStatus.RECONNECTING -> "RECONNECTING" to Color(0xFFEF6C00)
        LinkStatus.NEEDS_OPERATOR -> "ACTION NEEDED" to Color(0xFFC62828)
    }
    Box(Modifier.background(col, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp)) { Text(txt, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Meter(label: String, level: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(64.dp), fontSize = 12.sp)
        LinearProgressIndicator(progress = { level.coerceIn(0f, 1f) }, Modifier.weight(1f).height(10.dp),
            color = if (level > 0.85f) Color(0xFFFF5252) else Color(0xFF66BB6A))
    }
}

@Composable
private fun SettingsPanel(p: Profile, onChange: (Profile) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(p.eventName, { onChange(p.copy(eventName = it)) }, label = { Text("Event name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(p.streamName, { onChange(p.copy(streamName = it.lowercase())) }, label = { Text("Stream name (a-z 0-9 _ -)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(p.server.controlUrl, { onChange(p.copy(server = p.server.copy(controlUrl = it))) }, label = { Text("RTMP host:port (direct)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("SSH tunnel: pending the aidos module. Captions: off (speech-to-text is not in this version).", fontSize = 12.sp, color = Color.Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Transition.entries.forEach { t ->
                FilterChip(p.audio.transition == t, { onChange(p.copy(audio = p.audio.copy(transition = t))) }, label = { Text(t.name.lowercase()) })
            }
        }
    }
}
