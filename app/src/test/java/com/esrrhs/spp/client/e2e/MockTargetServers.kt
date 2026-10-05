package com.esrrhs.spp.client.e2e

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 目标测试服务器集合：
 * 1. TcpEchoServer: 回显 TCP 收到的所有字节，支持 IPv4 ("127.0.0.1") 与 IPv6 ("::1")
 * 2. UdpEchoServer: 回显 UDP 数据报文，支持 IPv4 与 IPv6
 * 3. HttpEchoServer: 响应 HTTP 请求，回显请求 body 与 query
 */
object MockTargetServers {

    class TcpEchoServer(val host: String = "127.0.0.1") : AutoCloseable {
        private val running = AtomicBoolean(false)
        private var serverSocket: ServerSocket? = null
        private val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "tcp-echo-$host").apply { isDaemon = true }
        }
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()

        val port: Int get() = serverSocket?.localPort ?: 0

        fun start(): TcpEchoServer {
            if (running.getAndSet(true)) return this
            val addr = InetAddress.getByName(host)
            val ss = if (addr is java.net.Inet6Address) {
                val ch = java.nio.channels.ServerSocketChannel.open()
                ch.bind(InetSocketAddress(addr, 0), 64)
                ch.socket()
            } else {
                ServerSocket(0, 64, addr)
            }
            serverSocket = ss
            pool.execute {
                while (running.get()) {
                    val s = try {
                        ss.accept()
                    } catch (_: Exception) {
                        break
                    }
                    sockets.add(s)
                    pool.execute {
                        try {
                            s.tcpNoDelay = true
                            val buf = ByteArray(8192)
                            val input = s.getInputStream()
                            val out = s.getOutputStream()
                            while (running.get() && !s.isClosed) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                out.flush()
                            }
                        } catch (_: Exception) {
                        } finally {
                            runCatching { s.close() }
                            sockets.remove(s)
                        }
                    }
                }
            }
            return this
        }

        override fun close() {
            if (!running.getAndSet(false)) return
            runCatching { serverSocket?.close() }
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
            pool.shutdownNow()
        }
    }

    class UdpEchoServer(val host: String = "127.0.0.1") : AutoCloseable {
        private val running = AtomicBoolean(false)
        private var socket: DatagramSocket? = null
        private val pool = Executors.newSingleThreadExecutor { r ->
            Thread(r, "udp-echo-$host").apply { isDaemon = true }
        }

        val port: Int get() = socket?.localPort ?: 0

        fun start(): UdpEchoServer {
            if (running.getAndSet(true)) return this
            val s = DatagramSocket(0, InetAddress.getByName(host))
            socket = s
            pool.execute {
                val buf = ByteArray(65535)
                while (running.get()) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                        // 原路回显数据
                        val echo = DatagramPacket(p.data, p.offset, p.length, p.socketAddress)
                        s.send(echo)
                    } catch (_: Exception) {
                        break
                    }
                }
            }
            return this
        }

        override fun close() {
            if (!running.getAndSet(false)) return
            runCatching { socket?.close() }
            pool.shutdownNow()
        }
    }

    class HttpEchoServer(val host: String = "127.0.0.1") : AutoCloseable {
        private var server: HttpServer? = null

        val port: Int get() = server?.address?.port ?: 0

        fun start(): HttpEchoServer {
            val s = HttpServer.create(InetSocketAddress(InetAddress.getByName(host), 0), 64)
            s.createContext("/echo", HttpHandler { exchange ->
                val body = exchange.requestBody.readBytes()
                val resp = if (body.isNotEmpty()) body else "HELLO_HTTP_ECHO".toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/plain")
                exchange.sendResponseHeaders(200, resp.size.toLong())
                exchange.responseBody.use { it.write(resp) }
            })
            s.executor = Executors.newCachedThreadPool { r ->
                Thread(r, "http-echo-$host").apply { isDaemon = true }
            }
            s.start()
            server = s
            return this
        }

        override fun close() {
            server?.stop(0)
        }
    }
}
