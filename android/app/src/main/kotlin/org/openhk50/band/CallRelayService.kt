package org.openhk50.band

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager
import org.openhk50.OpenHkApp
import org.openhk50.R
import org.openhk50.ui.MainActivity

internal class CallRelayService : Service() {
    private val app get() = application as OpenHkApp
    private var registered = false
    private var ringing = false

    private val calls = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            val next = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> true
                TelephonyManager.EXTRA_STATE_OFFHOOK, TelephonyManager.EXTRA_STATE_IDLE -> false
                else -> return
            }
            if (ringing != next) {
                ringing = next
                app.band.relayIncomingCall(ringing)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (registered) return START_STICKY
        if (!BandClient.hasPermissions(this) ||
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            app.callAlertsIssue = "Allow phone and Bluetooth access, then turn on call alerts."
            app.notifyChanged()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(ID, notification)
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(calls, filter, Context.RECEIVER_EXPORTED)
            else registerReceiver(calls, filter)
            registered = true
            app.callAlertsIssue = null
            app.callAlertsRunning = true
            app.band.keepConnected(true)
            app.notifyChanged()
            return START_STICKY
        } catch (_: SecurityException) {
            app.callAlertsIssue = "Couldn’t start call alerts. Check app permissions and try again."
            app.notifyChanged()
            stopSelf()
            return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        if (registered) {
            unregisterReceiver(calls)
            app.callAlertsRunning = false
            app.band.relayIncomingCall(false)
            app.band.keepConnected(false)
            app.notifyChanged()
        }
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Band call alerts", NotificationManager.IMPORTANCE_LOW)
        )
        val pendingFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), pendingFlags)
        val stop = PendingIntent.getService(this, 0, Intent(this, CallRelayService::class.java).setAction(ACTION_STOP), pendingFlags)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Band call alerts")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
    }

    companion object {
        private const val ID = 50
        private const val CHANNEL = "band_calls"
        private const val ACTION_STOP = "org.openhk50.STOP_CALL_ALERTS"
    }
}
