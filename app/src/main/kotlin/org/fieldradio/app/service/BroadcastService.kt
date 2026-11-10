package org.fieldradio.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import fieldradio.audio.TransitionPolicy
import fieldradio.profile.Profile
import fieldradio.profile.Transition
import fieldradio.profile.parseProfile
import fieldradio.profile.storeJson
import fieldradio.publish.BroadcastSession
import fieldradio.publish.Route
import fieldradio.publish.RtmpPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.fieldradio.app.audio.Engine
import org.fieldradio.app.audio.Meters

/**
 * Owns the engine and the broadcast session. Started from an on-screen tap only (Android 14 forbids starting a
 * microphone foreground service from the background). The mic is never opened automatically after a process restart.
 */
class BroadcastService : Service() {
    var engine: Engine? = null; private set
    private var session: BroadcastSession? = null
    private var scope: CoroutineScope? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        val profile = intent?.getStringExtra(EXTRA_PROFILE)?.let { runCatching { parseProfile(it) }.getOrNull() }
        if (profile == null || engine != null) { if (engine == null) stopSelf(); return START_NOT_STICKY }
        startForegroundCompat(profile)
        begin(profile)
        return START_NOT_STICKY      // do not auto-restart: the operator must tap START again
    }

    private fun startForegroundCompat(p: Profile) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Broadcast", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, BroadcastService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("On air: ${p.eventName.ifBlank { p.name }}")
            .setContentText("Stream ${p.streamName}")
            .setOngoing(true).addAction(0, "Stop", stop).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun begin(p: Profile) {
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fieldradio:broadcast").apply { acquire() }
        val publisher = RtmpPublisher()
        val policy = when (p.audio.transition) {
            Transition.STOP -> TransitionPolicy.Stop
            Transition.FADE -> TransitionPolicy.FadeOut(p.audio.fadeMs)
            Transition.DUCK -> TransitionPolicy.Duck(p.audio.duckDb)
            Transition.KEEP -> TransitionPolicy.Keep
        }
        val eng = Engine(
            this, { publisher.send(it) },
            { msg -> BroadcastController._state.value = BroadcastController._state.value.copy(error = msg) },
            { BroadcastController._state.value = BroadcastController._state.value.copy(nowPlaying = null) },
            p.audio.bitrateKbps, policy, p.audio.micGainDb, p.audio.playbackGainDb, p.monitorEnabled,
        )
        publisher.setAudioConfig(eng.encoder.audioSpecificConfig)
        engine = eng; BroadcastController.service = this
        eng.start()
        val s = BroadcastSession(sc, publisher, tunnel = null)   // SSH tunnel plugs in here when the aidos module is available
        session = s
        val (host, port) = hostPort(p.server.controlUrl)
        s.start(Route.Direct(host, port), "live", p.streamName)
        sc.launch {
            combine(s.status, s.error, eng.meters) { link, err, m -> Triple(link, err, m) }.collect { (link, err, m) ->
                val cur = BroadcastController._state.value
                BroadcastController._state.value = cur.copy(running = true, link = link, error = err ?: cur.error?.takeIf { link == fieldradio.publish.LinkStatus.LIVE }, meters = m)
            }
        }
    }

    /** Server field is "host:port" for the direct route (the SSH route will use its own settings). */
    private fun hostPort(s: String): Pair<String, Int> {
        val t = s.trim().removePrefix("rtmp://").substringBefore('/')
        return t.substringBeforeLast(':', t) to (t.substringAfterLast(':', "1935").toIntOrNull() ?: 1935)
    }

    fun playItem(uri: Uri, title: String) {
        engine?.play(uri)
        BroadcastController._state.value = BroadcastController._state.value.copy(nowPlaying = title)
    }

    fun stopItem() {
        engine?.stopPlayback()
        BroadcastController._state.value = BroadcastController._state.value.copy(nowPlaying = null)
    }

    private fun shutdown() {
        runCatching { runBlocking { session?.stop() } }
        engine?.stop(); engine = null; session = null
        scope?.cancel(); scope = null
        wake?.let { if (it.isHeld) it.release() }; wake = null
        BroadcastController.service = null
        BroadcastController._state.value = UiState()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() { if (engine != null) shutdown(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "broadcast"
        const val ACTION_STOP = "org.fieldradio.app.STOP"
        const val EXTRA_PROFILE = "profile"
        fun start(c: Context, p: Profile) = c.startForegroundService(Intent(c, BroadcastService::class.java).putExtra(EXTRA_PROFILE, storeJson(p)))
        fun stop(c: Context) { c.startService(Intent(c, BroadcastService::class.java).setAction(ACTION_STOP)) }
    }
}
