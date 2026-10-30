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
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.localair.airplay.nativebridge.AirPlayNative

class AirPlayService : Service() {

    private var multicastLock: WifiManager.MulticastLock? = null
    private lateinit var mdns: MdnsAdvertiser
    private val audio = AudioDecoder()
    val video = VideoDecoder(
        onFramesChanged = { hasFrames -> onFramesChanged(hasFrames) },
        onVideoSize = { w, h ->
            sendBroadcast(Intent(ACTION_VIDEO_SIZE).putExtra("width", w).putExtra("height", h))
        },
    )
    lateinit var urlPlayer: UrlPlayer; private set
    private val handler = Handler(Looper.getMainLooper())
    private var port = 0
    private var lastBroughtForward = 0L

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
        urlPlayer.setSurfaceChangedListener { hasFrames -> onFramesChanged(hasFrames) }
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
        // A connection alone doesn't take over the screen: senders that fail
        // the handshake retry every few seconds, and an audio-only stream has
        // nothing to show. The activity comes forward on the first video
        // (onFramesChanged).
        AirPlayNative.connectionListener = { Log.i(TAG, "sender connected") }
        val name = DeviceIdentity.deviceName(this)
        mdns = MdnsAdvertiser(this)
        if (port > 0) {
            mdns.register(port, DeviceIdentity.macAddress(this), name)
            Log.i(TAG, "receiver up: $name on port $port (mac=${DeviceIdentity.macAddress(this)})")
        } else {
            Log.w(TAG, "native start returned 0 — skipping mDNS")
        }
    }

    /** Video (mirroring or a URL) has started or stopped. Only a start brings the activity forward. */
    private fun onFramesChanged(hasFrames: Boolean) {
        sendBroadcast(Intent(ACTION_FRAMES_CHANGED).putExtra("hasFrames", hasFrames))
        if (hasFrames) bringActivityForward()
    }

    /**
     * Wakes the screen (ending the screensaver; an activity started behind a
     * dream loses its surface at once) and brings MainActivity forward. Skipped
     * while it's already visible, and at most once every few seconds, so a
     * stream that keeps resetting can't loop the TV.
     */
    private fun bringActivityForward() {
        if (MainActivity.visible) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBroughtForward < BRING_FORWARD_MIN_INTERVAL_MS) return
        lastBroughtForward = now
        val pm = getSystemService(PowerManager::class.java)
        @Suppress("DEPRECATION")
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "localair:cast"
        ).acquire(WAKE_HOLD_MS)
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        })
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
        const val ACTION_VIDEO_SIZE = "com.localair.airplay.VIDEO_SIZE"
        private const val TAG = "AirPlayService"
        private const val CHANNEL = "airplay"
        private const val HEALTH_INTERVAL_MS = 10_000L
        private const val BRING_FORWARD_MIN_INTERVAL_MS = 5_000L
        private const val WAKE_HOLD_MS = 10_000L
        @Volatile var instance: AirPlayService? = null; private set
    }
}
