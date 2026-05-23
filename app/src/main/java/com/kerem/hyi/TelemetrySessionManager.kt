package com.kerem.hyi

import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.*

class TelemetrySessionManager {
    private val _sessionData = mutableListOf<TelemetryRecord>()
    
    data class TelemetryRecord(
        val timestamp: String,
        val data: TelemetryData,
    )

    fun addRecord(data: TelemetryData) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        _sessionData.add(TelemetryRecord(ts, data))
    }

    fun clearSession() {
        _sessionData.clear()
    }

    fun getCsvContent(): String {
        val header = "timestamp,teamId,packetCount,altitude_m,angle_deg," +
                "accelX_g,accelY_g,accelZ_g,gyroX_dps,gyroY_dps,gyroZ_dps," +
                "gpsLat,gpsLon,gpsAlt_m\n"
        
        val body = _sessionData.joinToString("\n") { record ->
            val t = record.data
            "${record.timestamp},${t.teamId},${t.packetCount}," +
            "${t.altitude},${t.angle}," +
            "${t.accelX},${t.accelY},${t.accelZ}," +
            "${t.gyroX},${t.gyroY},${t.gyroZ}," +
            "${t.gpsLatitude},${t.gpsLongitude},${t.gpsAltitude}"
        }
        
        return header + body
    }

    fun isSessionEmpty() = _sessionData.isEmpty()
}