// A minimal HTTP/1.0 server on sockets through `external fun` (ffi_http.c): one process plays client and
// server on a loopback port, sequentially (the kernel queues the connection and the request until accept/read).
external fun kx_tcp_listen(): Int
external fun kx_tcp_port(fd: Int): Int
external fun kx_tcp_connect(port: Int): Int
external fun kx_tcp_accept(fd: Int): Int
external fun kx_send(fd: Int, p: CPointer, n: Long): Long
external fun kx_recv(fd: Int, p: CPointer, n: Long): Long
external fun kx_shutdown_write(fd: Int): Unit
external fun kx_close(fd: Int): Unit

fun sendAll(fd: Int, s: String) {
    val b = s.encodeToByteArray()
    b.usePinned { kx_send(fd, it.addressOf(0), b.size.toLong()) }
}
fun recvAll(fd: Int): String {
    val sb = StringBuilder()
    val buf = ByteArray(256)
    while (true) {
        val n = buf.usePinned { kx_recv(fd, it.addressOf(0), 256L) }
        if (n <= 0L) break
        sb.append(buf.decodeToString(0, n.toInt()))
    }
    return sb.toString()
}

class Request(val method: String, val path: String, val headers: Map<String, String>)

fun parse(raw: String): Request {
    val lines = raw.split("\r\n")
    val first = lines[0].split(" ")
    val h = LinkedHashMap<String, String>()
    for (i in 1 until lines.size) {
        val l = lines[i]
        if (l.isEmpty()) break
        val c = l.indexOf(":")
        if (c > 0) h[l.substring(0, c).lowercase()] = l.substring(c + 1).trim()
    }
    return Request(first[0], first[1], h)
}

fun handle(r: Request): String {
    val body = when (r.path) {
        "/" -> "hello from KotlinX"
        "/agent" -> "you are " + (r.headers["user-agent"] ?: "nobody")
        else -> null
    }
    return if (body == null) "HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n"
    else "HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\n\r\n$body"
}

fun main() {
    val server = kx_tcp_listen()
    val port = kx_tcp_port(server)
    println("listening: " + (port > 0))
    for (path in listOf("/", "/agent", "/missing")) {
        val c = kx_tcp_connect(port)
        sendAll(c, "GET $path HTTP/1.0\r\nUser-Agent: kx-test\r\n\r\n")
        kx_shutdown_write(c)
        val s = kx_tcp_accept(server)
        val req = parse(recvAll(s))
        sendAll(s, handle(req))
        kx_close(s)
        val resp = recvAll(c)
        kx_close(c)
        println(req.method + " " + req.path + " -> " + resp.substring(0, resp.indexOf("\r\n")) + " | " + resp.substring(resp.indexOf("\r\n\r\n") + 4))
    }
    kx_close(server)
}
