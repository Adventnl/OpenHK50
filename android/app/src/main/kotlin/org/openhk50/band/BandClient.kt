package org.openhk50.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.openhk50.band.NativeProtocol.BATTERY
import org.openhk50.band.NativeProtocol.HEART_HISTORY
import org.openhk50.band.NativeProtocol.HEART_LIVE
import org.openhk50.band.NativeProtocol.HEART_LOG
import org.openhk50.band.NativeProtocol.HEART_STOP
import org.openhk50.band.NativeProtocol.STEPS
import org.openhk50.data.Metric
import org.openhk50.data.Reading
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

data class BandDevice(val name: String, val address: String)

enum class Connection { DISCONNECTED, CONNECTING, CONNECTED }
enum class BandAction { IDLE, IMPORTING, MEASURING, SETTING }

data class BandState(
    val device: BandDevice? = null,
    val connection: Connection = Connection.DISCONNECTED,
    val action: BandAction = BandAction.IDLE,
    val scanning: Boolean = false,
    val searched: Boolean = false,
    val nearby: List<BandDevice> = emptyList(),
    val battery: Int? = null,
    val charging: Boolean = false,
    val liveHeart: Int? = null,
    val progress: Int = 0,
    val days: Int = 0,
    val message: String? = null
)

@SuppressLint("UnspecifiedRegisterReceiverFlag")
internal class BandClient(context: Context, private val save: (List<Reading>) -> Unit) : GattLink.Events {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("band", Context.MODE_PRIVATE)
    private val link = GattLink(context, main, this)
    private val refresh = Runnable { importHistory(false) }
    private val reconnectJob = Runnable { reconnect() }
    private var foreground = false
    private var retryConnection = false
    private var closingAfterMeasurement = false
    private var reopenAfterMeasurement = false
    private var settingUp: BandDevice? = null
    private var exchange: Exchange? = null
    private var deadline: Runnable? = null
    private var liveCount = 0
    private var stopRequested = false
    private var historyReceived = false
    private var stepsReceived = false
    private var bandZone = ZoneId.of(prefs.getString("zone", null) ?: ZoneId.systemDefault().id)
    val zone: ZoneId get() = if (state.device == null) ZoneId.systemDefault() else bandZone

    var changed: (() -> Unit)? = null
    var state = BandState(device = prefs.getString("address", null)?.let {
        BandDevice(prefs.getString("name", null) ?: "HK50", it)
    })
        private set

