package com.sisbom.sisbomcar

import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
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
                    }?.filter {
                        it.operadorFinal.trim().isEmpty() &&
                        it.estado.trim().lowercase() != "finalizada" &&
                        it.estado.trim().lowercase() != "cancelada"
                    } ?: emptyList()
                    trySend(list)
                }
            awaitClose { listener.remove() }
        } catch (e: Exception) {
            trySend(emptyList())
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

        val updates = hashMapOf<String, Any>(
            "estado" to newStatus,
            "lastUpdate" to timeNow
        )
        if (enServicio.isNotEmpty()) {
            updates["enServicio"] = enServicio
        }
        if (notas.isNotEmpty()) {
            updates["notas"] = notas
        } else if (newStatus == "0-9" || newStatus == "1") {
            updates["notas"] = ""
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

                    // Actualizar vehiculos
                    val vehUpdates = hashMapOf<String, Any>(
                        "estado" to type,
                        "enServicio" to type,
                        "conductor" to conductor,
                        "obac" to obac,
                        "aCargo" to obac,
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
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val firestore = db ?: return
        if (vehicleId.isEmpty()) return
        val timeNow = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val dateNow = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date())
        val timestampField = "${milestoneKey}Timestamp"

        val cleanVeh = vehicleId.replace("-", "").trim().uppercase()

        // Función auxiliar para actualizar Bitácora
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
                val bitUpdates = hashMapOf<String, Any>(
                    "hora60" to timeNow,
                    "fecha60" to dateNow,
                    "estadoMovil" to "en trayecto"
                )
                updateBitacoraDoc(bitacoraId, bitUpdates)
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
            }
            "traslado615At" -> {
                val bitUpdates = hashMapOf<String, Any>(
                    "observacion" to "TRASLADO CENTRO ASISTENCIAL (6-15)"
                )
                updateBitacoraDoc(bitacoraId, bitUpdates)
                updateVehicleStatus(vehicleId, "6-15", dispatchId)
            }
            "llegada63SaludAt" -> {
                val bitUpdates = hashMapOf<String, Any>(
                    "observacion" to "LLEGADA CENTRO ASISTENCIAL (6-3 SALUD)"
                )
                updateBitacoraDoc(bitacoraId, bitUpdates)
                updateVehicleStatus(vehicleId, "6-3", dispatchId)
            }
            "retornoEmergencia613At" -> {
                val bitUpdates = hashMapOf<String, Any>(
                    "observacion" to "6-13 RETORNO A OTRA EMERGENCIA"
                )
                updateBitacoraDoc(bitacoraId, bitUpdates)
                updateVehicleStatus(vehicleId, "6-13", dispatchId)
            }
            "retorno69At" -> {
                val bitUpdates = hashMapOf<String, Any>(
                    "hora69" to timeNow,
                    "fecha69" to dateNow,
                    "estadoMovil" to "retorno"
                )
                updateBitacoraDoc(bitacoraId, bitUpdates)
                updateVehicleStatus(vehicleId, "6-9", dispatchId)
            }
            "llegadaCuartel610At", "llegada610At" -> {
                val bitUpdates = hashMapOf<String, Any>(
                    "hora610" to timeNow,
                    "fecha610" to dateNow,
                    "estadoMovil" to "en cuartel"
                )
                updateBitacoraDoc(bitacoraId, bitUpdates)
                updateVehicleStatus(vehicleId, "6-10", dispatchId)
            }
            "disponible68At" -> {
                val bitUpdates = hashMapOf<String, Any>(
                    "hora68" to timeNow,
                    "fecha68" to dateNow,
                    "estadoMovil" to "en cuartel"
                )
                if (kmValue.isNotBlank()) bitUpdates["km"] = kmValue
                updateBitacoraDoc(bitacoraId, bitUpdates)

                // Actualizar vehículo a Disponible (0-9), enServicio a 0 y limpiar dotación
                val vehUpdates = hashMapOf<String, Any>(
                    "enServicio" to "0",
                    "estado" to "0-9",
                    "conductor" to "",
                    "obac" to "",
                    "aCargo" to "",
                    "notas" to "",
                    "lugar" to "Cuartel",
                    "lastUpdate" to "$dateNow $timeNow"
                )
                if (kmValue.isNotBlank()) vehUpdates["kmActual"] = kmValue
                firestore.collection("vehiculos").document(vehicleId).set(vehUpdates, SetOptions.merge())
            }
        }

        // Si hay un despacho de central activo, actualizar también el documento de despacho
        if (dispatchId.isNotEmpty()) {
            val unitNestedPath = "unidades.$vehicleId"
            val updates = hashMapOf<String, Any>(
                "$unitNestedPath.$milestoneKey" to timeNow,
                "$unitNestedPath.$timestampField" to System.currentTimeMillis()
            )
            if (kmValue.isNotBlank()) {
                updates["$unitNestedPath.km"] = kmValue
            }
            when (milestoneKey) {
                "salida60At" -> {
                    updates["$unitNestedPath.status"] = "6-0"
                    updates["$unitNestedPath.estado"] = "en_trayecto"
                    updates["$unitNestedPath.hora60"] = timeNow
                    updates["$unitNestedPath.horaSalida"] = timeNow
                }
                "llegada63At" -> {
                    updates["$unitNestedPath.status"] = "6-3"
                    updates["$unitNestedPath.estado"] = "en_lugar"
                    updates["$unitNestedPath.hora63"] = timeNow
                    updates["$unitNestedPath.horaLlegada"] = timeNow
                }
                "retorno69At" -> {
                    updates["$unitNestedPath.status"] = "6-9"
                    updates["$unitNestedPath.estado"] = "retorno"
                    updates["$unitNestedPath.hora69"] = timeNow
                }
                "llegadaCuartel610At", "llegada610At" -> {
                    updates["$unitNestedPath.status"] = "6-10"
                    updates["$unitNestedPath.estado"] = "en_cuartel"
                    updates["$unitNestedPath.hora610"] = timeNow
                }
                "disponible68At" -> {
                    updates["$unitNestedPath.status"] = "6-8"
                    updates["$unitNestedPath.estado"] = "finalizado"
                    updates["$unitNestedPath.hora68"] = timeNow
                }
            }

            firestore.collection("despachos").document(dispatchId)
                .update(updates)
                .addOnSuccessListener { onSuccess() }
                .addOnFailureListener { _ ->
                    val fallbackMap = hashMapOf(
                        "unidades" to hashMapOf(
                            vehicleId to hashMapOf(
                                milestoneKey to timeNow,
                                timestampField to System.currentTimeMillis()
                            )
                        )
                    )
                    firestore.collection("despachos").document(dispatchId).set(fallbackMap, SetOptions.merge())
                        .addOnSuccessListener { onSuccess() }
                        .addOnFailureListener { err -> onFailure(err) }
                }
        } else {
            onSuccess()
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
            "posicionGps" to geoPoint
        )

        firestore.collection("vehiculos").document(vehicleId)
            .set(data, SetOptions.merge())
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

            val geoPoint = doc.getGeoPoint("posicionGps")
            val latVal = geoPoint?.latitude
                ?: (doc.get("lat") as? Number)?.toDouble()
                ?: (doc.get("lat") as? String)?.toDoubleOrNull()
            val lngVal = geoPoint?.longitude
                ?: (doc.get("lng") as? Number)?.toDouble()
                ?: (doc.get("lng") as? String)?.toDoubleOrNull()

            Vehicle(
                idCarro = doc.id,
                clave = claveVal,
                patente = patenteVal,
                tipo = tipoVal,
                compania = doc.getString("compania") ?: doc.getString("cia") ?: "",
                estado = doc.getString("estado") ?: "0-8",
                enServicio = enServicioVal,
                conductor = doc.getString("conductor") ?: doc.getString("maquinista") ?: "",
                obac = doc.getString("obac") ?: doc.getString("aCargo") ?: "",
                tripulacion = tripulacionList,
                numTripulantes = (doc.get("numTripulantes") as? Number)?.toInt() ?: tripulacionList.size,
                notas = doc.getString("notas") ?: doc.getString("observacion") ?: "",
                lat = latVal,
                lng = lngVal,
                speed = speedVal,
                heading = headingVal,
                lastUpdate = doc.getString("lastUpdate") ?: ""
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun mapToDispatch(doc: DocumentSnapshot): Dispatch? {
        return try {
            val carrosRaw = doc.get("carros") ?: doc.get("unidadesDespachadas")
            val carrosStr = when (carrosRaw) {
                is List<*> -> carrosRaw.joinToString(", ") { it.toString() }
                is String -> carrosRaw
                else -> ""
            }

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

            val geoMap = (doc.get("geo") as? Map<*, *>)
                ?: (doc.get("ubicacionGps") as? Map<*, *>)
                ?: (doc.get("geolocalizacion") as? Map<*, *>)
                ?: (doc.get("coordenadas") as? Map<*, *>)
                ?: (doc.get("alertanteGeo") as? Map<*, *>)
                ?: (doc.get("geolocalizacionAlertante") as? Map<*, *>)

            val geoPoint = doc.getGeoPoint("posicionGps") ?: doc.getGeoPoint("geo") ?: doc.getGeoPoint("ubicacionGps")
            val latVal = geoPoint?.latitude
                ?: (geoMap?.get("lat") as? Number)?.toDouble()
                ?: (geoMap?.get("lat") as? String)?.toDoubleOrNull()
                ?: (doc.get("lat") as? Number)?.toDouble()
                ?: (doc.get("lat") as? String)?.toDoubleOrNull()

            val lngVal = geoPoint?.longitude
                ?: (geoMap?.get("lng") as? Number)?.toDouble()
                ?: (geoMap?.get("lng") as? String)?.toDoubleOrNull()
                ?: (doc.get("lng") as? Number)?.toDouble()
                ?: (doc.get("lng") as? String)?.toDoubleOrNull()

            val phoneVal = doc.getString("telefono")
                ?: (doc.get("smsSolicitudGeo") as? Map<*, *>)?.get("telefono")?.toString()
                ?: (doc.get("geolocalizacionAlertante") as? Map<*, *>)?.get("telefono")?.toString()
                ?: (doc.get("alertanteGeo") as? Map<*, *>)?.get("telefono")?.toString()
                ?: ""

            val solicitanteVal = doc.getString("solicitante")
                ?: doc.getString("alertante")
                ?: doc.getString("nombreAlertante")
                ?: ""

            Dispatch(
                idServicio = doc.id,
                clave = doc.getString("clave") ?: "",
                claveApoyo = doc.getString("claveApoyo") ?: "",
                lugar = doc.getString("lugar") ?: "",
                preinforme = doc.getString("preinforme") ?: "",
                carros = carrosStr,
                horaDespacho = doc.getString("horaDespacho") ?: "",
                fechaDespacho = doc.getString("fechaDespacho") ?: "",
                hora67 = doc.getString("hora67") ?: "",
                quienDespacha = doc.getString("quienDespacha") ?: "",
                operadorFinal = doc.getString("operadorFinal") ?: "",
                obacGeneral = doc.getString("obacGeneral") ?: "",
                unidades = unidadesMap,
                bitacora = bitacoraList,
                solicitarConfirmacion = doc.getBoolean("solicitarConfirmacion") ?: false,
                estado = doc.getString("estado") ?: "",
                solicitante = solicitanteVal,
                telefono = phoneVal,
                lat = latVal,
                lng = lngVal
            )
        } catch (_: Exception) {
            null
        }
    }

    // 9. Escuchar lista de personal en tiempo real
    fun getPersonalFlow(): Flow<List<PersonItem>> = callbackFlow {
        val firestore = db
        if (firestore == null) {
            trySend(emptyList())
            awaitClose {}
            return@callbackFlow
        }
        try {
            val listener = firestore.collection("personal")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(emptyList())
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
                    trySend(list)
                }
            awaitClose { listener.remove() }
        } catch (e: Exception) {
            trySend(emptyList())
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

        // 1. Actualizar vehiculos
        val vUpdates = mutableMapOf<String, Any>()
        if (finalDriverName.isNotEmpty()) vUpdates["conductor"] = finalDriverName
        if (finalObacName.isNotEmpty()) {
            vUpdates["obac"] = finalObacName
            vUpdates["aCargo"] = finalObacName
        }
        if (vUpdates.isNotEmpty()) {
            firestore.collection("vehiculos").document(vehicleId).set(vUpdates, SetOptions.merge())
        }

        // 2. Actualizar bitácora (Documento bitacora/{bitacoraId} o búsqueda activa)
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

        // 3. Actualizar documento de despacho si existe
        if (dispatchId.isNotEmpty()) {
            val dUpdates = hashMapOf<String, Any>(
                "unidades.$vehicleId.tripulantesDetalle" to listMap,
                "unidades.$vehicleId.tripulantesNombres" to namesStr,
                "unidades.$vehicleId.tripulacion" to tripulantes.map { it.nombreBombero },
                "unidades.$vehicleId.count" to finalCountStr,
                "unidades.$vehicleId.cuantosBomberos" to finalCountStr
            )
            if (finalDriverRad.isNotEmpty()) {
                dUpdates["unidades.$vehicleId.driverRad"] = finalDriverRad
                dUpdates["unidades.$vehicleId.conductor"] = finalDriverName
            }
            if (finalObacRad.isNotEmpty()) {
                dUpdates["unidades.$vehicleId.obacRad"] = finalObacRad
                dUpdates["unidades.$vehicleId.obac"] = finalObacName
                dUpdates["unidades.$vehicleId.aCargo"] = finalObacName
            }
            firestore.collection("despachos").document(dispatchId).set(dUpdates, SetOptions.merge())
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
}

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
