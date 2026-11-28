package com.dormpanel.app.airplay

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URL

/** One active RAOP session. The DACP address must be the actual RTSP peer. */
internal class AirPlayRemote(
    private val nsd: NsdManager,
    private val worker: Handler,
    private val peer: InetAddress,
    id: String,
    private val token: String,
    private val onCommands: (Set<AirPlayCommand>) -> Unit,
) {
    private val name = "iTunes_Ctrl_$id"
    private var open = true
    private var port = 0
    private var attempts = 0
    private var commands = emptySet<AirPlayCommand>()
    private var request: Thread? = null
    @Volatile private var closed = false
    private val retry = Runnable { start() }

    @Suppress("DEPRECATION")
    fun start() {
        if (!open || ++attempts > 3) return
        val service = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_dacp._tcp."
        }
        try {
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    worker.post { retryIfOpen() }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    worker.post {
                        if (!open || !info.serviceName.equals(name, ignoreCase = true) ||
                            info.host != peer || info.port !in 1..65535) return@post
                        port = info.port
                        query("playstatusupdate?revision-number=1", probe = true) { code ->
                            if (code == 200) {
                                commands = AirPlayCommand.entries.toSet()
                                onCommands(commands)
                            } else {
                                retryIfOpen()
                            }
                        }
                    }
                }
            })
        } catch (_: RuntimeException) {
            retryIfOpen()
        }
    }

    private fun retryIfOpen() {
        if (open && attempts < 3) worker.postDelayed(retry, 2_500)
    }

    fun send(command: AirPlayCommand) {
        if (command !in commands) return
        query(command.path) { code ->
            if (code == 401 || code == 403) commands = emptySet()
            else if (code !in 200..299) commands -= command
            onCommands(commands)
        }
    }

    private fun query(path: String, probe: Boolean = false, complete: (Int) -> Unit) {
        if (!open || port == 0 || request != null) return
        val targetPort = port
        request = Thread({
            val host = if (peer is Inet6Address) "[${peer.hostAddress}]" else peer.hostAddress
            val code = try {
                if (closed) throw IOException()
                val connection = URL("http", host, targetPort, "/ctrl-int/1/$path")
                    .openConnection(Proxy.NO_PROXY) as HttpURLConnection
                try {
                    connection.connectTimeout = 1_500
                    connection.readTimeout = 1_500
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.setRequestProperty("Active-Remote", token)
                    connection.setRequestProperty("Connection", "close")
                    val status = connection.responseCode
                    if (probe && status == 200) {
                        val signature = ByteArray(4)
                        java.io.DataInputStream(connection.inputStream).readFully(signature)
                        if (!signature.contentEquals("cmst".toByteArray(Charsets.US_ASCII))) 404 else status
                    } else status
                } finally {
                    connection.disconnect()
                }
            } catch (_: IOException) {
                0
            } catch (_: RuntimeException) {
                0
            }
            worker.post {
                request = null
                if (open) complete(code)
            }
        }, "AirPlay DACP").apply { start() }
    }

    fun close() {
        closed = true
        open = false
        worker.removeCallbacks(retry)
        request?.interrupt()
    }
}
