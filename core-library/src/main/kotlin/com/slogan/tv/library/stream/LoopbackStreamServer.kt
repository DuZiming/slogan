package com.slogan.tv.library.stream

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 可被回环流媒体服务器服务的字节源（smbj/libnfs 读桥实现本接口）。
 */
interface StreamSource {
    /** 已知总长返回之；未知返回 null（服务器按无 Content-Length 流式应答）。 */
    val lengthBytes: Long?

    /** 从指定偏移打开读取流（服务器负责关闭）。 */
    fun open(offset: Long): InputStream
}

/**
 * 127.0.0.1 回环 HTTP 流媒体服务器：mpv 从这里拉 SMB/NFS 的字节流。
 * 最小 HTTP/1.1：GET/HEAD、Range（bytes=a-b 与 bytes=-n）、206/Content-Range。
 * 只绑回环、无鉴权（本机唯一消费者）。
 */
class LoopbackStreamServer {

    private val sources = HashMap<String, StreamSource>()
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var running = false
    private val pool = Executors.newCachedThreadPool()

    /** 启动（幂等）；port=0 表示随机可用端口。返回实际端口。 */
    @Synchronized
    fun start(port: Int = 0): Int {
        serverSocket?.let { return it.localPort }
        val socket = ServerSocket(port, 8, InetAddress.getLoopbackAddress())
        serverSocket = socket
        running = true
        Thread({ acceptLoop(socket) }, "loopback-stream").apply {
            isDaemon = true
            start()
        }
        return socket.localPort
    }

    /** 注册一个字节源，返回可交给播放器的回环 URL。同 id 重复注册覆盖。 */
    fun register(id: String, source: StreamSource): String {
        val port = start()
        synchronized(sources) { sources[id] = source }
        return "http://127.0.0.1:$port/$id"
    }

    fun unregister(id: String) {
        synchronized(sources) { sources.remove(id) }
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(sources) { sources.clear() }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                return
            }
            pool.submit { runCatching { handle(client) } }
        }
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 15_000
            val input = s.getInputStream()
            val output = s.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].removePrefix("/").substringBefore('?')

            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }

            val source = synchronized(sources) { sources[path] }
            if (source == null) {
                respond(output, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                return
            }
            if (method != "GET" && method != "HEAD") {
                respond(output, "HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                return
            }

            val rangeHeader = headers["range"]
            val total = source.lengthBytes

            if (rangeHeader != null && total != null) {
                serveRange(output, method, source, rangeHeader, total)
            } else {
                serveFull(output, method, source, total)
            }
        }
    }

    private fun serveFull(output: OutputStream, method: String, source: StreamSource, total: Long?) {
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            total?.let { append("Content-Length: $it\r\n") }
            append("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n")
        }
        output.write(head.toByteArray())
        output.flush()
        if (method == "GET") {
            source.open(0).use { input -> copy(input, output, total ?: Long.MAX_VALUE) }
        }
    }

    private fun serveRange(output: OutputStream, method: String, source: StreamSource, rangeHeader: String, total: Long) {
        val parsed = parseRange(rangeHeader, total)
        if (parsed == null) {
            respond(output, "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            return
        }
        val (start, endInclusive) = parsed
        val length = endInclusive - start + 1
        val head = buildString {
            append("HTTP/1.1 206 Partial Content\r\n")
            append("Content-Range: bytes $start-$endInclusive/$total\r\n")
            append("Content-Length: $length\r\n")
            append("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n")
        }
        output.write(head.toByteArray())
        output.flush()
        if (method == "GET") {
            source.open(start).use { input -> copy(input, output, length) }
        }
    }

    /** Range 解析：bytes=a-b / bytes=a- / bytes=-n；非法或越界返回 null。 */
    private fun parseRange(range: String, total: Long): Pair<Long, Long>? {
        val m = Regex("bytes=(\\d*)-(\\d*)").matchEntire(range.trim()) ?: return null
        val a = m.groupValues[1]
        val b = m.groupValues[2]
        return when {
            a.isEmpty() && b.isNotEmpty() -> {
                val n = b.toLong().coerceAtMost(total)
                (total - n).coerceAtLeast(0) to total - 1
            }
            a.isNotEmpty() -> {
                val start = a.toLong()
                if (start >= total) return null
                val end = (b.takeIf { it.isNotEmpty() }?.toLong() ?: total - 1).coerceAtMost(total - 1)
                if (end < start) return null
                start to end
            }
            else -> null
        }
    }

    private fun copy(input: InputStream, output: OutputStream, limit: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) break
            output.write(buf, 0, n)
            remaining -= n
        }
        output.flush()
    }

    private fun respond(output: OutputStream, response: String) {
        output.write(response.toByteArray())
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }
}
