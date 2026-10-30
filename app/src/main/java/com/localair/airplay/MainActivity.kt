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
            val has = intent.getBooleanExtra("hasFrames", false)
            waiting.visibility = if (has) View.GONE else View.VISIBLE
        }
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

        registerReceiver(framesReceiver, IntentFilter(AirPlayService.ACTION_FRAMES_CHANGED))
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
}
