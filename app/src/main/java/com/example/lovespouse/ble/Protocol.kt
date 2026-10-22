package com.example.lovespouse.ble

import java.util.UUID

/**
 * Connection data and command builders, taken from the decompiled LoveSpouse app
 * (com.weibuiot.lib_device.ble / com.weibuiot.device.ble).
 */
object Protocol {

    // ── Broadcast / "2.4G" mode (BleManager24) ────────────────────────────────
    /** Service UUID advertised in broadcast mode (string resource ble_service_8f). */
    const val ADV_SERVICE_UUID = "0000ae8f-0000-1000-8000-00805f9b34fb"

    /** Manufacturer / company id used in the advertisement (0xFF). */
    const val MANUFACTURER_ID = 0xFF

    /** Default broadcast prefix "wbMSE". Private-protocol toys use a per-device prefix. */
    val DEFAULT_PREFIX = byteArrayOf(0x77, 0x62, 0x4d, 0x53, 0x45)

    // ── Connected GATT mode (BleController / BleCommandUtil) ───────────────────
    val SERVICE_CONTROL: UUID = uuid("0000aaa0")
    val CHAR_WRITE: UUID = uuid("0000aaa1")       // primary command characteristic
    val CHAR_WRITE_ALT: UUID = uuid("0000aaa3")   // video / "trends" characteristic
    val SERVICE_BATTERY: UUID = uuid("0000180f")
    val CHAR_BATTERY: UUID = uuid("00002a19")

    // ── Encrypted GATT mode (CryptBleUtils, isCryptBle devices) ────────────────
    val SERVICE_CRYPT: UUID = uuid("0000f6c0")
    val CHAR_CRYPT_NOTIFY: UUID = uuid("0000f6c1")
    val CHAR_CRYPT_WRITE: UUID = uuid("0000f6c2")

    /** A scanned device matches when its name equals the configured one, or starts with these. */
    val NAME_PREFIXES = listOf("KGL", "WB", "ls")

    private fun uuid(short: String): UUID =
        UUID.fromString("$short-0000-1000-8000-00805f9b34fb")

    // ── Command builders ───────────────────────────────────────────────────────

    /**
     * Broadcast command byte: off = 0x00, gear 1..9 -> 0x11..0x19.
     * (BleCommandUtil.I / the official bridge's intensityToCmd.)
     */
    fun gearCmd(gear: Int): Byte =
        if (gear <= 0) 0x00 else (0x10 or gear.coerceIn(1, 9)).toByte()

    /**
     * Connected-mode 2-byte frame {level, mode} written to CHAR_WRITE.
     * level 1..10 with mode 0 (steady) or mode 1 (pulse/gear-2). (BleCommandUtil.l / c)
     */
    fun gattFrame(level: Int, mode: Int): ByteArray =
        byteArrayOf(level.coerceIn(0, 10).toByte(), (mode and 1).toByte())

    /**
     * Preset-mode families (DeviceNormalViewModel.group). Each is a base byte; mode N
     * sends base+N and "stop" sends the base itself. Firmware plays the waveform; the
     * command is re-broadcast to hold it.
     */
    enum class ModeFamily(val label: String, val base: Int) {
        A("Control (01-09)", 0x00),
        B("Vibration (31-39)", 0x30),
        C("Rotation/Suck (41-49)", 0x40),
    }

    /** Convenience: UI intensity 0..100 -> broadcast gear byte. */
    fun intensityToGear(level0to100: Int): Byte {
        if (level0to100 <= 0) return 0x00
        val gear = Math.round(level0to100 * 9.0 / 100.0).toInt().coerceIn(1, 9)
        return (0x10 or gear).toByte()
    }
}
