package com.example.lovespouse.ble

/** One step of an app-side waveform: hold [level] (gear 0..9) for [durationMs]. */
data class VibeStep(val level: Int, val durationMs: Long)

/**
 * A vibration pattern. Three shapes, unified in one type:
 *  - Firmware pattern: [mode] != 0 is a command byte (0x01..0x09, Family A). The toy's
 *    firmware plays the rhythm; we just hold that byte on-air. Confirmed via HCI snoop.
 *  - Waveform: [steps] is a variable-duration app-side sequence (level + ms per step).
 *    Adapted from OpenSpouse's VibrationPatternEngine (MIT). Level 0 steps brake (send 0x00).
 *  - Recorded: [stages] holds fixed-interval samples ([intervalMs] each) from the Record tab.
 */
data class VibePattern(
    val name: String,
    val mode: Int = 0,
    val steps: List<VibeStep> = emptyList(),
    val stages: IntArray = IntArray(0),
    val intervalMs: Long = 0,
) {
    val isFirmware: Boolean get() = mode != 0

    /** Normalized playback steps for a non-firmware pattern. */
    fun playbackSteps(): List<VibeStep> =
        if (steps.isNotEmpty()) steps else stages.map { VibeStep(it, intervalMs) }
}

object Patterns {

    /**
     * The 9 authentic firmware patterns, selected by mode byte (Family A). Byte order was
     * recovered from HCI snoop of the original app; it does NOT follow wheel order:
     * Heartbeat=01, Wave=02, Leap=03, Entwine=04, Climax=05, Sea wave=06, Dance=07,
     * Surging=08, Cha-cha=09.
     */
    val FIRMWARE: List<VibePattern> = listOf(
        VibePattern("Heartbeat", mode = 0x01),
        VibePattern("Wave",      mode = 0x02),
        VibePattern("Cha-cha",   mode = 0x09),
        VibePattern("Surging",   mode = 0x08),
        VibePattern("Dance",     mode = 0x07),
        VibePattern("Sea wave",  mode = 0x06),
        VibePattern("Climax",    mode = 0x05),
        VibePattern("Entwine",   mode = 0x04),
        VibePattern("Leap",      mode = 0x03),
    )

    /**
     * 14 app-side haptic waveforms adapted from OpenSpouse (MIT, Aurora-Nasa-1). These run
     * via the client-side player with 0x00 braking in the pauses, and complement — not
     * duplicate — the firmware patterns above (e.g. "Realistic Heartbeat" is a richer,
     * double-beat take versus the firmware "Heartbeat").
     */
    val WAVEFORMS: List<VibePattern> = listOf(
        wf("Realistic Heartbeat", 3 to 150L, 0 to 120L, 6 to 240L, 0 to 800L),
        wf("Tidal Surge", 1 to 300L, 2 to 300L, 4 to 300L, 6 to 350L, 8 to 400L, 9 to 500L, 7 to 350L, 4 to 300L, 2 to 250L, 0 to 500L),
        wf("Escalation", 1 to 700L, 3 to 700L, 5 to 700L, 7 to 700L, 9 to 900L, 0 to 800L),
        wf("Frenzy", 9 to 150L, 2 to 150L, 9 to 150L, 2 to 150L, 9 to 200L, 0 to 300L),
        wf("Burst", 9 to 140L, 0 to 140L, 9 to 140L, 0 to 140L, 9 to 140L, 0 to 140L, 9 to 140L, 0 to 140L, 9 to 140L, 0 to 750L),
        wf("Twin Peaks", 4 to 300L, 0 to 150L, 9 to 600L, 4 to 250L, 0 to 800L),
        wf("Deep Breath", 2 to 2500L, 0 to 1800L),
        wf("Accelerando", 4 to 450L, 0 to 450L, 5 to 320L, 0 to 320L, 6 to 220L, 0 to 220L, 7 to 160L, 0 to 160L, 8 to 140L, 0 to 140L, 9 to 1500L, 0 to 1000L),
        wf("Waterfall", 9 to 800L, 7 to 700L, 5 to 600L, 3 to 500L, 1 to 600L, 0 to 900L),
        wf("Hypnotic", 1 to 1200L, 3 to 800L, 1 to 1200L, 0 to 600L),
        wf("Sprint", 9 to 1800L, 0 to 250L, 9 to 1800L, 0 to 400L),
        wf("Tease & Deny", 1 to 900L, 0 to 600L, 8 to 350L, 0 to 1500L, 2 to 800L, 0 to 1200L),
        wf("Sine Flutter", 3 to 250L, 5 to 250L, 7 to 300L, 5 to 250L, 3 to 250L, 1 to 300L, 0 to 350L),
        wf("Volcano", 2 to 3500L, 5 to 1500L, 9 to 2500L, 0 to 2000L),
    )

    /** All selectable presets (firmware first, then waveforms). */
    val ALL: List<VibePattern> = FIRMWARE + WAVEFORMS

    private fun wf(name: String, vararg steps: Pair<Int, Long>): VibePattern =
        VibePattern(name, steps = steps.map { VibeStep(it.first, it.second) })
}
