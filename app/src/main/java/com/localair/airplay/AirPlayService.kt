package com.localair.airplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.localair.airplay.nativebridge.AirPlayNative

class AirPlayService : Service() {

    private var multicastLock: WifiManager.MulticastLock? = null
    private lateinit var mdns: MdnsAdvertiser
    private val audio = AudioDecoder()
    val video = VideoDecoder { hasFrames ->
        val intent = Intent(ACTION_FRAMES_CHANGED).putExtra("hasFrames", hasFrames)
        sendBroadcast(intent)
    }
    lateinit var urlPlayer: UrlPlayer; private set
    private val handler = Handler(Looper.getMainLooper())
    private var port = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            if (it.hasExtra("surface_attached")) {
                // Activity is telling us about its surface — handled via attachSurface/detachSurface
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        urlPlayer = UrlPlayer(this)
        urlPlayer.setSurfaceChangedListener { hasFrames ->
            sendBroadcast(Intent(ACTION_FRAMES_CHANGED).putExtra("hasFrames", hasFrames))
        }
        startInForeground()
        acquireMulticastLock()
        startReceiver()
        scheduleHealthCheck()
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        if (::mdns.isInitialized) mdns.unregister()
        AirPlayNative.setVideoSink(null)
        AirPlayNative.setAudioSink(null)
        AirPlayNative.playbackListener = null
        AirPlayNative.stop()
        urlPlayer.release()
        video.release()
        audio.release()
        multicastLock?.release()
        super.onDestroy()
    }

    fun attachSurface(s: Surface) {
        video.attachSurface(s)
        urlPlayer.attachSurface(s)
    }
    fun detachSurface() {
        video.detachSurface()
        urlPlayer.detachSurface()
    }

    private fun startReceiver() {
        port = AirPlayNative.start()
        AirPlayNative.setVideoSink(video)
        AirPlayNative.setAudioSink(audio)
        AirPlayNative.playbackListener = urlPlayer
        AirPlayNative.connectionListener = {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
        }
        val name = DeviceIdentity.deviceName(this)
        mdns = MdnsAdvertiser(this)
        if (port > 0) {
            mdns.register(port, DeviceIdentity.macAddress(this), name)
            Log.i(TAG, "receiver up: $name on port $port (mac=${DeviceIdentity.macAddress(this)})")
        } else {
            Log.w(TAG, "native start returned 0 — skipping mDNS")
        }
    }

    private fun scheduleHealthCheck() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (port > 0 && !AirPlayNative.isRunning()) {
                    Log.w(TAG, "raop died — restarting receiver")
                    AirPlayNative.stop()
                    if (::mdns.isInitialized) mdns.unregister()
                    audio.release()
                    startReceiver()
                }
                handler.postDelayed(this, HEALTH_INTERVAL_MS)
            }
        }, HEALTH_INTERVAL_MS)
    }

    private fun acquireMulticastLock() {
        val wifi = getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock(TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "AirPlay", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val name = DeviceIdentity.deviceName(this)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("localair")
            .setContentText("Receiver active — $name")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
        startForeground(1, n)
    }

    companion object {
        const val ACTION_FRAMES_CHANGED = "com.localair.airplay.FRAMES_CHANGED"
        private const val TAG = "AirPlayService"
        private const val CHANNEL = "airplay"
        private const val HEALTH_INTERVAL_MS = 10_000L
        @Volatile var instance: AirPlayService? = null; private set
    }
}
