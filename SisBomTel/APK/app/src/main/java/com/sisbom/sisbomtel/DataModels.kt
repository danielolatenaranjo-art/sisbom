package com.sisbom.sisbomtel

data class CallItem(
    val id: String = "",
    val telefono: String = "",
    val fecha: String = "",
    val hora: String = "",
    val timestamp: Long = 0L,
    val estado: String = "ENTRANTE", // ENTRANTE, TIMBRANDO, ATENDIDA, FINALIZADA
    val nombre: String = "",
    val origen: String = "SisBomTel"
)

data class SmsQueueItem(
    val id: String = "",
    val idDespacho: String = "",
    val telefono: String = "",
    val mensaje: String = "",
    val enlace: String = "",
    val estado: String = "PENDIENTE", // PENDIENTE, ENVIADO, FALLIDO
    val timestamp: Long = 0L,
    val fecha: String = "",
    val hora: String = "",
    val canal: String = "sms",
    val error: String = ""
)

data class ComandanteAuth(
    val idRegistro: String = "",
    val nombre: String = "",
    val cargo: String = "",
    val idRadial: String = ""
)
