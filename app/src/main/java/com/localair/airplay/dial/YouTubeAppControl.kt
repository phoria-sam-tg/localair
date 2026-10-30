package com.localair.airplay.dial

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
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

    private fun controller(): MediaController? {
        val msm = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, MediaAccessListener::class.java)
        return try {
            msm.getActiveSessions(listener).firstOrNull { it.packageName == YOUTUBE_TV }
                .also { if (it == null) Log.w(TAG, "no active YouTube session to control") }
        } catch (e: SecurityException) {
            Log.w(TAG, "notification-listener access not granted — see README")
            null
        }
    }

    companion object {
        const val YOUTUBE_TV = "com.google.android.youtube.tv"
        private const val TAG = "YouTubeAppControl"
    }
}
