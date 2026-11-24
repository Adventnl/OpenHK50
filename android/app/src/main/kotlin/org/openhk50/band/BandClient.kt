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
import org.openhk50.band.NativeProtocol.HEART_LOG
import org.openhk50.band.NativeProtocol.HRV_HISTORY
import org.openhk50.band.NativeProtocol.LIVE
import org.openhk50.band.NativeProtocol.LIVE_STOP
import org.openhk50.band.NativeProtocol.STEPS
import org.openhk50.band.NativeProtocol.STRESS_HISTORY
import org.openhk50.data.Metric
import org.openhk50.data.Reading
import org.openhk50.data.SleepNight
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

data class BandDevice(val name: String, val address: String)

enum class Connection { DISCONNECTED, CONNECTING, CONNECTED }
enum class BandAction { IDLE, IMPORTING, MEASURING, SETTING, CAMERA }

data class BandState(
    val device: BandDevice? = null,
    val connecting: BandDevice? = null,
    val connection: Connection = Connection.DISCONNECTED,
    val action: BandAction = BandAction.IDLE,
    val scanning: Boolean = false,
    val searched: Boolean = false,
    val nearby: List<BandDevice> = emptyList(),
    val battery: Int? = null,
    val charging: Boolean = false,
    val measurement: Metric? = null,
    val liveValue: Int? = null,
    val measurementStopping: Boolean = false,
    val measurementMessage: String? = null,
    val automaticHeartLogging: Boolean? = null,
    val lastSync: Long? = null,
    val detailSyncAvailable: Boolean = false,
    val detailSyncEnabled: Boolean = false,
    val detailUsernameSet: Boolean = false,
    val progress: Int = 0,
    val days: Int = 0,
    val cameraActive: Boolean = false,
    val cameraIssue: String? = null,
    val message: String? = null
)

