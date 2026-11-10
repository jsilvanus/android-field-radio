package fieldradio.publish

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Minimal AMF0 writer/reader: just what an RTMP publisher needs. */
object Amf0 {
    fun write(vararg values: Any?): ByteArray {
        val bos = ByteArrayOutputStream(); val o = DataOutputStream(bos)
        values.forEach { writeValue(o, it) }
        return bos.toByteArray()
    }

    private fun writeValue(o: DataOutputStream, v: Any?) {
        when (v) {
            null -> o.writeByte(0x05)
            is Number -> { o.writeByte(0x00); o.writeDouble(v.toDouble()) }
            is Boolean -> { o.writeByte(0x01); o.writeByte(if (v) 1 else 0) }
            is String -> { o.writeByte(0x02); writeStr(o, v) }
            is Map<*, *> -> {
                o.writeByte(0x03)
                v.forEach { (k, value) -> writeStr(o, k as String); writeValue(o, value) }
                o.writeShort(0); o.writeByte(0x09)
            }
            else -> error("unsupported AMF0 value ${v::class}")
        }
    }

    private fun writeStr(o: DataOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8); o.writeShort(b.size); o.write(b)
    }

    /** Reads all top-level values; objects become maps, ECMA arrays maps, unknown markers stop parsing. */
    fun read(data: ByteArray): List<Any?> {
        val out = ArrayList<Any?>(); val pos = intArrayOf(0)
        try { while (pos[0] < data.size) out.add(readValue(data, pos)) } catch (_: IndexOutOfBoundsException) {} catch (_: IllegalStateException) {}
        return out
    }

    private fun readValue(d: ByteArray, p: IntArray): Any? {
        val t = d[p[0]++].toInt() and 0xFF
        return when (t) {
            0x00 -> { var l = 0L; repeat(8) { l = (l shl 8) or (d[p[0]++].toLong() and 0xFF) }; java.lang.Double.longBitsToDouble(l) }
            0x01 -> d[p[0]++].toInt() != 0
            0x02 -> readStr(d, p)
            0x03 -> readObject(d, p)
            0x08 -> { p[0] += 4; readObject(d, p) }
            0x05, 0x06 -> null
            else -> error("unsupported AMF0 marker $t")
        }
    }

    private fun readStr(d: ByteArray, p: IntArray): String {
        val n = ((d[p[0]].toInt() and 0xFF) shl 8) or (d[p[0] + 1].toInt() and 0xFF); p[0] += 2
        val s = String(d, p[0], n, Charsets.UTF_8); p[0] += n; return s
    }

    private fun readObject(d: ByteArray, p: IntArray): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        while (true) {
            val n = ((d[p[0]].toInt() and 0xFF) shl 8) or (d[p[0] + 1].toInt() and 0xFF)
            if (n == 0 && (d[p[0] + 2].toInt() and 0xFF) == 0x09) { p[0] += 3; return m }
            val k = readStr(d, p); m[k] = readValue(d, p)
        }
    }
}
