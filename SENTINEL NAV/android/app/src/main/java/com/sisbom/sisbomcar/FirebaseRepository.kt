package com.sisbom.sisbomcar

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class FirebaseRepository {
    private val db: FirebaseFirestore?
        get() = try {
            FirebaseFirestore.getInstance()
        } catch (_: Exception) {
            null
        }

    // 1. Escuchar lista de vehículos en tiempo real
    fun getVehiclesFlow(): Flow<List<Vehicle>> = callbackFlow {
        val firestore = db
        if (firestore == null) {
            trySend(emptyList())
            awaitClose {}
            return@callbackFlow
        }
        try {
            val listener = firestore.collection("vehiculos")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val list = snapshot?.documents?.mapNotNull { doc ->
                        mapToVehicle(doc)
                    } ?: emptyList()
                    trySend(list)
                }
            awaitClose { listener.remove() }
        } catch (e: Exception) {
            trySend(emptyList())
            awaitClose {}
        }
    }

    // 2. Escuchar un único vehículo seleccionado
    fun getVehicleSelfFlow(vehicleId: String): Flow<Vehicle?> = callbackFlow {
        if (vehicleId.isEmpty()) {
            trySend(null)
            awaitClose {}
            return@callbackFlow
        }
        val firestore = db
        if (firestore == null) {
            trySend(null)
            awaitClose {}
            return@callbackFlow
        }
        try {
            val listener = firestore.collection("vehiculos").document(vehicleId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(null)
                        return@addSnapshotListener
                    }
                    val vehicle = snapshot?.let { mapToVehicle(it) }
                    trySend(vehicle)
                }
            awaitClose { listener.remove() }
        } catch (e: Exception) {
            trySend(null)
            awaitClose {}
        }
    }

    // 3. Escuchar despachos activos en tiempo real
    fun getDispatchesFlow(): Flow<List<Dispatch>> = callbackFlow {
        val firestore = db
        if (firestore == null) {
            trySend(emptyList())
            awaitClose {}
            return@callbackFlow
        }
        try {
            val listener = firestore.collection("despachos")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val list = snapshot?.documents?.mapNotNull { doc ->
                        mapToDispatch(doc)
                    }?.filter { d ->
                        val st = d.estado.trim().lowercase()
                        val isFinalized = (st == "finalizada" || st == "finalizado" || st == "cancelada" || st == "cancelado") && d.operadorFinal.isNotEmpty()
                        !isFinalized
                    } ?: emptyList()
                    trySend(list)
                }
            awaitClose { listener.remove() }
        } catch (e: Exception) {
            trySend(emptyList())
            awaitClose {}
        }
    }

    // 3.1 Obtener despacho específico por ID
    fun getDispatchById(dispatchId: String, onResult: (Dispatch?) -> Unit) {
        val firestore = db ?: run { onResult(null); return }
        if (dispatchId.isBlank()) { onResult(null); return }
        val cleanId = dispatchId.trim()
        firestore.collection("despachos").document(cleanId)
            .get()
            .addOnSuccessListener { doc ->
                if (doc != null && doc.exists()) {
                    onResult(mapToDispatch(doc))
                } else {
                    firestore.collection("despachos").whereEqualTo("id", cleanId).limit(1).get()
                        .addOnSuccessListener { querySnap ->
                            val match = querySnap.documents.firstOrNull()?.let { mapToDispatch(it) }
                            onResult(match)
                        }
                        .addOnFailureListener { onResult(null) }
                }
            }
            .addOnFailureListener { onResult(null) }
    }

    // 3.2 Escuchar geolocalización en vivo del alertante
    fun getAlertanteLocationFlow(dispatchId: String): Flow<Pair<Double, Double>?> = callbackFlow {
        if (dispatchId.isBlank()) {
            trySend(null)
            awaitClose {}
            return@callbackFlow
        }
        val firestore = db
        if (firestore == null) {
            trySend(null)
            awaitClose {}
            return@callbackFlow
        }
        try {
            val cleanId = dispatchId.trim()
            val listener = firestore.collection("geolocalizaciones_alertantes").document(cleanId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null || !snapshot.exists()) {
                        trySend(null)
                        return@addSnapshotListener
                    }
                    val lat = (snapshot.get("lat") ?: snapshot.get("latitude"))?.let {
                        when (it) {
                            is Number -> it.toDouble()
                            is String -> it.toDoubleOrNull()
                            else -> null
                        }
                    }
                    val lng = (snapshot.get("lng") ?: snapshot.get("longitude"))?.let {
                        when (it) {
                            is Number -> it.toDouble()
                            is String -> it.toDoubleOrNull()
                            else -> null
                        }
                    }
                    val isDisconnected = (snapshot.get("desconectado") as? Boolean) == true
                    if (lat != null && lng != null && lat != 0.0 && lng != 0.0 && !isDisconnected) {
                        trySend(Pair(lat, lng))
                    } else {
                        trySend(null)
                    }
                }
            awaitClose { listener.remove() }
        } catch (e: Exception) {
            trySend(null)
            awaitClose {}
        }
    }

    // 4. Agregar entrada a la bitácora del OBAC
    fun addBitacoraEntry(
        dispatchId: String,
        author: String,
        text: String,
        vehicleId: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val firestore = db ?: return
        if (dispatchId.isEmpty() || text.trim().isEmpty()) return
        val timeNow = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val dateNow = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date())

        val entry = hashMapOf<String, Any>(
            "autor" to author,
            "hora" to timeNow,
            "fecha" to dateNow,
            "texto" to text.trim(),
            "vehicleId" to vehicleId,
            "timestamp" to System.currentTimeMillis()
        )

        firestore.collection("despachos").document(dispatchId)
            .get()
            .addOnSuccessListener { doc ->
                val currentBitacora = (doc.get("bitacora") as? List<Map<String, Any>>)?.toMutableList() ?: mutableListOf()
                currentBitacora.add(entry)
                firestore.collection("despachos").document(dispatchId)
                    .update("bitacora", currentBitacora)
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener { err -> onFailure(err) }
            }
            .addOnFailureListener { err -> onFailure(err) }
    }

    // 5. Actualizar estado del vehículo (0-8 Fuera de Servicio / 0-9 Disponible)
    fun updateVehicleStatus(
        vehicleId: String,
        newStatus: String,
        enServicio: String = "",
        notas: String = "",
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val firestore = db ?: return
        if (vehicleId.isEmpty()) return
        val timeNow = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault()).format(Date())

        val cleanStatus = if (newStatus == "0" || newStatus == "0-8" || newStatus == "08" || newStatus.equals("false", ignoreCase = true)) "0" else "1"
        val updates = hashMapOf<String, Any>(
            "estado" to cleanStatus,
            "lastUpdate" to timeNow
        )
        if (enServicio.isNotEmpty()) {
            updates["enServicio"] = enServicio
        }
        if (notas.isNotEmpty()) {
            val upperNotas = notas.trim().uppercase(Locale.getDefault())
            updates["notas"] = upperNotas
            updates["Observacion"] = upperNotas
        } else if (cleanStatus == "1") {
            updates["notas"] = ""
            updates["Observacion"] = ""
        }

        firestore.collection("vehiculos").document(vehicleId)
            .set(updates, SetOptions.merge())
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { err -> onFailure(err) }
    }

    // 5.0 Obtener correlativo de bitácora
    fun getNextBitacoraId(onResult: (String) -> Unit) {
        val firestore = db ?: run {
            onResult("1")
            return
        }
        firestore.collection("bitacora").get()
            .addOnSuccessListener { snapshot ->
                var maxId = 0
                for (doc in snapshot.documents) {
                    val docIdNum = doc.id.toIntOrNull()
                    val idSalidaNum = doc.getString("idSalida")?.toIntOrNull()
                    val candidate = docIdNum ?: idSalidaNum
                    if (candidate != null && candidate > maxId) {
                        maxId = candidate
                    }
                }
                onResult((maxId + 1).toString())
            }
            .addOnFailureListener {
                onResult("1")
            }
    }

    // 5.1 Registrar salidas especiales (6-13 Trámites / 6-14 Combustible) con estructura canónica
    fun registerSpecialExit(
        vehicleId: String,
        type: String,
        lugar: String,
        motivo: String,
        conductor: String,
        obac: String,
        tripulantesCount: String,
        tripulantesList: List<PersonItem> = emptyList(),
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val firestore = db ?: return
        if (vehicleId.isEmpty()) return
        val dateNow = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date())
        val timeNow = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        getNextBitacoraId { nextId ->
            val tripulantesNombres = tripulantesList.joinToString(", ") { "${it.idRadial} ${it.nombreBombero}".trim() }
            val tripulantesDetalleMaps = tripulantesList.map { p ->
                mapOf(
                    "idRegistro" to p.idRegistro,
                    "idRadial" to p.idRadial,
                    "nombre" to p.nombreBombero,
                    "compania" to p.compania,
                    "cargo" to p.cargo
                )
            }

            val logEntry = hashMapOf<String, Any>(
                "idSalida" to nextId,
                "id" to nextId,
                "ID" to nextId,
                "idRegistro" to nextId,
                "idServicio" to "",
                "idCarro" to vehicleId,
                "carro" to vehicleId,
                "clave" to type,
                "lugar" to lugar,
                "preInforme" to motivo,
                "informe63" to "",
                "observacion" to "",
                "conductor" to conductor,
                "obac" to obac,
                "cuantosBomberos" to tripulantesCount.ifEmpty { "0" },
                "tripulantes" to tripulantesCount.ifEmpty { "0" },
                "tripulantesNombres" to tripulantesNombres,
                "tripulantesDetalle" to tripulantesDetalleMaps,
                "fecha60" to dateNow,
                "hora60" to timeNow,
                "fecha63" to "", "hora63" to "",
                "fecha69" to "", "hora69" to "",
                "fecha610" to "", "hora610" to "",
                "fecha68" to "", "hora68" to "",
                "estadoMovil" to "en servicio",
                "km" to "",
                "timestamp" to System.currentTimeMillis()
            )

            firestore.collection("bitacora").document(nextId).set(logEntry)
                .addOnSuccessListener {
                    // Guardar subcolección de tripulantes si aplica
                    tripulantesList.forEach { p ->
                        if (p.idRegistro.isNotEmpty()) {
                            val subMap = mapOf(
                                "idRegistro" to p.idRegistro,
                                "idRadial" to p.idRadial,
                                "nombre" to p.nombreBombero,
                                "compania" to p.compania,
                                "cargo" to p.cargo,
                                "timestamp" to System.currentTimeMillis()
                            )
                            firestore.collection("bitacora").document(nextId)
                                .collection("tripulantes").document(p.idRegistro)
                                .set(subMap)
                        }
                    }

                    // Actualizar vehiculos (solo estado operativo y servicio)
                    val vehUpdates = hashMapOf<String, Any>(
                        "estado" to "1",
                        "enServicio" to type,
                        "notas" to motivo,
                        "lugar" to lugar,
                        "lastUpdate" to "$dateNow $timeNow"
                    )
                    firestore.collection("vehiculos").document(vehicleId)
                        .set(vehUpdates, SetOptions.merge())

                    onSuccess()
                }
                .addOnFailureListener { err -> onFailure(err) }
        }
    }

    // 5.2 Escuchar salida activa en Bitácora para el vehículo
    fun getActiveBitacoraFlow(vehicleId: String): Flow<BitacoraTrip?> = callbackFlow {
        val firestore = db
        if (firestore == null || vehicleId.isEmpty()) {
            trySend(null)
            awaitClose {}
            return@callbackFlow
        }

        val cleanVeh = vehicleId.replace("-", "").trim().uppercase()
        val listener = firestore.collection("bitacora")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val matches = snapshot.documents.mapNotNull { doc ->
                        val bCarro = (doc.getString("carro") ?: doc.getString("idCarro") ?: "").replace("-", "").trim().uppercase()
                        val hora68 = doc.getString("hora68") ?: ""
                        val fecha68 = doc.getString("fecha68") ?: ""
                        val estadoMovil = (doc.getString("estadoMovil") ?: "").trim().lowercase()
                        val fecha60 = doc.getString("fecha60") ?: ""
                        val dateToday = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date())
                        val isToday = fecha60.isEmpty() || fecha60 == dateToday

                        val isSameVeh = bCarro.isNotEmpty() && (bCarro == cleanVeh || cleanVeh.contains(bCarro) || bCarro.contains(cleanVeh))
                        val isActive = isToday && hora68.isEmpty() && fecha68.isEmpty() && estadoMovil != "en cuartel" && estadoMovil != "6-8"

                        if (isSameVeh && isActive) {
                            val tDetalleRaw = doc.get("tripulantesDetalle") as? List<*>
                            val tDetalleList = mutableListOf<PersonItem>()
                            tDetalleRaw?.forEach { item ->
                                if (item is Map<*, *>) {
                                    tDetalleList.add(
                                        PersonItem(
                                            idRegistro = item["idRegistro"]?.toString() ?: "",
                                            idRadial = item["idRadial"]?.toString() ?: "",
                                            nombreBombero = (item["nombre"] ?: item["nombreBombero"])?.toString() ?: "",
                                            compania = item["compania"]?.toString() ?: "",
                                            cargo = item["cargo"]?.toString() ?: ""
                                        )
                                    )
                                }
                            }

                            BitacoraTrip(
                                idSalida = doc.getString("idSalida") ?: doc.id,
                                idServicio = doc.getString("idServicio") ?: "",
                                carro = doc.getString("carro") ?: doc.getString("idCarro") ?: vehicleId,
                                clave = doc.getString("clave") ?: "",
                                lugar = doc.getString("lugar") ?: "",
                                preInforme = doc.getString("preInforme") ?: "",
                                informe63 = doc.getString("informe63") ?: "",
                                observacion = doc.getString("observacion") ?: "",
                                conductor = doc.getString("conductor") ?: doc.getString("conductor60") ?: "",
                                obac = doc.getString("obac") ?: doc.getString("obac60") ?: "",
                                cuantosBomberos = doc.getString("cuantosBomberos") ?: doc.getString("tripulacion60") ?: "0",
                                tripulantes = doc.getString("tripulantes") ?: "0",
                                tripulantesNombres = doc.getString("tripulantesNombres") ?: "",
                                tripulantesDetalle = tDetalleList,
                                hora60 = doc.getString("hora60") ?: "",
                                fecha60 = doc.getString("fecha60") ?: "",
                                hora63 = doc.getString("hora63") ?: "",
                                fecha63 = doc.getString("fecha63") ?: "",
                                hora69 = doc.getString("hora69") ?: "",
                                fecha69 = doc.getString("fecha69") ?: "",
                                hora610 = doc.getString("hora610") ?: "",
                                fecha610 = doc.getString("fecha610") ?: "",
                                hora68 = doc.getString("hora68") ?: "",
                                fecha68 = doc.getString("fecha68") ?: "",
                                km = doc.getString("km") ?: "",
                                estadoMovil = doc.getString("estadoMovil") ?: "",
                                timestamp = doc.getLong("timestamp") ?: 0L
                            )
                        } else null
                    }

                    val latestActive = matches.maxByOrNull { it.idSalida.toIntOrNull() ?: 0 }
                    trySend(latestActive)
                }
            }
        awaitClose { listener.remove() }
    }

    // 6. Registrar hitos operativos (6-0, 6-3, 6-15, 6-13, 6-9, 6-10, 6-8)
    fun recordDispatchMilestone(
        dispatchId: String,
        vehicleId: String,
        milestoneKey: String,
        kmValue: String = "",
        bitacoraId: String = "",
        clave: String = "",
        lugar: String = "",
        preinforme: String = "",
        conductor: String = "",
        obac: String = "",
        tripulantesList: List<PersonItem> = emptyList(),
        destinoSalud: String = "",
        destinoSaludLat: Double = 0.0,
        destinoSaludLng: Double = 0.0,
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val firestore = db ?: return
        if (vehicleId.isEmpty()) return
        val timeNow = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val dateNow = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date())
        val timestampField = "${milestoneKey}Timestamp"
        val cleanVeh = vehicleId.replace("-", "").trim().uppercase()

        // 1. Preparar campos para actualizar/crear en mapa anidado de unidades en despacho
        val unitData = hashMapOf<String, Any>(
            milestoneKey to timeNow,
            timestampField to System.currentTimeMillis()
        )
        if (kmValue.isNotBlank()) {
            unitData["km"] = kmValue
        }
        when (milestoneKey) {
            "salida60At" -> {
                unitData["status"] = "6-0"
                unitData["estado"] = "en_trayecto"
                unitData["hora60"] = timeNow
                unitData["salida60At"] = timeNow
                unitData["horaSalida"] = timeNow
            }
            "llegada63At" -> {
                unitData["status"] = "6-3"
                unitData["estado"] = "en_lugar"
                unitData["hora63"] = timeNow
                unitData["llegada63At"] = timeNow
                unitData["horaLlegada"] = timeNow
            }
            "traslado615At" -> {
                unitData["status"] = "6-15"
                unitData["estado"] = "6-15"
                unitData["is615"] = true
                unitData["hora615"] = timeNow
                unitData["traslado615At"] = timeNow
                if (destinoSalud.isNotEmpty()) {
                    unitData["destinoSalud"] = destinoSalud
                    unitData["lugarSalud"] = destinoSalud
                    unitData["destinoSaludLat"] = destinoSaludLat
                    unitData["destinoSaludLng"] = destinoSaludLng
                }
            }
            "llegada63SaludAt" -> {
                unitData["status"] = "6-3"
                unitData["estado"] = "en_lugar"
                unitData["is615"] = true
                unitData["hora63Salud"] = timeNow
                unitData["llegada63SaludAt"] = timeNow
            }
            "retorno69At" -> {
                unitData["status"] = "6-9"
                unitData["estado"] = "retorno"
                unitData["is615"] = false
                unitData["hora69"] = timeNow
                unitData["retorno69At"] = timeNow
            }
            "llegadaCuartel610At", "llegada610At" -> {
                unitData["status"] = "6-10"
                unitData["estado"] = "en_cuartel"
                unitData["is615"] = false
                unitData["hora610"] = timeNow
                unitData["llegada610At"] = timeNow
            }
            "disponible68At" -> {
                unitData["status"] = "6-8"
                unitData["estado"] = "finalizado"
                unitData["is615"] = false
                unitData["hora68"] = timeNow
                unitData["disponible68At"] = timeNow
            }
        }

        // 2. Función para escribir despacho con mapa anidado canonical (nunca dot keys en SetOptions.merge)
        fun executeDispatchUpdate(bId: String) {
            if (bId.isNotEmpty()) {
                unitData["bitacoraId"] = bId
                unitData["idSalida"] = bId
            }
            if (dispatchId.isNotEmpty()) {
                val nestedDispatch = hashMapOf<String, Any>(
                    "unidades" to hashMapOf<String, Any>(
                        vehicleId to unitData
                    )
                )
                firestore.collection("despachos").document(dispatchId)
                    .set(nestedDispatch, SetOptions.merge())
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener { err -> onFailure(err) }
            } else {
                onSuccess()
            }
        }

        // 3. Helper para actualizar documento de Bitácora existente
        fun updateBitacoraDoc(bDocId: String, extraUpdates: Map<String, Any>) {
            if (bDocId.isNotEmpty()) {
                firestore.collection("bitacora").document(bDocId).set(extraUpdates, SetOptions.merge())
            } else {
                firestore.collection("bitacora").get().addOnSuccessListener { bSnap ->
                    val matches = bSnap.documents.filter { bDoc ->
                        val bCarro = (bDoc.getString("carro") ?: bDoc.getString("idCarro") ?: "").replace("-", "").trim().uppercase()
                        val h68 = bDoc.getString("hora68") ?: ""
                        val f68 = bDoc.getString("fecha68") ?: ""
                        val sameServicio = dispatchId.isNotEmpty() && bDoc.getString("idServicio") == dispatchId
                        val isVehMatch = bCarro.isNotEmpty() && (bCarro == cleanVeh || cleanVeh.contains(bCarro) || bCarro.contains(cleanVeh))
                        (sameServicio || isVehMatch) && (h68.isEmpty() && f68.isEmpty())
                    }
                    matches.forEach { bDoc ->
                        bDoc.reference.set(extraUpdates, SetOptions.merge())
                    }
                }
            }
        }

        when (milestoneKey) {
            "salida60At" -> {
                fun createNewBitacoraEntry() {
                    getNextBitacoraId { nextId ->
                        val tripNombres = tripulantesList.joinToString(", ") { "${it.idRadial} ${it.nombreBombero}".trim() }
                        val tripMaps = tripulantesList.map { p ->
                            mapOf(
                                "idRegistro" to p.idRegistro,
                                "idRadial" to p.idRadial,
                                "nombre" to p.nombreBombero,
                                "compania" to p.compania,
                                "cargo" to p.cargo
                            )
                        }
                        val logData = hashMapOf<String, Any>(
                            "idSalida" to nextId,
                            "id" to nextId,
                            "ID" to nextId,
                            "idRegistro" to nextId,
                            "idServicio" to dispatchId,
                            "carro" to vehicleId,
                            "clave" to clave,
                            "lugar" to lugar,
                            "preInforme" to preinforme,
                            "informe63" to "",
                            "observacion" to "",
                            "conductor" to conductor,
                            "obac" to obac,
                            "cuantosBomberos" to if (tripulantesList.isNotEmpty()) tripulantesList.size.toString() else "0",
                            "tripulantes" to if (tripulantesList.isNotEmpty()) tripulantesList.size.toString() else "0",
                            "tripulantesNombres" to tripNombres,
                            "tripulantesDetalle" to tripMaps,
                            "fecha60" to dateNow,
                            "hora60" to timeNow,
                            "fecha63" to "", "hora63" to "",
                            "fecha69" to "", "hora69" to "",
                            "fecha610" to "", "hora610" to "",
                            "fecha68" to "", "hora68" to "",
                            "estadoMovil" to "en trayecto",
                            "km" to "",
                            "timestamp" to System.currentTimeMillis()
                        )
                        firestore.collection("bitacora").document(nextId).set(logData)
                            .addOnSuccessListener {
                                tripulantesList.forEach { p ->
                                    if (p.idRegistro.isNotEmpty()) {
                                        val subMap = mapOf(
                                            "idRegistro" to p.idRegistro,
                                            "idRadial" to p.idRadial,
                                            "nombre" to p.nombreBombero,
                                            "compania" to p.compania,
                                            "cargo" to p.cargo,
                                            "timestamp" to System.currentTimeMillis()
                                        )
                                        firestore.collection("bitacora").document(nextId)
                                            .collection("tripulantes").document(p.idRegistro)
                                            .set(subMap)
                                    }
                                }
                                executeDispatchUpdate(nextId)
                            }
                            .addOnFailureListener {
                                executeDispatchUpdate("")
                            }
                    }
                }

                val bitUpdates = hashMapOf<String, Any>(
                    "hora60" to timeNow,
                    "fecha60" to dateNow,
                    "estadoMovil" to "en trayecto"
                )
                if (conductor.isNotEmpty()) bitUpdates["conductor"] = conductor
                if (obac.isNotEmpty()) bitUpdates["obac"] = obac

                if (bitacoraId.isNotEmpty()) {
                    firestore.collection("bitacora").document(bitacoraId).set(bitUpdates, SetOptions.merge())
                    executeDispatchUpdate(bitacoraId)
                } else {
                    firestore.collection("bitacora").get().addOnSuccessListener { bSnap ->
                        val matches = bSnap.documents.filter { bDoc ->
                            val bCarro = (bDoc.getString("carro") ?: bDoc.getString("idCarro") ?: "").replace("-", "").trim().uppercase()
                            val h68 = bDoc.getString("hora68") ?: ""
                            val f68 = bDoc.getString("fecha68") ?: ""
                            val sameServicio = dispatchId.isNotEmpty() && bDoc.getString("idServicio") == dispatchId
                            val isVehMatch = bCarro.isNotEmpty() && (bCarro == cleanVeh || cleanVeh.contains(bCarro) || bCarro.contains(cleanVeh))
                            (sameServicio || isVehMatch) && (h68.isEmpty() && f68.isEmpty())
                        }
                        if (matches.isNotEmpty()) {
                            val activeDoc = matches.first()
                            activeDoc.reference.set(bitUpdates, SetOptions.merge())
                            executeDispatchUpdate(activeDoc.id)
                        } else {
                            createNewBitacoraEntry()
                        }
                    }.addOnFailureListener {
                        createNewBitacoraEntry()
                    }
                }
                updateVehicleStatus(vehicleId, "6-0", dispatchId)
            }
            "llegada63At" -> {
                val bitUpdates = hashMapOf<String, Any>(
                    "hora63" to timeNow,
                    "fecha63" to dateNow,
                    "estadoMovil" to "en el lugar"
                )
                updateBitacoraDoc(bitacoraId, bitUpdates)
                updateVehicleStatus(vehicleId, "6-3", dispatchId)
                executeDispatchUpdate(bitacoraId)
            }
            "traslado615At" -> {
                val obsText = if (destinoSalud.isNotEmpty()) "TRASLADO A $destinoSalud (6-15)" else "TRASLADO CENTRO ASISTENCIAL (6-15)"
                val bitUpdates = hashMapOf<String, Any>(
                    "observacion" to obsText,
                    "estadoMovil" to "traslado salud"
                )
                if (destinoSalud.isNotEmpty()) {
                    bitUpdates["destinoSalud"] = destinoSalud
                }
                updateBitacoraDoc(bitacoraId, bitUpdates)
                updateVehicleStatus(
                    vehicleId = vehicleId,
                    newStatus = "6-15",
                    enServicio = dispatchId,
                    notas = if (destinoSalud.isNotEmpty()) "6-15 $destinoSalud" else "6-15"
                )
                executeDispatchUpdate(bitacoraId)
            }
            "llegada63SaludAt" -> {
                val bitUpdates = hashMapOf<String, Any>(
                    "observacion" to "LLEGADA CENTRO ASISTENCIAL (6-3 SALUD)",
                    "estadoMovil" to "en centro asistencial"
                )
                updateBitacoraDoc(bitacoraId, bitUpdates)
                updateVehicleStatus(vehicleId, "6-3", dispatchId)
                executeDispatchUpdate(bitacoraId)
            }
            "retornoEmergencia613At" -> {
                val bitUpdates = hashMapOf<String, Any>(
                    "observacion" to "6-13 RETORNO A OTRA EMERGENCIA"
                )
                updateBitacoraDoc(bitacoraId, bitUpdates)
                updateVehicleStatus(vehicleId, "6-13", dispatchId)
                executeDispatchUpdate(bitacoraId)
            }
            "retorno69At" -> {
                val bitUpdates = hashMapOf<String, Any>(
                    "hora69" to timeNow,
                    "fecha69" to dateNow,
                    "estadoMovil" to "retorno"
                )
                updateBitacoraDoc(bitacoraId, bitUpdates)
                updateVehicleStatus(vehicleId, "6-9", dispatchId)
                executeDispatchUpdate(bitacoraId)
            }
            "llegadaCuartel610At", "llegada610At" -> {
                val bitUpdates = hashMapOf<String, Any>(
                    "hora610" to timeNow,
                    "fecha610" to dateNow,
                    "estadoMovil" to "en cuartel"
                )
                updateBitacoraDoc(bitacoraId, bitUpdates)
                updateVehicleStatus(vehicleId, "6-10", dispatchId)
                executeDispatchUpdate(bitacoraId)
            }
            "disponible68At" -> {
                val bitUpdates = hashMapOf<String, Any>(
                    "hora68" to timeNow,
                    "fecha68" to dateNow,
                    "estadoMovil" to "en cuartel"
                )
                if (kmValue.isNotBlank()) bitUpdates["km"] = kmValue
                updateBitacoraDoc(bitacoraId, bitUpdates)

                // Actualizar vehículo a Disponible (estado = "1", enServicio = "0")
                val vehUpdates = hashMapOf<String, Any>(
                    "enServicio" to "0",
                    "estado" to "1",
                    "notas" to "",
                    "lugar" to "Cuartel",
                    "lastUpdate" to "$dateNow $timeNow"
                )
                if (kmValue.isNotBlank()) {
                    vehUpdates["kmActual"] = kmValue
                    vehUpdates["km"] = kmValue
                    vehUpdates["odometro"] = kmValue
                }
                firestore.collection("vehiculos").document(vehicleId).set(vehUpdates, SetOptions.merge())

                firestore.collection("vehiculos").get().addOnSuccessListener { vSnap ->
                    vSnap.documents.forEach { vDoc ->
                        val vId = vDoc.id.replace("-", "").trim().uppercase()
                        val cId = (vDoc.getString("idCarro") ?: vDoc.getString("carro") ?: "").replace("-", "").trim().uppercase()
                        if (vId == cleanVeh || cId == cleanVeh) {
                            vDoc.reference.set(vehUpdates, SetOptions.merge())
                        }
                    }
                }

                // Liberar personal vinculado a este despacho
                if (dispatchId.isNotEmpty()) {
                    firestore.collection("personal").whereEqualTo("enServicio", dispatchId).get()
                        .addOnSuccessListener { pSnap ->
                            pSnap.documents.forEach { pDoc ->
                                pDoc.reference.set(mapOf("enServicio" to "0"), SetOptions.merge())
                            }
                        }
                }
                executeDispatchUpdate(bitacoraId)
            }
            else -> {
                executeDispatchUpdate(bitacoraId)
            }
        }
    }

    // 7. Enviar telemetría GPS del carro a Firestore (sin speed)
    fun updateVehicleLocation(
        vehicleId: String,
        lat: Double,
        lng: Double,
        heading: Float
    ) {
        val firestore = db ?: return
        if (vehicleId.isEmpty() || lat == 0.0 || lng == 0.0) return
        val timeNow = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault()).format(Date())

        val geoPoint = com.google.firebase.firestore.GeoPoint(lat, lng)
        val data = hashMapOf<String, Any>(
            "lat" to lat,
            "lng" to lng,
            "heading" to heading,
            "lastUpdate" to timeNow,
            "posicionGps" to geoPoint,
            "solicitudGps" to false
        )

        firestore.collection("vehiculos").document(vehicleId)
            .set(data, SetOptions.merge())
    }

    fun clearGpsRequest(vehicleId: String) {
        val firestore = db ?: return
        if (vehicleId.isEmpty()) return
        firestore.collection("vehiculos").document(vehicleId)
            .set(mapOf("solicitudGps" to false), SetOptions.merge())
    }

    // 8. Verificar credenciales y cargo de Comandante (idRadial 1) para autorizar la tablet
    suspend fun verifyComandanteCredentials(
        idRegistro: String,
        pass: String
    ): Result<ComandanteAuth> = withContext(Dispatchers.IO) {
        try {
            val firestore = db ?: return@withContext Result.failure(Exception("Base de datos no inicializada."))
            val cleanId = idRegistro.trim()
            val cleanPass = pass.trim()

            if (cleanId.isEmpty() || cleanPass.isEmpty()) {
                return@withContext Result.failure(Exception("Debe ingresar ID de Registro y Contraseña"))
            }

            // Intento 1: Autenticación Firebase Auth si está disponible
            var authSuccessful = false
            try {
                val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
                val email = "$cleanId@sisbom.com"
                val securePass = "${cleanPass}_secure_sisbom"
                Tasks.await(
                    auth.signInWithEmailAndPassword(email, securePass),
                    3, TimeUnit.SECONDS
                )
                authSuccessful = (auth.currentUser != null)
            } catch (_: Exception) {}

            // Buscar documento en "personal", "usuarios" o "bomberos"
            var docSnap: DocumentSnapshot? = null
            val collections = listOf("personal", "usuarios", "bomberos")

            for (colName in collections) {
                if (docSnap != null && docSnap.exists()) break

                // A. Direct doc(cleanId)
                try {
                    val directSnap = Tasks.await(firestore.collection(colName).document(cleanId).get(), 4, TimeUnit.SECONDS)
                    if (directSnap.exists()) {
                        docSnap = directSnap
                        break
                    }
                } catch (_: Exception) {}

                // B. Query whereEqualTo("idRegistro", cleanId)
                try {
                    val qSnap = Tasks.await(
                        firestore.collection(colName).whereEqualTo("idRegistro", cleanId).get(),
                        4, TimeUnit.SECONDS
                    )
                    if (!qSnap.isEmpty) {
                        docSnap = qSnap.documents[0]
                        break
                    }
                } catch (_: Exception) {}

                // C. Query whereEqualTo("idRegistro", Number)
                cleanId.toLongOrNull()?.let { numId ->
                    try {
                        val qSnap = Tasks.await(
                            firestore.collection(colName).whereEqualTo("idRegistro", numId).get(),
                            4, TimeUnit.SECONDS
                        )
                        if (!qSnap.isEmpty) {
                            docSnap = qSnap.documents[0]
                        }
                    } catch (_: Exception) {}
                }
                if (docSnap != null && docSnap.exists()) break

                // D. Query whereEqualTo("idRadial", cleanId)
                try {
                    val qSnap = Tasks.await(
                        firestore.collection(colName).whereEqualTo("idRadial", cleanId).get(),
                        4, TimeUnit.SECONDS
                    )
                    if (!qSnap.isEmpty) {
                        docSnap = qSnap.documents[0]
                        break
                    }
                } catch (_: Exception) {}
            }

            if (docSnap == null || !docSnap.exists()) {
                return@withContext Result.failure(Exception("No se encontró el ID de Registro $cleanId en la base de datos."))
            }

            val rawPass = docSnap.getString("contrasena")
                ?: docSnap.get("contrasena")?.toString()
                ?: docSnap.getString("password")
                ?: docSnap.get("password")?.toString()
                ?: ""

            if (!authSuccessful && rawPass.isNotEmpty() && rawPass.trim() != cleanPass) {
                return@withContext Result.failure(Exception("Contraseña incorrecta."))
            }

            // Verificar si el usuario está activo
            val activoVal = docSnap.get("activo")
            val isActivo = when (activoVal) {
                is Boolean -> activoVal
                is Number -> activoVal.toInt() == 1
                is String -> activoVal.trim().uppercase() in listOf("SI", "1", "TRUE", "ACTIVO")
                else -> true
            }

            if (!isActivo) {
                return@withContext Result.failure(Exception("El usuario se encuentra inactivo en el sistema."))
            }

            val cargo = docSnap.getString("cargo") ?: docSnap.get("cargo")?.toString() ?: ""
            val idRadial = docSnap.getString("idRadial") ?: docSnap.get("idRadial")?.toString() ?: ""
            val autorizadoAdmin = docSnap.get("autorizadoAdmin")

            val isAdmin = when (autorizadoAdmin) {
                is Boolean -> autorizadoAdmin
                is Number -> autorizadoAdmin.toInt() == 1
                is String -> autorizadoAdmin.trim().uppercase() in listOf("1", "SI", "TRUE")
                else -> false
            }

            val isComandante = idRadial.trim() == "1" ||
                    cargo.contains("Comandante", ignoreCase = true) ||
                    cargo.contains("Comandancia", ignoreCase = true) ||
                    isAdmin

            if (!isComandante) {
                return@withContext Result.failure(Exception("El usuario ingresado ($cargo) no posee permisos de Comandante (idRadial 1)."))
            }

            val nombre = docSnap.getString("nombreBombero")
                ?: docSnap.getString("nombre")
                ?: docSnap.getString("nombreCompleto")
                ?: "Comandante Institucional"

            Result.success(
                ComandanteAuth(
                    idRegistro = cleanId,
                    nombre = nombre,
                    cargo = if (cargo.isNotEmpty()) cargo else "Comandante",
                    idRadial = if (idRadial.isNotEmpty()) idRadial else "1"
                )
            )
        } catch (e: Exception) {
            Result.failure(Exception(e.localizedMessage ?: "Error de autenticación"))
        }
    }

    // Mappers
    private fun mapToVehicle(doc: DocumentSnapshot): Vehicle? {
        return try {
            val tripulacionRaw = doc.get("tripulacion") ?: doc.get("tripulantes") ?: doc.get("personal")
            val tripulacionList = when (tripulacionRaw) {
                is List<*> -> tripulacionRaw.mapNotNull {
                    when (it) {
                        is String -> it
                        is Map<*, *> -> (it["nombre"] ?: it["idRadial"] ?: it["idRegistro"]) as? String
                        else -> null
                    }
                }
                is String -> tripulacionRaw.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }
                else -> emptyList()
            }

            val enServicioVal = doc.get("enServicio")?.toString() ?: "0"

            val claveVal = doc.getString("clave")
                ?: doc.getString("nombre")
                ?: doc.id

            val patenteVal = doc.getString("patente")
                ?: doc.getString("placa")
                ?: ""

            val tipoVal = doc.getString("tipo")
                ?: doc.getString("tipoCarro")
                ?: ""

            val speedVal = (doc.get("speed") as? Number)?.toFloat()
                ?: (doc.get("velocidad") as? Number)?.toFloat()
                ?: 0f

            val headingVal = (doc.get("heading") as? Number)?.toFloat()
                ?: (doc.get("rumbo") as? Number)?.toFloat()
                ?: 0f

            val (latVal, lngVal) = extractCoordinates(doc, "posicionGps", "geo", "ubicacionGps")

            val rawEstado = doc.get("estado")
            val cleanEstado = when (rawEstado) {
                is Boolean -> if (rawEstado) "1" else "0"
                is Number -> if (rawEstado.toLong() == 0L) "0" else "1"
                is String -> {
                    val s = rawEstado.trim().lowercase()
                    if (s == "0" || s == "0-8" || s == "08" || s == "false" || s == "fuera de servicio") "0" else "1"
                }
                else -> "1"
            }

            val condTs = (doc.get("solicitudConductorTimestamp") as? Number)?.toLong() ?: 0L
            val persTs = (doc.get("solicitudPersonalTimestamp") as? Number)?.toLong() ?: 0L
            val solGpsVal = doc.getBoolean("solicitudGps") ?: doc.getBoolean("solicitud_gps") ?: false

            Vehicle(
                idCarro = doc.id,
                clave = claveVal,
                patente = patenteVal,
                tipo = tipoVal,
                compania = doc.getString("compania") ?: doc.getString("cia") ?: "",
                estado = cleanEstado,
                enServicio = enServicioVal,
                conductor = doc.getString("conductor") ?: doc.getString("maquinista") ?: "",
                obac = doc.getString("obac") ?: doc.getString("aCargo") ?: "",
                tripulacion = tripulacionList,
                numTripulantes = (doc.get("numTripulantes") as? Number)?.toInt() ?: tripulacionList.size,
                notas = doc.getString("notas") ?: doc.getString("observacion") ?: doc.getString("Observacion") ?: "",
                lat = latVal,
                lng = lngVal,
                speed = speedVal,
                heading = headingVal,
                lastUpdate = doc.getString("lastUpdate") ?: "",
                solicitudConductorAt = doc.getString("solicitudConductorAt") ?: "",
                solicitudConductorTimestamp = condTs,
                solicitudPersonalAt = doc.getString("solicitudPersonalAt") ?: "",
                solicitudPersonalTimestamp = persTs,
                solicitudGps = solGpsVal
            )
        } catch (e: Exception) {
            android.util.Log.e("SisBomCar", "Error en mapToVehicle doc ${doc.id}: ${e.message}", e)
            null
        }
    }

    private fun extractCoordinates(doc: DocumentSnapshot, vararg fieldNames: String): Pair<Double?, Double?> {
        return extractCoordinates(doc, true, *fieldNames)
    }

    private fun extractCoordinates(doc: DocumentSnapshot, fallbackToTopLevel: Boolean = true, vararg fieldNames: String): Pair<Double?, Double?> {
        for (field in fieldNames) {
            val raw = doc.get(field) ?: continue
            when (raw) {
                is com.google.firebase.firestore.GeoPoint -> {
                    if (raw.latitude != 0.0 && raw.longitude != 0.0) {
                        return Pair(raw.latitude, raw.longitude)
                    }
                }
                is Map<*, *> -> {
                    val lat = (raw["lat"] ?: raw["latitude"] ?: raw["latitud"])?.let {
                        when (it) {
                            is Number -> it.toDouble()
                            is String -> it.toDoubleOrNull()
                            else -> null
                        }
                    }
                    val lng = (raw["lng"] ?: raw["longitude"] ?: raw["longitud"] ?: raw["lon"])?.let {
                        when (it) {
                            is Number -> it.toDouble()
                            is String -> it.toDoubleOrNull()
                            else -> null
                        }
                    }
                    if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
                        return Pair(lat, lng)
                    }
                }
                else -> {}
            }
        }

        if (!fallbackToTopLevel) {
            return Pair(null, null)
        }

        val topLat = (doc.get("lat") ?: doc.get("latitude") ?: doc.get("latitud"))?.let {
            when (it) {
                is Number -> it.toDouble()
                is String -> it.toDoubleOrNull()
                else -> null
            }
        }
        val topLng = (doc.get("lng") ?: doc.get("longitude") ?: doc.get("longitud") ?: doc.get("lon"))?.let {
            when (it) {
                is Number -> it.toDouble()
                is String -> it.toDoubleOrNull()
                else -> null
            }
        }
        return Pair(topLat, topLng)
    }

    private fun safeBoolean(doc: DocumentSnapshot, key: String): Boolean {
        val raw = doc.get(key) ?: return false
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() == 1
            is String -> raw.equals("true", ignoreCase = true) || raw.equals("si", ignoreCase = true) || raw == "1"
            else -> false
        }
    }

    private fun safeString(doc: DocumentSnapshot, key: String): String {
        val obj = doc.get(key) ?: return ""
        return when (obj) {
            is String -> obj.trim()
            is Map<*, *> -> {
                val calle = (obj["calle"] ?: obj["street"] ?: "")?.toString()?.trim() ?: ""
                val num = (obj["numero"] ?: obj["number"] ?: "")?.toString()?.trim() ?: ""
                val com = (obj["comuna"] ?: obj["city"] ?: "")?.toString()?.trim() ?: ""
                listOf(calle, num, com).filter { it.isNotEmpty() }.joinToString(" ")
            }
            else -> obj.toString().trim()
        }
    }

    private fun mapToDispatch(doc: DocumentSnapshot): Dispatch? {
        return try {
            val carrosRaw = doc.get("carros") ?: doc.get("unidadesDespachadas") ?: doc.get("unidadesAsignadas")

            val unidadesRaw = doc.get("unidades") as? Map<String, Any> ?: emptyMap()
            val unidadesMap = mutableMapOf<String, MutableMap<String, Any>>()
            unidadesRaw.forEach { (k, v) ->
                val converted = mutableMapOf<String, Any>()
                if (v is Map<*, *>) {
                    v.forEach { (ik, iv) -> if (ik != null && iv != null) converted[ik.toString()] = iv }
                }
                unidadesMap[k] = converted
            }

            // También fusionar cualquier campo con notación de punto ("unidades.B1.obac", etc.) guardado en doc.data
            doc.data?.forEach { (key, value) ->
                if (key.startsWith("unidades.") && value != null) {
                    val parts = key.split(".")
                    if (parts.size >= 3) {
                        val unitKey = parts[1]
                        val fieldKey = parts.subList(2, parts.size).joinToString(".")
                        val curMap = unidadesMap.getOrPut(unitKey) { mutableMapOf() }
                        curMap[fieldKey] = value
                    }
                }
            }

            val carrosStr = when {
                carrosRaw is List<*> && carrosRaw.isNotEmpty() -> carrosRaw.joinToString(", ") { it.toString() }
                carrosRaw is String && carrosRaw.isNotBlank() -> carrosRaw
                unidadesMap.isNotEmpty() -> unidadesMap.keys.joinToString(", ")
                else -> safeString(doc, "carrosTexto")
            }

            val bitacoraRaw = doc.get("bitacora") as? List<*> ?: emptyList<Any>()
            val bitacoraList = bitacoraRaw.mapNotNull {
                if (it is Map<*, *>) {
                    BitacoraEntry(
                        autor = it["autor"]?.toString() ?: "",
                        hora = it["hora"]?.toString() ?: "",
                        fecha = it["fecha"]?.toString() ?: "",
                        texto = it["texto"]?.toString() ?: "",
                        vehicleId = it["vehicleId"]?.toString() ?: ""
                    )
                } else null
            }

            // Coordenadas del Incidente (Marcadas por Central CAD / Mapa Táctico)
            val (latVal, lngVal) = extractCoordinates(doc, true, "geo", "geolocalizacion", "ubicacionGps", "posicionGps", "coordenadas")

            // Coordenadas separadas del Alertante (si reportó ubicación por link SMS/WhatsApp)
            val (alertanteLatVal, alertanteLngVal) = extractCoordinates(doc, false, "geolocalizacionAlertante", "alertanteGeo")
            val alertanteMap = (doc.get("geolocalizacionAlertante") as? Map<*, *>)
                ?: (doc.get("alertanteGeo") as? Map<*, *>)
                ?: ((doc.get("geo") as? Map<*, *>)?.get("alertanteGeo") as? Map<*, *>)
            val alertanteAccVal = (alertanteMap?.get("accuracy") as? Number)?.toFloat()
                ?: (alertanteMap?.get("accuracy") as? String)?.toFloatOrNull()

            val phoneVal = safeString(doc, "telefono").ifEmpty {
                (doc.get("smsSolicitudGeo") as? Map<*, *>)?.get("telefono")?.toString()
                    ?: (doc.get("geolocalizacionAlertante") as? Map<*, *>)?.get("telefono")?.toString()
                    ?: (doc.get("alertanteGeo") as? Map<*, *>)?.get("telefono")?.toString()
                    ?: ""
            }

            val solicitanteVal = safeString(doc, "solicitante").ifEmpty {
                safeString(doc, "alertante").ifEmpty {
                    safeString(doc, "nombreAlertante")
                }
            }

            val idVal = doc.get("id")?.toString()
                ?: doc.get("idDespacho")?.toString()
                ?: doc.get("idServicio")?.toString()
                ?: doc.get("numero")?.toString()
                ?: doc.id

            val claveVal = safeString(doc, "clave").ifEmpty {
                safeString(doc, "key").ifEmpty {
                    safeString(doc, "claveEmergencia").ifEmpty {
                        safeString(doc, "tipoEmergencia")
                    }
                }
            }

            val dirVal = safeString(doc, "lugar").ifEmpty {
                safeString(doc, "direccion").ifEmpty {
                    safeString(doc, "ubicacion").ifEmpty {
                        (doc.get("geo") as? Map<*, *>)?.get("direccion")?.toString()
                            ?: (doc.get("geo") as? Map<*, *>)?.get("address")?.toString()
                            ?: (doc.get("ubicacionGps") as? Map<*, *>)?.get("direccion")?.toString()
                            ?: ""
                    }
                }
            }

            val esquinaVal = safeString(doc, "esquina").ifEmpty {
                safeString(doc, "interseccion").ifEmpty {
                    safeString(doc, "referencia")
                }
            }

            val comunaVal = safeString(doc, "comuna").ifEmpty {
                safeString(doc, "ciudad")
            }

            var lugarVal = dirVal
            if (esquinaVal.isNotBlank() && !lugarVal.contains(esquinaVal, ignoreCase = true)) {
                lugarVal = if (lugarVal.isNotBlank()) "$lugarVal (Esq. $esquinaVal)" else "Esq. $esquinaVal"
            }
            if (comunaVal.isNotBlank() && !lugarVal.contains(comunaVal, ignoreCase = true)) {
                lugarVal = if (lugarVal.isNotBlank()) "$lugarVal, $comunaVal" else comunaVal
            }

            Dispatch(
                idServicio = idVal,
                clave = claveVal,
                claveApoyo = safeString(doc, "claveApoyo"),
                lugar = lugarVal,
                preinforme = safeString(doc, "preinforme").ifEmpty { safeString(doc, "preInforme").ifEmpty { safeString(doc, "informe").ifEmpty { safeString(doc, "detalles") } } },
                carros = carrosStr,
                horaDespacho = safeString(doc, "horaDespacho"),
                fechaDespacho = safeString(doc, "fechaDespacho"),
                hora67 = safeString(doc, "hora67"),
                quienDespacha = safeString(doc, "quienDespacha"),
                operadorFinal = safeString(doc, "operadorFinal"),
                obacGeneral = safeString(doc, "obacGeneral"),
                unidades = unidadesMap,
                bitacora = bitacoraList,
                solicitarConfirmacion = safeBoolean(doc, "solicitarConfirmacion"),
                estado = safeString(doc, "estado"),
                solicitante = solicitanteVal,
                telefono = phoneVal,
                lat = latVal,
                lng = lngVal,
                alertanteLat = alertanteLatVal,
                alertanteLng = alertanteLngVal,
                alertanteAccuracy = alertanteAccVal
            )
        } catch (e: Exception) {
            android.util.Log.e("SisBomCar", "Error en mapToDispatch doc ${doc.id}: ${e.message}", e)
            null
        }
    }

    // 9. Escuchar lista de personal en tiempo real con soporte 100% offline
    fun getPersonalFlow(context: Context? = null): Flow<List<PersonItem>> = callbackFlow {
        // Emitir caché local offline inmediatamente
        if (context != null) {
            val cached = loadCachedPersonal(context)
            if (cached.isNotEmpty()) {
                trySend(cached)
            }
        }

        val firestore = db
        if (firestore == null) {
            if (context == null) trySend(emptyList())
            awaitClose {}
            return@callbackFlow
        }
        try {
            val listener = firestore.collection("personal")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        return@addSnapshotListener
                    }
                    val list = snapshot?.documents?.mapNotNull { doc ->
                        val idReg = doc.getString("idRegistro") ?: doc.id
                        val nombre = doc.getString("nombreBombero") ?: doc.getString("nombre") ?: "Bombero"
                        val radial = doc.getString("idRadial") ?: ""
                        val cia = doc.getString("compania") ?: ""
                        val cargo = doc.getString("cargo") ?: ""
                        val enServicio = doc.getString("enServicio") ?: "0"
                        val lat = (doc.get("lat") as? Number)?.toDouble() ?: 0.0
                        val lng = (doc.get("lng") as? Number)?.toDouble() ?: 0.0
                        val ts = doc.getLong("gpsTimestamp") ?: 0L
                        PersonItem(
                            idRegistro = idReg,
                            nombreBombero = nombre,
                            idRadial = radial,
                            compania = cia,
                            cargo = cargo,
                            enServicio = enServicio,
                            lat = lat,
                            lng = lng,
                            gpsTimestamp = ts
                        )
                    } ?: emptyList()

                    if (list.isNotEmpty()) {
                        trySend(list)
                        if (context != null) {
                            saveCachedPersonal(context, list)
                        }
                    }
                }
            awaitClose { listener.remove() }
        } catch (e: Exception) {
            awaitClose {}
        }
    }

    // 10. Asignar dotación (conductor, obac y tripulación) identificados en la tablet
    fun assignTripulantesToDispatch(
        dispatchId: String,
        vehicleId: String,
        bitacoraId: String = "",
        tripulantes: List<PersonItem> = emptyList(),
        driverRadText: String = "",
        driverNameText: String = "",
        driverPerson: PersonItem? = null,
        obacRadText: String = "",
        obacNameText: String = "",
        obacPerson: PersonItem? = null,
        crewCountText: String = "",
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val firestore = db ?: return
        if (vehicleId.isEmpty()) return
        val listMap = tripulantes.map {
            hashMapOf(
                "idRegistro" to it.idRegistro,
                "nombre" to it.nombreBombero,
                "nombreBombero" to it.nombreBombero,
                "idRadial" to it.idRadial,
                "compania" to it.compania,
                "cargo" to it.cargo
            )
        }
        val namesStr = tripulantes.joinToString(", ") { "${it.idRadial} ${it.nombreBombero}".trim() }
        val cleanVehId = vehicleId.replace("-", "").trim().uppercase()

        val finalCountStr = if (crewCountText.isNotBlank()) {
            crewCountText.trim()
        } else if (tripulantes.isNotEmpty()) {
            tripulantes.size.toString()
        } else {
            "0"
        }

        val finalDriverRad = driverPerson?.idRadial ?: driverRadText.trim()
        val finalDriverName = when {
            driverPerson != null -> "${driverPerson.idRadial} - ${driverPerson.nombreBombero}".trim()
            driverNameText.isNotBlank() -> driverNameText.trim()
            finalDriverRad.isNotBlank() -> finalDriverRad
            else -> ""
        }

        val finalObacRad = obacPerson?.idRadial ?: obacRadText.trim()
        val finalObacName = when {
            obacPerson != null -> "${obacPerson.idRadial} - ${obacPerson.nombreBombero}".trim()
            obacNameText.isNotBlank() -> obacNameText.trim()
            finalObacRad.isNotBlank() -> finalObacRad
            else -> ""
        }

        // 1. Actualizar bitácora (Documento bitacora/{bitacoraId} o búsqueda activa)
        val bUpdates = hashMapOf<String, Any>(
            "tripulantes" to namesStr,
            "tripulantesNombres" to namesStr,
            "cuantosBomberos" to finalCountStr,
            "tripulantesDetalle" to listMap
        )
        if (finalDriverName.isNotEmpty()) bUpdates["conductor"] = finalDriverName
        if (finalObacName.isNotEmpty()) bUpdates["obac"] = finalObacName

        fun writeToBitacora(docRef: com.google.firebase.firestore.DocumentReference) {
            docRef.set(bUpdates, SetOptions.merge())
            tripulantes.forEach { person ->
                if (person.idRegistro.isNotEmpty()) {
                    val tData = hashMapOf(
                        "idRegistro" to person.idRegistro,
                        "nombre" to person.nombreBombero,
                        "nombreBombero" to person.nombreBombero,
                        "idRadial" to person.idRadial,
                        "compania" to person.compania,
                        "cargo" to person.cargo,
                        "timestamp" to System.currentTimeMillis()
                    )
                    docRef.collection("tripulantes").document(person.idRegistro).set(tData, SetOptions.merge())
                }
            }
        }

        if (bitacoraId.isNotEmpty()) {
            writeToBitacora(firestore.collection("bitacora").document(bitacoraId))
        } else {
            firestore.collection("bitacora").get().addOnSuccessListener { bitacoraSnap ->
                val matchedDocs = bitacoraSnap.documents.filter { bDoc ->
                    val bCarro = (bDoc.getString("carro") ?: bDoc.getString("idCarro") ?: "").replace("-", "").trim().uppercase()
                    val h68 = bDoc.getString("hora68") ?: ""
                    val f68 = bDoc.getString("fecha68") ?: ""
                    val sameServicio = dispatchId.isNotEmpty() && bDoc.getString("idServicio") == dispatchId
                    val isVehMatch = bCarro.isNotEmpty() && (bCarro == cleanVehId || cleanVehId.contains(bCarro) || bCarro.contains(cleanVehId))
                    (sameServicio || isVehMatch) && (h68.isEmpty() && f68.isEmpty())
                }
                matchedDocs.forEach { bDoc ->
                    writeToBitacora(bDoc.reference)
                }
            }
        }

        // 3. Actualizar documento de despacho si existe (con mapa anidado canonical)
        if (dispatchId.isNotEmpty()) {
            val unitData = hashMapOf<String, Any>(
                "tripulantesDetalle" to listMap,
                "tripulantesNombres" to namesStr,
                "tripulacion" to tripulantes.map { it.nombreBombero },
                "count" to finalCountStr,
                "cuantosBomberos" to finalCountStr
            )
            if (finalDriverRad.isNotEmpty()) {
                unitData["driverRad"] = finalDriverRad
                unitData["conductor"] = finalDriverName
            }
            if (finalObacRad.isNotEmpty()) {
                unitData["obacRad"] = finalObacRad
                unitData["obac"] = finalObacName
                unitData["aCargo"] = finalObacName
            }
            val dNestedMap = hashMapOf<String, Any>(
                "unidades" to hashMapOf<String, Any>(
                    vehicleId to unitData
                )
            )
            firestore.collection("despachos").document(dispatchId).set(dNestedMap, SetOptions.merge())
        }

        // 4. Marcar a cada bombero como en servicio
        val allPersons = tripulantes.toMutableList()
        if (driverPerson != null) allPersons.add(driverPerson)
        if (obacPerson != null) allPersons.add(obacPerson)

        val serviceVal = dispatchId.ifEmpty { "1" }
        allPersons.forEach { p ->
            if (p.idRegistro.isNotEmpty()) {
                try {
                    firestore.collection("personal").document(p.idRegistro)
                        .set(
                            mapOf(
                                "enServicio" to serviceVal,
                                "estado" to "0-9"
                            ),
                            SetOptions.merge()
                        )
                } catch (_: Exception) {}
            }
        }
        onSuccess()
    }

    // 11. Solicitar ubicación de bombero (5 min)
    fun solicitarUbicacionBombero(userId: String, serviceId: String) {
        val firestore = db ?: return
        if (userId.isEmpty()) return
        val serviceVal = serviceId.ifEmpty { "1" }
        firestore.collection("personal").document(userId)
            .set(
                mapOf(
                    "solicitarGpsTimestamp" to System.currentTimeMillis(),
                    "solicitarGpsServiceId" to serviceVal
                ),
                SetOptions.merge()
            )
    }

    // 12. Solicitar 12-10 (Conductor para la unidad)
    fun solicitarConductor1210(
        dispatchId: String,
        vehicleId: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val firestore = db ?: return
        if (vehicleId.isEmpty()) return
        val timeNow = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val ts = System.currentTimeMillis()

        val vUpdates = hashMapOf<String, Any>(
            "solicitudConductorAt" to timeNow,
            "solicitudConductorTimestamp" to ts
        )
        firestore.collection("vehiculos").document(vehicleId).set(vUpdates, SetOptions.merge())

        if (dispatchId.isNotEmpty()) {
            val dNestedMap = hashMapOf<String, Any>(
                "unidades" to hashMapOf<String, Any>(
                    vehicleId to hashMapOf<String, Any>(
                        "solicitudConductorAt" to timeNow,
                        "solicitudConductorTimestamp" to ts
                    )
                )
            )
            firestore.collection("despachos").document(dispatchId).set(dNestedMap, SetOptions.merge())
                .addOnSuccessListener { onSuccess() }
                .addOnFailureListener { err -> onFailure(err) }
        } else {
            onSuccess()
        }
    }

    // 13. Solicitar 6-6 (Personal / Dotación para la unidad)
    fun solicitarPersonal66(
        dispatchId: String,
        vehicleId: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val firestore = db ?: return
        if (vehicleId.isEmpty()) return
        val timeNow = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val ts = System.currentTimeMillis()

        val vUpdates = hashMapOf<String, Any>(
            "solicitudPersonalAt" to timeNow,
            "solicitudPersonalTimestamp" to ts
        )
        firestore.collection("vehiculos").document(vehicleId).set(vUpdates, SetOptions.merge())

        if (dispatchId.isNotEmpty()) {
            val dNestedMap = hashMapOf<String, Any>(
                "unidades" to hashMapOf<String, Any>(
                    vehicleId to hashMapOf<String, Any>(
                        "solicitudPersonalAt" to timeNow,
                        "solicitudPersonalTimestamp" to ts
                    )
                )
            )
            firestore.collection("despachos").document(dispatchId).set(dNestedMap, SetOptions.merge())
                .addOnSuccessListener { onSuccess() }
                .addOnFailureListener { err -> onFailure(err) }
        } else {
            onSuccess()
        }
    }

    // =========================================================================
    // PERSISTENCIA LOCAL 100% OFFLINE (Personal y Rutas OSRM)
    // =========================================================================

    fun saveCachedPersonal(context: Context, list: List<PersonItem>) {
        try {
            val prefs = context.getSharedPreferences("SisBomOfflineCache", Context.MODE_PRIVATE)
            val jsonArray = JSONArray()
            list.forEach { p ->
                val obj = JSONObject().apply {
                    put("idRegistro", p.idRegistro)
                    put("nombreBombero", p.nombreBombero)
                    put("idRadial", p.idRadial)
                    put("compania", p.compania)
                    put("cargo", p.cargo)
                    put("enServicio", p.enServicio)
                    put("lat", p.lat)
                    put("lng", p.lng)
                    put("gpsTimestamp", p.gpsTimestamp)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString("cached_personal_list", jsonArray.toString()).apply()
        } catch (_: Exception) {}
    }

    fun loadCachedPersonal(context: Context): List<PersonItem> {
        return try {
            val prefs = context.getSharedPreferences("SisBomOfflineCache", Context.MODE_PRIVATE)
            val raw = prefs.getString("cached_personal_list", null) ?: return emptyList()
            val array = JSONArray(raw)
            val result = mutableListOf<PersonItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    PersonItem(
                        idRegistro = obj.optString("idRegistro", ""),
                        nombreBombero = obj.optString("nombreBombero", ""),
                        idRadial = obj.optString("idRadial", ""),
                        compania = obj.optString("compania", ""),
                        cargo = obj.optString("cargo", ""),
                        enServicio = obj.optString("enServicio", "0"),
                        lat = obj.optDouble("lat", 0.0),
                        lng = obj.optDouble("lng", 0.0),
                        gpsTimestamp = obj.optLong("gpsTimestamp", 0L)
                    )
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCachedRoute(
        context: Context,
        routeKey: String,
        points: List<GeoPoint>,
        maneuvers: List<TacticalManeuverStep>,
        duration: Double,
        distance: Double
    ) {
        try {
            if (routeKey.isBlank() || points.isEmpty()) return
            val prefs = context.getSharedPreferences("SisBomRouteCache", Context.MODE_PRIVATE)

            val root = JSONObject()
            root.put("duration", duration)
            root.put("distance", distance)

            val ptsArray = JSONArray()
            points.forEach { pt ->
                val pObj = JSONObject()
                pObj.put("lat", pt.latitude)
                pObj.put("lng", pt.longitude)
                ptsArray.put(pObj)
            }
            root.put("points", ptsArray)

            val manArray = JSONArray()
            maneuvers.forEach { m ->
                val mObj = JSONObject()
                mObj.put("instruction", m.instruction)
                mObj.put("streetName", m.streetName)
                mObj.put("distanceMeters", m.distanceMeters)
                mObj.put("modifier", m.modifier)
                mObj.put("maneuverType", m.maneuverType)
                mObj.put("lat", m.location.latitude)
                mObj.put("lng", m.location.longitude)
                manArray.put(mObj)
            }
            root.put("maneuvers", manArray)

            prefs.edit().putString("route_$routeKey", root.toString()).apply()
        } catch (_: Exception) {}
    }

    fun loadCachedRoute(context: Context, routeKey: String): CachedRouteData? {
        return try {
            if (routeKey.isBlank()) return null
            val prefs = context.getSharedPreferences("SisBomRouteCache", Context.MODE_PRIVATE)
            val raw = prefs.getString("route_$routeKey", null) ?: return null
            val root = JSONObject(raw)

            val duration = root.optDouble("duration", 0.0)
            val distance = root.optDouble("distance", 0.0)

            val ptsArray = root.optJSONArray("points") ?: JSONArray()
            val points = mutableListOf<GeoPoint>()
            for (i in 0 until ptsArray.length()) {
                val pObj = ptsArray.getJSONObject(i)
                points.add(GeoPoint(pObj.getDouble("lat"), pObj.getDouble("lng")))
            }

            val manArray = root.optJSONArray("maneuvers") ?: JSONArray()
            val maneuvers = mutableListOf<TacticalManeuverStep>()
            for (i in 0 until manArray.length()) {
                val mObj = manArray.getJSONObject(i)
                maneuvers.add(
                    TacticalManeuverStep(
                        instruction = mObj.optString("instruction", ""),
                        streetName = mObj.optString("streetName", ""),
                        distanceMeters = mObj.optDouble("distanceMeters", 0.0),
                        modifier = mObj.optString("modifier", ""),
                        maneuverType = mObj.optString("maneuverType", ""),
                        location = GeoPoint(mObj.optDouble("lat", 0.0), mObj.optDouble("lng", 0.0))
                    )
                )
            }

            if (points.isNotEmpty()) {
                CachedRouteData(points, maneuvers, duration, distance)
            } else null
        } catch (_: Exception) {
            null
        }
    }
}

data class CachedRouteData(
    val points: List<GeoPoint>,
    val maneuvers: List<TacticalManeuverStep>,
    val duration: Double,
    val distance: Double
)

data class PersonItem(
    val idRegistro: String = "",
    val nombreBombero: String = "",
    val idRadial: String = "",
    val compania: String = "",
    val cargo: String = "",
    val enServicio: String = "0",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val gpsTimestamp: Long = 0L
)
