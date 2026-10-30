package com.localair.airplay

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import java.net.NetworkInterface

object DeviceIdentity {

    fun macAddress(context: Context): String {
        val mac = networkInterfaceMac("wlan0")
            ?: networkInterfaceMac("eth0")
            ?: wifiManagerMac(context)
            ?: "AA:BB:CC:DD:EE:FF"
        return if (BuildConfig.DEV_RECEIVER) devVariant(mac) else mac
    }

    /** Suffix for advertised names, so a debug build never passes for the real receiver. */
    val nameSuffix: String get() = if (BuildConfig.DEV_RECEIVER) " (dev)" else ""

    /**
     * Senders key AirPlay receivers by deviceid (the MAC), so a debug build
     * beside the release needs its own: set the locally-administered bit.
     */
    private fun devVariant(mac: String): String {
        val b = mac.split(":").map { it.toInt(16) }.toMutableList()
        b[0] = b[0] or 0x02
        return b.joinToString(":") { "%02X".format(it) }
    }

    fun macBytes(context: Context): ByteArray {
        return macAddress(context).split(":").map { it.toInt(16).toByte() }.toByteArray()
    }

    fun deviceName(context: Context): String {
        val prefs = context.getSharedPreferences("localair", Context.MODE_PRIVATE)
        return (prefs.getString("device_name", null) ?: Build.MODEL ?: "localair") + nameSuffix
    }

    private fun networkInterfaceMac(ifName: String): String? = runCatching {
        val ni = NetworkInterface.getByName(ifName) ?: return null
        val mac = ni.hardwareAddress ?: return null
        if (mac.size != 6 || mac.all { it == 0.toByte() }) return null
        mac.joinToString(":") { "%02X".format(it) }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun wifiManagerMac(context: Context): String? = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo ?: return null
        val mac = info.macAddress ?: return null
        if (mac == "02:00:00:00:00:00") return null
        mac.uppercase()
    }.getOrNull()
}
