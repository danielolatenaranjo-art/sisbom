package com.sisbom.misisbom

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeValidation {
    fun isTooOld(fechaStr: String, horaStr: String): Boolean {
        if (fechaStr.isEmpty() || horaStr.isEmpty()) return false
        try {
            // Unify dates to dd-MM-yyyy format by changing slashes to dashes
            val cleanFecha = fechaStr.trim().replace("\"", "").replace("'", "").replace("/", "-")
            val cleanHora = horaStr.trim().replace("\"", "").replace("'", "")
            val dateStr = "$cleanFecha $cleanHora"
            
            // Choose format based on whether seconds are provided
            val formatStr = if (cleanHora.count { it == ':' } == 2) {
                "dd-MM-yyyy HH:mm:ss"
            } else {
                "dd-MM-yyyy HH:mm"
            }
            
            val sdf = SimpleDateFormat(formatStr, Locale.getDefault())
            val date = sdf.parse(dateStr) ?: return false
            
            val diffMs = System.currentTimeMillis() - date.time
            // 2 minutes in milliseconds = 120,000 ms
            return diffMs > 120000
        } catch (e: Exception) {
            e.printStackTrace()
            // On parse failure, default to false (not too old) to avoid missing critical alarms
            return false
        }
    }
}
