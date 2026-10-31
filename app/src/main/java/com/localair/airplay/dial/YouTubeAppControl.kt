package com.localair.airplay.dial

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Relays Lounge transport commands to the YouTube TV app's media session.
 * Needs notification-listener access (MediaAccessListener); without it every
 * call logs once and does nothing, and casting still starts playback.
 */
class YouTubeAppControl(private val context: Context) {

    fun apply(control: LoungeControl) {
        val c = controller() ?: return
        val t = c.transportControls
        when (control) {
            LoungeControl.Play -> t.play()
            LoungeControl.Pause -> t.pause()
            LoungeControl.Stop -> t.stop()
            is LoungeControl.Seek -> t.seekTo((control.seconds * 1000).toLong())
        }
        Log.i(TAG, "relayed $control")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var watched: MediaController? = null
    private var onPlayback: ((Playback) -> Unit)? = null

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = emit()
        override fun onMetadataChanged(metadata: MediaMetadata?) = emit()
        override fun onSessionDestroyed() {
            watched = null
            onPlayback?.invoke(Playback.IDLE)
        }
    }

    /** While playing, re-report every few seconds so the sender's scrubber never drifts far. */
    private val tick = object : Runnable {
        override fun run() {
            if (snapshot()?.state == Playback.State.PLAYING) emit()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /**
     * Follows the YouTube app's media session and reports every state change
     * (and a periodic position while playing) to [onPlayback]. Picks the
     * session up when it appears, so it can be called before anything plays.
     * Needs the same notification-listener access as [apply].
     */
    fun watch(onPlayback: (Playback) -> Unit) {
        this.onPlayback = onPlayback
        val msm = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, MediaAccessListener::class.java)
        try {
            msm.addOnActiveSessionsChangedListener({ sessions -> attach(sessions) }, listener, handler)
            attach(msm.getActiveSessions(listener))
        } catch (e: SecurityException) {
            Log.w(TAG, "notification-listener access not granted — no now-playing for senders")
            return
        }
        handler.postDelayed(tick, TICK_MS)
    }

    fun unwatch() {
        handler.removeCallbacks(tick)
        watched?.unregisterCallback(callback)
        watched = null
        onPlayback = null
    }

    /** Current YouTube playback, position extrapolated to now; null if YouTube has no session. */
    fun snapshot(): Playback? {
        val c = watched ?: controller(quiet = true) ?: return null
        val ps = c.playbackState ?: return null
        val state = when (ps.state) {
            PlaybackState.STATE_PLAYING -> Playback.State.PLAYING
            PlaybackState.STATE_PAUSED -> Playback.State.PAUSED
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> Playback.State.BUFFERING
            PlaybackState.STATE_STOPPED -> Playback.State.ENDED
            else -> Playback.State.UNSTARTED
        }
        var posMs = ps.position.toDouble()
        if (state == Playback.State.PLAYING && ps.lastPositionUpdateTime > 0) {
            posMs += (SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed
        }
        val durMs = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        return Playback(state, maxOf(0.0, posMs / 1000.0), maxOf(0L, durMs) / 1000.0)
    }

    private fun attach(sessions: List<MediaController>?) {
        val yt = sessions?.firstOrNull { it.packageName == YOUTUBE_TV }
        if (yt?.sessionToken == watched?.sessionToken) return
        watched?.unregisterCallback(callback)
        watched = yt
        yt?.registerCallback(callback, handler)
        Log.i(TAG, if (yt != null) "watching YouTube session" else "YouTube session gone")
        emit()
    }

    private fun emit() {
        onPlayback?.invoke(snapshot() ?: Playback.IDLE)
    }

    private fun controller(quiet: Boolean = false): MediaController? {
        val msm = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, MediaAccessListener::class.java)
        return try {
            msm.getActiveSessions(listener).firstOrNull { it.packageName == YOUTUBE_TV }
                .also { if (it == null && !quiet) Log.w(TAG, "no active YouTube session to control") }
        } catch (e: SecurityException) {
            Log.w(TAG, "notification-listener access not granted — see README")
            null
        }
    }

    companion object {
        const val YOUTUBE_TV = "com.google.android.youtube.tv"
        private const val TICK_MS = 5_000L
        private const val TAG = "YouTubeAppControl"
    }
}
