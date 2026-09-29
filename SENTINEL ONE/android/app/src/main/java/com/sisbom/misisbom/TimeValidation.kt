package com.sisbom.misisbom

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeValidation {
    /**
     * Valida si un despacho o alerta tiene más de 5 minutos de antigüedad tomando como referencia
     * createdAt o la horaDespacho (y fechaDespacho).
     *
     * Retorna true si tiene más de 5 minutos (> 300.000 ms) o no se puede validar -> NO DEBE SONAR.
     * Retorna false si está dentro de los 5 minutos (<= 300.000 ms) -> PUEDE SONAR.
     */
    fun isTooOld(fechaStr: String, horaStr: String, createdAt: Long = 0L): Boolean {
        val now = System.currentTimeMillis()

        // 1. Si existe createdAt válido (timestamp numérico en ms)
        if (createdAt > 0L) {
            val diffMs = now - createdAt
            return diffMs > 300_000L || diffMs < -180_000L
        }

        val cleanHora = horaStr.trim()
            .replace("\"", "")
            .replace("'", "")
            .replace("hrs", "", ignoreCase = true)
            .replace("hr", "", ignoreCase = true)
            .replace(".", "")
            .trim()

        if (cleanHora.isEmpty()) return true

        try {
            val cleanFecha = fechaStr.trim()
                .replace("\"", "")
                .replace("'", "")
                .replace("/", "-")
            val todayStr = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date(now))
            val finalFecha = if (cleanFecha.isNotEmpty()) cleanFecha else todayStr

            val formatsToTry = listOf(
                "dd-MM-yyyy HH:mm:ss",
                "dd-MM-yyyy HH:mm",
                "dd-MM-yyyy H:mm:ss",
                "dd-MM-yyyy H:mm",
                "dd-MM-yyyy hh:mm:ss a",
                "dd-MM-yyyy hh:mm a",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd H:mm:ss",
                "yyyy-MM-dd H:mm"
            )

            var parsedDate: Date? = null
            for (fmt in formatsToTry) {
                try {
                    val sdf = SimpleDateFormat(fmt, Locale.getDefault())
                    sdf.isLenient = true
                    val d = sdf.parse("$finalFecha $cleanHora")
                    if (d != null) {
                        parsedDate = d
                        break
                    }
                } catch (_: Exception) {}
            }

            // Fallback: parsear hora con la fecha de hoy si la fecha guardada falló
            if (parsedDate == null) {
                for (fmt in listOf("dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm", "dd-MM-yyyy H:mm:ss", "dd-MM-yyyy H:mm", "dd-MM-yyyy hh:mm:ss a", "dd-MM-yyyy hh:mm a")) {
                    try {
                        val sdf = SimpleDateFormat(fmt, Locale.getDefault())
                        sdf.isLenient = true
                        val d = sdf.parse("$todayStr $cleanHora")
                        if (d != null) {
                            parsedDate = d
                            break
                        }
                    } catch (_: Exception) {}
                }
            }

            if (parsedDate == null) {
                // Si no se puede verificar la fecha de emisión, por seguridad NO debe sonar
                return true
            }

            val diffMs = now - parsedDate.time

            // 5 minutos exactos = 300.000 ms
            return diffMs > 300_000L || diffMs < -180_000L
        } catch (e: Exception) {
            e.printStackTrace()
            return true
        }
    }
}
