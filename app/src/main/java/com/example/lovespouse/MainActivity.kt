package com.example.lovespouse

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lovespouse.ble.Broadcaster
import com.example.lovespouse.ble.CustomPatterns
import com.example.lovespouse.ble.GattController
import com.example.lovespouse.ble.LoveSpouseCrypto
import com.example.lovespouse.ble.Patterns
import com.example.lovespouse.ble.VibePattern
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var broadcaster: Broadcaster
    private lateinit var gatt: GattController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        broadcaster = Broadcaster(this)
        gatt = GattController(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    }

    override fun onStop() { super.onStop(); broadcaster.stop() }
    override fun onDestroy() { super.onDestroy(); gatt.disconnect(); broadcaster.stop() }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    @OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @Composable
    private fun App() {
        val context = LocalContext.current
        var screen by remember { mutableStateOf("control") }
        var broadcastMode by remember { mutableStateOf(true) }
        var gear by remember { mutableIntStateOf(0) }
        var mode by remember { mutableIntStateOf(0) }
        var connected by remember { mutableStateOf(false) }
        var battery by remember { mutableStateOf<Int?>(null) }
        var devices by remember { mutableStateOf<List<GattController.Found>>(emptyList()) }
        var cryptEnabled by remember { mutableStateOf(false) }
        var seedHex by remember { mutableStateOf("") }
        var cryptInfo by remember { mutableStateOf<String?>(null) }
        var activePattern by remember { mutableStateOf<VibePattern?>(null) }
        var customPatterns by remember { mutableStateOf(CustomPatterns.load(context)) }
        val logs = remember { mutableStateListOf<String>() }

        // Pattern player. Firmware patterns: hold one mode byte on-air (the toy plays
        // the rhythm). Recorded patterns: loop the client-side intensity stages.
        LaunchedEffect(activePattern, broadcastMode) {
            val p = activePattern ?: return@LaunchedEffect
            if (!broadcastMode) return@LaunchedEffect
            if (p.isFirmware) {
                broadcaster.sendByte(p.mode)   // advertises continuously until changed
            } else {
                val steps = p.playbackSteps()
                while (isActive) {
                    for (s in steps) {
                        if (s.level <= 0) broadcaster.brake() else broadcaster.sendGear(s.level)
                        delay(s.durationMs)
                    }
                }
            }
        }

        val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {}
        LaunchedEffect(Unit) {
            permLauncher.launch(requiredPermissions())
            gatt.listener = object : GattController.Listener {
                override fun onDevicesChanged(d: List<GattController.Found>) { devices = d }
                override fun onConnectionChanged(c: Boolean) { connected = c }
                override fun onBattery(percent: Int) { battery = percent }
                override fun onCryptInfo(version: String, mac: String) { cryptInfo = "v$version  $mac" }
                override fun onLog(line: String) { logs.add(0, line); if (logs.size > 30) logs.removeAt(logs.lastIndex) }
            }
        }

        fun applyConnected(level: Int, m: Int) { gear = level; mode = m; gatt.send(level, m) }

        Scaffold(topBar = { TopAppBar(title = { Text("LoveSpark") }) }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                TabRow(selectedTabIndex = if (screen == "control") 0 else 1) {
                    Tab(screen == "control", { screen = "control" }, text = { Text("Control") })
                    Tab(screen == "record", {
                        activePattern = null; broadcaster.sendGear(0); broadcaster.stop(); screen = "record"
                    }, text = { Text("Record") })
                }

                if (screen == "record") {
                    RecordScreen(onSaved = { p -> customPatterns = CustomPatterns.add(context, p) })
                    return@Column
                }

                val scroll = rememberScrollState()
                Column(
                    Modifier.padding(16.dp).fillMaxSize().verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (!broadcaster.isSupported)
                        Text("This phone does not support BLE advertising",
                            style = MaterialTheme.typography.bodyMedium)
                    val toggle: (VibePattern) -> Unit = { p ->
                        if (activePattern?.name == p.name) {
                            activePattern = null; broadcaster.brake()
                        } else activePattern = p
                    }

                    // Firmware patterns: 3x3 grid of equal-size cells.
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Patterns.FIRMWARE.chunked(3).forEach { rowItems ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                rowItems.forEach { p ->
                                    val on = activePattern?.name == p.name
                                    val cell = Modifier.weight(1f).height(52.dp)
                                    if (on) Button(onClick = { toggle(p) }, modifier = cell,
                                        contentPadding = PaddingValues(2.dp)) {
                                        Text(p.name, textAlign = TextAlign.Center,
                                            style = MaterialTheme.typography.labelMedium)
                                    } else OutlinedButton(onClick = { toggle(p) }, modifier = cell,
                                        contentPadding = PaddingValues(2.dp)) {
                                        Text(p.name, textAlign = TextAlign.Center,
                                            style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                    Button(onClick = { activePattern = null; broadcaster.brake() },
                        enabled = activePattern != null, modifier = Modifier.fillMaxWidth()) { Text("STOP") }

                    // App-side waveforms (adapted from OpenSpouse).
                    Text("Waveforms", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Patterns.WAVEFORMS.forEach { p ->
                            FilterChip(activePattern?.name == p.name, { toggle(p) }, label = { Text(p.name) })
                        }
                    }

                    if (customPatterns.isNotEmpty()) {
                        Text("Your recordings", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            customPatterns.forEach { p ->
                                val on = activePattern?.name == p.name
                                FilterChip(on, { toggle(p) }, label = { Text(p.name) })
                                OutlinedButton(onClick = {
                                    if (on) { activePattern = null; broadcaster.brake() }
                                    customPatterns = CustomPatterns.delete(context, p.name)
                                }) { Text("✕") }
                            }
                        }
                    }
                }
            }
        }
    }

    private val tickMs = 120L

    @Composable
    private fun RecordScreen(onSaved: (VibePattern) -> Unit) {
        var recording by remember { mutableStateOf(false) }
        var level by remember { mutableIntStateOf(0) }
        val samples = remember { mutableStateListOf<Int>() }
        var showSave by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }

        // Sampling tick: while recording, capture the current finger level every tickMs.
        LaunchedEffect(recording) {
            if (!recording) return@LaunchedEffect
            while (isActive) { samples.add(level); delay(tickMs) }
        }
        DisposableEffect(Unit) { onDispose { broadcaster.sendGear(0); broadcaster.stop() } }

        fun setLevel(l: Int) { level = l; broadcaster.sendGear(l) }

        val ms = samples.size * tickMs
        val timer = "%d:%02d.%d".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 100)

        Column(Modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Hold the scale to vibrate (1–9). Release = pause (0).", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(timer, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(16.dp))
                Text("level $level", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (recording) Text("● REC", color = Color(0xFFE23A6A))
            }

            // Vertical touch scale: top = 9, bottom = 1; release = 0.
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF241233))
                    .pointerInput(Unit) {
                        val h = size.height.toFloat()
                        fun lvl(y: Float): Int {
                            val frac = 1f - (y / h).coerceIn(0f, 1f)
                            return (frac * 9f).roundToInt().coerceIn(1, 9)
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            setLevel(lvl(down.position.y))
                            while (true) {
                                val e = awaitPointerEvent()
                                val ch = e.changes.firstOrNull() ?: break
                                if (!ch.pressed) { setLevel(0); break }
                                setLevel(lvl(ch.position.y))
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // fill from bottom proportional to level
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight((level / 9f).coerceAtLeast(0.0001f))
                        .align(Alignment.BottomCenter)
                        .background(Color(0xFF8E3FD6))
                )
                Text(if (level == 0) "—" else "$level", fontSize = 72.sp, color = Color.White)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    if (recording) {
                        recording = false
                        if (samples.isNotEmpty()) showSave = true
                    } else { samples.clear(); recording = true }
                }) { Text(if (recording) "Stop" else "Record") }
                OutlinedButton(onClick = { recording = false; samples.clear(); setLevel(0); broadcaster.stop() }) {
                    Text("Clear")
                }
                Text("tick ${tickMs}ms · ${samples.size} steps", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterVertically))
            }
        }

        if (showSave) {
            AlertDialog(
                onDismissRequest = { showSave = false },
                title = { Text("Save pattern") },
                text = {
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text("Name") }, singleLine = true)
                },
                confirmButton = {
                    TextButton(onClick = {
                        val finalName = name.ifBlank { "Rec ${System.currentTimeMillis() % 100000}" }
                        onSaved(VibePattern(finalName, stages = samples.toIntArray(), intervalMs = tickMs))
                        samples.clear(); name = ""; showSave = false
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showSave = false }) { Text("Discard") } }
            )
        }
    }
}
