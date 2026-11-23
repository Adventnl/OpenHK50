package org.openhk50.alerts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit

internal class PhoneVibration(private val context: Context) {
    private val prefs = context.getSharedPreferences("phone_vibration", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val vibrator by lazy {
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java).defaultVibrator
        else context.getSystemService(Vibrator::class.java)
    }
    private var ringing = false
    private val ringPulse = Runnable {
        if (ringing && callsEnabled) {
            vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 170, 350, 550), 0), call = true)
        }
    }

    val available get() = vibrator.hasVibrator()
    val apps get() = prefs.getStringSet("apps", emptySet()).orEmpty()
    val notificationAccess get() = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    val phoneAccess get() = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED
    val callsEnabled get() = prefs.getBoolean("calls", false) && phoneAccess

    fun setApp(packageName: String, enabled: Boolean) {
        prefs.edit { putStringSet("apps", if (enabled) apps + packageName else apps - packageName) }
    }

    fun setCalls(enabled: Boolean) {
        prefs.edit { putBoolean("calls", enabled) }
        if (!enabled) callState(false)
    }

    fun callState(isRinging: Boolean) {
        val repeat = isRinging && callsEnabled && available
        if (ringing == repeat) return
        ringing = repeat
        if (ringing) {
            // Telecom can start its ringtone haptics after publishing RINGING.
            main.postDelayed(ringPulse, 500)
        } else {
            main.removeCallbacks(ringPulse)
            vibrator.cancel()
        }
    }

    fun notification(packageName: String) {
        if (ringing || packageName !in apps || !available) return
        vibrate(VibrationEffect.createOneShot(240, VibrationEffect.DEFAULT_AMPLITUDE), call = false)
    }

    @Suppress("DEPRECATION") // Audio attributes carry the vibration usage through API 32.
    private fun vibrate(effect: VibrationEffect, call: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) {
            val usage = if (call) VibrationAttributes.USAGE_RINGTONE else VibrationAttributes.USAGE_NOTIFICATION
            vibrator.vibrate(effect, VibrationAttributes.Builder().setUsage(usage).build())
        } else {
            val usage = if (call) AudioAttributes.USAGE_NOTIFICATION_RINGTONE else AudioAttributes.USAGE_NOTIFICATION
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(usage).build())
        }
    }
}
