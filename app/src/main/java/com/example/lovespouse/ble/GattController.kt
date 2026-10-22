package com.example.lovespouse.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * Connected-GATT control, mirroring BleController + BleCommandUtil.k() + CryptBleUtils.
 *
 * Plain devices:  write {level,mode} to aaa0/aaa1, battery on 180f/2a19.
 * Crypt devices:  subscribe f6c0/f6c1, write plaintext handshake [02 DB] to f6c2, then
 *                 send encrypted commands (needs a per-version `seed` + the device MAC).
 */
class GattController(context: Context) {

    data class Found(val device: BluetoothDevice, val name: String, val rssi: Int, val scanRecord: ByteArray?)

    interface Listener {
        fun onDevicesChanged(devices: List<Found>) {}
        fun onConnectionChanged(connected: Boolean) {}
        fun onBattery(percent: Int) {}
        fun onCryptInfo(version: String, mac: String) {}
        fun onLog(line: String) {}
    }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val main = Handler(Looper.getMainLooper())
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    var listener: Listener? = null

    /** Set before connecting to a crypt device. */
    var cryptMode: Boolean = false
    var seed: ByteArray? = null

    private val found = LinkedHashMap<String, Found>()
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null   // plain aaa1 OR crypt f6c2
    private var deviceMac: String = ""
    private var cryptReady = false
    private var awaitingHandshake = false

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    private fun log(s: String) { Log.d(TAG, s); main.post { listener?.onLog(s) } }

    private fun hasScan() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    private fun hasConnect() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    // ── Scanning ───────────────────────────────────────────────────────────────
    private val scanCb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rawName = result.device.name ?: result.scanRecord?.deviceName
            val svcs = result.scanRecord?.serviceUuids?.joinToString() ?: "-"
            log("seen: name=$rawName addr=${result.device.address} rssi=${result.rssi} svcs=$svcs")
            val name = rawName ?: return
            if (Protocol.NAME_PREFIXES.none { name.startsWith(it) }) return
            found[result.device.address] = Found(result.device, name, result.rssi, result.scanRecord?.bytes)
            main.post { listener?.onDevicesChanged(found.values.toList()) }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { log("No BLE scanner"); return }
        if (!hasScan()) { log("Missing BLUETOOTH_SCAN permission"); return }
        found.clear(); listener?.onDevicesChanged(emptyList())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCb)
        log("Scanning...")
        main.postDelayed({ stopScan() }, 8000)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!hasScan()) return
        adapter?.bluetoothLeScanner?.stopScan(scanCb)
    }

    // ── Connect ──────────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    fun connect(found: Found) {
        if (!hasConnect()) { log("Missing BLUETOOTH_CONNECT permission"); return }
        stopScan()
        cryptReady = false
        deviceMac = found.device.address
        found.scanRecord?.let { rec ->
            LoveSpouseCrypto.parseAdv(rec)?.let { (version, mac) ->
                log("adv version=$version mac=$mac")
                main.post { listener?.onCryptInfo(version, mac) }
            }
        }
        log("Connecting to ${found.device.address} (crypt=$cryptMode)")
        gatt = found.device.connectGatt(appContext, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        if (hasConnect()) { gatt?.disconnect(); gatt?.close() }
        gatt = null; writeChar = null; cryptReady = false; awaitingHandshake = false
        listener?.onConnectionChanged(false)
    }

    private val gattCb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected, discovering services")
                if (hasConnect()) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected")
                writeChar = null; cryptReady = false; awaitingHandshake = false
                main.post { listener?.onConnectionChanged(false) }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (cryptMode) {
                val svc = g.getService(Protocol.SERVICE_CRYPT)
                writeChar = svc?.getCharacteristic(Protocol.CHAR_CRYPT_WRITE)
                val notifyChar = svc?.getCharacteristic(Protocol.CHAR_CRYPT_NOTIFY)
                if (writeChar == null || notifyChar == null) { log("Crypt chars f6c1/f6c2 not found"); return }
                enableNotify(g, notifyChar)
                // handshake write happens after notify is enabled (onDescriptorWrite)
                log("Crypt channel found, enabling notify")
            } else {
                val svc = g.getService(Protocol.SERVICE_CONTROL)
                writeChar = svc?.getCharacteristic(Protocol.CHAR_WRITE)
                if (writeChar == null) { log("Control characteristic aaa1 not found"); return }
                log("Ready (aaa0/aaa1 bound)")
                main.post { listener?.onConnectionChanged(true) }
                g.getService(Protocol.SERVICE_BATTERY)?.getCharacteristic(Protocol.CHAR_BATTERY)
                    ?.let { enableNotify(g, it) }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // notify enabled; for crypt, fire the plaintext handshake now
            if (cryptMode && descriptor.characteristic.uuid == Protocol.CHAR_CRYPT_NOTIFY) {
                awaitingHandshake = true
                log("Writing handshake 02 DB")
                writeBytes(LoveSpouseCrypto.HANDSHAKE)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (awaitingHandshake && ch.uuid == Protocol.CHAR_CRYPT_WRITE) {
                awaitingHandshake = false
                cryptReady = true
                log("Crypt handshake OK — channel ready")
                main.post { listener?.onConnectionChanged(true) }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) =
            handleNotify(ch.uuid, ch.value)

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) =
            handleNotify(ch.uuid, value)
    }

    private fun handleNotify(uuid: UUID, value: ByteArray?) {
        when (uuid) {
            Protocol.CHAR_BATTERY ->
                value?.firstOrNull()?.toInt()?.and(0xFF)?.let { pct -> main.post { listener?.onBattery(pct) } }
            Protocol.CHAR_CRYPT_NOTIFY ->
                LoveSpouseCrypto.parseNotify(value)?.battery?.let { pct -> main.post { listener?.onBattery(pct) } }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        if (!hasConnect()) return
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(cccd) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
        }
    }

    // ── Write command ──────────────────────────────────────────────────────────
    /** level 0..10, mode 0 (steady) / 1 (pulse). Routes to plain or crypt as configured. */
    fun send(level: Int, mode: Int) {
        if (!cryptMode) { writeBytes(Protocol.gattFrame(level, mode)); return }
        val s = seed
        if (!cryptReady) { log("Crypt channel not ready"); return }
        if (s == null || s.size < 4) { log("No seed set (need the per-version key)"); return }
        val value = if (mode == 1) LoveSpouseCrypto.z(level) else level
        val plain = LoveSpouseCrypto.classicPlain(value)
        val packet = LoveSpouseCrypto.encrypt(plain, s, deviceMac)
        writeBytes(packet)
    }

    /** Poll battery on a crypt device (encrypted query). */
    fun queryCryptBattery() {
        val s = seed ?: return
        if (!cryptMode || !cryptReady || s.size < 4) return
        writeBytes(LoveSpouseCrypto.encrypt(LoveSpouseCrypto.BATTERY_QUERY, s, deviceMac))
    }

    @SuppressLint("MissingPermission")
    private fun writeBytes(bytes: ByteArray) {
        val g = gatt ?: return
        val ch = writeChar ?: return
        if (!hasConnect()) return
        val type =
            if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, bytes, type)
        } else {
            @Suppress("DEPRECATION")
            run { ch.writeType = type; ch.value = bytes; g.writeCharacteristic(ch) }
        }
    }

    companion object { private const val TAG = "LSGatt" }
}
