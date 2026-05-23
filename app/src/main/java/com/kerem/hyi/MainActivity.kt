package com.kerem.hyi

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.kerem.hyi.ui.theme.HypermachHYITheme
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────
// Activity
// ─────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    private lateinit var usbSerialManager: UsbSerialManager
    private val sessionManager = TelemetrySessionManager()
    private var isReceiverRegistered = false

    // Session-level stats tracked in the Activity and exposed as flows
    private val _validPackets  = MutableStateFlow(0)
    private val _badPackets    = MutableStateFlow(0)
    private val _packetsPerSec = MutableStateFlow(0f)
    private val _maxAltitude   = MutableStateFlow(0f)

    // Rate tracking
    private var pktCountWindow = 0
    private var windowStartMs  = System.currentTimeMillis()
    private var ppsTimeoutJob: Job? = null
    
    // Calibration
    private val _altitudeOffset = MutableStateFlow(0f)

    companion object {
        private const val ACTION_USB_PERMISSION = "com.kerem.hyi.USB_PERMISSION"
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION != intent.action) return
            synchronized(this) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    device?.let { usbSerialManager.connectDevice(it) }
                } else {
                    Toast.makeText(context, "USB Permission Denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val createCsvLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let {
            try {
                contentResolver.openOutputStream(it)?.use { stream ->
                    stream.write(sessionManager.getCsvContent().toByteArray())
                }
                Toast.makeText(this, "Export successful", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbSerialManager = UsbSerialManager(this)

        // Keep screen on during telemetry session
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Hook into incoming packets to update session stats
        usbSerialManager.setOnPacketReceived { telemetry, isValid ->
            if (isValid && telemetry != null) {
                // Apply altitude tare
                val calibratedAlt = telemetry.altitude - _altitudeOffset.value
                val calibratedData = telemetry.copy(altitude = calibratedAlt)

                sessionManager.addRecord(calibratedData)
                _validPackets.value++
                
                if (calibratedData.altitude > _maxAltitude.value) {
                    _maxAltitude.value = calibratedData.altitude
                }
                pktCountWindow++
                val now = System.currentTimeMillis()
                val elapsed = now - windowStartMs
                if (elapsed >= 1000L) {
                    _packetsPerSec.value = pktCountWindow * 1000f / elapsed
                    pktCountWindow = 0
                    windowStartMs = now
                }

                // Reset timeout job on every valid packet
                ppsTimeoutJob?.cancel()
                ppsTimeoutJob = lifecycleScope.launch {
                    delay(2000)
                    _packetsPerSec.value = 0f
                }
            } else if (!isValid) {
                _badPackets.value++
            }
        }

        setContent {
            HypermachHYITheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    DashboardScreen(
        usbManager    = usbSerialManager,
        validPackets  = _validPackets,
        badPackets    = _badPackets,
        packetsPerSec = _packetsPerSec,
        maxAltitude   = _maxAltitude,
        onConnect     = { handleUsbConnectionAttempt() },
        onDisconnect  = {
            usbSerialManager.closePortSafely()
            resetSessionStats()
            sessionManager.clearSession()
        },
        onExportCsv   = { 
            if (sessionManager.isSessionEmpty()) {
                Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show()
            } else {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                createCsvLauncher.launch("hyi_log_$ts.csv")
            }
        },
        onTareAltitude = {
            val currentRawAlt = usbSerialManager.telemetryState.value?.altitude ?: 0f
            _altitudeOffset.value = currentRawAlt
            _maxAltitude.value = 0f // Reset max on new baseline
            Toast.makeText(this, "Altitude Tared to %.2fm".format(currentRawAlt), Toast.LENGTH_SHORT).show()
        }
    )
                }
            }
        }
    }

    private fun resetSessionStats() {
        _validPackets.value  = 0
        _badPackets.value    = 0
        _packetsPerSec.value = 0f
        _maxAltitude.value   = 0f
        pktCountWindow       = 0
        windowStartMs        = System.currentTimeMillis()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun handleUsbConnectionAttempt() {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbSerialManager.usbManager)
        if (availableDrivers.isEmpty()) {
            Toast.makeText(this, "No USB device found attached", Toast.LENGTH_SHORT).show()
            return
        }
        val device = availableDrivers[0].device
        if (usbSerialManager.usbManager.hasPermission(device)) {
            usbSerialManager.connectDevice(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            if (!isReceiverRegistered) {
                val filter = IntentFilter(ACTION_USB_PERMISSION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(usbReceiver, filter)
                }
                isReceiverRegistered = true
            }
            usbSerialManager.usbManager.requestPermission(device, permIntent)
        }
    }

    // Exports the last received telemetry + session stats to Downloads/hyi_log_<timestamp>.csv
    /* Removed in favor of Session Logging + Scoped Storage
    private fun exportCsv() {
        ...
    }
    */

    override fun onDestroy() {
        super.onDestroy()
        usbSerialManager.closePortSafely()
        if (isReceiverRegistered) {
            runCatching { unregisterReceiver(usbReceiver) }
            isReceiverRegistered = false
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Design tokens — all colour decisions live here
// ─────────────────────────────────────────────────────────────

private val CyanAccent    = Color(0xFF00E5FF)
private val AmberAccent   = Color(0xFFFFB300)
private val GreenAccent   = Color(0xFF69F0AE)
private val PurpleAccent  = Color(0xFFB388FF)
private val RedAccent     = Color(0xFFFF5252)
private val SurfaceDark   = Color(0xFF0D1117)
private val CardDark      = Color(0xFF161B22)
private val CardDarker    = Color(0xFF0D1117)
private val DividerColor  = Color(0xFF21262D)
private val TextPrimary   = Color(0xFFE6EDF3)
private val TextSecondary = Color(0xFF8B949E)
private val TextDim       = Color(0xFF484F58)
private val MonoFont      = FontFamily.Monospace

// ─────────────────────────────────────────────────────────────
// Top-level screen composable
// ─────────────────────────────────────────────────────────────

@Composable
fun DashboardScreen(
    usbManager:    UsbSerialManager,
    validPackets:  StateFlow<Int>,
    badPackets:    StateFlow<Int>,
    packetsPerSec: StateFlow<Float>,
    maxAltitude:   StateFlow<Float>,
    onConnect:     () -> Unit,
    onDisconnect:  () -> Unit,
    onExportCsv:   () -> Unit,
    onTareAltitude: () -> Unit,
) {
    val isConnected   by usbManager.connectionState.collectAsState()
    val telemetry     by usbManager.telemetryState.collectAsState()
    val validCount    by validPackets.collectAsState()
    val badCount      by badPackets.collectAsState()
    val pps           by packetsPerSec.collectAsState()
    val maxAlt        by maxAltitude.collectAsState()

    DashboardContent(
        isConnected   = isConnected,
        telemetry     = telemetry,
        validPackets  = validCount,
        badPackets    = badCount,
        packetsPerSec = pps,
        maxAltitude   = maxAlt,
        onConnect     = onConnect,
        onDisconnect  = onDisconnect,
        onExportCsv   = onExportCsv,
        onTareAltitude = onTareAltitude,
    )
}

// ─────────────────────────────────────────────────────────────
// Main content
// ─────────────────────────────────────────────────────────────

@Composable
fun DashboardContent(
    isConnected:   Boolean,
    telemetry:     TelemetryData?,
    validPackets:  Int   = 0,
    badPackets:    Int   = 0,
    packetsPerSec: Float = 0f,
    maxAltitude:   Float = 0f,
    onConnect:     () -> Unit,
    onDisconnect:  () -> Unit,
    onExportCsv:   () -> Unit = {},
    onTareAltitude: () -> Unit = {},
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // ── Header bar ──────────────────────────────────────────
        HeaderBar(
            isConnected   = isConnected,
            onConnect     = onConnect,
            onDisconnect  = onDisconnect,
            onExportCsv   = onExportCsv,
            onTareAltitude = onTareAltitude,
        )

        if (isLandscape) {
            LandscapeMainContent(
                telemetry = telemetry,
                validPackets = validPackets,
                badPackets = badPackets,
                packetsPerSec = packetsPerSec,
                maxAltitude = maxAltitude
            )
        } else {
            PortraitMainContent(
                telemetry = telemetry,
                validPackets = validPackets,
                badPackets = badPackets,
                packetsPerSec = packetsPerSec,
                maxAltitude = maxAltitude
            )
        }

        if (telemetry != null) {
            // ── Metadata footer ─────────────────────────────────
            MetadataFooter(telemetry)
        }
    }
}

@Composable
fun PortraitMainContent(
    telemetry: TelemetryData?,
    validPackets: Int,
    badPackets: Int,
    packetsPerSec: Float,
    maxAltitude: Float
) {
    // ── Link quality strip ──────────────────────────────────
    LinkQualityStrip(
        validPackets = validPackets,
        badPackets = badPackets,
        packetsPerSec = packetsPerSec,
    )

    // ── Primary metrics ─────────────────────────────────
    PrimaryMetricsRow(
        altitude = telemetry?.altitude,
        maxAltitude = maxAltitude,
        angle = telemetry?.angle,
    )

    if (telemetry != null) {
        // ── GPS section (only when meaningful) ─────────────
        GpsSection(telemetry)

        // ── IMU section ─────────────────────────────────────
        ImuSection(telemetry)
    } else {
        EmptyStateCard(isConnected = true) // EmptyStateCard inside Scrollable logic
    }
}

@Composable
fun LandscapeMainContent(
    telemetry: TelemetryData?,
    validPackets: Int,
    badPackets: Int,
    packetsPerSec: Float,
    maxAltitude: Float
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max), // Use intrinsic size to fit content without clipping
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Column 1: Link Quality
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            LinkQualityColumn(
                validPackets = validPackets,
                badPackets = badPackets,
                packetsPerSec = packetsPerSec
            )
        }

        // Column 2: Altitude & Angle
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AltitudeCard(
                altitude = telemetry?.altitude,
                maxAltitude = maxAltitude,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            AngleCard(
                angle = telemetry?.angle,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }

        // Column 3: IMU Sensors
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (telemetry != null) {
                AccelCard(t = telemetry, modifier = Modifier.weight(1f).fillMaxHeight())
                GyroCard(t = telemetry, modifier = Modifier.weight(1f).fillMaxHeight())
            } else {
                GsCard(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Box(contentAlignment = Alignment.Center) { Text("ACCEL ---", color = TextDim, fontSize = 10.sp) }
                }
                GsCard(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Box(contentAlignment = Alignment.Center) { Text("GYRO ---", color = TextDim, fontSize = 10.sp) }
                }
            }
        }
    }
    
    if (telemetry != null) {
        // GPS Section below for landscape too if it's there
        GpsSection(telemetry)
    }
}

