package com.example.lovespouse.rf

/**
 * Pure-Kotlin port of the LoveSpouse app's RfPayloadEncoder
 * (com.weibuiot.lib_device.ble.RfPayloadEncoder). Replaces the native
 * libble.so `get_rf_payload`. Builds the nRF24-style on-air packet the toy's
 * 2.4G receiver expects, carried inside BLE advertising manufacturer data.
 *
 * Layout before whitening (buffer index 15 onward):
 *   [bitrev(0x71,0x0F,0x55)] [bitrev(address, placed reversed)] [payload] [CRC16 lo] [CRC16 hi]
 * Then the region is whitened twice with a 7-bit LFSR (seeds 63 then 37),
 * and the leading 15 bytes are dropped.
 *
 * Output length == address.size + payload.size + 5.
 */
object RfPayloadEncoder {

    /**
     * @param address broadcast prefix, e.g. "wbMSE" -> {0x77,0x62,0x4d,0x53,0x45}
     * @param payload command bytes (e.g. one gear byte 0x11..0x19, or "AA 01 xx yy zz")
     */
    fun encode(address: ByteArray, payload: ByteArray): ByteArray {
        val length = address.size + 18 + payload.size
        val total = length + 2
        val buf = ByteArray(total)

        buf[15] = 0x71
        buf[16] = 0x0F
        buf[17] = 0x55
        // address placed reversed: buf[address.size + 17 - i] = address[i]
        for (i in address.indices) {
            buf[address.size + 17 - i] = address[i]
        }
        System.arraycopy(payload, 0, buf, address.size + 18, payload.size)

        // bit-reverse the preamble + address region [15 .. 15 + address.size + 2]
        val n = address.size + 3
        for (i in 0 until n) {
            val idx = i + 15
            buf[idx] = reverse8(buf[idx].toInt()).toByte()
        }

        val crc = crc16(address, payload)
        buf[length] = (crc and 0xFF).toByte()
        buf[length + 1] = (crc ushr 8).toByte()

        whiten(buf, address.size + 2 + payload.size, lfsr(63), 18)
        whiten(buf, total, lfsr(37), 0)

        return buf.copyOfRange(15, total)
    }

    private fun crc16(address: ByteArray, payload: ByteArray): Int {
        var crc = 0xFFFF
        for (i in address.indices.reversed()) {
            crc = ccitt(((address[i].toInt() and 0xFF) shl 8) xor crc)
        }
        for (b in payload) {
            crc = ccitt(crc xor (reverse8(b.toInt()) shl 8))
        }
        return reverse16(crc).inv() and 0xFFFF
    }

    /** One CCITT round over 8 bits, polynomial 0x1021. */
    private fun ccitt(input: Int): Int {
        var v = input and 0xFFFF
        repeat(8) {
            v = (if (v and 0x8000 != 0) (v shl 1) xor 0x1021 else v shl 1) and 0xFFFF
        }
        return v
    }

    private fun reverse16(i: Int): Int {
        var x = i and 0xFFFF
        var r = 0
        repeat(16) { r = (r shl 1) or (x and 1); x = x ushr 1 }
        return r
    }

    private fun reverse8(i: Int): Int {
        var x = i and 0xFF
        var r = 0
        repeat(8) { r = (r shl 1) or (x and 1); x = x ushr 1 }
        return r
    }

    private fun lfsr(seed: Int): IntArray = intArrayOf(
        1,
        (seed ushr 5) and 1,
        (seed ushr 4) and 1,
        (seed ushr 3) and 1,
        (seed ushr 2) and 1,
        (seed ushr 1) and 1,
        seed and 1,
    )

    private fun whiten(buf: ByteArray, count: Int, s: IntArray, offset: Int) {
        for (i in 0 until count) {
            val a6 = s[6]; val a5 = s[5]; val a4 = s[4]; val a3 = s[3]
            val x2 = s[2] xor a5
            val x1 = s[1] xor a4
            val x5 = a6 xor a3
            val x0 = x5 xor s[0]
            val head = x2 xor a6
            s[0] = head; s[1] = x0; s[2] = x1; s[3] = x2
            s[4] = a3 xor x2; s[5] = x0 xor a4; s[6] = x1 xor a5
            val idx = i + offset
            val v = buf[idx].toInt() and 0xFF
            buf[idx] = (((v and 128) xor (head shl 7)) +
                ((v and 64) xor (x0 shl 6)) +
                ((v and 32) xor (x1 shl 5)) +
                ((v and 16) xor (x2 shl 4)) +
                ((v and 8) xor (x5 shl 3)) +
                ((v and 4) xor (a4 shl 2)) +
                ((v and 2) xor (a5 shl 1)) +
                ((v and 1) xor a6)).toByte()
        }
    }
}
