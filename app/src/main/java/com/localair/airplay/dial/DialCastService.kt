package com.localair.airplay.dial

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.localair.airplay.DeviceIdentity
import java.util.UUID

/**
 * YouTube-via-DIAL, running alongside AirPlayService (separate protocol,
 * separate discovery mechanism — see SsdpResponder's doc comment). Owns the
 * SSDP responder and the DIAL HTTP server; a launch call hands us a pairing
 * code and nothing more happens with it yet.
 *
 * NOT wired up: the Lounge API client (youtube.com/api/lounge/ — exchanging
 * the pairing code for a screen token, then long-polling bc/bind for
 * setPlaylist/play/pause). That's the next piece, deliberately left for a
 * second look before it starts talking to YouTube's servers — see ticket
 * #1019. Playback itself can reuse the existing ExoPlayer-backed player
 * once that lands.
 */
class DialCastService : Service() {

    private var multicastLock: WifiManager.MulticastLock? = null
    private var ssdp: SsdpResponder? = null
    private var http: DialHttpServer? = null

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
        multicastLock?.release()
        super.onDestroy()
    }

    private fun startServers() {
        val uuid = deviceUuid()
        // Deliberately not DeviceIdentity.deviceName() (shared with AirPlay,
        // and matches mediashell's dead "Living Room TV" too closely) —
        // this needs to read as obviously the working option in a cast
        // sheet that also lists the broken built-in receiver.
        val name = "Projector"
        val descriptionPath = "/dd.xml"

        val server = DialHttpServer(
            uuid = uuid,
            friendlyName = name,
            descriptionPath = descriptionPath,
            onLaunch = { pairingCode -> Log.i(TAG, "launch: pairingCode=$pairingCode (Lounge API not wired up yet)") },
        )
        val httpPort = server.start()
        http = server

        val responder = SsdpResponder(uuid = uuid, httpPort = httpPort, descriptionPath = descriptionPath)
        responder.start()
        ssdp = responder

        Log.i(TAG, "DIAL up: $name, http=$httpPort, uuid=$uuid")
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
    }
}
