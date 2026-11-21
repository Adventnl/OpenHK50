package org.openhk50.band

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import java.util.Locale
import java.util.UUID

@SuppressLint("MissingPermission")
internal class GattLink(private val context: Context, private val main: Handler, private val events: Events) {
    interface Events {
        fun scanning(active: Boolean, candidates: List<BandDevice>)
        fun scanFailed(message: String)
        fun connected()
        fun lost(message: String)
        fun frame(bytes: ByteArray)
    }

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val candidates = linkedMapOf<String, BandDevice>()
    private var scan: ScanCallback? = null
    private val scanDeadline = Runnable { stopScan() }
    private val connectionDeadline = Runnable { fail("Connection timed out. Keep the band nearby and try again.") }
    private val writeDeadline = Runnable { fail("Band stopped responding. Reconnect and try again.") }
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var writing: ((Boolean) -> Unit)? = null
    private var ready = false

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
                    val record = result.scanRecord ?: return@post
                    val name = record.deviceName
                    if (record.serviceUuids?.any { it.uuid == SERVICE } != true
                        && name?.uppercase(Locale.ROOT)?.startsWith("HK50") != true) return@post
                    val address = result.device.address
                    if (candidates.size < 20 && address !in candidates) {
                        candidates[address] = BandDevice(name ?: "HK50", address)
                        publishScan()
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

    private fun publishScan() = events.scanning(scan != null, candidates.values.sortedBy { it.name })

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
            gatt = adapter?.getRemoteDevice(address)?.connectGatt(context, false, callback,
                BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, main)
            if (gatt == null) fail("Couldn’t connect. Try again.")
            else main.postDelayed(connectionDeadline, 14_000)
        } catch (_: IllegalArgumentException) {
            fail("Invalid band address. Scan for your band again.")
        } catch (_: SecurityException) {
            fail("Allow Bluetooth access to connect.")
        }
    }

    fun close() {
        main.removeCallbacks(connectionDeadline)
        main.removeCallbacks(writeDeadline)
        ready = false
        tx = null
        writing = null
        val previous = gatt
        gatt = null
        runCatching { previous?.close() }
    }

    private fun fail(message: String) {
        close()
        events.lost(message)
    }

    fun write(packet: ByteArray, completion: (Boolean) -> Unit) {
        val link = gatt
        val characteristic = tx
        if (!ready || writing != null || link == null || characteristic == null) {
            completion(false)
            return
        }
        val type = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        writing = completion
        if (runCatching { writeCharacteristic(link, characteristic, packet, type) }.getOrDefault(false)) {
            main.postDelayed(writeDeadline, 3_000)
        } else {
            finishWrite(false)
        }
    }

    private fun finishWrite(ok: Boolean) {
        main.removeCallbacks(writeDeadline)
        val completion = writing
        writing = null
        completion?.invoke(ok)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(link: BluetoothGatt, status: Int, state: Int) {
            if (link != gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                fail("Band disconnected. Keep it nearby and try again.")
            } else if (state == BluetoothProfile.STATE_CONNECTED
                && !runCatching { link.discoverServices() }.getOrDefault(false)) {
                fail("Couldn’t read band services. Reconnect and try again.")
            }
        }

        override fun onServicesDiscovered(link: BluetoothGatt, status: Int) {
            if (link != gatt) return
            val service = link.getService(SERVICE)
            val write = service?.getCharacteristic(WRITE)
            val notify = service?.getCharacteristic(NOTIFY)
            val descriptor = notify?.getDescriptor(CCCD)
            val writeFlags = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            val notifyFlags = BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE
            if (status != BluetoothGatt.GATT_SUCCESS || write == null || notify == null || descriptor == null
                || write.properties and writeFlags == 0 || notify.properties and notifyFlags == 0) {
                fail("This device doesn’t expose the HK50 service.")
                return
            }
            tx = write
            val enable = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            val subscribed = runCatching {
                link.setCharacteristicNotification(notify, true) && writeDescriptor(link, descriptor, enable)
            }.getOrDefault(false)
            if (!subscribed) fail("Couldn’t subscribe to band updates. Reconnect and try again.")
        }

        override fun onDescriptorWrite(link: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (link != gatt || descriptor.uuid != CCCD) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Band rejected Bluetooth notifications. Reconnect and try again.")
                return
            }
            main.removeCallbacks(connectionDeadline)
            ready = true
            events.connected()
        }

        override fun onCharacteristicWrite(link: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (link == gatt && characteristic.uuid == WRITE) finishWrite(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(link: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (link == gatt && ready && characteristic.uuid == NOTIFY) events.frame(value)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Required on Android 12 and earlier")
        override fun onCharacteristicChanged(link: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) characteristic.value?.clone()?.let {
                onCharacteristicChanged(link, characteristic, it)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(link: BluetoothGatt, target: BluetoothGattCharacteristic, bytes: ByteArray, type: Int): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            return link.writeCharacteristic(target, bytes, type) == BluetoothStatusCodes.SUCCESS
        }
        target.writeType = type
        return target.setValue(bytes) && link.writeCharacteristic(target)
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(link: BluetoothGatt, descriptor: BluetoothGattDescriptor, bytes: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) link.writeDescriptor(descriptor, bytes) == BluetoothStatusCodes.SUCCESS
        else descriptor.setValue(bytes) && link.writeDescriptor(descriptor)

    companion object {
        private val SERVICE = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")
        private val WRITE = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NOTIFY = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun permissions() = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        fun hasPermissions(context: Context) =
            permissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
