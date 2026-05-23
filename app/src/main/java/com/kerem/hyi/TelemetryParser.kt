package com.kerem.hyi

import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    fun parse(buffer: ByteArray): TelemetryData? {
        if (buffer.size != PACKET_SIZE) return null

        // Validate Header (0xFF, 0xFF, 0x54, 0x52)
        if (buffer[0] != 0xFF.toByte() || buffer[1] != 0xFF.toByte() ||
            buffer[2] != 0x54.toByte() || buffer[3] != 0x52.toByte()) {
            return null
        }

        // Validate Footer (0x0D, 0x0A)
        if (buffer[76] != 0x0D.toByte() || buffer[77] != 0x0A.toByte()) {
            return null
        }

        // Validate Checksum (Sum of bytes 4 to 74 % 256)
        var sum = 0
        for (i in 4..74) {
            sum += buffer[i].toInt() and 0xFF
        }
        if ((sum % 256) != (buffer[75].toInt() and 0xFF)) return null

        val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

        return TelemetryData(
            teamId = buffer[4].toInt() and 0xFF,
            packetCount = buffer[5].toInt() and 0xFF,
            altitude = byteBuffer.getFloat(6),
            gpsAltitude = byteBuffer.getFloat(10),
            gpsLatitude = byteBuffer.getFloat(14),
            gpsLongitude = byteBuffer.getFloat(18),
            gyroX = byteBuffer.getFloat(46),
            gyroY = byteBuffer.getFloat(50),
            gyroZ = byteBuffer.getFloat(54),
            accelX = byteBuffer.getFloat(58),
            accelY = byteBuffer.getFloat(62),
            accelZ = byteBuffer.getFloat(66),
            angle = byteBuffer.getFloat(70)
        )
    }
}