@Composable
fun LinkQualityColumn(
    validPackets: Int,
    badPackets: Int,
    packetsPerSec: Float,
) {
    val totalPackets = validPackets + badPackets
    val qualityRatio = if (totalPackets > 0) validPackets.toFloat() / totalPackets else 0f
    val qualityColor = when {
        totalPackets == 0 -> TextDim
        qualityRatio >= 0.95f -> GreenAccent
        qualityRatio >= 0.80f -> AmberAccent
        else -> RedAccent
    }
    val qualityLabel = when {
        totalPackets == 0 -> "NO DATA"
        qualityRatio >= 0.95f -> "LINK OK"
        qualityRatio >= 0.80f -> "LINK DEGRADED"
        else -> "LINK POOR"
    }

    GsCard(modifier = Modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Link quality badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(qualityColor)
                )
                Spacer(Modifier.width(6.dp))
                Text(qualityLabel, color = qualityColor, fontFamily = MonoFont, fontSize = 10.sp, letterSpacing = 1.sp)
            }

            Spacer(Modifier.height(12.dp))

            // Three mini stats stacked
            StatChip(label = "VALID", value = validPackets.toString(), color = GreenAccent)
            Spacer(Modifier.height(8.dp))
            StatChip(label = "BAD", value = badPackets.toString(), color = if (badPackets > 0) RedAccent else TextDim)
            Spacer(Modifier.height(8.dp))
            StatChip(label = "PKT/S", value = "%.1f".format(packetsPerSec), color = CyanAccent)
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Header bar
// ─────────────────────────────────────────────────────────────

