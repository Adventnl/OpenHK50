package org.openhk50.band

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.openhk50.OpenHkApp
import org.openhk50.R
import org.openhk50.ui.MainActivity

internal class SpotCheckService : Service() {
    private val app get() = application as OpenHkApp
    private val band get() = app.band
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            band.stopMeasurement()
            return START_NOT_STICKY
        }
        try {
            val notice = notification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else startForeground(ID, notice)
        } catch (_: SecurityException) {
            band.measurementStartFailed()
            stopSelf()
            return START_NOT_STICKY
        }
        band.spotCheckChanged = {
            if (!band.spotCheckPending) app.main.post { if (!band.spotCheckPending) stopSelf() }
        }
        if (band.spotCheckPending) {
            if (wakeLock?.isHeld != true) wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenHK50:SpotCheck").apply {
                    setReferenceCounted(false)
                    acquire(120_000)
                }
            band.startQueuedMeasurement()
        } else stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        band.spotCheckChanged = null
        if (band.spotCheckPending) band.stopMeasurement()
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Band measurements", NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 0, Intent(this, SpotCheckService::class.java).setAction(STOP), flags)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Band spot check")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private companion object {
        const val ID = 51
        const val CHANNEL = "band_measurements"
        const val STOP = "org.openhk50.STOP_MEASUREMENT"
    }
}
