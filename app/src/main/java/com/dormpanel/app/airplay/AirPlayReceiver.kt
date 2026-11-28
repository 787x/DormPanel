package com.dormpanel.app.airplay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.bridge.RaopCallbackHandler
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Owns native handles, discovery and focus. All mutations run on one service worker. */
internal class AirPlayReceiver(
    private val context: Context,
    private val worker: Handler,
    private val publish: (AirPlayState) -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private var wake: PowerManager.WakeLock? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var state = AirPlayState(DEFAULT_RECEIVER_NAME)
    private var running = false
    private var handle = 0L
    private var generation = 0
    private var retries = 0
    private var pendingRegistrations = 0
    private var registeredName: String? = null
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()
    private var focus: AudioFocusRequest? = null
    private var focusGranted = false
    private var focusBlocked = false
    private var duck = 1f
    private var clientGain = 1f
    private var output = false
    private var session = false
    private var packets = false
    private var audioFailed = false
    private var peer: InetAddress? = null
    private var remote: AirPlayRemote? = null
    private var remoteIdentity: Pair<String, String>? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val networks = mutableMapOf<Network, Set<LinkAddress>>()
    private var links = emptySet<LinkAddress>()

    private val restart = Runnable { openReceiver() }
    private val discoveryTimeout = Runnable {
        if (pendingRegistrations > 0) startupFailed("局域网广播超时")
    }
    private val silenceTimeout = Runnable { pauseStream() }

    fun start(name: String) {
        if (running && state.name == name && networkCallback != null) {
            if (handle == 0L && links.isNotEmpty() && state.phase == AirPlayPhase.FAILED) {
                retries = 0
                worker.removeCallbacks(restart)
                openReceiver()
            }
            return
        }
        close()
        running = true
        state = AirPlayState(name)
        phase(AirPlayPhase.STARTING)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (networkCallback !== this) return
                networks[network] = properties.linkAddresses.filter {
                    !it.address.isAnyLocalAddress && !it.address.isLoopbackAddress
                }.toSet()
                networkChanged()
            }

            override fun onLost(network: Network) {
                if (networkCallback !== this) return
                networks.remove(network)
                networkChanged()
            }
        }
        networkCallback = callback
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            connectivity.registerNetworkCallback(request, callback, worker)
            phase(AirPlayPhase.OFFLINE, "等待 Wi-Fi 或有线局域网")
        } catch (_: RuntimeException) {
            networkCallback = null
            phase(AirPlayPhase.FAILED, "无法监听局域网变化")
        }
    }

    private fun networkChanged() {
        val next = networks.values.flatten().toSet()
        if (next == links) return
        links = next
        retries = 0
        worker.removeCallbacks(restart)
        releaseReceiver()
        if (links.isEmpty()) {
            phase(AirPlayPhase.OFFLINE, "等待 Wi-Fi 或有线局域网")
        } else {
            phase(AirPlayPhase.STARTING)
            worker.postDelayed(restart, 750)
        }
    }

    private fun openReceiver() {
        if (!running || links.isEmpty() || handle != 0L) return
        phase(AirPlayPhase.STARTING)
        val epoch = ++generation
        try {
            multicast = wifi.createMulticastLock("DormPanel:AirPlay").apply {
                setReferenceCounted(false)
                acquire()
            }
            NativeBridge.nativeSetDefaultStreamValues(
                audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000,
                audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256,
            )
            val keyFile = context.noBackupFilesDir.resolve("airplay-key")
            val identity = identity()
            handle = NativeBridge.nativeInit(Callbacks(epoch), identity, state.name, keyFile.path, false, false)
            check(handle != 0L)
            NativeBridge.nativeSetH265Enabled(handle, false)
            NativeBridge.nativeSetHlsEnabled(handle, false)
            // HomeView's audio profile with the remaining display-related flags cleared.
            for (bit in listOf(1, 2, 5, 6, 7, 8)) NativeBridge.nativeSetFeature(handle, bit, false)
            NativeBridge.nativeSetCodecs(handle, true, true)
            check(NativeBridge.nativeServerAudioConfigure(handle, 0, 95, 0, true, false,
                false, false, false))
            val port = NativeBridge.nativeStart(handle, 0)
            check(port in 1..65535)
            val raopName = requireNotNull(NativeBridge.nativeGetRaopServiceName(handle))
            pendingRegistrations = 2
            register(epoch, raopName, "_raop._tcp.", port, NativeBridge.nativeGetRaopTxtRecords(handle))
            register(epoch, state.name, "_airplay._tcp.", port, NativeBridge.nativeGetAirplayTxtRecords(handle))
            worker.postDelayed(discoveryTimeout, 8_000)
        } catch (_: LinkageError) {
            startupFailed("此设备无法加载 AirPlay 音频库", retry = false)
        } catch (error: RuntimeException) {
            Log.w(TAG, "AirPlay start failed (${error.javaClass.simpleName})")
            startupFailed("无法启动监听或局域网广播")
        }
    }

    private fun register(epoch: Int, name: String, type: String, port: Int, txt: Map<String, String>?) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            setPort(port)
            requireNotNull(txt).forEach { (key, value) -> setAttribute(key, value) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                worker.post {
                    if (epoch != generation) {
                        unregister(this)
                        return@post
                    }
                    if (type == "_airplay._tcp.") registeredName = info.serviceName
                    if (--pendingRegistrations == 0) {
                        worker.removeCallbacks(discoveryTimeout)
                        retries = 0
                        if (!session) phase(AirPlayPhase.WAITING,
                            registeredName?.takeIf { it != state.name }?.let { "局域网名称为 $it" })
                    }
                }
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                worker.post { if (epoch == generation) startupFailed("局域网广播注册失败（$code）") }
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "AirPlay NSD unregistration failed ($code)")
            }
        }
        registrations += listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun unregister(listener: NsdManager.RegistrationListener) {
        runCatching { nsd.unregisterService(listener) }
    }

    private fun identity(): ByteArray {
        val file = context.noBackupFilesDir.resolve("airplay-identity")
        if (file.exists()) file.readBytes().takeIf { it.size == 6 }?.let { return it }
        return ByteArray(6).also {
            SecureRandom().nextBytes(it)
            it[0] = ((it[0].toInt() and 0xfc) or 0x02).toByte()
            file.writeBytes(it)
        }
    }

    private fun startupFailed(message: String, retry: Boolean = true) {
        releaseReceiver()
        phase(AirPlayPhase.FAILED, message)
        if (retry && running && links.isNotEmpty() && retries < 3) {
            worker.postDelayed(restart, 1_000L shl retries++)
        }
    }

    fun close() {
        running = false
        worker.removeCallbacks(restart)
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
        links = emptySet()
        networks.clear()
        releaseReceiver()
    }

    private fun releaseReceiver() {
        ++generation
        worker.removeCallbacks(discoveryTimeout)
        registrations.forEach(::unregister)
        registrations.clear()
        pendingRegistrations = 0
        registeredName = null
        clearSession()
        if (handle != 0L) {
            NativeBridge.nativeStop(handle)
            NativeBridge.nativeDestroy(handle)
            handle = 0L
        }
        multicast?.release()
        multicast = null
    }

    private fun clearSession() {
        session = false
        packets = false
        audioFailed = false
        worker.removeCallbacks(silenceTimeout)
        releaseFocus()
        remote?.close()
        remote = null
        remoteIdentity = null
        peer = null
        clientGain = 1f
        state = AirPlayState(state.name, state.phase)
    }

    private fun endSession() {
        if (!session) return
        clearSession()
        phase(if (pendingRegistrations == 0) AirPlayPhase.WAITING else AirPlayPhase.STARTING)
    }

    private fun phase(phase: AirPlayPhase, detail: String? = null) =
        update(state.copy(phase = phase, detail = detail))

    private fun update(value: AirPlayState) {
        state = value
        publish(value)
    }

    private fun onPacket() {
        if (!session) return
        packets = true
        worker.removeCallbacks(silenceTimeout)
        worker.postDelayed(silenceTimeout, 3_000)
        if (output || audioFailed || focusBlocked) return
        if (focus == null) requestFocus()
        if (focusGranted) startOutput()
    }

    private fun pauseStream() {
        if (!session) return
        packets = false
        audioFailed = false
        worker.removeCallbacks(silenceTimeout)
        releaseFocus()
        phase(AirPlayPhase.PAUSED)
    }

    private fun requestFocus() {
        lateinit var request: AudioFocusRequest
        request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (focus === request && session) onFocusChange(change)
            }, worker)
            .build()
        if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focus = request
            focusGranted = true
        } else {
            focusBlocked = true
            phase(AirPlayPhase.PAUSED, "其他应用正在使用音频")
        }
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusGranted = true
                duck = 1f
                applyGain()
                if (packets && !output) startOutput()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                duck = 0.2f
                applyGain()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusGranted = false
                stopOutput()
                phase(AirPlayPhase.PAUSED, "音频暂被其他应用占用")
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                releaseFocus()
                focusBlocked = true
                phase(AirPlayPhase.PAUSED, "音频已切换到其他应用")
            }
        }
    }

    private fun startOutput() {
        applyGain()
        if (NativeBridge.nativeServerAudioStart(handle)) {
            output = true
            wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DormPanel:AirPlayAudio")
                .apply { setReferenceCounted(false); acquire() }
            phase(AirPlayPhase.PLAYING)
        } else {
            audioFailed = true
            releaseFocus()
            phase(AirPlayPhase.FAILED, "无法启动系统音频输出，请重新连接")
        }
    }

    private fun stopOutput() {
        if (output) NativeBridge.nativeServerAudioStop(handle)
        output = false
        wake?.release()
        wake = null
    }

    private fun releaseFocus() {
        stopOutput()
        focus?.let(audio::abandonAudioFocusRequest)
        focus = null
        focusGranted = false
        focusBlocked = false
        duck = 1f
    }

    private fun applyGain() = NativeBridge.nativeServerAudioSetClientGain(handle, clientGain * duck)

    fun command(command: AirPlayCommand) {
        if (command in state.commands) remote?.send(command)
    }

    private fun remoteIdentity(id: String, token: String) {
        val host = peer ?: return
        if (!session || !id.matches(Regex("[0-9A-Fa-f]{1,16}")) ||
            token.toUIntOrNull() == null || links.none { sameSubnet(it, host) }) return
        if (remoteIdentity == (id to token)) return
        remote?.close()
        update(state.copy(commands = emptySet()))
        remoteIdentity = id to token
        remote = AirPlayRemote(nsd, worker, host, id, token) { commands ->
            update(state.copy(commands = commands))
        }.also { it.start() }
    }

    private fun sameSubnet(link: LinkAddress, host: InetAddress): Boolean {
        if (host.isAnyLocalAddress || host.isLoopbackAddress || host.isMulticastAddress) return false
        return containsAddress(link.address.address, link.prefixLength, host.address)
    }

    private inner class Callbacks(private val epoch: Int) : RaopCallbackHandler {
        private val metadataQueued = AtomicBoolean()
        private val artworkQueued = AtomicBoolean()
        private val progressQueued = AtomicBoolean()
        private val volumeQueued = AtomicBoolean()
        @Volatile private var senderVolume = 0f
        private fun event(action: () -> Unit) {
            worker.post { if (epoch == generation && handle != 0L) action() }
        }
        private fun bounded(pending: AtomicBoolean, action: () -> Unit) {
            if (!pending.compareAndSet(false, true)) return
            worker.post {
                try {
                    if (epoch == generation && handle != 0L && session) action()
                } finally {
                    pending.set(false)
                }
            }
        }

        override fun onAudioPeer(peer: String) = event {
            endSession()
            this@AirPlayReceiver.peer = numericAddress(peer)
        }
        override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) = event {
            if (usingScreen || ct !in SUPPORTED_CODECS) return@event
            NativeBridge.nativeServerAudioFormat(handle, ct, spf.takeIf { it in 1..2048 } ?: 0)
            session = true
            phase(AirPlayPhase.CONNECTED)
        }
        override fun onAudioPacket() = event { onPacket() }
        override fun onAudioFlush() = event { pauseStream() }
        override fun onAudioTeardown() = event { endSession() }
        override fun onConnectionDestroy() = event { endSession() }
        override fun onConnectionReset(reason: Int) = event { endSession() }

        override fun onClientVolume(db: Float) {
            if (!db.isFinite()) return
            senderVolume = db.coerceIn(-144f, 0f)
            bounded(volumeQueued) {
                clientGain = if (senderVolume <= -144f) 0f
                    else Math.pow(10.0, senderVolume.coerceAtLeast(-30f) / 20.0).toFloat()
                applyGain()
            }
        }
        override fun onClientVolumeQuery(): Float = senderVolume
        override fun onMetadata(data: ByteArray) = bounded(metadataQueued) {
            val track = AirPlayMetadata.track(data) ?: return@bounded
            if (track != state.track) update(state.copy(track = track, artwork = null, progress = null))
        }
        override fun onCoverArt(data: ByteArray) = bounded(artworkQueued) {
            update(state.copy(artwork = AirPlayMetadata.artwork(data)))
        }
        override fun onProgress(start: Long, curr: Long, end: Long) = bounded(progressQueued) {
            val progress = AirPlayMetadata.progress(start, curr, end)
            update(state.copy(progress = progress))
        }
        override fun onDacpId(dacpId: String, activeRemote: String) =
            event { remoteIdentity(dacpId, activeRemote) }
    }

    companion object {
        private const val TAG = "DormPanelAirPlay"
        private val SUPPORTED_CODECS = setOf(2, 4, 8)

        private fun numericAddress(value: String): InetAddress? {
            val address = value.substringBefore('%')
            if (':' in address && address.all { it in "0123456789abcdefABCDEF:." }) {
                return runCatching { InetAddress.getByName(address) }.getOrNull()
            }
            val octets = address.split('.')
            if (octets.size != 4) return null
            return InetAddress.getByAddress(octets.map {
                val number = it.toIntOrNull()?.takeIf { number -> number in 0..255 } ?: return null
                number.toByte()
            }.toByteArray())
        }
    }
}
