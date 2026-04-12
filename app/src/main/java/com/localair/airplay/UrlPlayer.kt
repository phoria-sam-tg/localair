package com.localair.airplay

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.localair.airplay.nativebridge.PlaybackListener

class UrlPlayer(private val context: Context) : PlaybackListener {

    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var surfaceChanged: ((Boolean) -> Unit)? = null

    fun setSurfaceChangedListener(listener: (Boolean) -> Unit) {
        surfaceChanged = listener
    }

    fun attachSurface(s: Surface) {
        surface = s
        player?.setVideoSurface(s)
    }

    fun detachSurface() {
        player?.clearVideoSurface()
        surface = null
    }

    fun release() {
        player?.release()
        player = null
        surface = null
    }

    override fun onPlayUrl(url: String, startPos: Double) {
        Log.i(TAG, "play URL: $url start=$startPos")
        val p = getOrCreatePlayer()
        p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        p.prepare()
        if (startPos > 0.0) {
            p.seekTo((startPos * p.duration).toLong().coerceAtLeast(0))
        }
        p.play()
        surfaceChanged?.invoke(true)
    }

    override fun onPlayStop() {
        Log.i(TAG, "stop")
        player?.stop()
        surfaceChanged?.invoke(false)
    }

    override fun onPlayRate(rate: Float) {
        Log.i(TAG, "rate=$rate")
        val p = player ?: return
        if (rate == 0f) p.pause() else { p.playbackParameters = p.playbackParameters.withSpeed(rate); p.play() }
    }

    override fun onPlayInfo(out: DoubleArray) {
        val p = player
        if (p == null || out.size < 3) return
        out[0] = p.duration.toDouble() / 1000.0
        out[1] = p.currentPosition.toDouble() / 1000.0
        out[2] = if (p.isPlaying) 1.0 else 0.0
    }

    private fun getOrCreatePlayer(): ExoPlayer {
        player?.release()
        val p = ExoPlayer.Builder(context).build()
        surface?.let { p.setVideoSurface(it) }
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    Log.i(TAG, "playback ended")
                    surfaceChanged?.invoke(false)
                }
            }
        })
        player = p
        return p
    }

    companion object { private const val TAG = "UrlPlayer" }
}