    private class Exchange(val command: Int, val completion: (Exchange) -> Unit) {
        val frames = arrayListOf<ByteArray>()
        var written = false
        var rejected = false
        var terminal = false
    }

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        link.stopScan()
                        if (state.connection != Connection.DISCONNECTED) {
                            link.close()
                            lost("Turn on Bluetooth and try again.")
                        }
                    }
                    BluetoothAdapter.STATE_ON -> reconnect()
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
    }

    private fun publish(next: BandState) {
        state = next
        changed?.invoke()
    }

    fun foreground(active: Boolean) {
        if (foreground == active) return
        foreground = active
        if (active) {
            retryConnection = true
            if (closingAfterMeasurement) reopenAfterMeasurement = true else reconnect()
        } else {
            link.stopScan()
            disconnect()
        }
    }

    fun scan() {
        main.removeCallbacks(reconnectJob)
        state = state.copy(message = null, nearby = emptyList())
        link.startScan()
    }

    fun reconnect() {
        if (foreground && state.connection == Connection.DISCONNECTED) state.device?.let(::connect)
    }

    fun connect(device: BandDevice) {
        if (!foreground || state.action == BandAction.MEASURING) return
        main.removeCallbacks(reconnectJob)
        main.removeCallbacks(refresh)
        clearExchange()
        settingUp = device
        publish(state.copy(connection = Connection.CONNECTING, action = BandAction.IDLE, message = null,
            battery = null, liveHeart = null))
        link.connect(device.address)
    }

    private fun closeLink(message: String?) {
        clearExchange()
        main.removeCallbacks(refresh)
        main.removeCallbacks(reconnectJob)
        link.close()
        settingUp = null
        closingAfterMeasurement = false
        reopenAfterMeasurement = false
        publish(state.copy(connection = Connection.DISCONNECTED, action = BandAction.IDLE, battery = null,
            charging = false, liveHeart = null, message = message))
    }

    fun disconnect() {
        if (state.action == BandAction.MEASURING && state.connection == Connection.CONNECTED) {
            closingAfterMeasurement = true
            stopMeasurement()
        } else {
            closeLink(null)
        }
    }

    override fun scanning(active: Boolean, candidates: List<BandDevice>) =
        publish(state.copy(scanning = active, searched = !active && state.scanning, nearby = candidates))

    override fun scanFailed(message: String) = publish(state.copy(message = message, scanning = false))

    override fun connected() {
        val device = settingUp ?: return
        if (state.device?.address != device.address) bandZone = ZoneId.systemDefault()
        prefs.edit().putString("address", device.address).putString("name", device.name)
            .putString("zone", bandZone.id).apply()
        settingUp = null
        state = state.copy(device = device, connection = Connection.CONNECTED, message = null)
        importHistory(false)
    }

    override fun lost(message: String) {
        val retry = foreground && retryConnection && state.connection == Connection.CONNECTED
        closeLink(message)
        if (retry) {
            retryConnection = false
            main.postDelayed(reconnectJob, 8_000)
        }
    }

    fun importHistory(full: Boolean) {
        val device = availableDevice() ?: return
        main.removeCallbacks(refresh)
        val days = if (full) 7 else 1
        historyReceived = false
        stepsReceived = false
        publish(state.copy(action = BandAction.IMPORTING, progress = 0, days = days, message = null))
        val today = LocalDate.now(zone)
        request(NativeProtocol.packet(BATTERY), 3_000) { result ->
            result.frames.firstOrNull()?.let {
                publish(state.copy(battery = it[1].toInt() and 255, charging = it[2].toInt() != 0))
            }
            importDay(device, today, 0)
        }
    }

    private fun importDay(device: BandDevice, today: LocalDate, offset: Int) {
        if (offset >= state.days) {
            finish(when {
                historyReceived && stepsReceived -> null
                historyReceived -> "Step history didn’t reply. Try importing again."
                stepsReceived -> "Heart-rate history didn’t reply. Try importing again."
                else -> "No history reply. Try importing again."
            })
            return
        }
        publish(state.copy(progress = offset + 1))
        val day = today.minusDays(offset.toLong())
        request(NativeProtocol.heartRequest(day), 3_500) { heart ->
            if (!heart.rejected && heart.frames.isNotEmpty()) {
                historyReceived = true
                writeIntervals(device, Metric.HEART, day, NativeProtocol.intervals(heart.frames, null))
            }
            if (today != LocalDate.now(zone)) {
                finish("The day changed. Import again to update today.")
            } else {
                request(NativeProtocol.stepRequest(offset), 3_500) { steps ->
                    if (!steps.rejected && steps.frames.isNotEmpty()) {
                        stepsReceived = true
                        writeIntervals(device, Metric.STEPS, day, NativeProtocol.intervals(steps.frames, day))
                    }
                    importDay(device, today, offset + 1)
                }
            }
        }
    }

    private fun writeIntervals(device: BandDevice, metric: Metric, day: LocalDate, intervals: IntArray) {
        val now = System.currentTimeMillis()
        val readings = buildList {
            for (packed in intervals) {
                Reading.history(device.address, metric, day, packed ushr 16, zone, packed and 65535, now)?.let(::add)
            }
        }
        if (readings.isNotEmpty()) save(readings)
    }

    fun measure() {
        availableDevice() ?: return
        main.removeCallbacks(refresh)
        liveCount = 0
        stopRequested = false
        closingAfterMeasurement = false
        publish(state.copy(action = BandAction.MEASURING, liveHeart = null, message = null))
        request(NativeProtocol.packet(HEART_LIVE, 1, 1), 30_000) { result ->
            val message = when {
                result.rejected && liveCount == 0 -> "Band couldn’t measure. Adjust the strap and try again."
                liveCount == 0 -> "No reading. Wear the band snugly and hold still."
                else -> null
            }
            stopOnBand(message)
        }
    }

    fun stopMeasurement() {
        val current = exchange ?: return
        if (current.command != HEART_LIVE) return
        stopRequested = true
        if (current.written) {
            clearExchange()
            stopOnBand(null)
        }
    }

    private fun stopOnBand(message: String?) {
        request(NativeProtocol.packet(HEART_STOP, 1), 500) {
            if (!foreground || closingAfterMeasurement) {
                val reopen = reopenAfterMeasurement
                closeLink(null)
                if (reopen) reconnect()
            } else {
                finish(message)
            }
        }
    }

    fun setLogging(enabled: Boolean) = setting(
        NativeProtocol.packet(HEART_LOG, 2, if (enabled) 1 else 2, 5),
        "logging change", if (enabled) "Band accepted the logging request." else "Band accepted the pause request."
    )

    fun setClock() {
        val now = ZonedDateTime.now(zone)
        if (now.year !in 2000..2099) return
        val fields = intArrayOf(now.year % 100, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second)
        val time = fields.map { (it / 10 shl 4) or (it % 10) }.toIntArray() + 1
        setting(NativeProtocol.packet(1, *time), "clock change", "Band accepted the clock update.")
    }

    private fun setting(packet: ByteArray, label: String, success: String) {
        availableDevice() ?: return
        main.removeCallbacks(refresh)
        publish(state.copy(action = BandAction.SETTING, message = null))
        request(packet, 3_000) {
            finish(if (it.rejected || it.frames.isEmpty()) "Band didn’t confirm the $label." else success)
        }
    }

    private fun availableDevice() =
        state.device?.takeIf { foreground && state.connection == Connection.CONNECTED && state.action == BandAction.IDLE }

    private fun finish(message: String?) {
        if (foreground) {
            main.removeCallbacks(refresh)
            main.postDelayed(refresh, 5 * 60_000L)
        }
        publish(state.copy(action = BandAction.IDLE, message = message))
    }

    private fun request(packet: ByteArray, wait: Long, done: (Exchange) -> Unit) {
        val current = Exchange(packet[0].toInt() and 255, done)
        exchange = current
        link.write(packet) { ok ->
            if (exchange != current) return@write
            if (!ok) {
                lost("Couldn’t send to the band. Reconnect and try again.")
                return@write
            }
            current.written = true
            if (current.command == HEART_LIVE && stopRequested) stopMeasurement()
            else if (current.terminal) complete(current)
            else deadline = Runnable { complete(current) }.also { main.postDelayed(it, wait) }
        }
    }

    override fun frame(bytes: ByteArray) {
        val current = exchange ?: return
        if (current.terminal) return
        val header = NativeProtocol.header(bytes)
        if (header == 0) return
        val command = header ushr 24
        val sub = header ushr 16 and 255
        when (command) {
            current.command or 128 -> {
                current.rejected = true
                current.terminal = true
            }
            current.command -> when (command) {
                HEART_LIVE -> {
                    if (sub != 1 || stopRequested) return
                    val error = header ushr 8 and 255
                    val bpm = header and 255
                    if (error != 0) {
                        current.rejected = true
                        current.terminal = true
                    } else if (bpm in 1..254) {
                        val device = state.device ?: return
                        liveCount++
                        save(listOf(Reading.live(device.address, bpm, Instant.now())))
                        publish(state.copy(liveHeart = bpm))
                        current.terminal = liveCount >= 6
                    }
                }
                else -> {
                    if (command == BATTERY && sub > 100) return
                    if (current.frames.size < 120) current.frames.add(bytes)
                    current.terminal = when (command) {
                        HEART_HISTORY -> sub == 23 || sub == 255
                        STEPS -> false
                        else -> true
                    }
                }
            }
        }
        if (current.terminal && current.written) complete(current)
    }

    private fun complete(current: Exchange) {
        if (exchange == current) {
            clearExchange()
            current.completion(current)
        }
    }

    private fun clearExchange() {
        deadline?.let(main::removeCallbacks)
        deadline = null
        exchange = null
    }

    companion object {
        fun permissions() = GattLink.permissions()
        fun hasPermissions(context: Context) = GattLink.hasPermissions(context)
    }
}
