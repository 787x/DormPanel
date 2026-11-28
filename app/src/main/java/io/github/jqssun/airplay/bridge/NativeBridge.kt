package io.github.jqssun.airplay.bridge

// JNI entry points use the names in the pinned Android receiver.
internal object NativeBridge {
    init { System.loadLibrary("airplay_native") }

    external fun nativeInit(callback: RaopCallbackHandler, hwAddr: ByteArray, name: String,
                            keyFile: String, nohold: Boolean, requirePin: Boolean): Long
    external fun nativeStart(handle: Long, port: Int): Int
    external fun nativeStop(handle: Long)
    external fun nativeDestroy(handle: Long)
    external fun nativeSetH265Enabled(handle: Long, enabled: Boolean)
    external fun nativeSetHlsEnabled(handle: Long, enabled: Boolean)
    external fun nativeSetFeature(handle: Long, bit: Int, enabled: Boolean)
    external fun nativeSetCodecs(handle: Long, alac: Boolean, aac: Boolean)
    external fun nativeGetRaopTxtRecords(handle: Long): Map<String, String>?
    external fun nativeGetAirplayTxtRecords(handle: Long): Map<String, String>?
    external fun nativeGetRaopServiceName(handle: Long): String?
    external fun nativeSetDefaultStreamValues(sampleRate: Int, framesPerBurst: Int)
    external fun nativeServerAudioConfigure(handle: Long, cushionMs: Int, percentilePct: Int,
                                            oboeBufferFrames: Int, forceSwAlac: Boolean,
                                            forceSwAac: Boolean, realtimePriority: Boolean,
                                            lowLatency: Boolean, benchmarkLog: Boolean): Boolean
    external fun nativeServerAudioStart(handle: Long): Boolean
    external fun nativeServerAudioStop(handle: Long)
    external fun nativeServerAudioSetClientGain(handle: Long, gain: Float)
    external fun nativeServerAudioFormat(handle: Long, ct: Int, spf: Int)
}

@androidx.annotation.Keep
internal interface RaopCallbackHandler {
    fun onAudioPeer(peer: String)
    fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean)
    fun onAudioPacket()
    fun onAudioFlush()
    fun onAudioTeardown()
    fun onConnectionDestroy()
    fun onConnectionReset(reason: Int)
    fun onClientVolume(db: Float)
    fun onClientVolumeQuery(): Float
    fun onMetadata(data: ByteArray)
    fun onCoverArt(data: ByteArray)
    fun onProgress(start: Long, curr: Long, end: Long)
    fun onDacpId(dacpId: String, activeRemote: String)
    fun onConnectionInit() = Unit
    fun onDisplayPin(pin: String) = Unit
    fun onLog(message: String) = Unit
    fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) = Unit
    fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) = Unit
    fun onMirrorRunning(running: Boolean) = Unit
    fun onVideoPlay(location: String, startPositionSeconds: Float) = Unit
    fun onVideoScrub(positionSeconds: Float) = Unit
    fun onVideoRate(rate: Float) = Unit
    fun onVideoStop() = Unit
    fun onVideoSessionPoll() = Unit
}
