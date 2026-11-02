package vn.airplayrx

import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/** HTTP server tối giản theo giao thức AirPlay 1 (video). */
class AirPlayServer(private val deviceId: String, private val onPlay: (String, Double) -> Unit) {
    companion object {
        const val PORT = 7000
        /** true: bỏ qua lệnh /stop từ iPhone -> video chỉ dừng khi iPhone phát video KHÁC. */
        const val IGNORE_REMOTE_STOP = true
        const val FEATURES = 0x19 // video + volume + HLS (chỉnh nếu iPhone không thấy thiết bị)
    }
    private var server: ServerSocket? = null
    private val events = CopyOnWriteArrayList<OutputStream>()
    @Volatile private var running = false

    fun start() {
        running = true
        Thread {
            try {
                val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
                server = s
                while (running) { val c = s.accept(); Thread { handle(c) }.start() }
            } catch (_: Exception) {}
        }.start()
    }
    fun stop() { running = false; try { server?.close() } catch (_: Exception) {} }

    private fun handle(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            val inp = BufferedInputStream(sock.getInputStream()); val out = sock.getOutputStream()
            while (true) {
                val line = readLine(inp) ?: return
                if (line.isEmpty()) continue
                val parts = line.split(" "); val method = parts[0]; val target = parts[1]
                val headers = HashMap<String, String>()
                while (true) {
                    val h = readLine(inp) ?: return
                    if (h.isEmpty()) break
                    val i = h.indexOf(':'); if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
                }
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(len); var r = 0
                while (r < len) { val n = inp.read(body, r, len - r); if (n < 0) return; r += n }
                val path = target.substringBefore('?')
                val q = target.substringAfter('?', "").split('&').filter { it.contains('=') }
                    .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                if (path == "/reverse") { // kênh sự kiện: iPhone đóng kênh này (khoá màn hình) cũng KHÔNG dừng video
                    out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: PTTH/1.0\r\nConnection: Upgrade\r\n\r\n".toByteArray())
                    out.flush(); events.add(out)
                    while (inp.read() >= 0) { }
                    events.remove(out); return
                }
                route(method, path, q, body, out)
            }
        } catch (_: Exception) { } finally { try { sock.close() } catch (_: Exception) {} }
    }

    private fun route(method: String, path: String, q: Map<String, String>, body: ByteArray, out: OutputStream) {
        when (path) {
            "/info" -> reply(out, 404)
            "/server-info" -> reply(out, 200, "text/x-apple-plist+xml", plist(
                "<key>deviceid</key><string>$deviceId</string><key>features</key><integer>$FEATURES</integer>" +
                "<key>model</key><string>AppleTV3,2</string><key>protovers</key><string>1.0</string><key>srcvers</key><string>220.68</string>"))
            "/play" -> {
                val r = parsePlay(body)
                if (r != null) { onPlay(r.first, r.second); reply(out, 200) } else reply(out, 400)
            }
            "/rate" -> { PlayerHolder.setRate(q["value"]?.toDoubleOrNull() ?: 0.0); reply(out, 200) }
            "/scrub" -> if (method == "POST") {
                PlayerHolder.seekSeconds(q["position"]?.toDoubleOrNull() ?: 0.0); reply(out, 200)
            } else {
                val (d, p, _) = PlayerHolder.info()
                reply(out, 200, "text/parameters", "duration: $d\r\nposition: $p\r\n".toByteArray())
            }
            "/playback-info" -> {
                val (d, p, r) = PlayerHolder.info()
                val range = "<array><dict><key>duration</key><real>$d</real><key>start</key><real>0.0</real></dict></array>"
                reply(out, 200, "text/x-apple-plist+xml", plist(
                    "<key>duration</key><real>$d</real><key>position</key><real>$p</real><key>rate</key><real>$r</real>" +
                    "<key>readyToPlay</key><true/><key>playbackBufferEmpty</key><false/><key>playbackBufferFull</key><true/>" +
                    "<key>playbackLikelyToKeepUp</key><true/><key>loadedTimeRanges</key>$range<key>seekableTimeRanges</key>$range"))
            }
            "/stop" -> { if (!IGNORE_REMOTE_STOP) PlayerHolder.stop(); reply(out, 200) }
            else -> reply(out, 200)
        }
    }

    private fun parsePlay(body: ByteArray): Pair<String, Double>? = try {
        val head = String(body, 0, minOf(6, body.size), Charsets.ISO_8859_1)
        if (head == "bplist" || String(body).trimStart().startsWith("<?xml")) {
            val d = PropertyListParser.parse(body) as NSDictionary
            val url = (d.objectForKey("Content-Location") as? NSString)?.content
            val start = (d.objectForKey("Start-Position") as? NSNumber)?.doubleValue() ?: 0.0
            url?.let { it to start }
        } else {
            val m = String(body).lines().mapNotNull { l ->
                l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()
            m["Content-Location"]?.let { it to (m["Start-Position"]?.toDoubleOrNull() ?: 0.0) }
        }
    } catch (_: Exception) { null }

    fun sendEvent(state: String) {
        val b = plist("<key>category</key><string>video</string><key>state</key><string>$state</string>")
        val msg = "POST /event HTTP/1.1\r\nContent-Type: text/x-apple-plist+xml\r\nContent-Length: ${b.size}\r\n\r\n".toByteArray() + b
        for (o in events) try { o.write(msg); o.flush() } catch (_: Exception) { events.remove(o) }
    }

    private fun plist(inner: String) = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" " +
        "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n<plist version=\"1.0\"><dict>$inner</dict></plist>").toByteArray()

    private fun reply(o: OutputStream, code: Int, type: String? = null, body: ByteArray = ByteArray(0)) {
        val st = when (code) { 200 -> "OK"; 400 -> "Bad Request"; else -> "Not Found" }
        val h = "HTTP/1.1 $code $st\r\n" + (if (type != null) "Content-Type: $type\r\n" else "") + "Content-Length: ${body.size}\r\n\r\n"
        o.write(h.toByteArray() + body); o.flush()
    }

    private fun readLine(i: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read(); if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }
}
