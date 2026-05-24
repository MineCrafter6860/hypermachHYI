package com.kerem.hyi

import androidx.compose.runtime.Immutable
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Immutable
data class TelemetryData(
    val teamId: Int,
    val packetCount: Int,
    val altitude: Float,
    val gpsAltitude: Float,
    val gpsLatitude: Float,
    val gpsLongitude: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val angle: Float,
    val hasGpsFix: Boolean = gpsLatitude != 0.0f || gpsLongitude != 0.0f
)

object TelemetryParser {
    const val PACKET_SIZE = 78
    
    // Reuse a ByteBuffer to avoid allocations in the parsing loop
    private val threadLocalBuffer = ThreadLocal.withInitial {
        ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    }

    fun parse(buffer: ByteArray, offset: Int = 0): TelemetryData? {
        if (buffer.size - offset < PACKET_SIZE) return null

        // Validate Header (0xFF, 0xFF, 0x54, 0x52)
        if (buffer[offset + 0] != 0xFF.toByte() || buffer[offset + 1] != 0xFF.toByte() ||
            buffer[offset + 2] != 0x54.toByte() || buffer[offset + 3] != 0x52.toByte()) {
            return null
        }

        // Validate Footer (0x0D, 0x0A)
        if (buffer[offset + 76] != 0x0D.toByte() || buffer[offset + 77] != 0x0A.toByte()) {
            return null
        }

        // Validate Checksum (Sum of bytes 4 to 74 % 256)
        var sum = 0
        for (i in 4..74) {
            sum += buffer[offset + i].toInt() and 0xFF
        }
        if ((sum % 256) != (buffer[offset + 75].toInt() and 0xFF)) return null

        val bb = threadLocalBuffer.get() ?: return null
        bb.clear()
        bb.put(buffer, offset, PACKET_SIZE)
        
        return TelemetryData(
            teamId = buffer[offset + 4].toInt() and 0xFF,
            packetCount = buffer[offset + 5].toInt() and 0xFF,
            altitude = bb.getFloat(6),
            gpsAltitude = bb.getFloat(10),
            gpsLatitude = bb.getFloat(14),
            gpsLongitude = bb.getFloat(18),
            gyroX = bb.getFloat(46),
            gyroY = bb.getFloat(50),
            gyroZ = bb.getFloat(54),
            accelX = bb.getFloat(58),
            accelY = bb.getFloat(62),
            accelZ = bb.getFloat(66),
            angle = bb.getFloat(70)
        )
    }
}
