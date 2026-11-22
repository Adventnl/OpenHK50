package org.openhk50.band

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.util.Locale
import java.util.UUID

@SuppressLint("MissingPermission")
internal class GattLink(private val context: Context, private val main: Handler, private val events: Events) {
    interface Events {
        fun scanning(active: Boolean, candidates: List<BandDevice>)
        fun scanFailed(message: String)
        fun connected(detailSyncAvailable: Boolean)
        fun lost(message: String)
        fun frame(bytes: ByteArray)
        fun detailFrame(bytes: ByteArray)
    }

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private data class Candidate(val band: BandDevice, val device: BluetoothDevice, val modelName: Boolean)

    private val candidates = linkedMapOf<String, Candidate>()
    private var scan: ScanCallback? = null
    private val scanDeadline = Runnable { stopScan() }
    private var session: BandGatt? = null

    private fun unavailable(discovery: Boolean): String? = when {
        adapter == null -> "Bluetooth LE is unavailable on this phone."
        !hasPermissions(context) -> "Allow Bluetooth access to connect."
        !adapter.isEnabled -> "Turn on Bluetooth and try again."
        discovery && Build.VERSION.SDK_INT < 31
            && !locationEnabled() ->
            "Turn on location to scan on this Android version."
        else -> null
    }

    private fun locationEnabled(): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled else
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun startScan() {
        stopScan()
        candidates.clear()
        unavailable(true)?.let { events.scanFailed(it); return }
        val scanner = adapter?.bluetoothLeScanner ?: run {
            events.scanFailed("Bluetooth scanner unavailable. Try again.")
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                main.post {
                    if (scan != this) return@post
                    val record = result.scanRecord
                    val name = record?.deviceName ?: runCatching { result.device.name }.getOrNull()
                    val modelName = name?.uppercase(Locale.ROOT)?.let {
                        it.startsWith("HK50") || it.startsWith("H59")
                    } == true
                    val advertisesService = record?.serviceUuids?.any { it.uuid == SERVICE } == true
                    if (!modelName && !advertisesService) return@post
                    val address = result.device.address
                    val previous = candidates[address]
                    val band = BandDevice(name ?: previous?.band?.name ?: "Nordic UART device", address)
                    if (previous != null || candidates.size < 20 || modelName) {
                        if (previous == null && candidates.size >= 20) {
                            val other = candidates.entries.lastOrNull { !it.value.modelName } ?: return@post
                            candidates.remove(other.key)
                        }
                        candidates[address] = Candidate(band, result.device, modelName || previous?.modelName == true)
                        if (previous?.band != band) publishScan()
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                main.post {
                    if (scan != this) return@post
                    stopScan()
                    events.scanFailed("Scan failed. Wait a moment and scan again.")
                }
            }
        }
        scan = callback
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            publishScan()
            main.postDelayed(scanDeadline, 12_000)
        } catch (_: IllegalStateException) {
            stopScan()
            events.scanFailed("Couldn’t start scanning. Check Bluetooth and try again.")
        } catch (_: SecurityException) {
            stopScan()
            events.scanFailed("Allow Bluetooth access to scan.")
        }
    }

    private fun publishScan() = events.scanning(scan != null, candidates.values
        .sortedWith(compareByDescending<Candidate> { it.modelName }.thenBy { it.band.name })
        .map { it.band })

    fun stopScan() {
        val callback = scan ?: return
        main.removeCallbacks(scanDeadline)
        scan = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        publishScan()
    }

    fun connect(address: String) {
        stopScan()
        close()
        unavailable(false)?.let { events.lost(it); return }
        try {
            val device = candidates[address]?.device ?: checkNotNull(adapter).getRemoteDevice(address)
            val next = BandGatt()
            session = next
            next.open(device)
        } catch (_: IllegalArgumentException) {
            fail("Invalid band address. Scan again.")
        } catch (_: SecurityException) {
            fail("Allow Bluetooth access to connect.")
        }
    }

    fun close() {
        val previous = session ?: return
        session = null
        previous.setConnectionObserver(null)
        val forceClose = Runnable { previous.close() }
        main.postDelayed(forceClose, 5_000)
        runCatching {
            previous.disconnect().timeout(4_000).then {
                main.removeCallbacks(forceClose)
                forceClose.run()
            }.enqueue()
        }.onFailure {
            main.removeCallbacks(forceClose)
            forceClose.run()
        }
    }

    private fun fail(message: String) {
        close()
        events.lost(message)
    }

    fun write(packet: ByteArray, completion: (Boolean) -> Unit) {
        val current = session
        if (current == null || !current.isReady) completion(false)
        else current.sendUart(packet, completion)
    }

    fun prepareDetails(completion: (Boolean) -> Unit) =
        session?.prepareDetails(completion) ?: completion(false)

    fun writeDetail(packet: ByteArray, completion: (Boolean) -> Unit) =
        session?.sendDetail(packet, completion) ?: completion(false)

