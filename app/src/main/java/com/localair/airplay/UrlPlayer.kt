package com.localair.airplay

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.localair.airplay.nativebridge.PlaybackListener

/**
 * AirPlay URL playback (Safari video during mirroring, `play_url` senders).
 *
 * The PlaybackListener callbacks arrive on RPiPlay's native httpd thread, but ExoPlayer must be built
 * and driven on one application thread. Every call is posted to the main looper. `/playback-info`
 * has to answer synchronously on the native thread, so it reads a snapshot the main thread refreshes.
 */
class UrlPlayer(private val context: Context) : PlaybackListener {

    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var surfaceChanged: ((Boolean) -> Unit)? = null

    // Snapshot for /playback-info (read on the native thread, written on main).
    @Volatile private var snapDuration = 0.0
    @Volatile private var snapPosition = 0.0
    @Volatile private var snapPlaying = false
    @Volatile private var snapActive = false

    private val refresh = object : Runnable {
        override fun run() {
            val p = player ?: return
            val d = p.duration
            snapDuration = if (d == C.TIME_UNSET) 0.0 else d / 1000.0
            snapPosition = p.currentPosition / 1000.0
            snapPlaying = p.isPlaying
            main.postDelayed(this, 250)
        }
    }

    fun setSurfaceChangedListener(listener: (Boolean) -> Unit) {
        surfaceChanged = listener
    }

    fun attachSurface(s: Surface) = main.post {
        surface = s
        player?.setVideoSurface(s)
    }

    fun detachSurface() = main.post {
        player?.clearVideoSurface()
        surface = null
    }

    fun release() = main.post {
        main.removeCallbacks(refresh)
        player?.release()
        player = null
        surface = null
        snapActive = false
    }

    override fun onPlayUrl(url: String, startPos: Double) {
        Log.i(TAG, "play URL: $url start=$startPos")
        main.post {
            val p = getOrCreatePlayer()
            p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            p.prepare()
            if (startPos > 0.0) pendingStartFraction = startPos   // applied once the duration is known
            p.play()
            snapActive = true
            main.removeCallbacks(refresh); main.post(refresh)
            surfaceChanged?.invoke(true)
        }
    }

    // AirPlay's Start-Position is a fraction of the duration, which is unknown until the player is ready.
    private var pendingStartFraction = 0.0

    override fun onPlayStop() {
        Log.i(TAG, "stop")
        main.post {
            main.removeCallbacks(refresh)
            player?.stop()
            snapActive = false; snapPlaying = false
            surfaceChanged?.invoke(false)
        }
    }

    override fun onPlayRate(rate: Float) {
        Log.i(TAG, "rate=$rate")
        main.post {
            val p = player ?: return@post
            if (rate == 0f) p.pause() else { p.playbackParameters = p.playbackParameters.withSpeed(rate); p.play() }
        }
    }

    override fun onPlayInfo(out: DoubleArray) {
        if (!snapActive || out.size < 3) return
        out[0] = snapDuration
        out[1] = snapPosition
        out[2] = if (snapPlaying) 1.0 else 0.0
    }

    private fun getOrCreatePlayer(): ExoPlayer {
        main.removeCallbacks(refresh)
        player?.release()
        val p = ExoPlayer.Builder(context).setLooper(Looper.getMainLooper()).build()
        surface?.let { p.setVideoSurface(it) }
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && pendingStartFraction > 0.0 && p.duration != C.TIME_UNSET) {
                    p.seekTo((pendingStartFraction * p.duration).toLong().coerceAtLeast(0))
                    pendingStartFraction = 0.0
                }
                if (state == Player.STATE_ENDED) {
                    Log.i(TAG, "playback ended")
                    snapPlaying = false
                    snapActive = false          // /playback-info goes empty, so the sender knows playback is over
                    surfaceChanged?.invoke(false)
                }
            }
        })
        player = p
        return p
    }

    companion object { private const val TAG = "UrlPlayer" }
}
