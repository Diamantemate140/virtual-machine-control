package pet.toasty.vmpowerphone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

const val NEED_PASSWORD = "NEED_PASSWORD"
const val WRONG_PASSWORD = "WRONG_PASSWORD"

// Encoding choices (see Settings)
const val ENC_RAW = 0
const val ENC_ZRLE = 1
const val ENC_TIGHT = 2
const val ENC_AUTO = 3

private val EOF = ByteArray(0)

private class WsInput : InputStream() {
    private val q = LinkedBlockingQueue<ByteArray>()
    private var cur = ByteArray(0)
    private var pos = 0
    fun push(b: ByteArray) { q.put(b) }
    fun finish() { q.put(EOF) }

    override fun read(): Int {
        while (pos >= cur.size) {
            cur = q.take(); pos = 0
            if (cur === EOF) { q.put(EOF); return -1 }
        }
        return cur[pos++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val first = read()
        if (first < 0) return -1
        b[off] = first.toByte()
        var n = 1
        while (n < len && pos < cur.size) b[off + n++] = cur[pos++]
        return n
    }
}

/** Quality slider (0..9) -> colour depth in bits per pixel. */
fun bitsForQuality(q: Int): Int = when {
    q <= 3 -> 8
    q <= 6 -> 16
    else -> 32
}

class VncClient(
    private val url: String,
    private val token: String,
    private val password: String?,
    private val quality: Int,
    private val compression: Int,
    private val encoding: Int,
    private val onFrame: () -> Unit,
    private val onResizeSupported: () -> Unit,
    private val onEnd: (String) -> Unit
) {
    @Volatile var bitmap: Bitmap? = null
    @Volatile var resizeSupported = false
    // Live numbers for the stats line in the console
    val bytesIn = AtomicLong()
    val frames = AtomicLong()
    @Volatile var lastEncoding = ""
    @Volatile private var stopped = false
    @Volatile private var screenId = 0
    @Volatile private var screenFlags = 0
    private val input = WsInput()
    private val http = OkHttpClient()
    private var ws: WebSocket? = null
    private var lastX = 0
    private var lastY = 0

    // Tight needs 24-bit pixels. Otherwise the quality slider picks the colour depth.
    private val bpp = if (encoding == ENC_TIGHT) 32 else bitsForQuality(quality)
    private val bytesPP = bpp / 8
    private val cpSize = if (bpp == 32) 3 else bpp / 8   // ZRLE "CPIXEL" size
    private val inflater = Inflater()                     // ZRLE
    private val tightStreams = Array(4) { Inflater() }    // Tight keeps four zlib streams

    fun start() {
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                bytesIn.addAndGet(bytes.size.toLong())
                input.push(bytes.toByteArray())
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { input.finish() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                input.finish()
                if (!stopped) onEnd(
                    when (response?.code) {
                        401 -> "Not signed in"
                        else -> t.message ?: "Connection failed"
                    }
                )
            }
        })
        thread(isDaemon = true) {
            try {
                run()
            } catch (e: Exception) {
                if (!stopped) onEnd(e.message ?: "Disconnected")
            } finally {
                try { inflater.end() } catch (e: Exception) { }
                tightStreams.forEach { try { it.end() } catch (e: Exception) { } }
            }
        }
    }

    fun stop() { stopped = true; ws?.close(1000, null); input.finish() }

    private fun send(b: ByteArray) { ws?.send(b.toByteString()) }

    // VNC password auth: DES with each key byte bit-reversed
    private fun vncResponse(challenge: ByteArray, pass: String): ByteArray {
        val pb = pass.toByteArray(Charsets.ISO_8859_1)
        val key = ByteArray(8) { i ->
            val b = if (i < pb.size) pb[i].toInt() else 0
            (Integer.reverse(b) ushr 24).toByte()
        }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return cipher.doFinal(challenge)
    }

    // ---- pixel formats ----

    private fun pixelFormatMessage(): ByteArray {
        val bb = ByteBuffer.allocate(20)
        bb.put(0); bb.put(0); bb.put(0); bb.put(0)       // SetPixelFormat + padding
        when (bpp) {
            32 -> {
                bb.put(32); bb.put(24); bb.put(0); bb.put(1)
                bb.putShort(255); bb.putShort(255); bb.putShort(255)
                bb.put(16); bb.put(8); bb.put(0)
            }
            16 -> {
                bb.put(16); bb.put(16); bb.put(0); bb.put(1)
                bb.putShort(31); bb.putShort(63); bb.putShort(31)
                bb.put(11); bb.put(5); bb.put(0)
            }
            else -> {
                bb.put(8); bb.put(8); bb.put(0); bb.put(1)
                bb.putShort(7); bb.putShort(7); bb.putShort(3)
                bb.put(5); bb.put(2); bb.put(0)
            }
        }
        bb.put(0); bb.put(0); bb.put(0)
        return bb.array()
    }

    private fun valueAt(buf: ByteArray, o: Int, n: Int): Int {
        var v = 0
        for (i in 0 until n) v = v or ((buf[o + i].toInt() and 255) shl (8 * i))
        return v
    }

    private fun toArgb(v: Int): Int = when (bpp) {
        32 -> 0xFF000000.toInt() or (v and 0xFFFFFF)
        16 -> {
            val r = ((v shr 11) and 31) * 255 / 31
            val g = ((v shr 5) and 63) * 255 / 63
            val b = (v and 31) * 255 / 31
            0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
        else -> {
            val r = ((v shr 5) and 7) * 255 / 7
            val g = ((v shr 2) and 7) * 255 / 7
            val b = (v and 3) * 255 / 3
            0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
    }

    // ---- main loop ----

    private fun run() {
        val din = DataInputStream(input)
        fun skip(n: Int) { if (n > 0) din.readFully(ByteArray(n)) }

        skip(12)                                   // server version
        send("RFB 003.008\n".toByteArray())

        val n = din.readUnsignedByte()
        if (n == 0) {
            val msg = ByteArray(din.readInt()); din.readFully(msg)
            error(String(msg))
        }
        val types = ByteArray(n); din.readFully(types)

        var usedPassword = false
        when {
            1.toByte() in types -> send(byteArrayOf(1))
            2.toByte() in types -> {
                if (password.isNullOrEmpty()) error(NEED_PASSWORD)
                send(byteArrayOf(2))
                val challenge = ByteArray(16); din.readFully(challenge)
                send(vncResponse(challenge, password.orEmpty()))
                usedPassword = true
            }
            else -> error("Unsupported VNC security type")
        }
        if (din.readInt() != 0) {
            val len = din.readInt()
            if (len in 1..1000) skip(len)
            error(if (usedPassword) WRONG_PASSWORD else "VNC refused the connection")
        }
        send(byteArrayOf(1))                       // shared session

        var w = din.readUnsignedShort()
        var h = din.readUnsignedShort()
        skip(16)                                   // server pixel format
        skip(din.readInt())                        // desktop name

        send(pixelFormatMessage())

        // Encodings in order of preference (the server uses the first one it supports):
        // 7 = Tight, 16 = ZRLE, 1 = CopyRect, 0 = Raw, -223 DesktopSize, -308 ExtendedDesktopSize,
        // -32..-23 quality level hint, -256..-247 compression level hint.
        val encs = mutableListOf<Int>()
        when (encoding) {
            ENC_TIGHT, ENC_AUTO -> { if (bpp == 32) encs.add(7); encs.add(16) }
            ENC_ZRLE -> encs.add(16)
            else -> {}
        }
        encs.add(1); encs.add(0); encs.add(-223); encs.add(-308)
        encs.add(-32 + quality.coerceIn(0, 9))
        if (encoding != ENC_RAW) encs.add(-256 + compression.coerceIn(0, 9))
        send(ByteBuffer.allocate(4 + 4 * encs.size).apply {
            put(2); put(0); putShort(encs.size.toShort())
            encs.forEach { putInt(it) }
        }.array())

        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        requestUpdate(false, w, h)

        var needFull = false
        var resizeAnnounced = false

        while (true) {
            when (din.readUnsignedByte()) {
                0 -> {                              // FramebufferUpdate
                    skip(1)
                    val rects = din.readUnsignedShort()
                    repeat(rects) {
                        val rx = din.readUnsignedShort()
                        val ry = din.readUnsignedShort()
                        val rw = din.readUnsignedShort()
                        val rh = din.readUnsignedShort()
                        val enc = din.readInt()
                        lastEncoding = when (enc) {
                            0 -> "Raw"
                            1 -> "CopyRect"
                            7 -> "Tight"
                            16 -> "ZRLE"
                            else -> lastEncoding
                        }
                        when (enc) {
                            0 -> {
                                val bmp = bitmap!!
                                val buf = ByteArray(rw * rh * bytesPP); din.readFully(buf)
                                if (rx + rw <= bmp.width && ry + rh <= bmp.height) {
                                    val px = IntArray(rw * rh)
                                    for (i in px.indices) px[i] = toArgb(valueAt(buf, i * bytesPP, bytesPP))
                                    bmp.setPixels(px, 0, rw, rx, ry, rw, rh)
                                }
                            }
                            1 -> {
                                val sx = din.readUnsignedShort()
                                val sy = din.readUnsignedShort()
                                val bmp = bitmap!!
                                val tmp = IntArray(rw * rh)
                                bmp.getPixels(tmp, 0, rw, sx, sy, rw, rh)
                                bmp.setPixels(tmp, 0, rw, rx, ry, rw, rh)
                            }
                            16 -> decodeZrle(din, rx, ry, rw, rh)
                            7 -> decodeTight(din, rx, ry, rw, rh)
                            -223 -> {
                                w = rw; h = rh
                                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                needFull = true
                            }
                            -308 -> {
                                val screens = din.readUnsignedByte()
                                skip(3)
                                repeat(screens) { i ->
                                    val id = din.readInt()
                                    skip(8)
                                    val fl = din.readInt()
                                    if (i == 0) { screenId = id; screenFlags = fl }
                                }
                                if (!resizeAnnounced) {
                                    resizeAnnounced = true
                                    resizeSupported = true
                                    onResizeSupported()
                                }
                                if (rw > 0 && rh > 0 && (rw != w || rh != h)) {
                                    w = rw; h = rh
                                    bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    needFull = true
                                }
                            }
                            else -> error("Unsupported encoding $enc")
                        }
                    }
                    frames.incrementAndGet()
                    onFrame()
                    requestUpdate(!needFull, w, h)
                    needFull = false
                }
                2 -> {}                             // bell
                3 -> { skip(3); skip(din.readInt()) } // clipboard text from server
                else -> error("Unknown server message")
            }
        }
    }

    /** ZRLE: zlib-compressed 64x64 tiles. One zlib stream lives for the whole connection. */
    private fun decodeZrle(din: DataInputStream, rx: Int, ry: Int, rw: Int, rh: Int) {
        val len = din.readInt()
        if (len < 0 || len > 50_000_000) error("Bad ZRLE data")
        val comp = ByteArray(len); din.readFully(comp)
        inflater.setInput(comp)
        val out = ByteArrayOutputStream(maxOf(1024, rw * rh * cpSize))
        val tmp = ByteArray(16384)
        while (true) {
            val got = inflater.inflate(tmp)
            if (got > 0) out.write(tmp, 0, got)
            else if (inflater.needsInput() || inflater.finished() || inflater.needsDictionary()) break
        }
        val z = out.toByteArray()
        var p = 0
        fun u8(): Int = z[p++].toInt() and 255
        fun cpixel(): Int {
            val v = valueAt(z, p, cpSize)
            p += cpSize
            return toArgb(v)
        }

        val bmp = bitmap ?: return
        var ty = ry
        while (ty < ry + rh) {
            val th = minOf(64, ry + rh - ty)
            var tx = rx
            while (tx < rx + rw) {
                val tw = minOf(64, rx + rw - tx)
                val sub = u8()
                val px = IntArray(tw * th)
                val rle = (sub and 128) != 0
                val pal = sub and 127
                if (!rle) {
                    when {
                        pal == 0 -> for (i in px.indices) px[i] = cpixel()
                        pal == 1 -> px.fill(cpixel())
                        pal in 2..16 -> {
                            val palette = IntArray(pal) { cpixel() }
                            val bits = if (pal == 2) 1 else if (pal <= 4) 2 else 4
                            val rowBytes = (tw * bits + 7) / 8
                            for (y in 0 until th) {
                                val rowStart = p
                                for (x in 0 until tw) {
                                    val bitIndex = x * bits
                                    val b = z[rowStart + bitIndex / 8].toInt() and 255
                                    val shift = 8 - bits - (bitIndex % 8)
                                    val idx = (b shr shift) and ((1 shl bits) - 1)
                                    px[y * tw + x] = palette[idx.coerceAtMost(pal - 1)]
                                }
                                p = rowStart + rowBytes
                            }
                        }
                        else -> error("Bad ZRLE tile")
                    }
                } else if (pal == 0) {
                    var i = 0
                    while (i < px.size) {
                        val c = cpixel()
                        var run = 1
                        var b: Int
                        do { b = u8(); run += b } while (b == 255)
                        val end = minOf(px.size, i + run)
                        while (i < end) { px[i] = c; i++ }
                    }
                } else {
                    if (pal < 2) error("Bad ZRLE tile")
                    val palette = IntArray(pal) { cpixel() }
                    var i = 0
                    while (i < px.size) {
                        val idx = u8()
                        var run = 1
                        if ((idx and 128) != 0) {
                            var b: Int
                            do { b = u8(); run += b } while (b == 255)
                        }
                        val c = palette[(idx and 127).coerceAtMost(pal - 1)]
                        val end = minOf(px.size, i + run)
                        while (i < end) { px[i] = c; i++ }
                    }
                }
                if (tx + tw <= bmp.width && ty + th <= bmp.height) {
                    bmp.setPixels(px, 0, tw, tx, ty, tw, th)
                }
                tx += 64
            }
            ty += 64
        }
    }

    // ---- Tight ----

    private fun readCompactLen(din: DataInputStream): Int {
        var len = din.readUnsignedByte()
        if ((len and 0x80) != 0) {
            val b2 = din.readUnsignedByte()
            len = (len and 0x7F) or ((b2 and 0x7F) shl 7)
            if ((b2 and 0x80) != 0) len = len or (din.readUnsignedByte() shl 14)
        }
        return len
    }

    private fun inflateExact(inf: Inflater, comp: ByteArray, expected: Int): ByteArray {
        inf.setInput(comp)
        val out = ByteArray(expected)
        var off = 0
        while (off < expected) {
            val n = inf.inflate(out, off, expected - off)
            if (n > 0) off += n
            else if (inf.needsInput() || inf.finished() || inf.needsDictionary()) break
        }
        if (off < expected) error("Bad Tight data")
        // Consume the zlib sync-flush marker so the stream stays in step with the server
        val scratch = ByteArray(64)
        while (inf.inflate(scratch) > 0) { }
        return out
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        0xFF000000.toInt() or ((r and 255) shl 16) or ((g and 255) shl 8) or (b and 255)

    /** Tight: fill, JPEG, or zlib-compressed pixels with an optional palette or gradient filter. */
    private fun decodeTight(din: DataInputStream, rx: Int, ry: Int, rw: Int, rh: Int) {
        if (bpp != 32) error("Tight needs 24-bit color")
        val ctrl = din.readUnsignedByte()
        for (i in 0 until 4) {
            if (((ctrl shr i) and 1) != 0) tightStreams[i].reset()
        }
        val bmp = bitmap ?: return
        val fits = rx + rw <= bmp.width && ry + rh <= bmp.height
        val type = ctrl shr 4

        if (type == 8) {                                   // fill with one colour
            val color = rgb(din.readUnsignedByte(), din.readUnsignedByte(), din.readUnsignedByte())
            if (fits) bmp.setPixels(IntArray(rw * rh) { color }, 0, rw, rx, ry, rw, rh)
            return
        }
        if (type == 9) {                                   // JPEG
            val len = readCompactLen(din)
            val data = ByteArray(len)
            din.readFully(data)
            val jpg = BitmapFactory.decodeByteArray(data, 0, len)
            if (jpg != null) {
                if (fits) android.graphics.Canvas(bmp).drawBitmap(jpg, rx.toFloat(), ry.toFloat(), null)
                jpg.recycle()
            }
            return
        }
        if (type > 9) error("Bad Tight control byte")

        val streamId = type and 3
        var filter = 0
        if ((type and 4) != 0) filter = din.readUnsignedByte()

        var palette: IntArray? = null
        val dataSize: Int
        when (filter) {
            0, 2 -> dataSize = rw * rh * 3
            1 -> {
                val n = din.readUnsignedByte() + 1
                palette = IntArray(n) { rgb(din.readUnsignedByte(), din.readUnsignedByte(), din.readUnsignedByte()) }
                dataSize = if (n == 2) ((rw + 7) / 8) * rh else rw * rh
            }
            else -> error("Unknown Tight filter")
        }

        val data: ByteArray = if (dataSize < 12) {
            ByteArray(dataSize).also { din.readFully(it) }         // too small to compress
        } else {
            val len = readCompactLen(din)
            val comp = ByteArray(len)
            din.readFully(comp)
            inflateExact(tightStreams[streamId], comp, dataSize)
        }

        val px = IntArray(rw * rh)
        when (filter) {
            0 -> for (i in px.indices) {
                val o = i * 3
                px[i] = rgb(data[o].toInt(), data[o + 1].toInt(), data[o + 2].toInt())
            }
            1 -> {
                val pal = palette!!
                if (pal.size == 2) {
                    val rowBytes = (rw + 7) / 8
                    for (y in 0 until rh) for (x in 0 until rw) {
                        val bit = (data[y * rowBytes + x / 8].toInt() shr (7 - x % 8)) and 1
                        px[y * rw + x] = pal[bit]
                    }
                } else {
                    for (i in px.indices) px[i] = pal[(data[i].toInt() and 255).coerceAtMost(pal.size - 1)]
                }
            }
            2 -> {
                // Gradient filter: each value is stored as the difference from left + up - upper-left
                val prev = IntArray(rw * 3)
                val cur = IntArray(rw * 3)
                for (y in 0 until rh) {
                    for (x in 0 until rw) {
                        for (c in 0 until 3) {
                            val left = if (x > 0) cur[(x - 1) * 3 + c] else 0
                            val up = if (y > 0) prev[x * 3 + c] else 0
                            val upLeft = if (x > 0 && y > 0) prev[(x - 1) * 3 + c] else 0
                            val pred = (left + up - upLeft).coerceIn(0, 255)
                            cur[x * 3 + c] = ((data[(y * rw + x) * 3 + c].toInt() and 255) + pred) and 255
                        }
                        px[y * rw + x] = rgb(cur[x * 3], cur[x * 3 + 1], cur[x * 3 + 2])
                    }
                    System.arraycopy(cur, 0, prev, 0, rw * 3)
                }
            }
        }
        if (fits) bmp.setPixels(px, 0, rw, rx, ry, rw, rh)
    }

    private fun requestUpdate(incremental: Boolean, w: Int, h: Int) {
        send(ByteBuffer.allocate(10).apply {
            put(3); put(if (incremental) 1 else 0)
            putShort(0); putShort(0); putShort(w.toShort()); putShort(h.toShort())
        }.array())
    }

    /** Ask the server to change the guest resolution (needs ExtendedDesktopSize support). */
    fun requestResize(w: Int, h: Int) {
        if (!resizeSupported) return
        send(ByteBuffer.allocate(24).apply {
            put(251.toByte()); put(0)
            putShort(w.toShort()); putShort(h.toShort())
            put(1); put(0)
            putInt(screenId)
            putShort(0); putShort(0); putShort(w.toShort()); putShort(h.toShort())
            putInt(screenFlags)
        }.array())
    }

    // ---- input ----
    fun pointer(x: Int, y: Int, mask: Int) {
        lastX = x; lastY = y
        send(ByteBuffer.allocate(6).apply {
            put(5); put(mask.toByte()); putShort(x.toShort()); putShort(y.toShort())
        }.array())
    }

    fun click(x: Int, y: Int, mask: Int) { pointer(x, y, mask); pointer(x, y, 0) }
    fun releasePointer() = pointer(lastX, lastY, 0)

    /** Mouse wheel: button 4 (up) = mask 8, button 5 (down) = mask 16. */
    fun wheel(x: Int, y: Int, up: Boolean) {
        pointer(x, y, if (up) 8 else 16)
        pointer(x, y, 0)
    }

    fun key(sym: Int, down: Boolean) {
        send(ByteBuffer.allocate(8).apply {
            put(4); put(if (down) 1 else 0); putShort(0); putInt(sym)
        }.array())
    }

    fun tap(sym: Int) { key(sym, true); key(sym, false) }

    private fun keysym(cp: Int): Int = when {
        cp == '\n'.code -> 0xFF0D
        cp == '\t'.code -> 0xFF09
        cp < 0x20 -> 0
        cp <= 0xFF -> cp
        else -> 0x01000000 + cp
    }

    fun type(text: String) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val s = keysym(cp)
            if (s != 0) tap(s)
        }
    }

    fun ctrlAltDel() {
        val keys = intArrayOf(0xFFE3, 0xFFE9, 0xFFFF)
        keys.forEach { key(it, true) }
        keys.reversed().forEach { key(it, false) }
    }
}