    private inner class BandGatt : BleManager(context, main), ConnectionObserver {
        private var tx: BluetoothGattCharacteristic? = null
        private var rx: BluetoothGattCharacteristic? = null
        private var detailTx: BluetoothGattCharacteristic? = null
        private var detailRx: BluetoothGattCharacteristic? = null
        private var subscribed = false
        private var detailSubscribed = false
        private var connected = false

        fun open(device: BluetoothDevice) {
            setConnectionObserver(this)
            connect(device)
                .retry(2, 350)
                .timeout(20_000)
                .done {
                    if (session == this) {
                        if (subscribed) {
                            connected = true
                            events.connected(detailTx != null && detailRx != null)
                        } else {
                            fail("Couldn’t turn on band updates. Reconnect and try again.")
                        }
                    }
                }
                .fail { _, status ->
                    if (session == this) fail(when (status) {
                        FailCallback.REASON_DEVICE_NOT_SUPPORTED -> "The device doesn’t expose the HK50/H59 service."
                        FailCallback.REASON_TIMEOUT -> "Connection timed out. Keep the band nearby and try again."
                        FailCallback.REASON_BLUETOOTH_DISABLED -> "Turn on Bluetooth and try again."
                        else -> "Couldn’t connect. Disconnect the band from other apps and try again."
                    })
                }
                .enqueue()
        }

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val service = gatt.getService(SERVICE) ?: return false
            tx = service.getCharacteristic(WRITE)?.takeIf(::writable) ?: return false
            rx = service.getCharacteristic(NOTIFY)?.takeIf(::notifiable) ?: return false
            val details = gatt.services.firstOrNull {
                it.getCharacteristic(DETAIL_WRITE)?.let(::writable) == true &&
                    it.getCharacteristic(DETAIL_NOTIFY)?.let(::notifiable) == true
            }
            detailTx = details?.getCharacteristic(DETAIL_WRITE)
            detailRx = details?.getCharacteristic(DETAIL_NOTIFY)
            return true
        }

        override fun initialize() {
            val incoming = rx ?: return
            subscribe(incoming, events::frame) { subscribed = it }
        }

        fun prepareDetails(completion: (Boolean) -> Unit) {
            val incoming = detailRx
            if (!connected || incoming == null) {
                completion(false)
            } else if (detailSubscribed) {
                completion(true)
            } else {
                requestMtu(247).enqueue()
                subscribe(incoming, events::detailFrame) {
                    detailSubscribed = it
                    completion(it)
                }
            }
        }

        private fun subscribe(incoming: BluetoothGattCharacteristic, received: (ByteArray) -> Unit,
                              completion: (Boolean) -> Unit) {
            val notifications = incoming.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val callback = if (notifications) setNotificationCallback(incoming) else setIndicationCallback(incoming)
            callback.with { _, data -> if (session == this && connected) data.value?.let(received) }
            val request = if (notifications) enableNotifications(incoming) else enableIndications(incoming)
            request.timeout(5_000)
                .done { if (session == this) completion(true) }
                .fail { _, _ -> if (session == this) completion(false) }
                .invalid { if (session == this) completion(false) }
                .enqueue()
        }

        override fun onServicesInvalidated() {
            subscribed = false
            detailSubscribed = false
            tx = null
            rx = null
            detailTx = null
            detailRx = null
        }

        fun sendUart(packet: ByteArray, completion: (Boolean) -> Unit) = send(tx, packet, completion)

        fun sendDetail(packet: ByteArray, completion: (Boolean) -> Unit) {
            if (!detailSubscribed || packet.size > getMtu() - 3) completion(false)
            else send(detailTx, packet, completion)
        }

        private fun send(outgoing: BluetoothGattCharacteristic?, packet: ByteArray, completion: (Boolean) -> Unit) {
            if (outgoing == null) {
                completion(false)
                return
            }
            val type = if (outgoing.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            writeCharacteristic(outgoing, packet, type).timeout(4_000)
                .done { if (session == this) completion(true) }
                .fail { _, _ -> if (session == this) completion(false) }
                .invalid { if (session == this) completion(false) }
                .enqueue()
        }

        override fun onDeviceConnecting(device: BluetoothDevice) = Unit
        override fun onDeviceConnected(device: BluetoothDevice) = Unit
        override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) = Unit
        override fun onDeviceReady(device: BluetoothDevice) = Unit
        override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit
        override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
            if (session == this && connected) fail("Band disconnected. Keep it nearby and try again.")
        }
    }

    companion object {
        private val SERVICE = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")
        private val WRITE = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NOTIFY = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val DETAIL_WRITE = UUID.fromString("de5bf72a-d711-4e47-af26-65e3012a5dc7")
        private val DETAIL_NOTIFY = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")

        private fun writable(value: BluetoothGattCharacteristic): Boolean =
            value.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

        private fun notifiable(value: BluetoothGattCharacteristic): Boolean =
            value.getDescriptor(CCCD) != null && value.properties and
                (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

        fun permissions() = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        fun hasPermissions(context: Context) =
            permissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
