package fieldradio.publish

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.*
import kotlin.test.*

private class FakePublisher(var failures: Int = 0) : Publisher {
    val st = MutableStateFlow(PublisherState.IDLE)
    override val state: Flow<PublisherState> = st
    var connects = 0
    var lastTarget: String? = null
    override suspend fun connect(host: String, port: Int, app: String, streamName: String) {
        connects++
        if (failures > 0) { failures--; st.value = PublisherState.FAILED; throw RuntimeException("refused") }
        lastTarget = "$host:$port/$app/$streamName"; st.value = PublisherState.LIVE
    }
    override fun send(frame: AacFrame) {}
    override suspend fun disconnect() { st.value = PublisherState.IDLE }
}

private class FakeTunnel(val end: TunnelState) : TunnelTransport {
    val s = MutableStateFlow<TunnelState>(TunnelState.Idle)
    override val state: Flow<TunnelState> = s
    override suspend fun open(spec: ForwardSpec) { s.value = end }
    override suspend fun close() { s.value = TunnelState.Idle }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {
    private val spec = ForwardSpec("vps.example", 22, "pub", "127.0.0.1", 1935)

    @Test fun directGoesLiveAndRetriesWithBackoff() = runTest {
        val pub = FakePublisher(failures = 2)
        val sess = BroadcastSession(backgroundScope, pub, null) { 100 }
        sess.start(Route.Direct("h", 1935), "live", "evt")
        runCurrent(); assertEquals(LinkStatus.RECONNECTING, sess.status.value)
        advanceTimeBy(250); runCurrent()
        assertEquals(LinkStatus.LIVE, sess.status.value)
        assertEquals(3, pub.connects)
        assertEquals("h:1935/live/evt", pub.lastTarget)
    }

    @Test fun reconnectsAfterLinkLoss() = runTest {
        val pub = FakePublisher()
        val sess = BroadcastSession(backgroundScope, pub, null) { 50 }
        sess.start(Route.Direct("h", 1935), "live", "evt"); runCurrent()
        assertEquals(LinkStatus.LIVE, sess.status.value)
        pub.st.value = PublisherState.RECONNECTING; runCurrent()
        advanceTimeBy(60); runCurrent()
        assertEquals(2, pub.connects)
    }

    @Test fun tunnelRouteUsesLocalEndpoint() = runTest {
        val pub = FakePublisher()
        val sess = BroadcastSession(backgroundScope, pub, FakeTunnel(TunnelState.Up("127.0.0.1", 41935)))
        sess.start(Route.ViaTunnel(spec), "live", "evt"); runCurrent()
        assertEquals("127.0.0.1:41935/live/evt", pub.lastTarget)
    }

    @Test fun hostKeyChangeStopsAutomaticRecovery() = runTest {
        val pub = FakePublisher()
        val sess = BroadcastSession(backgroundScope, pub, FakeTunnel(TunnelState.HostKeyChanged("SHA256:x")))
        sess.start(Route.ViaTunnel(spec), "live", "evt"); runCurrent()
        assertEquals(LinkStatus.NEEDS_OPERATOR, sess.status.value)
        assertEquals(0, pub.connects)
        assertNotNull(sess.error.value)
    }

    @Test fun duplicateStartRejected() = runTest {
        val sess = BroadcastSession(backgroundScope, FakePublisher(), null)
        sess.start(Route.Direct("h", 1), "a", "b")
        assertFailsWith<IllegalArgumentException> { sess.start(Route.Direct("h", 1), "a", "b") }
    }

    @Test fun stopGoesOffline() = runTest {
        val sess = BroadcastSession(backgroundScope, FakePublisher(), null)
        sess.start(Route.Direct("h", 1), "a", "b"); runCurrent()
        sess.stop()
        assertEquals(LinkStatus.OFFLINE, sess.status.value)
    }
}