@Composable
fun HeaderBar(
    isConnected:  Boolean,
    onConnect:    () -> Unit,
    onDisconnect: () -> Unit,
    onExportCsv:  () -> Unit,
    onTareAltitude: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App Logo
        Icon(
            painter = painterResource(id = R.drawable.hypermach_logo),
            contentDescription = null,
            tint = Color.Unspecified, 
            modifier = Modifier.size(24.dp)
        )

        Spacer(Modifier.width(12.dp))

        Text(
            text = if (isConnected) stringResource(R.string.connected).uppercase() else stringResource(R.string.disconnected).uppercase(),
            color = if (isConnected) GreenAccent else TextSecondary,
            fontFamily = MonoFont,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
        )

        Spacer(Modifier.weight(1f))

        // Tare button
        IconButton(
            onClick = onTareAltitude,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FilterCenterFocus, // Looks like a tare/zeroing target
                contentDescription = "Tare Altitude",
                tint = if (isConnected) AmberAccent.copy(alpha = 0.8f) else TextDim,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(Modifier.width(4.dp))

        // Export button — only enabled when connected and data exists
        IconButton(
            onClick = onExportCsv,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Export CSV",
                tint = if (isConnected) CyanAccent.copy(alpha = 0.8f) else TextDim,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(Modifier.width(4.dp))

        // Connect / Disconnect button
        val btnColor = if (isConnected) RedAccent else GreenAccent
        OutlinedButton(
            onClick = if (isConnected) onDisconnect else onConnect,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = btnColor),
            border = androidx.compose.foundation.BorderStroke(1.dp, btnColor.copy(alpha = 0.6f)),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text(
                text = if (isConnected) stringResource(R.string.disconnect).uppercase() else stringResource(R.string.connect_usb).uppercase(),
                fontFamily = MonoFont,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
            )
        }
    }

    // Divider below header
    Spacer(Modifier.height(4.dp))
    HorizontalDivider(color = DividerColor)
}

