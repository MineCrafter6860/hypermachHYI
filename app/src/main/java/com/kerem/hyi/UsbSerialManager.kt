package com.kerem.hyi

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class UsbSerialManager(context: Context) {

    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: UsbSerialPort? = null

    // Class-level scope so it's properly cancelled on closePortSafely()
    private var ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readJob: Job? = null

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState

    private val _telemetryState = MutableStateFlow<TelemetryData?>(null)
    val telemetryState: StateFlow<TelemetryData?> = _telemetryState

    // Called for every packet attempt: (telemetryOrNull, isValid)
    // Set by MainActivity to drive session stats
    private var onPacketReceived: ((TelemetryData?, Boolean) -> Unit)? = null

    fun setOnPacketReceived(callback: (TelemetryData?, Boolean) -> Unit) {
        onPacketReceived = callback
    }

    fun connectDevice(device: UsbDevice) {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = availableDrivers.firstOrNull { it.device.deviceId == device.deviceId } ?: return
        if (driver.ports.isNotEmpty()) {
            openPort(driver.ports[0], device)
        }
    }

    private fun openPort(port: UsbSerialPort, device: UsbDevice) {
        val connection = usbManager.openDevice(device) ?: return
        serialPort = port
        serialPort?.open(connection)
        serialPort?.setParameters(19200, 8, 1, UsbSerialPort.PARITY_NONE)
        _connectionState.value = true
        startReading()
    }

    private fun startReading() {
        readJob?.cancel()
        readJob = ioScope.launch {
            val accumulationBuffer = ByteArray(TelemetryParser.PACKET_SIZE * 2)
            var bytesInAccumulator = 0
            val readBuffer = ByteArray(4096)

            while (isActive) {
                try {
                    val len = serialPort?.read(readBuffer, 100) ?: 0
                    if (len <= 0) continue

                    // Process incoming data by moving it through the accumulator
                    for (i in 0 until len) {
                        accumulationBuffer[bytesInAccumulator++] = readBuffer[i]

                        // Try to find a header once we have enough bytes
                        while (bytesInAccumulator >= TelemetryParser.PACKET_SIZE) {
                            // Check if the current window starts with the header
                            // Header: 0xFF, 0xFF, 0x54, 0x52
                            if (accumulationBuffer[0] == 0xFF.toByte() && 
                                accumulationBuffer[1] == 0xFF.toByte() &&
                                accumulationBuffer[2] == 0x54.toByte() && 
                                accumulationBuffer[3] == 0x52.toByte()) {
                                
                                val telemetry = TelemetryParser.parse(accumulationBuffer, 0)
                                if (telemetry != null) {
                                    _telemetryState.value = telemetry
                                    onPacketReceived?.invoke(telemetry, true)
                                } else {
                                    onPacketReceived?.invoke(null, false)
                                }
                                
                                // Shift buffer left by PACKET_SIZE
                                System.arraycopy(accumulationBuffer, TelemetryParser.PACKET_SIZE, accumulationBuffer, 0, bytesInAccumulator - TelemetryParser.PACKET_SIZE)
                                bytesInAccumulator -= TelemetryParser.PACKET_SIZE
                            } else {
                                // Not a header at the start, shift by 1 and continue searching
                                System.arraycopy(accumulationBuffer, 1, accumulationBuffer, 0, bytesInAccumulator - 1)
                                bytesInAccumulator--
                            }
                        }
                    }
                } catch (e: Exception) {
                    closePortSafely()
                    break
                }
            }
        }
    }

    fun closePortSafely() {
        readJob?.cancel()
        readJob = null
        try { serialPort?.close() } catch (_: Exception) {}
        serialPort = null
        _connectionState.value = false

        // Replace the scope so the next connect gets a clean one
        ioScope.cancel()
        ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}