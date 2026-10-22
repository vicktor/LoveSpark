package com.example.lovespouse.ble

import kotlin.random.Random

/**
 * Exact port of the encrypted channel from CryptBleUtils, for `isCryptBle` devices
 * (GATT service f6c0, write char f6c2, notify char f6c1).
 *
 * Flow (mirrors the decompiled app):
 *   1. subscribe notify on f6c1
 *   2. write the PLAINTEXT handshake [0x02, 0xDB] to f6c2 -> channel becomes ready
 *   3. every command is encrypt(plain, seed, mac) written to f6c2, where
 *        plain = classicPlain(value)  for mode 0  (value used directly)
 *        plain = classicPlain(z(v))   for mode 1  ("custom")
 *        plain = BATTERY_QUERY        to poll battery
 *   4. notify frames on f6c1 are read by parseNotify()
 *
 * The `seed` is a per-firmware-version secret the official app provisions
 * (CryptBleUtils.v -> BaseCacheUtil.e(version)); it is NOT contained in the APK and
 * must be supplied by the caller. parseAdv() extracts the `version` to look it up by,
 * and the device MAC (also available natively via BluetoothDevice.getAddress()).
 */
object LoveSpouseCrypto {

    /** Plaintext handshake written once after subscribing to f6c1. */
    val HANDSHAKE = byteArrayOf(0x02, 0xDB.toByte())

    /** Battery-query plaintext (CryptBleUtils.f32157b = {0x0C, '0','0','0'}). */
    val BATTERY_QUERY = byteArrayOf(0x0C, 0x30, 0x30, 0x30)

    /** CryptBleUtils.n(): plaintext command {0x0C, 0x00, value, random}. */
    fun classicPlain(value: Int, rnd: Int = Random.nextInt(0, 256)): ByteArray =
        byteArrayOf(0x0C, 0x00, value.toByte(), rnd.toByte())

    /** CryptBleUtils.z(): map a "custom" value to its gear code. */
    fun z(value: Int): Int {
        var i = value % 22              // Ascii.SYN
        if (i == 0) i = 16
        return i + 16
    }

    /**
     * CryptBleUtils.o(): encrypt a plaintext command into the on-air packet for f6c2.
     * @return [len = n+4][0x81][R][cipher...][tail]
     */
    fun encrypt(plain: ByteArray, seed: ByteArray, macStr: String, rnd: Int = Random.nextInt(0, 256)): ByteArray {
        require(seed.size >= 4) { "seed must be >= 4 bytes" }
        val mac = macStr.split(":").map { it.toInt(16) and 0xFF }
        require(mac.size == 6) { "mac must be 6 bytes" }

        val macLow = IntArray(4) { mac[it + 2] }                  // last 4 MAC bytes
        val psk = IntArray(4) { (seed[it].toInt() and 0xFF) xor macLow[it] }

        val r = rnd and 0xFF
        var checksum = r
        for (b in plain) checksum = (checksum + (b.toInt() and 0xFF)) and 0xFF

        val header = (psk[0] xor checksum) xor 0x55
        val cipher = ByteArray(plain.size) { i ->
            (((psk[i % 4] + r) and 0xFF) xor (plain[i].toInt() and 0xFF)).toByte()
        }
        val tail = (header xor ((psk[2] + r) and 0xFF)).toByte()

        val out = ByteArray(plain.size + 4)
        out[0] = (plain.size + 4).toByte()
        out[1] = 0x81.toByte()
        out[2] = r.toByte()
        System.arraycopy(cipher, 0, out, 3, plain.size)
        out[plain.size + 3] = tail
        return out
    }

    data class Notify(val battery: Int? = null, val feedback: Int? = null)

    /** CryptBleUtils.w(): decode a notify frame from f6c1. */
    fun parseNotify(b: ByteArray?): Notify? {
        if (b == null || b.size < 6) return null
        val len = b[0].toInt() and 0xFF
        if (len < 6 || len > b.size) return null
        if ((b[1].toInt() and 0xFF) != 1) return null
        return when (b[2].toInt() and 0xFF) {
            138 -> Notify(battery = (b[5].toInt() and 0xFF).coerceIn(0, 100))            // A()
            139 -> Notify(feedback = ((b[5].toInt() and 0xFF) * 2).coerceAtMost(200))    // B()
            else -> null
        }
    }

    /**
     * Parse the encrypted-device advertisement (CryptBleUtils.E): find the 0xFF
     * manufacturer AD element and return Pair(version, mac).
     */
    fun parseAdv(scanRecord: ByteArray): Pair<String, String>? {
        var i = 0
        while (i < scanRecord.size) {
            val len = scanRecord[i].toInt() and 0xFF
            if (len == 0 || i + len >= scanRecord.size) break
            val type = scanRecord[i + 1].toInt() and 0xFF
            if (type == 0xFF && len >= 9) {
                val p = i + 2
                val version = "%04X".format(
                    ((scanRecord[i + 3].toInt() and 0xFF) shl 8) or (scanRecord[p].toInt() and 0xFF)
                )
                // mac = bytes at p+2+(5..0) formatted big-endian with ':' (CryptBleUtils.F)
                val mac = (5 downTo 0).joinToString(":") { "%02X".format(scanRecord[p + 2 + it].toInt() and 0xFF) }
                return version to mac
            }
            i += len + 1
        }
        return null
    }

    /** Parse a hex string like "0a1b2c3d" (spaces ok) into bytes. */
    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace(":", "")
        require(clean.length % 2 == 0) { "hex length must be even" }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