// ─────────────────────────────────────────────────────────────
// Link quality strip
// ─────────────────────────────────────────────────────────────

@Composable
fun LinkQualityStrip(
    validPackets:  Int,
    badPackets:    Int,
    packetsPerSec: Float,
) {
    val totalPackets = validPackets + badPackets
    val qualityRatio = if (totalPackets > 0) validPackets.toFloat() / totalPackets else 0f
    val qualityColor = when {
        totalPackets == 0      -> TextDim
        qualityRatio >= 0.95f  -> GreenAccent
        qualityRatio >= 0.80f  -> AmberAccent
        else                   -> RedAccent
    }
    val qualityLabel = when {
        totalPackets == 0      -> "NO DATA"
        qualityRatio >= 0.95f  -> "LINK OK"
        qualityRatio >= 0.80f  -> "LINK DEGRADED"
        else                   -> "LINK POOR"
    }

    GsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Link quality badge
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(qualityColor)
                )
                Spacer(Modifier.width(6.dp))
                Text(qualityLabel, color = qualityColor, fontFamily = MonoFont, fontSize = 10.sp, letterSpacing = 1.sp)
            }

            // Three mini stats symmetrically spaced
            Row(
                modifier = Modifier.weight(2f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatChip(label = "VALID", value = validPackets.toString(), color = GreenAccent)
                StatChip(label = "BAD", value = badPackets.toString(), color = if (badPackets > 0) RedAccent else TextDim)
                StatChip(label = "PKT/S", value = "%.1f".format(packetsPerSec), color = CyanAccent)
            }
        }
    }
}

@Composable
fun StatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(label, color = TextDim, fontFamily = MonoFont, fontSize = 8.sp, letterSpacing = 0.5.sp)
    }
}

// ─────────────────────────────────────────────────────────────
// Primary metrics row  (Altitude + Angle)
// ─────────────────────────────────────────────────────────────

@Composable
fun PrimaryMetricsRow(altitude: Float?, maxAltitude: Float, angle: Float?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AltitudeCard(
            altitude = altitude,
            maxAltitude = maxAltitude,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
        AngleCard(
            angle = angle,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
    }
}

@Composable
fun AltitudeCard(altitude: Float?, maxAltitude: Float, modifier: Modifier = Modifier) {
    val altText = altitude?.let { "%.2f".format(it) } ?: "---"
    GsCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SectionLabel(stringResource(R.string.altitude).uppercase(), CyanAccent)
            Spacer(Modifier.height(4.dp))
            Text(
                text = altText,
                color = TextPrimary,
                fontFamily = MonoFont,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
            )
            Text(stringResource(R.string.meters_label), color = TextDim, fontFamily = MonoFont, fontSize = 9.sp)
            Spacer(Modifier.height(8.dp))
            // Max altitude tracker
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                    contentDescription = null,
                    tint = AmberAccent,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "MAX  ${"%.2f".format(maxAltitude)} m",
                    color = AmberAccent,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp,
                )
            }
        }
    }
}

