package com.localair.airplay.nativebridge

interface VideoSink {
    fun onNalUnit(data: ByteArray, ptsUs: Long)
    fun onSessionEnd()
}

interface AudioSink {
    fun onAacFrame(data: ByteArray, ptsUs: Long)
}

interface PlaybackListener {
    fun onPlayUrl(url: String, startPos: Double)
    fun onPlayStop()
    fun onPlayRate(rate: Float)
    fun onPlayInfo(out: DoubleArray)
}

object AirPlayNative {
    init { System.loadLibrary("airplay_native") }

    fun start(): Int = nativeStart()
    fun stop() = nativeStop()
    fun isRunning(): Boolean = nativeIsRunning()
    fun setVideoSink(sink: VideoSink?) = nativeSetSink(sink)
    fun setAudioSink(sink: AudioSink?) = nativeSetAudioSink(sink)

    var connectionListener: (() -> Unit)? = null
    var playbackListener: PlaybackListener? = null

    @JvmStatic fun onConnectionInit() { connectionListener?.invoke() }

    @JvmStatic fun onPlayUrl(url: String, startPos: Double) {
        playbackListener?.onPlayUrl(url, startPos)
    }
    @JvmStatic fun onPlayStop() { playbackListener?.onPlayStop() }
    @JvmStatic fun onPlayRate(rate: Float) { playbackListener?.onPlayRate(rate) }
    @JvmStatic fun onPlayInfo(out: DoubleArray) { playbackListener?.onPlayInfo(out) }

    @JvmStatic private external fun nativeStart(): Int
    @JvmStatic private external fun nativeStop()
    @JvmStatic private external fun nativeIsRunning(): Boolean
    @JvmStatic private external fun nativeSetSink(sink: VideoSink?)
    @JvmStatic private external fun nativeSetAudioSink(sink: AudioSink?)
}
