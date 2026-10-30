package com.localair.airplay.dial

import android.service.notification.NotificationListenerService

/**
 * Empty on purpose. Holding notification-listener access is what lets
 * MediaSessionManager hand us the YouTube app's MediaController, so pause,
 * seek and stop from the phone can be relayed after the hand-off. Android TV
 * has no settings screen for this; grant it once over adb (see README).
 */
class MediaAccessListener : NotificationListenerService()
