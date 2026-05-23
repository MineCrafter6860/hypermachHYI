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
            val ringBuffer = ByteArray(TelemetryParser.PACKET_SIZE)
            var bytesInBuffer = 0
            val readBuffer = ByteArray(2048)

            while (isActive) {
                try {
                    val len = serialPort?.read(readBuffer, 100) ?: 0
                    if (len > 0) {
                        for (i in 0 until len) {
                            // Slide current byte into the buffer
                            if (bytesInBuffer < TelemetryParser.PACKET_SIZE) {
                                ringBuffer[bytesInBuffer++] = readBuffer[i]
                            } else {
                                System.arraycopy(ringBuffer, 1, ringBuffer, 0, TelemetryParser.PACKET_SIZE - 1)
                                ringBuffer[TelemetryParser.PACKET_SIZE - 1] = readBuffer[i]
                            }

                            // Look for the 4-byte header: 0xFFFF5452
                            if (bytesInBuffer == TelemetryParser.PACKET_SIZE) {
                                if (ringBuffer[0] == 0xFF.toByte() && ringBuffer[1] == 0xFF.toByte() &&
                                    ringBuffer[2] == 0x54.toByte() && ringBuffer[3] == 0x52.toByte()) {
                                    
                                    val telemetry = TelemetryParser.parse(ringBuffer)
                                    if (telemetry != null) {
                                        _telemetryState.value = telemetry
                                        onPacketReceived?.invoke(telemetry, true)
                                        bytesInBuffer = 0 // Valid packet consumed, start fresh for next
                                    } else {
                                        // Header matched but parsing (checksum/footer) failed
                                        onPacketReceived?.invoke(null, false)
                                    }
                                }
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