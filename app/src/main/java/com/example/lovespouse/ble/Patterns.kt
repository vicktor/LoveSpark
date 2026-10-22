package com.example.lovespouse.ble

/**
 * Vibration patterns for a broadcast ("2.4G") LoveSpouse toy.
 *
 * Two kinds, unified in one type:
 *  - Firmware pattern: [mode] is a non-zero command byte (0x01..0x09, Family A /
 *    base 0x00 from DeviceNormalViewModel.group). The toy's firmware plays the
 *    rhythm; we just hold that byte on-air continuously. This is what the original
 *    app does — confirmed by HCI snoop: it advertises a single mode byte per pattern
 *    (0x01=Heartbeat, 0x02=Wave, 0x03=Cha-cha, … decoded from the capture).
 *  - Recorded pattern: [mode] == 0 and [stages] holds a client-side intensity
 *    sequence (gear 0..9) played by re-broadcasting each step every [intervalMs].
 *
 * The 9 preset names match the original app's wheel. All mode bytes are confirmed
 * from HCI snoop of the original app (the byte does NOT follow wheel order):
 * Heartbeat=01, Wave=02, Leap=03, Entwine=04, Climax=05, Sea wave=06, Dance=07,
 * Surging=08, Cha-cha=09.
 */
data class VibePattern(
    val name: String,
    val mode: Int = 0,
    val stages: IntArray = IntArray(0),
    val intervalMs: Long = 0,
) {
    val isFirmware: Boolean get() = mode != 0
}

object Patterns {
    val ALL: List<VibePattern> = listOf(
        VibePattern("Heartbeat", mode = 0x01),  // all confirmed via HCI snoop
        VibePattern("Wave",      mode = 0x02),
        VibePattern("Cha-cha",   mode = 0x09),
        VibePattern("Surging",   mode = 0x08),
        VibePattern("Dance",     mode = 0x07),
        VibePattern("Sea wave",  mode = 0x06),
        VibePattern("Climax",    mode = 0x05),
        VibePattern("Entwine",   mode = 0x04),
        VibePattern("Leap",      mode = 0x03),
    )
}
