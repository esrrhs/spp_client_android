package com.esrrhs.spp.client.spp

import android.content.Context
import com.esrrhs.spp.client.R
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import com.esrrhs.spp.client.util.LogSanitizer
import java.util.concurrent.TimeUnit

class SppException(message: String) : Exception(message)

/**
 * 以独立子进程运行 SPP `socks5_client`（打包为 jniLibs 里的 libspp.so，
 * 安装后位于 nativeLibraryDir，API 29+ 仍允许执行该目录下的文件）。
 *
 * 环路防护说明：`VpnService.protect()` 只能保护本进程创建的 fd，对子进程无效；
 * 本 App 采用 `addDisallowedApplication(自身包名)` 把整个 App 排除在 VPN 之外，
 * spp 子进程的出站 socket 天然绕过 TUN，不会形成「VPN 套 VPN」环路；
 * 而 hev-socks5-tunnel 在本进程内直接读写 TUN fd，不受 disallowed 影响。
 */
class SppProcess(context: Context) {

    private val appContext = context.applicationContext
    private var process: Process? = null
    private val outputLines = ArrayDeque<String>()

    /** 主动 stop 时置位，看门狗据此区分「意外退出」。 */
    @Volatile
    private var intentionallyStopped = false

    /** 进程意外退出（非 [stop]）回调；在守护线程触发，实现方需自行切线程。 */
    @Volatile
    var onUnexpectedExit: (() -> Unit)? = null

    /**
     * 启动 socks5_client，阻塞直至本地端口开始监听。
     *
     * @param startupTimeoutMs 等待本地 SOCKS 端口上线的最长时间（端口仅在到
     * server 的会话建立后才绑定，服务器不可达时会等到超时再抛异常）。
     * @return 本地 SOCKS5 监听端口（仅绑定 127.0.0.1）
     */
    fun start(config: SppConfig, startupTimeoutMs: Long = 15_000L): Int {
        val binary = File(appContext.applicationInfo.nativeLibraryDir, "libspp.so")
        if (!binary.exists() || !binary.canExecute()) {
            throw SppException(appContext.getString(R.string.error_missing_native))
        }

        val port = findFreeLoopbackPort()
        val args = buildList {
            add(binary.absolutePath)
            add("-type"); add("socks5_client")
            add("-name"); add("spp-android")
            add("-server"); add(config.serverAddr)
            add("-fromaddr"); add("127.0.0.1:$port")
            // SOCKS5 本体走 TCP；UDP ASSOCIATE 的中继由 spp 在同一条控制连接上处理
            add("-proxyproto"); add("tcp")
            add("-proto"); add(config.proto)
            add("-key"); add(config.key)
            if (config.encrypt.isNotBlank()) {
                add("-encrypt"); add(config.encrypt)
            }
            add("-compress"); add(config.compress.toString())
            // Android 上进程 cwd 不可写，gohome/loggo 会往 cwd 写 *.stderr 而崩溃；
            // 关闭其日志文件，输出走 stdout（下面已采集并落盘）
            add("-nolog"); add("1")
        }

        intentionallyStopped = false
        synchronized(outputLines) { outputLines.clear() }
        val proc = ProcessBuilder(args)
            // spp 的日志写 stdout，合并两个流统一采集
            .redirectErrorStream(true)
            // 防御：万一有代码仍按相对路径写文件，落到应用私有目录而不是 /
            .directory(File(appContext.filesDir.absolutePath))
            .start()
        process = proc

        Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { raw ->
                    // spp 的 stdout 带 ANSI 颜色码，剥除后再回放/落盘
                    val line = LogSanitizer.stripAnsi(raw)
                    synchronized(outputLines) {
                        outputLines.addLast(line)
                        while (outputLines.size > MAX_OUTPUT_LINES) outputLines.removeFirst()
                    }
                    appendToLogFile(line)
                }
            }
        }.apply { isDaemon = true; name = "spp-output" }.start()

        Thread {
            runCatching { proc.waitFor() }
            if (!intentionallyStopped) {
                android.util.Log.w(TAG, "spp process exited unexpectedly")
                onUnexpectedExit?.invoke()
            }
        }.apply { isDaemon = true; name = "spp-exit-watch" }.start()

        if (!waitUntilListening(port, startupTimeoutMs)) {
            val alive = proc.isAlive
            val tail = synchronized(outputLines) { outputLines.joinToString("\n") }
            stop()
            val detail = tail.ifBlank { appContext.getString(R.string.log_no_output) }
            throw SppException(
                when {
                    // SOCKS 端口只在到 server 的会话（认证+加密）建立后才绑定
                    alive -> appContext.getString(R.string.error_spp_session, detail)
                    else -> appContext.getString(R.string.error_spp_exited, detail)
                },
            )
        }
        return port
    }

    fun stop() {
        val proc = process ?: return
        intentionallyStopped = true
        process = null
        proc.destroy()
        if (!proc.waitFor(2, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
        }
    }

    /** 最近的输出（用于启动失败时报错回放）。 */
    fun recentOutput(): String = synchronized(outputLines) { outputLines.joinToString("\n") }

    private fun waitUntilListening(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var firstError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            val proc = process ?: return false
            if (!proc.isAlive) return false
            try {
                Socket().use {
                    // 显式用 IPv4 回环：Android 的 getLoopbackAddress() 返回 ::1，
                    // 而 spp 的 -fromaddr 绑定的是 127.0.0.1
                    it.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 250)
                }
                android.util.Log.i(TAG, "socks port $port is listening")
                return true
            } catch (e: Exception) {
                if (firstError == null) {
                    firstError = e
                    android.util.Log.w(TAG, "probe 127.0.0.1:$port -> ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            Thread.sleep(100)
        }
        android.util.Log.w(TAG, "probe timeout, last error: ${firstError?.message}")
        return false
    }

    // 与 -fromaddr 的绑定域保持一致，用 IPv4 回环探测空闲端口
    private fun findFreeLoopbackPort(): Int =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    /** 追加到 spp.log，超限时滚动为 spp.log.1（供日志页查看）。 */
    private fun appendToLogFile(line: String) {
        runCatching {
            val file = File(appContext.filesDir, LOG_FILE)
            if (file.length() > MAX_LOG_BYTES) {
                file.renameTo(File(appContext.filesDir, "$LOG_FILE.1"))
            }
            file.appendText(line + "\n")
        }
    }

    private companion object {
        const val MAX_OUTPUT_LINES = 50
        const val MAX_LOG_BYTES = 256L * 1024
        const val LOG_FILE = "spp.log"
        const val TAG = "SppProcess"
    }
}
