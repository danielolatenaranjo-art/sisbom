package com.sisbom.sisbomcrew.models

data class PersonItem(
    val idRegistro: String = "",
    val nombreBombero: String = "",
    val idRadial: String = "",
    val cargo: String = "",
    val compania: String = "",
    val activo: Boolean = true,
    val contrasena: String = "",
    val estadoBase: String = "",
    val permiso: Int = 0,
    val cds: Int = 0,
    val licenciaMedica: String? = null,
    val fechaSuspensionFin: String? = null,
    val foto: String = "",
    val ordenEnLista: Int = 999
)

enum class AttendanceStatus(val code: String, val label: String, val shortLabel: String, val colorHex: Long) {
    ASISTE("Asiste", "Asiste", "A", 0xFF10B981), // Verde
    FALTA("Falta", "Falta", "F", 0xFF64748B), // Slate
    CDS("CDS", "Canje Servicio", "CDS", 0xFF06B6D4), // Cyan
    PERMISO("Permiso", "Permiso", "P", 0xFFF59E0B), // Ambar
    LICENCIA("Licencia Médica", "Licencia Médica", "LM", 0xFFEC4899), // Magenta
    SUSPENDIDO("Suspendido", "Suspendido", "S", 0xFFEF4444) // Rojo
}

data class AttendanceSummary(
    val asisteCount: Int = 0,
    val cdsCount: Int = 0,
    val faltaCount: Int = 0,
    val permisoCount: Int = 0,
    val licenciaCount: Int = 0,
    val suspendidoCount: Int = 0,
    val totalDotacion: Int = 0
)

data class DespachoItem(
    val idServicio: String = "",
    val clave: String = "",
    val direccion: String = "",
    val fecha: String = "",
    val hora: String = "",
    val estado: String = ""
)

data class LicenseConfig(
    val key: String = "",
    val active: Boolean = false,
    val nombreCuerpo: String = "CUERPO DE BOMBEROS",
    val logoClienteUrl: String = "",
    val ciudad: String = "",
    val region: String = "",
    val expiresAt: String = ""
)
