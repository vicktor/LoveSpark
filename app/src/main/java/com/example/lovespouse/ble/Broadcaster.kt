package com.example.lovespouse.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.lovespouse.rf.RfPayloadEncoder

/**
 * Drives a broadcast ("2.4G") LoveSpouse toy by emitting the exact BLE advertisement
 * the official app sends — no GATT connection. Mirrors BleManager24 + BleCommandUtil.g().
 */
class Broadcaster(
    context: Context,
    private val prefix: ByteArray = Protocol.DEFAULT_PREFIX,
) {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
    private val advertiser: BluetoothLeAdvertiser?
        get() = adapter?.bluetoothLeAdvertiser

    private var activeCallback: AdvertiseCallback? = null

    val isSupported: Boolean
        get() = adapter?.isMultipleAdvertisementSupported == true && advertiser != null

    private fun hasAdvertisePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_ADVERTISE) ==
            PackageManager.PERMISSION_GRANTED

    /** Build the on-air manufacturer-data payload for a command (one or more bytes). */
    fun buildPayload(command: ByteArray): ByteArray = RfPayloadEncoder.encode(prefix, command)

    /** Hold a gear (0 = off, 1..9) by advertising continuously until [stop] or the next call. */
    fun sendGear(gear: Int) = sendCommand(byteArrayOf(Protocol.gearCmd(gear)))

    /** Hold a single raw command byte (e.g. a preset-mode code) continuously. */
    fun sendByte(cmd: Int) = sendCommand(byteArrayOf((cmd and 0xFF).toByte()))

    @SuppressLint("MissingPermission")
    fun sendCommand(command: ByteArray) {
        val adv = advertiser ?: run { Log.w(TAG, "No advertiser (BLE peripheral unsupported)"); return }
        if (!hasAdvertisePermission()) { Log.w(TAG, "Missing BLUETOOTH_ADVERTISE permission"); return }
        stop()

        val payload = buildPayload(command)
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid.fromString(Protocol.ADV_SERVICE_UUID))
            .addManufacturerData(Protocol.MANUFACTURER_ID, payload)
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setTimeout(0) // advertise until stopped
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) { Log.e(TAG, "advertise failed: $errorCode") }
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(TAG, "advertising ${payload.joinToString(" ") { "%02x".format(it) }}")
            }
        }
        activeCallback = cb
        advertiser?.startAdvertising(settings, data, cb)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = activeCallback ?: return
        activeCallback = null
        if (!hasAdvertisePermission()) return
        try { advertiser?.stopAdvertising(cb) } catch (_: Exception) {}
    }

    companion object { private const val TAG = "LSBroadcaster" }
}