@Composable
fun AngleCard(angle: Float?, modifier: Modifier = Modifier) {
    val angleText = angle?.let { "%.1f".format(it) } ?: "---"
    GsCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SectionLabel(stringResource(R.string.rocket_angle).uppercase(), AmberAccent)
            Spacer(Modifier.height(4.dp))
            Text(
                text = angleText,
                color = AmberAccent,
                fontFamily = MonoFont,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
            )
            Text(stringResource(R.string.degrees_label), color = TextDim, fontFamily = MonoFont, fontSize = 9.sp)
            Spacer(Modifier.height(8.dp))
            // Visual tilt bar
            TiltBar(angle ?: 0f)
        }
    }
}

// A small horizontal bar that fills proportionally to |angle| / 90°, color-coded.
@Composable
fun TiltBar(angle: Float) {
    val clamped = (kotlin.math.abs(angle) / 90f).coerceIn(0f, 1f)
    val barColor = when {
        clamped < 0.2f -> GreenAccent
        clamped < 0.5f -> AmberAccent
        else           -> RedAccent
    }
    val animatedFill by animateFloatAsState(targetValue = clamped, animationSpec = tween(300), label = "tilt")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(DividerColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedFill)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(barColor)
        )
    }
}

// ─────────────────────────────────────────────────────────────
// GPS section
// ─────────────────────────────────────────────────────────────

@Composable
fun GpsSection(t: TelemetryData) {
    SectionHeader(stringResource(R.string.gps_location).uppercase())

    if (t.hasGpsFix) {
        GsCard {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GpsDataItem(stringResource(R.string.latitude).uppercase(),  "%.6f".format(t.gpsLatitude),  Icons.Default.LocationOn, Modifier.weight(1f))
                    GpsDataItem(stringResource(R.string.longitude).uppercase(), "%.6f".format(t.gpsLongitude), Icons.Default.LocationOn, Modifier.weight(1f))
                }
                GpsDataItem(stringResource(R.string.gps_altitude).uppercase(), "%.1f m".format(t.gpsAltitude), Icons.Default.Height, Modifier.fillMaxWidth())
            }
        }
    } else {
        // GPS no-fix — compact single-line indicator, doesn't waste space
        GsCard(containerColor = Color(0xFF1A0A0A)) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.GpsOff, contentDescription = null, tint = RedAccent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.gps_no_fix),
                    color = RedAccent.copy(alpha = 0.8f),
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
fun GpsDataItem(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, color = TextDim, fontFamily = MonoFont, fontSize = 8.sp, letterSpacing = 0.5.sp)
            Text(value, color = TextPrimary, fontFamily = MonoFont, fontWeight = FontWeight.Medium, fontSize = 12.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────
// IMU section
// ─────────────────────────────────────────────────────────────

@Composable
fun AccelCard(t: TelemetryData, modifier: Modifier = Modifier) {
    GsCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(10.dp), // Reduced padding to save vertical space
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SectionLabel("ACCEL", PurpleAccent)
            Text("g", color = TextDim, fontFamily = MonoFont, fontSize = 8.sp)
            ImuAxisRow("X", t.accelX, PurpleAccent)
            ImuAxisRow("Y", t.accelY, PurpleAccent)
            ImuAxisRow("Z", t.accelZ, PurpleAccent)
        }
    }
}

@Composable
fun GyroCard(t: TelemetryData, modifier: Modifier = Modifier) {
    GsCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(10.dp), // Reduced padding to save vertical space
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SectionLabel("GYRO", CyanAccent)
            Text("dps", color = TextDim, fontFamily = MonoFont, fontSize = 8.sp)
            ImuAxisRow("X", t.gyroX, CyanAccent)
            ImuAxisRow("Y", t.gyroY, CyanAccent)
            ImuAxisRow("Z", t.gyroZ, CyanAccent)
        }
    }
}

