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
     * Command families (DeviceNormalViewModel.group), confirmed by decoding the original
     * app's BLE output and cross-checked against the OpenSpouse / autoveteran007CZ
     * captures. Each is a base byte; the command is re-broadcast to hold it.
     *
     *  - A, base 0x00: all channels. 0x00 = stop, 0x01..0x09 = the 9 firmware patterns.
     *  - B, base 0x30: channel 1 only (2-motor toys). 0x30 stop, 0x31..0x33 speed 1..3.
     *  - C, base 0x40: channel 2 only (2-motor toys). 0x40 stop, 0x41..0x43 speed 1..3.
     *
     * On a single-motor toy (e.g. 3744) channel 1 behaves like "all" and channel 2 is a no-op.
     */
    enum class ModeFamily(val label: String, val base: Int) {
        A("All channels", 0x00),
        B("Channel 1", 0x30),
        C("Channel 2", 0x40),
    }

    /**
     * Per-channel command byte for a 2-motor toy. [channel] 1 -> base 0x30, 2 -> base 0x40
     * (anything else -> all-channels base 0x00). [speed] 0 = stop, 1..3 = speed step.
     */
    fun channelCmd(channel: Int, speed: Int): Byte {
        val base = when (channel) { 1 -> 0x30; 2 -> 0x40; else -> 0x00 }
        return (base or speed.coerceIn(0, 3)).toByte()
    }

    /** Convenience: UI intensity 0..100 -> broadcast gear byte. */
    fun intensityToGear(level0to100: Int): Byte {
        if (level0to100 <= 0) return 0x00
        val gear = Math.round(level0to100 * 9.0 / 100.0).toInt().coerceIn(1, 9)
        return (0x10 or gear).toByte()
    }
}
