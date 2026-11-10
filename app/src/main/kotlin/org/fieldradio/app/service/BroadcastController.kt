package org.fieldradio.app.service

import android.content.Context
import android.net.Uri
import fieldradio.profile.Profile
import fieldradio.publish.LinkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.fieldradio.app.audio.Meters

data class UiState(
    val running: Boolean = false,
    val link: LinkStatus = LinkStatus.OFFLINE,
    val error: String? = null,
    val meters: Meters = Meters(),
    val nowPlaying: String? = null,
)

/** Process-wide bridge between the foreground service (owner of the engine) and the UI. */
object BroadcastController {
    internal val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    @Volatile internal var service: BroadcastService? = null

    fun start(context: Context, profile: Profile) = BroadcastService.start(context, profile)
    fun stop(context: Context) = BroadcastService.stop(context)
    fun setMic(on: Boolean) { service?.engine?.setMic(on) }
    fun play(uri: Uri, title: String) { service?.playItem(uri, title) }
    fun stopPlayback() { service?.stopItem() }
}