@Composable
fun ImuSection(t: TelemetryData) {
    SectionHeader(stringResource(R.string.imu_sensors).uppercase())

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AccelCard(
            t = t,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
        GyroCard(
            t = t,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
    }
}

@Composable
fun ImuAxisRow(axis: String, value: Float, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // Axis label badge
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = axis,
                color = accent,
                fontFamily = MonoFont,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    platformStyle = PlatformTextStyle(
                        includeFontPadding = false
                    )
                )
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "%+.3f".format(value),      // always show sign, 3 decimals
            color = TextPrimary,
            fontFamily = MonoFont,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Metadata footer
// ─────────────────────────────────────────────────────────────

@Composable
fun MetadataFooter(t: TelemetryData) {
    GsCard(containerColor = CardDarker) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                MetaItem(stringResource(R.string.team_id).uppercase(), t.teamId.toString())
            }
            VerticalDividerLine()
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                MetaItem(stringResource(R.string.packet_count).uppercase(), "#${t.packetCount}")
            }
        }
    }
}

@Composable
fun MetaItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextDim, fontFamily = MonoFont, fontSize = 8.sp, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = TextSecondary, fontFamily = MonoFont, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun VerticalDividerLine() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(28.dp)
            .background(DividerColor)
    )
}

// ─────────────────────────────────────────────────────────────
// Empty state
// ─────────────────────────────────────────────────────────────

@Composable
fun EmptyStateCard(isConnected: Boolean) {
    GsCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Default.HourglassEmpty else Icons.Default.UsbOff,
                contentDescription = null,
                tint = TextDim,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (isConnected) stringResource(R.string.waiting_for_telemetry) else stringResource(R.string.usb_disconnected),
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 12.sp,
            )
            if (!isConnected) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "19200 baud · 8N1 · HYİ EK-7",
                    color = TextDim,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Shared primitives
// ─────────────────────────────────────────────────────────────

@Composable
fun GsCard(
    modifier: Modifier = Modifier,
    containerColor: Color = CardDark,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        content()
    }
}

@Composable
fun SectionLabel(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontFamily = MonoFont,
        fontSize = 9.sp,
        letterSpacing = 1.5.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontFamily = MonoFont,
        fontSize = 9.sp,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(top = 4.dp, start = 2.dp, bottom = 2.dp)
    )
}

// ─────────────────────────────────────────────────────────────
// Previews
// ─────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF0D1117, name = "Connected – GPS Fix")
@Composable
private fun PreviewConnectedGps() {
    HypermachHYITheme {
        DashboardContent(
            isConnected   = true,
            telemetry     = TelemetryData(
                teamId = 7, packetCount = 214, altitude = 847.34f,
                gpsLatitude = 41.0082f, gpsLongitude = 28.9784f,
                gpsAltitude = 831.2f,
                gyroX = 12.4f, gyroY = -3.1f, gyroZ = 0.8f,
                accelX = 0.021f, accelY = 9.814f, accelZ = -0.043f,
                angle = 11.5f,
            ),
            validPackets  = 214,
            badPackets    = 2,
            packetsPerSec = 1.0f,
            maxAltitude   = 912.01f,
            onConnect = {}, onDisconnect = {}, onTareAltitude = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117, name = "Connected – No GPS")
@Composable
private fun PreviewConnectedNoGps() {
    HypermachHYITheme {
        DashboardContent(
            isConnected  = true,
            telemetry    = TelemetryData(
                teamId = 7, packetCount = 5, altitude = 12.10f,
                gpsLatitude = 0f, gpsLongitude = 0f, gpsAltitude = 0f,
                gyroX = 0.1f, gyroY = -0.2f, gyroZ = 0.5f,
                accelX = 0.01f, accelY = 9.81f, accelZ = -0.05f,
                angle = 3.2f,
            ),
            validPackets  = 5,
            badPackets    = 0,
            packetsPerSec = 1.0f,
            maxAltitude   = 12.10f,
            onConnect = {}, onDisconnect = {}, onTareAltitude = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117, name = "Disconnected")
@Composable
private fun PreviewDisconnected() {
    HypermachHYITheme {
        DashboardContent(
            isConnected = false,
            telemetry   = null,
            onConnect = {}, onDisconnect = {}, onTareAltitude = {}
        )
    }
}