@SuppressLint("UnspecifiedRegisterReceiverFlag")
internal class BandClient(
    private val context: Context,
    private val save: (List<Reading>) -> Unit,
    private val saveSleep: (String, List<SleepNight>) -> Unit
) : GattLink.Events {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("band", Context.MODE_PRIVATE)
    private val link = GattLink(context, main, this)
    private val refresh = Runnable { importHistory(false) }
    private val reconnectJob = Runnable { autoConnect() }
    private var foreground = false
    private var keepConnected = false
    private val connectionNeeded get() = foreground || keepConnected
    private var retryConnection = false
    private var autoReconnect = prefs.getBoolean("autoReconnect", true)
    var autoSync = prefs.getBoolean("autoSync", true)
        private set
    private var closingAfterMeasurement = false
    private var reopenAfterMeasurement = false
    private var exchange: Exchange? = null
    private var detailTransfer: DetailTransfer? = null
    private var deadline: Runnable? = null
    private val liveSamples = ArrayList<Int>(6)
    private var stopRequested = false
    private var shutter: (() -> Unit)? = null
    private var cameraClosing = false
    private var closeAfterCamera = false
    private var callRinging = false
    private var callAlertSent = false
    private var historyReceived = false
    private var stepsReceived = false
    private var lastSyncedYesterday: LocalDate? = null
    private val unavailableHistory = mutableSetOf<Int>()
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
                    BluetoothAdapter.STATE_ON -> autoConnect()
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
        if (!active) link.stopScan()
        updateConnection()
    }

    fun keepConnected(active: Boolean) {
        if (keepConnected == active) return
        keepConnected = active
        updateConnection()
    }

    private fun updateConnection() {
        if (connectionNeeded) {
            retryConnection = true
            if (closingAfterMeasurement) reopenAfterMeasurement = true else autoConnect()
        } else {
            endConnection()
        }
    }

    fun scan() {
        if (!foreground || state.connection != Connection.DISCONNECTED) return
        main.removeCallbacks(reconnectJob)
        state = state.copy(message = null, nearby = emptyList())
        link.startScan()
    }

    fun stopScan() = link.stopScan()

    fun reconnect() = state.device?.let(::connect)

    private fun autoConnect() {
        if (autoReconnect && !state.scanning && hasPermissions(context)) reconnect()
    }

    fun connect(device: BandDevice) {
        if (!connectionNeeded || state.connection != Connection.DISCONNECTED) return
        main.removeCallbacks(reconnectJob)
        main.removeCallbacks(refresh)
        clearExchange()
        unavailableHistory.clear()
        publish(state.copy(connection = Connection.CONNECTING, connecting = device, action = BandAction.IDLE,
            battery = null, charging = false, message = null, measurement = null, liveValue = null, measurementMessage = null))
        link.connect(device.address)
    }

    private fun closeLink(message: String?) {
        clearExchange()
        detailTransfer?.cancel()
        detailTransfer = null
        main.removeCallbacks(refresh)
        main.removeCallbacks(reconnectJob)
        link.close()
        closingAfterMeasurement = false
        reopenAfterMeasurement = false
        cameraClosing = false
        closeAfterCamera = false
        publish(state.copy(connection = Connection.DISCONNECTED, connecting = null, action = BandAction.IDLE,
            battery = null, charging = false, liveValue = null, measurementStopping = false, detailSyncAvailable = false,
            cameraActive = false, cameraIssue = null, message = message))
    }

    fun disconnect() {
        autoReconnect = false
        prefs.edit().putBoolean("autoReconnect", false).apply()
        endConnection()
    }

    fun forget() {
        if (state.connection != Connection.DISCONNECTED) return
        val editor = prefs.edit().remove("address").remove("name").remove("zone").remove("autoReconnect")
        state.device?.let { editor.remove(detailKey(it)).remove(loggingKey(it)) }
        editor.apply()
        autoReconnect = true
        publish(state.copy(device = null, lastSync = null, measurement = null, measurementMessage = null,
            detailSyncEnabled = false, detailUsernameSet = false, message = null))
    }

    private fun endConnection() {
        if (state.action == BandAction.MEASURING) {
            closingAfterMeasurement = true
            stopMeasurement()
        } else if (state.action == BandAction.CAMERA) {
            shutter = null
            stopCamera(close = true)
        } else {
            closeLink(null)
        }
    }

    override fun scanning(active: Boolean, candidates: List<BandDevice>) =
        publish(state.copy(scanning = active, searched = !active && state.scanning, nearby = candidates))

    override fun scanFailed(message: String) = publish(state.copy(message = message, scanning = false))

    override fun connected(detailSyncAvailable: Boolean) {
        val device = state.connecting ?: return
        val sameBand = state.device?.address == device.address
        if (!sameBand) {
            bandZone = ZoneId.systemDefault()
            lastSyncedYesterday = null
        }
        autoReconnect = true
        retryConnection = true
        prefs.edit().putString("address", device.address).putString("name", device.name)
            .putString("zone", bandZone.id).putBoolean("autoReconnect", true).apply()
        val usernameSet = detailSyncAvailable && prefs.contains(detailKey(device))
        state = state.copy(device = device, connecting = null, connection = Connection.CONNECTED,
            lastSync = state.lastSync.takeIf { sameBand }, detailSyncAvailable = detailSyncAvailable,
            detailSyncEnabled = detailSyncAvailable && (usernameSet || device.canReadDetailsDirectly()),
            detailUsernameSet = usernameSet, message = null)
        sendCallAlert()
        publish(state.copy(action = BandAction.SETTING, automaticHeartLogging = null))
        val logging = prefs.getBoolean(loggingKey(device), false)
        request(NativeProtocol.packet(HEART_LOG, 1), 1_500) { result ->
            val current = result.frames.firstOrNull()?.get(2)?.toInt()
            if (current == if (logging) 1 else 2) {
                state = state.copy(automaticHeartLogging = logging)
                beginConnectedSync()
            } else {
                writeLogging(logging, ::beginConnectedSync)
            }
        }
    }

    private fun beginConnectedSync() {
        if (autoSync) {
            state = state.copy(action = BandAction.IDLE)
            importHistory(false)
        } else {
            readBattery { finish(null) }
        }
    }

    fun setAutoSync(enabled: Boolean) {
        if (autoSync == enabled) return
        autoSync = enabled
        prefs.edit().putBoolean("autoSync", enabled).apply()
        if (enabled) importHistory(false) else main.removeCallbacks(refresh)
    }

    override fun lost(message: String) {
        val retry = connectionNeeded && autoReconnect &&
            (keepConnected || (retryConnection && state.connection == Connection.CONNECTED))
        closeLink(message)
        if (retry) {
            retryConnection = false
            main.postDelayed(reconnectJob, if (keepConnected) 15_000 else 8_000)
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
        readBattery { importDay(device, today, 0) }
    }

    private fun readBattery(done: () -> Unit) {
        request(NativeProtocol.packet(BATTERY), 3_000) { result ->
            result.frames.firstOrNull()?.let {
                publish(state.copy(battery = it[1].toInt() and 255, charging = it[2].toInt() != 0))
            }
            done()
        }
    }

    private fun importDay(device: BandDevice, today: LocalDate, offset: Int) {
        if (offset >= state.days) {
            val yesterday = today.minusDays(1)
            if (state.days == 1 && lastSyncedYesterday != yesterday) {
                importActivity(device, today, 1) { received ->
                    if (received) lastSyncedYesterday = yesterday
                    finishImport(device, today)
                }
            } else finishImport(device, today)
            return
        }
        publish(state.copy(progress = offset + 1))
        val day = today.minusDays(offset.toLong())
        request(NativeProtocol.heartRequest(day), 3_500) { heart ->
            if (!heart.rejected && heart.frames.isNotEmpty()) {
                historyReceived = true
                writeIntervals(device, Metric.HEART, day, NativeProtocol.heartIntervals(heart.frames))
            }
            if (!sameDay(today)) return@request
            importActivity(device, today, offset) { received ->
                stepsReceived = stepsReceived || received
                if (offset == 1 && received) lastSyncedYesterday = day
                importSlots(device, today, offset, STRESS_HISTORY, Metric.STRESS) {
                    importSlots(device, today, offset, HRV_HISTORY, Metric.HRV) {
                        importDay(device, today, offset + 1)
                    }
                }
            }
        }
    }

    private fun finishImport(device: BandDevice, today: LocalDate) {
        if (historyReceived || stepsReceived) state = state.copy(lastSync = System.currentTimeMillis())
        val message = when {
            historyReceived && stepsReceived -> null
            historyReceived -> "Step history didn’t reply. Sync again."
            stepsReceived -> "Heart-rate history didn’t reply. Sync again."
            else -> "No history reply. Sync again."
        }
        val username = prefs.getString(detailKey(device), null)
        if (state.detailSyncEnabled) startDetails(device, username, today, state.days, message)
        else finish(message)
    }

    private fun importActivity(device: BandDevice, today: LocalDate, offset: Int, done: (Boolean) -> Unit) {
        val day = today.minusDays(offset.toLong())
        request(NativeProtocol.stepRequest(offset), 3_500) { steps ->
            if (!sameDay(today)) return@request
            if (steps.rejected || steps.frames.isEmpty()) {
                done(false)
                return@request
            }
            val intervals = NativeProtocol.activityIntervals(steps.frames, day)
            val observed = System.currentTimeMillis()
            val readings = buildList {
                for (slot in intervals) {
                    addInterval(device, Metric.STEPS, day, slot.minute, slot.steps, observed)
                    addInterval(device, Metric.CALORIES, day, slot.minute, slot.calories, observed)
                    addInterval(device, Metric.DISTANCE, day, slot.minute, slot.distance, observed)
                }
            }
            if (readings.isNotEmpty()) save(readings)
            done(intervals.isNotEmpty() || steps.frames.any { (it[1].toInt() and 255) == 255 })
        }
    }

    private fun detailKey(device: BandDevice) = "detailUser.${device.address}"
    private fun BandDevice.canReadDetailsDirectly() = name.startsWith("HK50", ignoreCase = true)

    fun setDetailUsername(input: String?): Boolean {
        val device = availableDevice()?.takeIf { state.detailSyncAvailable } ?: return false
        val username = input?.trim()?.substringBefore('@')
        if (username != null && (username.length !in 1..48 || username.any { it.isISOControl() || it.isWhitespace() })) {
            return false
        }
        prefs.edit().putString(detailKey(device), username).apply()
        val enabled = username != null || device.canReadDetailsDirectly()
        publish(state.copy(detailSyncEnabled = enabled, detailUsernameSet = username != null))
        if (enabled) importHistory(false)
        return true
    }

    private fun startDetails(device: BandDevice, username: String?, today: LocalDate, days: Int, earlierIssue: String?) {
        val transfer = DetailTransfer(main, link, today, days,
            currentDay = { LocalDate.now(zone) },
            sleep = { saveSleep(device.address, it) },
            oxygen = { day, intervals -> writeIntervals(device, Metric.OXYGEN, day, intervals) }
        ) { received, message ->
            detailTransfer = null
            if (received) state = state.copy(lastSync = System.currentTimeMillis())
            finish(listOfNotNull(earlierIssue, message).joinToString(" ").ifEmpty { null })
        }
        detailTransfer = transfer
        transfer.start(username)
    }

    override fun detailFrame(bytes: ByteArray) {
        detailTransfer?.frame(bytes)
    }

    private fun sameDay(today: LocalDate): Boolean {
        if (today == LocalDate.now(zone)) return true
        finish("The day changed. Sync again.")
        return false
    }

    private fun importSlots(device: BandDevice, today: LocalDate, offset: Int, command: Int,
                            metric: Metric, done: () -> Unit) {
        if (command in unavailableHistory) {
            done()
            return
        }
        request(NativeProtocol.packet(command, offset), 2_000) { result ->
            if (!sameDay(today)) return@request
            if (result.rejected || result.frames.isEmpty()) unavailableHistory += command
            else writeIntervals(device, metric, today.minusDays(offset.toLong()), NativeProtocol.slotIntervals(result.frames))
            done()
        }
    }

    private fun writeIntervals(device: BandDevice, metric: Metric, day: LocalDate, intervals: IntArray) {
        val now = System.currentTimeMillis()
        val readings = buildList {
            for (packed in intervals) {
                addInterval(device, metric, day, packed ushr 16, packed and 65535, now)
            }
        }
        if (readings.isNotEmpty()) save(readings)
    }

    private fun MutableList<Reading>.addInterval(
        device: BandDevice, metric: Metric, day: LocalDate, minute: Int, value: Int, observed: Long
    ) {
        if (value in metric.intervalValues) {
            Reading.history(device.address, metric, day, minute, zone, value, observed)?.let(::add)
        }
    }

    fun measure(metric: Metric) {
        availableDevice() ?: return
        val type = metric.liveType ?: return
        main.removeCallbacks(refresh)
        liveSamples.clear()
        stopRequested = false
        closingAfterMeasurement = false
        publish(state.copy(action = BandAction.MEASURING, measurement = metric, liveValue = null,
            measurementStopping = false, measurementMessage = null))
        request(NativeProtocol.packet(LIVE, type, 1), if (metric == Metric.HEART) 60_000 else 30_000) { result ->
            val message = when {
                result.rejected && liveSamples.isEmpty() -> "The band couldn’t measure. Check the fit and try again."
                liveSamples.isEmpty() -> "No reading. Wear the band snugly and hold still."
                else -> null
            }
            stopOnBand(metric, type, message)
        }
    }

    fun stopMeasurement() {
        val current = exchange ?: return
        if (current.command != LIVE) return
        val metric = state.measurement ?: return
        val type = metric.liveType ?: return
        stopRequested = true
        if (!state.measurementStopping) publish(state.copy(measurementStopping = true))
        if (current.written) {
            clearExchange()
            stopOnBand(metric, type, null)
        }
    }

    private fun stopOnBand(metric: Metric, type: Int, message: String?) {
        if (!state.measurementStopping) publish(state.copy(measurementStopping = true))
        val device = state.device
        val reading = if (device != null && liveSamples.isNotEmpty()) {
            liveSamples.sort()
            val median = (liveSamples[(liveSamples.size - 1) / 2] + liveSamples[liveSamples.size / 2]) / 2
            liveSamples.clear()
            Reading.live(device.address, metric, median, Instant.now()).also { save(listOf(it)) }
        } else null
        request(NativeProtocol.packet(LIVE_STOP, type, 0, 0), 500) { result ->
            if (!connectionNeeded || closingAfterMeasurement) {
                val reopen = reopenAfterMeasurement
                closeLink(null)
                if (reopen) autoConnect()
            } else {
                state = state.copy(liveValue = reading?.value, measurementStopping = false,
                    measurementMessage = if (result.rejected) "The band rejected Stop. Reconnect and try again." else message)
                finish(state.message)
            }
        }
    }

    private fun loggingKey(device: BandDevice) = "heartLogging.${device.address}"

    fun setLogging(enabled: Boolean) {
        val device = availableDevice() ?: return
        prefs.edit().putBoolean(loggingKey(device), enabled).apply()
        if (state.automaticHeartLogging == enabled) return
        main.removeCallbacks(refresh)
        publish(state.copy(action = BandAction.SETTING, message = null))
        writeLogging(enabled) {
            finish(if (state.automaticHeartLogging == null) "Couldn’t confirm heart-rate logging. Try again." else null)
        }
    }

    private fun writeLogging(enabled: Boolean, done: () -> Unit) {
        request(NativeProtocol.packet(HEART_LOG, 2, if (enabled) 1 else 2, 5), 1_500) { result ->
            state = state.copy(automaticHeartLogging = enabled.takeIf { !result.rejected && result.frames.isNotEmpty() })
            done()
        }
    }

    fun setClock() {
        val now = ZonedDateTime.now(zone)
        if (now.year !in 2000..2099) return
        val fields = intArrayOf(now.year % 100, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second)
        val time = fields.map { (it / 10 shl 4) or (it % 10) }.toIntArray() + 1
        setting(NativeProtocol.packet(1, *time), "clock change", "Band accepted the clock update.")
    }

    fun findBand() {
        availableDevice() ?: return
        main.removeCallbacks(refresh)
        publish(state.copy(action = BandAction.SETTING, message = null))
        link.write(NativeProtocol.packet(NativeProtocol.FIND_DEVICE, 0x55, 0xaa), withResponse = true) { sent ->
            finish(if (sent) "Find signal sent." else "Couldn’t find the band. Reconnect and try again.")
        }
    }

    fun relayIncomingCall(ringing: Boolean) {
        callRinging = ringing
        if (!ringing) callAlertSent = false
        else sendCallAlert()
    }

    private fun sendCallAlert() {
        if (!callRinging || callAlertSent || state.connection != Connection.CONNECTED) return
        callAlertSent = true
        link.write(callPacket(), withResponse = true) {}
    }

    fun testCallAlert() = setting(callPacket(), "call alert", "Call alert sent.")

    private fun callPacket() = NativeProtocol.packet(NativeProtocol.PHONE_ALERT, 4, 1, 1,
        *"Phone call".map { it.code }.toIntArray())

    fun remoteCamera(onShutter: (() -> Unit)?) {
        shutter = onShutter
        if (onShutter == null) {
            if (state.action == BandAction.CAMERA) stopCamera()
        } else {
            state = state.copy(cameraIssue = null)
            startCamera()
        }
    }

    private fun startCamera() {
        if (shutter == null || state.cameraIssue != null || availableDevice() == null) return
        main.removeCallbacks(refresh)
        publish(state.copy(action = BandAction.CAMERA, cameraIssue = null))
        request(NativeProtocol.packet(NativeProtocol.CAMERA, 4), 2_000) { result ->
            if (result.rejected || result.frames.isEmpty()) {
                state = state.copy(cameraIssue = "The band didn’t confirm the shutter. Try again.")
                stopCamera()
            } else {
                publish(state.copy(cameraActive = true))
                if (shutter == null) stopCamera()
            }
        }
    }

    private fun stopCamera(close: Boolean = false) {
        closeAfterCamera = closeAfterCamera || close
        if (exchange?.command == NativeProtocol.CAMERA || cameraClosing) return
        cameraClosing = true
        state = state.copy(cameraActive = false)
        request(NativeProtocol.packet(NativeProtocol.CAMERA, 6), 1_000) {
            cameraClosing = false
            if (closeAfterCamera && (!connectionNeeded || !autoReconnect)) closeLink(null)
            else finish(state.message)
            closeAfterCamera = false
        }
    }

    private fun setting(packet: ByteArray, label: String, success: String) {
        availableDevice() ?: return
        main.removeCallbacks(refresh)
        publish(state.copy(action = BandAction.SETTING, message = null))
        request(packet, 3_000) {
            val response = it.frames.firstOrNull()
            finish(if (it.rejected || response == null ||
                (it.command == NativeProtocol.PHONE_ALERT && response[1].toInt() != 0)) {
                "Band didn’t confirm the $label."
            } else success)
        }
    }

    private fun availableDevice() =
        state.device?.takeIf { connectionNeeded && state.connection == Connection.CONNECTED && state.action == BandAction.IDLE }

    private fun finish(message: String?) {
        main.removeCallbacks(refresh)
        if (connectionNeeded && autoSync) {
            main.postDelayed(refresh, 5 * 60_000L)
        }
        publish(state.copy(action = BandAction.IDLE, message = message))
        startCamera()
    }

    private fun request(packet: ByteArray, wait: Long, done: (Exchange) -> Unit) {
        val current = Exchange(packet[0].toInt() and 255, done)
        exchange = current
        link.write(packet, withResponse = current.command == NativeProtocol.CAMERA ||
            current.command == NativeProtocol.PHONE_ALERT) { ok ->
            if (exchange != current) return@write
            if (!ok) {
                lost("Couldn’t send to the band. Reconnect and try again.")
                return@write
            }
            current.written = true
            if (current.command == LIVE && stopRequested) stopMeasurement()
            else if (current.terminal) complete(current)
            else deadline = Runnable { complete(current) }.also { main.postDelayed(it, wait) }
        }
    }

    override fun frame(bytes: ByteArray) {
        val header = NativeProtocol.header(bytes)
        if (header == 0) return
        val command = header ushr 24
        val sub = header ushr 16 and 255
        if (command == NativeProtocol.CAMERA && sub == 2) {
            if (state.cameraActive) shutter?.invoke()
            return
        }
        val current = exchange ?: return
        if (current.terminal) return
        when (command) {
            current.command or 128 -> {
                current.rejected = true
                current.terminal = true
            }
            current.command -> when (command) {
                LIVE -> {
                    val metric = state.measurement ?: return
                    if (sub != metric.liveType || stopRequested) return
                    val error = header ushr 8 and 255
                    val value = header and 255
                    if (error != 0) {
                        current.rejected = true
                        current.terminal = true
                    } else if (value in metric.intervalValues) {
                        liveSamples += value
                        publish(state.copy(liveValue = value))
                        current.terminal = metric != Metric.HEART && liveSamples.size >= 6
                    }
                }
                else -> {
                    if (command == BATTERY && sub > 100) return
                    if (command == NativeProtocol.CAMERA && sub != 0) return
                    if (current.frames.size < 120) current.frames.add(bytes)
                    current.terminal = when (command) {
                        HEART_HISTORY -> sub == 23 || sub == 255
                        STEPS -> sub == 255
                        STRESS_HISTORY, HRV_HISTORY -> sub == 255 || current.frames.firstOrNull { it[1].toInt() == 0 }
                            ?.let { (it[2].toInt() and 255) <= sub + 1 } == true
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
