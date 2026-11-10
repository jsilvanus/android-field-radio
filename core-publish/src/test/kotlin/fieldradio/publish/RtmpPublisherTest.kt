package fieldradio.publish

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.Assume.assumeTrue

class Amf0Test {
    @Test fun roundTrip() {
        val b = Amf0.write("connect", 1.0, mapOf("app" to "live", "n" to 2.0, "b" to true), null)
        val v = Amf0.read(b)
        assertEquals("connect", v[0]); assertEquals(1.0, v[1])
        assertEquals(mapOf("app" to "live", "n" to 2.0, "b" to true), v[2]); assertNull(v[3])
    }
}

/** Real RTMP against ffmpeg acting as a listening server. Skipped when ffmpeg is unavailable. */
class RtmpPublisherFfmpegTest {
    private fun have(cmd: String) = runCatching { ProcessBuilder(cmd, "-version").start().waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)

    private fun adtsFrames(f: File): Pair<ByteArray, List<ByteArray>> {
        val d = f.readBytes(); var i = 0; val frames = ArrayList<ByteArray>(); var asc = ByteArray(0)
        while (i + 7 <= d.size) {
            check((d[i].toInt() and 0xFF) == 0xFF) { "lost ADTS sync" }
            val len = ((d[i + 3].toInt() and 3) shl 11) or ((d[i + 4].toInt() and 0xFF) shl 3) or ((d[i + 5].toInt() and 0xFF) shr 5)
            val prot = d[i + 1].toInt() and 1
            val hdr = if (prot == 1) 7 else 9
            if (asc.isEmpty()) {
                val profile = ((d[i + 2].toInt() shr 6) and 3) + 1
                val sf = (d[i + 2].toInt() shr 2) and 0xF
                val ch = ((d[i + 2].toInt() and 1) shl 2) or ((d[i + 3].toInt() shr 6) and 3)
                val v = (profile shl 11) or (sf shl 7) or (ch shl 3)
                asc = byteArrayOf((v shr 8).toByte(), v.toByte())
            }
            frames.add(d.copyOfRange(i + hdr, i + len)); i += len
        }
        return asc to frames
    }

    @Test fun publishesAudioThatFfmpegDecodes() {
        assumeTrue(have("ffmpeg") && have("ffprobe"))
        val dir = createTempDir("rtmp")
        val src = File(dir, "src.aac"); val out = File(dir, "out.aac")
        assertEquals(0, ProcessBuilder("ffmpeg", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=3", "-ar", "48000", "-ac", "1", "-c:a", "aac", "-f", "adts", src.path)
            .redirectErrorStream(true).redirectOutput(File(dir, "gen.log")).start().waitFor())
        val (asc, frames) = adtsFrames(src)
        val port = 19350 + (System.nanoTime() % 400).toInt()
        val server = ProcessBuilder("ffmpeg", "-y", "-listen", "1", "-i", "rtmp://127.0.0.1:$port/live/test", "-c", "copy", "-f", "adts", out.path)
            .redirectErrorStream(true).redirectOutput(File(dir, "srv.log")).start()
        try {
            Thread.sleep(1500)
            val pub = RtmpPublisher()
            runBlocking {
                pub.setAudioConfig(asc)
                pub.connect("127.0.0.1", port, "live", "test")
                var pts = 0L
                for (f in frames) { pub.send(AacFrame(f, pts)); pts += 1_000_000L * 1024 / 48000; delay(21) }
                delay(500)
                pub.disconnect()
            }
            assertTrue(server.waitFor(15, TimeUnit.SECONDS), "ffmpeg server did not finish: " + File(dir, "srv.log").readText().takeLast(500))
        } finally { server.destroyForcibly() }
        val probe = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,sample_rate,channels:format=duration", "-of", "default=nw=1", out.path)
            .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        assertTrue("codec_name=aac" in probe && "sample_rate=48000" in probe && "channels=1" in probe, probe)
        val dur = Regex("duration=([0-9.]+)").find(probe)!!.groupValues[1].toDouble()
        assertTrue(dur in 2.5..3.6, "duration $dur")
    }
}
