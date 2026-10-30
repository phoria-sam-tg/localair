package com.localair.airplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.localair.airplay.dial.DialCastService

/**
 * Starts both receivers at boot (and after an app update), so a phone can
 * cast without anyone opening localair first. AirPlayService already brings
 * MainActivity forward when a sender connects, so nothing else needs to be
 * on screen until then.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "${intent.action} — starting receivers")
                start(context, Intent(context, AirPlayService::class.java))
                start(context, Intent(context, DialCastService::class.java))
            }
        }
    }

    private fun start(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    companion object { private const val TAG = "BootReceiver" }
}
