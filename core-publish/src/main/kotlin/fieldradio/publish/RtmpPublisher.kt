package fieldradio.publish

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Audio-only RTMP publisher (AAC). Plain RTMP over TCP, so it can run through a local SSH forward.
 * Frames are queued (bounded, oldest dropped) and written by one writer thread so the audio thread never blocks.
 * 
 * P5 feature: Reconnect buffer up to ~10 s of AAC frames (reconn buffer, oldest dropped on overflow).
 * On reconnect, the buffer is flushed to the publisher so the stream continues without silence gaps.
 */
class RtmpPublisher(
    private val connectTimeoutMs: Int = 10_000,
    private val maxQueuedFrames: Int = 500,   // about 10 s of 1024-sample frames at 48 kHz
    private val enableReconnectBuffer: Boolean = true,  // P5 feature
) : Publisher {
    private val st = MutableStateFlow(PublisherState.IDLE)
    override val state: Flow<PublisherState> = st

    @Volatile private var asc: ByteArray? = null
    @Volatile var droppedFrames = 0L; private set
    override fun setAudioConfig(asc: ByteArray) { this.asc = asc }

    private var conn: Connection? = null
    private val queue = LinkedBlockingQueue<AacFrame>()
    private val reconnectBuffer = LinkedBlockingQueue<AacFrame>()  // P5: buffer during reconnect
    private var baseUs = -1L
    @Volatile private var isConnected = false

    override suspend fun connect(host: String, port: Int, app: String, streamName: String) {
        disconnect()
        st.value = PublisherState.CONNECTING
        val config = asc ?: error("audio config not set")
        try {
            conn = withContext(Dispatchers.IO) {
                Connection.open(host, port, app, streamName, config, connectTimeoutMs, queue) { st.value = PublisherState.RECONNECTING }
            }
            // P5: Flush reconnect buffer to the queue when connection re-establishes
            if (enableReconnectBuffer && reconnectBuffer.isNotEmpty()) {
                val frames = mutableListOf<AacFrame>()
                reconnectBuffer.drainTo(frames)
                frames.forEach { queue.offer(it) }
            }
            baseUs = -1L
            isConnected = true
            st.value = PublisherState.LIVE
        } catch (e: Exception) {
            isConnected = false
            st.value = PublisherState.FAILED
            throw e
        }
    }

    override fun send(frame: AacFrame) {
        if (baseUs < 0) baseUs = frame.ptsUs
        val f = AacFrame(frame.data, frame.ptsUs - baseUs)
        
        // P5: If connected, send to main queue; otherwise buffer for reconnect
        if (isConnected && conn != null) {
            while (queue.size >= maxQueuedFrames) { queue.poll(); droppedFrames++ }
            queue.offer(f)
        } else if (enableReconnectBuffer) {
            while (reconnectBuffer.size >= maxQueuedFrames) { reconnectBuffer.poll() }
            reconnectBuffer.offer(f)
        }
    }

    override suspend fun disconnect() {
        isConnected = false
        val c = conn ?: run { if (st.value != PublisherState.IDLE) st.value = PublisherState.IDLE; return }
        conn = null
        withContext(Dispatchers.IO) { c.close() }
        queue.clear()
        // Keep reconnect buffer for next connection attempt
        st.value = PublisherState.IDLE
    }

    private class Connection(
        private val socket: Socket,
        private val out: OutputStream,
        private val streamId: Int,
        private val queue: LinkedBlockingQueue<AacFrame>,
        private val onLost: () -> Unit,
        asc: ByteArray,
    ) {
        @Volatile private var closed = false
        private var chunkSize = 4096
        private val writer = Thread({ writeLoop(asc) }, "rtmp-writer").apply { isDaemon = true }

        fun start() { writer.start() }

        private fun writeLoop(asc: ByteArray) {
            try {
                sendMessage(6, 8, streamId, 0, byteArrayOf(0xAF.toByte(), 0x00) + asc)
                while (!closed) {
                    val f = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    sendMessage(6, 8, streamId, f.ptsUs / 1000, byteArrayOf(0xAF.toByte(), 0x01) + f.data)
                }
            } catch (e: Exception) {
                if (!closed) { closed = true; onLost() }
            }
        }

        @Synchronized fun sendMessage(csid: Int, type: Int, msid: Int, timestampMs: Long, payload: ByteArray) {
            val ts = timestampMs.coerceAtLeast(0)
            val ext = ts >= 0xFFFFFF
            val hdr = java.io.ByteArrayOutputStream()
            hdr.write(csid and 0x3F)                                   // fmt 0, csid < 64
            val t3 = if (ext) 0xFFFFFF else ts.toInt()
            hdr.write(t3 shr 16); hdr.write(t3 shr 8); hdr.write(t3)
            hdr.write(payload.size shr 16); hdr.write(payload.size shr 8); hdr.write(payload.size)
            hdr.write(type)
            hdr.write(msid); hdr.write(msid shr 8); hdr.write(msid shr 16); hdr.write(msid shr 24)   // little endian
            if (ext) writeInt(hdr, ts.toInt())
            out.write(hdr.toByteArray())
            var off = 0
            while (off < payload.size) {
                val n = minOf(chunkSize, payload.size - off)
                out.write(payload, off, n); off += n
                if (off < payload.size) {
                    out.write(0xC0 or (csid and 0x3F))                  // fmt 3 continuation
                    if (ext) { val b = java.io.ByteArrayOutputStream(); writeInt(b, ts.toInt()); out.write(b.toByteArray()) }
                }
            }
            out.flush()
        }

        /** Called by the reader when the socket dies. */
        fun lost() { if (!closed) { closed = true; runCatching { socket.close() }; onLost() } }

        fun close() {
            closed = true
            runCatching { socket.close() }
            runCatching { writer.join(500) }
        }

        companion object {
            fun open(
                host: String, port: Int, app: String, stream: String, asc: ByteArray, timeoutMs: Int,
                queue: LinkedBlockingQueue<AacFrame>, onLost: () -> Unit,
            ): Connection {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                try {
                    val input = DataInputStream(socket.getInputStream().buffered())
                    val out = socket.getOutputStream().buffered(64 * 1024)
                    handshake(input, out)
                    val reader = ChunkReader(input)
                    val c = Connection(socket, out, 0, queue, onLost, asc)
                    c.sendMessage(2, 1, 0, 0, byteArrayOf(0, 0, 0x10, 0))               // Set Chunk Size 4096
                    c.sendMessage(3, 20, 0, 0, Amf0.write("connect", 1.0, mapOf(
                        "app" to app, "type" to "nonprivate", "flashVer" to "FMLE/3.0",
                        "tcUrl" to "rtmp://$host:$port/$app")))
                    reader.awaitResult(1.0, c)
                    c.sendMessage(3, 20, 0, 0, Amf0.write("releaseStream", 2.0, null, stream))
                    c.sendMessage(3, 20, 0, 0, Amf0.write("FCPublish", 3.0, null, stream))
                    c.sendMessage(3, 20, 0, 0, Amf0.write("createStream", 4.0, null))
                    val sid = (reader.awaitResult(4.0, c).getOrNull(3) as? Double)?.toInt() ?: error("no stream id")
                    val conn = Connection(socket, out, sid, queue, onLost, asc)
                    conn.sendMessage(4, 20, sid, 0, Amf0.write("publish", 5.0, null, stream, "live"))
                    reader.awaitStatus("NetStream.Publish.Start", conn)
                    socket.soTimeout = 0
                    conn.start()
                    Thread({ reader.drain(conn) }, "rtmp-reader").apply { isDaemon = true }.start()
                    return conn
                } catch (e: Exception) {
                    runCatching { socket.close() }
                    throw e
                }
            }

            private fun handshake(input: DataInputStream, out: OutputStream) {
                val c1 = ByteArray(1536).also { Random.nextBytes(it); for (i in 0 until 8) it[i] = 0 }
                out.write(3); out.write(c1); out.flush()
                val s0 = input.readUnsignedByte(); require(s0 == 3) { "bad RTMP version $s0" }
                val s1 = ByteArray(1536); input.readFully(s1)
                input.readFully(ByteArray(1536))          // S2
                out.write(s1); out.flush()                // C2 = echo of S1
            }

            private fun writeInt(o: java.io.ByteArrayOutputStream, v: Int) {
                o.write(v ushr 24); o.write(v ushr 16); o.write(v ushr 8); o.write(v)
            }
        }
    }

    private class Message(val type: Int, val payload: ByteArray)

    private class ChunkReader(private val input: DataInputStream) {
        private var chunkSize = 128
        private var received = 0L
        private var ackWindow = 2_500_000L
        private var lastAck = 0L
        private class Partial(var type: Int = 0, var length: Int = 0, var buf: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream(), var ts: Long = 0, var msid: Int = 0)
        private val partial = HashMap<Int, Partial>()

        private fun readMessage(): Message {
            while (true) {
                val b0 = input.readUnsignedByte(); received++
                val fmt = b0 shr 6
                var csid = b0 and 0x3F
                if (csid == 0) { csid = input.readUnsignedByte() + 64; received++ }
                else if (csid == 1) { csid = input.readUnsignedByte() + input.readUnsignedByte() * 256 + 64; received += 2 }
                val p = partial.getOrPut(csid) { Partial() }
                var ts = p.ts
                if (fmt <= 2) {
                    ts = ((input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()).toLong(); received += 3
                    if (fmt <= 1) {
                        p.length = (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
                        p.type = input.readUnsignedByte(); received += 4
                        if (fmt == 0) { p.msid = Integer.reverseBytes(input.readInt()); received += 4 }
                    }
                    if (ts >= 0xFFFFFF) { ts = input.readInt().toLong() and 0xFFFFFFFFL; received += 4 }
                    p.ts = ts
                }
                val remaining = p.length - p.buf.size()
                val n = minOf(chunkSize, remaining)
                val data = ByteArray(n); input.readFully(data); received += n
                p.buf.write(data)
                if (p.buf.size() >= p.length) {
                    val m = Message(p.type, p.buf.toByteArray()); p.buf.reset(); return m
                }
            }
        }

        /** Handles protocol control messages; returns true if the message was fully handled here. */
        private fun control(m: Message, c: Connection): Boolean {
            when (m.type) {
                1 -> chunkSize = java.nio.ByteBuffer.wrap(m.payload).int
                5 -> ackWindow = (java.nio.ByteBuffer.wrap(m.payload).int.toLong() and 0xFFFFFFFFL)
                4 -> if (m.payload.size >= 6 && m.payload[1].toInt() == 6) {            // ping request -> ping response
                    val resp = byteArrayOf(0, 7) + m.payload.copyOfRange(2, 6)
                    c.sendMessage(2, 4, 0, 0, resp)
                }
                else -> return false
            }
            return true
        }

        private fun maybeAck(c: Connection) {
            if (received - lastAck >= ackWindow / 2) {
                lastAck = received
                c.sendMessage(2, 3, 0, 0, java.nio.ByteBuffer.allocate(4).putInt(received.toInt()).array())
            }
        }

        fun awaitResult(txn: Double, c: Connection): List<Any?> {
            while (true) {
                val m = readMessage(); if (control(m, c)) continue
                if (m.type == 20) {
                    val v = Amf0.read(m.payload)
                    if (v.getOrNull(0) == "_error") error("RTMP error: $v")
                    if (v.getOrNull(0) == "_result" && v.getOrNull(1) == txn) return v
                }
            }
        }

        fun awaitStatus(code: String, c: Connection) {
            while (true) {
                val m = readMessage(); if (control(m, c)) continue
                if (m.type == 20) {
                    val v = Amf0.read(m.payload)
                    if (v.getOrNull(0) == "onStatus") {
                        val info = v.lastOrNull() as? Map<*, *>
                        val got = info?.get("code") as? String
                        if (got == code) return
                        if (got != null && (got.endsWith("BadName") || got.endsWith("Failed") || got.endsWith("Rejected"))) error("RTMP publish refused: $got")
                    }
                }
            }
        }

        /** Keeps reading so acks and pings are answered; ends (and flags the connection lost) on error. */
        fun drain(c: Connection) {
            try { while (true) { val m = readMessage(); control(m, c); maybeAck(c) } }
            catch (_: Exception) { c.lost() }
        }
    }
}
