package com.localair.airplay

import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var waiting: TextView
    private var surfaceReady = false

    private val svc get() = AirPlayService.instance

    private val framesReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                AirPlayService.ACTION_FRAMES_CHANGED -> {
                    val has = intent.getBooleanExtra("hasFrames", false)
                    waiting.visibility = if (has) View.GONE else View.VISIBLE
                    // Back to full screen between sessions; URL playback shares this view.
                    if (!has) surfaceView.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                AirPlayService.ACTION_VIDEO_SIZE -> fitVideo(
                    intent.getIntExtra("width", 0), intent.getIntExtra("height", 0)
                )
            }
        }
    }

    /**
     * Sizes the SurfaceView to the video's aspect ratio, centred, so a portrait
     * phone or a mid-stream size change is letterboxed instead of stretched to
     * the full screen.
     */
    private fun fitVideo(w: Int, h: Int) {
        val root = surfaceView.parent as? View ?: return
        val rw = root.width; val rh = root.height
        if (w <= 0 || h <= 0 || rw <= 0 || rh <= 0) return
        val scale = minOf(rw.toFloat() / w, rh.toFloat() / h)
        surfaceView.layoutParams = FrameLayout.LayoutParams(
            (w * scale).toInt(), (h * scale).toInt(), Gravity.CENTER
        )
        Log.i(TAG, "video ${w}x$h -> view ${(w * scale).toInt()}x${(h * scale).toInt()}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        val root = FrameLayout(this)
        surfaceView = SurfaceView(this).apply {
            holder.addCallback(this@MainActivity)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setZOrderOnTop(true)
        }
        root.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        waiting = TextView(this).apply {
            text = "${DeviceIdentity.deviceName(this@MainActivity)}\nwaiting for AirPlay…"
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
        }
        root.addView(waiting, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.CENTER })
        setContentView(root)

        val svcIntent = Intent(this, AirPlayService::class.java)
        val dialIntent = Intent(this, com.localair.airplay.dial.DialCastService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent)
            startForegroundService(dialIntent)
        } else {
            startService(svcIntent)
            startService(dialIntent)
        }

        registerReceiver(framesReceiver, IntentFilter().apply {
            addAction(AirPlayService.ACTION_FRAMES_CHANGED)
            addAction(AirPlayService.ACTION_VIDEO_SIZE)
        })
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        svc?.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        svc?.detachSurface()
        waiting.visibility = View.VISIBLE
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(framesReceiver)
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        if (svc?.video?.hasFrames == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean) {
        super.onPictureInPictureModeChanged(inPip)
        waiting.visibility = if (inPip) View.GONE else
            if (svc?.video?.hasFrames == true) View.GONE else View.VISIBLE
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        /** True between onStart and onStop; AirPlayService won't re-launch a visible activity. */
        @Volatile var visible = false; private set
    }
}
