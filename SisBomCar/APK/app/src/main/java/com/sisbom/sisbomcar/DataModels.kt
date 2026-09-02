package com.sisbom.sisbomcar

data class GpsLocationData(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val speedKmH: Float = 0f,
    val heading: Float = 0f,
    val accuracy: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

data class PointOfInterest(
    val id: String = "",
    val nombre: String = "",
    val tipo: String = "grifo",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val detalle: String = "",
    val caudal: String = "",
    val direccion: String = ""
)

data class Cuartel(
    val id: String = "",
    val nombre: String = "",
    val compania: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val direccion: String = ""
)

data class BitacoraEntry(
    val id: String = "",
    val autor: String = "",
    val hora: String = "",
    val fecha: String = "",
    val texto: String = "",
    val vehicleId: String = "",
    val timestamp: Long = 0L
)

data class BitacoraTrip(
    val idSalida: String = "",
    val idServicio: String = "",
    val carro: String = "",
    val clave: String = "",
    val lugar: String = "",
    val preInforme: String = "",
    val informe63: String = "",
    val observacion: String = "",
    val conductor: String = "",
    val obac: String = "",
    val cuantosBomberos: String = "0",
    val tripulantes: String = "0",
    val tripulantesNombres: String = "",
    val tripulantesDetalle: List<PersonItem> = emptyList(),
    val hora60: String = "",
    val fecha60: String = "",
    val hora63: String = "",
    val fecha63: String = "",
    val hora69: String = "",
    val fecha69: String = "",
    val hora610: String = "",
    val fecha610: String = "",
    val hora68: String = "",
    val fecha68: String = "",
    val km: String = "",
    val estadoMovil: String = "",
    val timestamp: Long = 0L
)

data class Vehicle(
    val idCarro: String = "",
    val clave: String = "",
    val patente: String = "",
    val tipo: String = "",
    val compania: String = "",
    val estado: String = "0-8",
    val enServicio: String = "0",
    val conductor: String = "",
    val obac: String = "",
    val tripulacion: List<String> = emptyList(),
    val numTripulantes: Int = 0,
    val notas: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val speed: Float = 0f,
    val heading: Float = 0f,
    val lastUpdate: String = ""
)

data class ComandanteAuth(
    val idRegistro: String = "",
    val nombre: String = "",
    val cargo: String = "",
    val idRadial: String = ""
)

data class Dispatch(
    val idServicio: String = "",
    val clave: String = "",
    val claveApoyo: String = "",
    val lugar: String = "",
    val preinforme: String = "",
    val carros: String = "",
    val horaDespacho: String = "",
    val fechaDespacho: String = "",
    val hora67: String = "",
    val quienDespacha: String = "",
    val operadorFinal: String = "",
    val obacGeneral: String = "",
    val unidades: Map<String, Map<String, Any>> = emptyMap(),
    val bitacora: List<BitacoraEntry> = emptyList(),
    val solicitarConfirmacion: Boolean = false,
    val estado: String = "",
    val lat: Double? = null,
    val lng: Double? = null
)

data class AppInfo(
    val label: String,
    val packageName: String,
    val activityName: String
)
