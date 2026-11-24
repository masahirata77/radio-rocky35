package com.rocky35.radio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

// Puente local entre la radio y MediaPlayer: pide el stream con metadatos ICY,
// le pasa a MediaPlayer solo el audio y avisa el título apenas la radio lo manda.
class IcyProxy(private val url:String, private val onTitle:(String)->Unit) {
    private val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
    @Volatile private var closed = false
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile private var client: Socket? = null
    private var lastRaw: String? = null
    val localUrl: String get() = "http://127.0.0.1:${server.localPort}/stream"

    fun start() { Thread({ acceptLoop() }, "icy-proxy").apply { isDaemon = true }.start() }

    private fun acceptLoop() {
        try {
            while (!closed) {
                val s = server.accept()
                // Si MediaPlayer vuelve a conectarse, se corta la sesión anterior.
                try { client?.close() } catch (_: Exception) {}
                try { conn?.disconnect() } catch (_: Exception) {}
                client = s
                Thread({ serve(s) }, "icy-session").apply { isDaemon = true }.start()
            }
        } catch (_: Exception) {}
    }

    private fun skipRequest(i: InputStream) {
        var state = 0
        while (true) {
            val b = i.read(); if (b < 0) return
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && state == 1 -> 2
                b == '\n'.code && state == 3 -> return
                else -> 0
            }
        }
    }

    private fun serve(s: Socket) {
        var c: HttpURLConnection? = null
        try {
            s.soTimeout = 15000
            skipRequest(s.getInputStream())
            c = URL(url).openConnection() as HttpURLConnection; conn = c
            c.connectTimeout = 10000; c.readTimeout = 20000
            c.setRequestProperty("Icy-MetaData", "1"); c.setRequestProperty("User-Agent", "RadioRocky35")
            if (c.responseCode !in 200..299) return
            val mi = c.getHeaderField("icy-metaint")?.trim()?.toIntOrNull() ?: 0
            val meta = mi in 1..65536
            val type = c.contentType ?: "audio/mpeg"
            val out = BufferedOutputStream(s.getOutputStream(), 16384)
            out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
            val up = BufferedInputStream(c.inputStream, 16384)
            val buf = ByteArray(8192)
            if (!meta) {
                while (!closed && s === client) { val n = up.read(buf); if (n < 0) return; out.write(buf, 0, n); out.flush(); RadioService.addBytes(n) }
                return
            }
            while (!closed && s === client) {
                var left = mi
                while (left > 0) { val n = up.read(buf, 0, minOf(left, buf.size)); if (n < 0) return; out.write(buf, 0, n); left -= n; RadioService.addBytes(n) }
                out.flush()
                val lb = up.read(); if (lb < 0) return
                val len = lb * 16
                if (len > 0) {
                    val m = ByteArray(len); var r = 0
                    while (r < len) { val n = up.read(m, r, len - r); if (n < 0) return; r += n }
                    var t = String(m, Charsets.UTF_8); if ('\uFFFD' in t) t = String(m, Charsets.ISO_8859_1)
                    val title = Regex("StreamTitle='(.*?)';").find(t)?.groupValues?.get(1)?.trim()
                    if (title != null && title != lastRaw) { lastRaw = title; onTitle(title) }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
            try { s.close() } catch (_: Exception) {}
        }
    }

    fun close() {
        closed = true
        try { client?.close() } catch (_: Exception) {}
        try { conn?.disconnect() } catch (_: Exception) {}
        try { server.close() } catch (_: Exception) {}
    }
}
