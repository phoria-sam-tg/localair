package com.localair.airplay.dial

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.localair.airplay.DeviceIdentity
import java.util.UUID

/**
 * YouTube-via-DIAL, running alongside AirPlayService (separate protocol,
 * separate discovery mechanism — see SsdpResponder's doc comment). Owns the
 * SSDP responder, the DIAL HTTP server, and the Lounge client. A real
 * phone's launch POST carries a pairingCode that LoungeClient registers
 * against our screen_id; a sender that already knows our screen_id (e.g.
 * read off the status XML's additionalData, see DialHttpServer) can skip
 * that and connect straight away — LoungeClient.ensureScreen() runs at
 * startup either way so that path is always open, not just after a launch.
 *
 * Playback: hands the video id to the YouTube TV app already on the
 * device (com.google.android.youtube.tv) via ACTION_VIEW rather than
 * resolving a playable stream ourselves — that's a separate, much bigger
 * problem (real YouTube stream URLs are signed/ciphered). Shipping the
 * hand-off now per ticket #1019; a real in-app player (NewPipeExtractor)
 * is the later upgrade if this feels clunky. Pause, play, seek and stop
 * from the sender are relayed to that app's media session (YouTubeAppControl),
 * and that session's state is reported back so the sender's cast bar follows.
 */
class DialCastService : Service() {

    private var multicastLock: WifiManager.MulticastLock? = null
    private var ssdp: SsdpResponder? = null
    private var http: DialHttpServer? = null
    private var lounge: LoungeClient? = null
    private val appControl by lazy { YouTubeAppControl(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        acquireMulticastLock()
        startServers()
    }

    override fun onDestroy() {
        ssdp?.stop()
        http?.stop()
        lounge?.stop()
        appControl.unwatch()
        reporter.shutdownNow()
        multicastLock?.release()
        super.onDestroy()
    }

    private fun startServers() {
        val uuid = deviceUuid()
        // Deliberately not DeviceIdentity.deviceName() (shared with AirPlay,
        // and matches mediashell's dead "Living Room TV" too closely) —
        // this needs to read as obviously the working option in a cast
        // sheet that also lists the broken built-in receiver.
        val name = "Projector" + com.localair.airplay.DeviceIdentity.nameSuffix
        val descriptionPath = "/dd.xml"

        val client = LoungeClient(
            deviceName = name,
            onPlayVideo = { videoId, startSeconds -> playInYouTubeApp(videoId, startSeconds) },
            onControl = { control -> appControl.apply(control) },
            currentPlayback = { appControl.snapshot() },
        )
        lounge = client
        appControl.watch { playback -> reportToSenders(playback) }

        val server = DialHttpServer(
            uuid = uuid,
            friendlyName = name,
            descriptionPath = descriptionPath,
            screenId = { client.screenIdOrNull() },
            onLaunch = { pairingCode -> client.registerPairingCode(pairingCode) },
        )
        val httpPort = server.start()
        http = server

        val responder = SsdpResponder(uuid = uuid, httpPort = httpPort, descriptionPath = descriptionPath)
        responder.start()
        ssdp = responder

        client.startIfNeeded()

        Log.i(TAG, "DIAL up: $name, http=$httpPort, uuid=$uuid")
    }

    /**
     * Forwards YouTube's playback state to senders, off the main thread, and
     * only when it changed enough to matter: the state itself, or a position
     * that no longer matches what senders extrapolate from the last report.
     */
    private fun reportToSenders(p: Playback) {
        val last = lastReported
        val now = android.os.SystemClock.elapsedRealtime()
        if (last != null && last.first.state == p.state) {
            val expected = last.first.positionSeconds +
                if (p.state == Playback.State.PLAYING) (now - last.second) / 1000.0 else 0.0
            val periodic = now - last.second >= REPORT_EVERY_MS
            if (kotlin.math.abs(p.positionSeconds - expected) < 1.5 && !periodic) return
        }
        lastReported = p to now
        reporter.execute { lounge?.reportPlayback(p) }
    }

    private var lastReported: Pair<Playback, Long>? = null
    private val reporter = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun playInYouTubeApp(videoId: String, startSeconds: Double?) {
        val url = buildString {
            append("https://www.youtube.com/watch?v=$videoId")
            if (startSeconds != null && startSeconds > 0) append("&t=${startSeconds.toInt()}")
        }
        Log.i(TAG, "handing off to YouTube app: $url")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage("com.google.android.youtube.tv")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.e(TAG, "YouTube app hand-off failed", it) }
    }

    private fun deviceUuid(): String {
        val prefs = getSharedPreferences("localair", Context.MODE_PRIVATE)
        prefs.getString("dial_uuid", null)?.let { return it }
        val mac = DeviceIdentity.macAddress(this)
        val uuid = UUID.nameUUIDFromBytes(mac.toByteArray()).toString()
        prefs.edit().putString("dial_uuid", uuid).apply()
        return uuid
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
                NotificationChannel(CHANNEL, "YouTube cast", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("localair")
            .setContentText("Discoverable for YouTube casting")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
        startForeground(2, n)
    }

    companion object {
        private const val TAG = "DialCastService"
        private const val CHANNEL = "dial"
        private const val REPORT_EVERY_MS = 15_000L
    }
}
