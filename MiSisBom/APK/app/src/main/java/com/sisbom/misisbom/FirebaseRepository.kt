package com.sisbom.misisbom

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

// Modelos de datos de Firestore
data class UserPersonal(
    val idRegistro: String = "",
    val nombreBombero: String = "",
    val idRadial: String = "",
    val contrasena: String = "",
    val activo: Any = 0,
    val conductor: Int = 0,
    val enServicio: String = "0",
    val cargo: String = "",
    val foto: String = "",
    val estado: String = "",
    val deviceId: String = "",
    val fechaSuspensionFin: String = "",
    val licenciaMedica: String = "",
    val permiso: Any = 0,
    val cds: Any = 0,
    val previousEstado: String = "",
    val restoreEstadoAt: Long = 0L,
    val puerta: Boolean = false
) {
    fun isUserActive(): Boolean {
        return when (activo) {
            is Number -> activo.toInt() == 1
            is String -> activo.toString().equals("SI", ignoreCase = true) || activo.toString() == "1"
            else -> false
        }
    }

    fun hasActiveSuspension(): Boolean {
        if (fechaSuspensionFin.isEmpty()) return false
        return try {
            val sdf = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.getDefault())
            val date = sdf.parse(fechaSuspensionFin.replace("/", "-"))
            date != null && date.after(java.util.Date())
        } catch (e: Exception) {
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                val date = sdf.parse(fechaSuspensionFin)
                date != null && date.after(java.util.Date())
            } catch (e2: Exception) {
                false
            }
        }
    }

    fun hasActiveLicense(): Boolean {
        if (licenciaMedica.isEmpty()) return false
        return try {
            val sdf = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.getDefault())
            val date = sdf.parse(licenciaMedica.replace("/", "-"))
            date != null && date.after(java.util.Date())
        } catch (e: Exception) {
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                val date = sdf.parse(licenciaMedica)
                date != null && date.after(java.util.Date())
            } catch (e2: Exception) {
                false
            }
        }
    }

    fun hasActivePermiso(): Boolean {
        return when (permiso) {
            is Number -> permiso.toInt() == 1
            is String -> permiso.toString().equals("SI", ignoreCase = true) || permiso.toString() == "1"
            else -> false
        }
    }

    fun hasActiveCDS(): Boolean {
        return when (cds) {
            is Number -> cds.toInt() == 1
            is String -> cds.toString().equals("SI", ignoreCase = true) || cds.toString() == "1"
            else -> false
        }
    }
}

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
    val unidades: Map<String, Map<String, Any>> = emptyMap(),
    val solicitarConfirmacion: Boolean = false,
    val estado: String = ""
)

data class Alert(
    val idAlerta: String = "",
    val tipo: String = "", // "orden" o "alerta"
    val gradoAlerta: String = "1",
    val aQuienAlerta: String = "TC",
    val quienAlerta: String = "",
    val razonAlerta: String = "",
    val mensajeAlerta: String = "",
    val fechaAlerta: String = "",
    val horaAlerta: String = "",
    val duracion: String = "",
    val conforme: String = "",
    val fijar: String = "",
    val numeroOrden: String = "",
    val fechaOrden: String = "",
    val firmaNombre: String = "",
    val firmaCargo: String = ""
)

data class Vehicle(
    val idCarro: String = "",
    val clave: String = "",
    val estado: String = "0-8",
    val enServicio: String = "0"
)

data class AttendanceSheet(
    val idLista: String = "",
    val clave: String = "",
    val tipo: String = "",
    val fecha: String = "",
    val hora: String = "",
    val lugar: String = "",
    val aprobadoPor: String = "",
    val anulada: Any = 0,
    var userEstado: String = "", // Creado localmente de la subcolección
    var userAbono: Any = 0,      // Creado localmente de la subcolección
    val obac: String = "",
    val detalle: String = ""
)

class FirebaseRepository {
    private val db = FirebaseFirestore.getInstance()

    private val isReadOnly: Boolean
        get() {
            return try {
                val context = com.google.firebase.FirebaseApp.getInstance().applicationContext
                val prefs = context.getSharedPreferences("SisBomPrefs", android.content.Context.MODE_PRIVATE)
                prefs.getString("saas_read_only", "0") == "1"
            } catch (e: Exception) {
                false
            }
        }

    // 1. Escuchar datos del personal en tiempo real
    fun getPersonnelFlow(): Flow<List<UserPersonal>> = callbackFlow {
        val listener = db.collection("personal")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("SisBom", "Error in getPersonnelFlow: ${error.message}")
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { doc ->
                    mapToUserPersonal(doc)
                } ?: emptyList()
                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    // 1b. Escuchar un único bombero en tiempo real (para reducir lecturas)
    fun getPersonnelSelfFlow(userId: String): Flow<UserPersonal?> = callbackFlow {
        val listener = db.collection("personal").document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("SisBom", "Error in getPersonnelSelfFlow: ${error.message}")
                    return@addSnapshotListener
                }
                val user = snapshot?.let { mapToUserPersonal(it) }
                trySend(user)
            }
        awaitClose { listener.remove() }
    }

    fun getDispatchesFlow(): Flow<List<Dispatch>> = callbackFlow {
        val listener = db.collection("despachos")
            .whereEqualTo("operadorFinal", "")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("SisBom", "Error in getDispatchesFlow: ${error.message}")
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { doc ->
                    mapToDispatch(doc)
                }?.filter { it.estado.trim().lowercase() != "cancelada" } ?: emptyList()
                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    // 3. Escuchar alertas en tiempo real
    fun getAlertsFlow(): Flow<List<Alert>> = callbackFlow {
        val listener = db.collection("alertas")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("SisBom", "Error in getAlertsFlow: ${error.message}")
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { doc ->
                    mapToAlert(doc)
                }?.filter { isAlertActive(it.duracion) } ?: emptyList()
                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    // 4. Escuchar vehículos en tiempo real
    fun getVehiclesFlow(): Flow<List<Vehicle>> = callbackFlow {
        val listener = db.collection("vehiculos")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("SisBom", "Error in getVehiclesFlow: ${error.message}")
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { doc ->
                    mapToVehicle(doc)
                } ?: emptyList()
                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    // 4.5 Obtener lista de asistencia de una sola vez
    fun fetchAttendanceOnce(userId: String, onComplete: (List<AttendanceSheet>) -> Unit) {
        val context = try {
            com.google.firebase.FirebaseApp.getInstance().applicationContext
        } catch (_: Exception) {
            null
        }
        val cached = if (context != null && userId.isNotEmpty()) {
            loadCachedAttendance(context, userId)
        } else {
            emptyList()
        }

        db.collection("asistencia")
            .orderBy("idLista", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .get()
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    onComplete(cached)
                    return@addOnCompleteListener
                }
                val snapshot = task.result
                val recentSheets = snapshot?.documents?.mapNotNull { doc ->
                    mapToAttendanceSheet(doc)
                } ?: emptyList()

                if (userId.isNotEmpty() && recentSheets.isNotEmpty()) {
                    val sheetsToFetch = mutableListOf<AttendanceSheet>()
                    recentSheets.forEach { sheet ->
                        val cachedSheet = cached.find { it.idLista == sheet.idLista }
                        if (cachedSheet != null && cachedSheet.userEstado.isNotEmpty()) {
                            sheet.userEstado = cachedSheet.userEstado
                            sheet.userAbono = cachedSheet.userAbono
                        } else {
                            sheetsToFetch.add(sheet)
                        }
                    }

                    if (sheetsToFetch.isEmpty()) {
                        if (context != null) {
                            val fullCache = loadCachedAttendance(context, userId).toMutableList()
                            recentSheets.forEach { recent ->
                                val idx = fullCache.indexOfFirst { it.idLista == recent.idLista }
                                if (idx != -1) {
                                    fullCache[idx] = recent
                                } else {
                                    fullCache.add(recent)
                                }
                            }
                            fullCache.sortByDescending { it.idLista.toIntOrNull() ?: 0 }
                            saveCachedAttendance(context, userId, fullCache)
                            onComplete(fullCache)
                        } else {
                            onComplete(recentSheets)
                        }
                    } else {
                        var completedCount = 0
                        val totalCount = sheetsToFetch.size
                        sheetsToFetch.forEach { sheet ->
                            db.collection("asistencia")
                                .document(sheet.idLista)
                                .collection("bomberos")
                                .document(userId)
                                .get()
                                .addOnCompleteListener { subTask ->
                                    if (subTask.isSuccessful) {
                                        val subSnap = subTask.result
                                        if (subSnap != null && subSnap.exists()) {
                                            sheet.userEstado = subSnap.getString("estado") ?: "FALTA"
                                        } else {
                                            sheet.userEstado = "FALTA"
                                        }
                                    } else {
                                        sheet.userEstado = "FALTA"
                                    }
                                    completedCount++
                                    if (completedCount == totalCount) {
                                        if (context != null) {
                                            val fullCache = loadCachedAttendance(context, userId).toMutableList()
                                            recentSheets.forEach { recent ->
                                                val idx = fullCache.indexOfFirst { it.idLista == recent.idLista }
                                                if (idx != -1) {
                                                    fullCache[idx] = recent
                                                } else {
                                                    fullCache.add(recent)
                                                }
                                            }
                                            fullCache.sortByDescending { it.idLista.toIntOrNull() ?: 0 }
                                            saveCachedAttendance(context, userId, fullCache)
                                            onComplete(fullCache)
                                        } else {
                                            onComplete(recentSheets)
                                        }
                                    }
                                }
                        }
                    }
                } else {
                    onComplete(cached.ifEmpty { recentSheets })
                }
            }
    }

    // 5. Escuchar lista de asistencia global en tiempo real con caché local y sin límites
    fun getAttendanceFlow(userId: String): Flow<List<AttendanceSheet>> = callbackFlow {
        val context = try {
            com.google.firebase.FirebaseApp.getInstance().applicationContext
        } catch (_: Exception) {
            null
        }

        // 1. Emitir caché primero si existe
        val cached = if (context != null && userId.isNotEmpty()) {
            loadCachedAttendance(context, userId)
        } else {
            emptyList()
        }
        if (cached.isNotEmpty()) {
            trySend(cached)
        }

        // 2. Escuchar todos los registros de asistencia en tiempo real sin límites
        val listener = db.collection("asistencia")
            .orderBy("idLista", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    return@addSnapshotListener
                }

                val recentSheets = snapshot?.documents?.mapNotNull { doc ->
                    mapToAttendanceSheet(doc)
                } ?: emptyList()

                if (userId.isNotEmpty() && recentSheets.isNotEmpty()) {
                    val sheetsToFetch = mutableListOf<AttendanceSheet>()
                    
                    recentSheets.forEach { sheet ->
                        val cachedSheet = cached.find { it.idLista == sheet.idLista }
                        if (cachedSheet != null && cachedSheet.userEstado.isNotEmpty()) {
                            // Copy from cache (no firestore fetch needed!)
                            sheet.userEstado = cachedSheet.userEstado
                            sheet.userAbono = cachedSheet.userAbono
                        } else {
                            // Needs fetch
                            sheetsToFetch.add(sheet)
                        }
                    }

                    if (sheetsToFetch.isEmpty()) {
                        // All are cached! Merge and emit immediately!
                        if (context != null) {
                            val fullCache = loadCachedAttendance(context, userId).toMutableList()
                            recentSheets.forEach { recent ->
                                val idx = fullCache.indexOfFirst { it.idLista == recent.idLista }
                                if (idx != -1) {
                                    fullCache[idx] = recent
                                } else {
                                    fullCache.add(recent)
                                }
                            }
                            fullCache.sortByDescending { it.idLista.toIntOrNull() ?: 0 }
                            saveCachedAttendance(context, userId, fullCache)
                            trySend(fullCache)
                        } else {
                            trySend(recentSheets)
                        }
                    } else {
                        var completedCount = 0
                        val totalCount = sheetsToFetch.size

                        sheetsToFetch.forEach { sheet ->
                            db.collection("asistencia")
                                .document(sheet.idLista)
                                .collection("bomberos")
                                .document(userId)
                                .get()
                                .addOnCompleteListener { task ->
                                    if (task.isSuccessful) {
                                        val subSnap = task.result
                                        if (subSnap != null && subSnap.exists()) {
                                            sheet.userEstado = subSnap.getString("estado") ?: "FALTA"
                                        } else {
                                            sheet.userEstado = "FALTA"
                                        }
                                    } else {
                                        sheet.userEstado = "FALTA"
                                    }
                                    completedCount++
                                    if (completedCount == totalCount) {
                                        if (context != null) {
                                            val fullCache = loadCachedAttendance(context, userId).toMutableList()
                                            recentSheets.forEach { recent ->
                                                val idx = fullCache.indexOfFirst { it.idLista == recent.idLista }
                                                if (idx != -1) {
                                                    fullCache[idx] = recent
                                                } else {
                                                    fullCache.add(recent)
                                                }
                                            }
                                            fullCache.sortByDescending { it.idLista.toIntOrNull() ?: 0 }
                                            saveCachedAttendance(context, userId, fullCache)
                                            trySend(fullCache)
                                        } else {
                                            trySend(recentSheets)
                                        }
                                    }
                                }
                        }
                    }
                } else {
                    if (context != null) {
                        trySend(cached)
                    } else {
                        trySend(recentSheets)
                    }
                }
            }
        awaitClose { listener.remove() }
    }

    // 6. Escuchar el estado de la Central Operativa
    fun getCentralStateFlow(): Flow<Map<String, Any>> = callbackFlow {
        val listener = db.collection("accesos").document("central")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("SisBom", "Error in getCentralStateFlow: ${error.message}")
                    return@addSnapshotListener
                }
                val map = snapshot?.data ?: emptyMap()
                trySend(map)
            }
        awaitClose { listener.remove() }
    }

    // MÉTODOS DE ESCRITURA EN FIRESTORE

    fun updatePersonalStatus(userId: String, status: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        if (isReadOnly) {
            android.util.Log.w("SisBom", "Write updatePersonalStatus blocked: Read-Only Mode")
            onSuccess()
            return
        }
        db.collection("personal").document(userId)
            .update("estado", status)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    fun updatePersonalService(userId: String, serviceId: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        if (isReadOnly) {
            android.util.Log.w("SisBom", "Write updatePersonalService blocked: Read-Only Mode")
            onSuccess()
            return
        }
        db.collection("personal").document(userId)
            .update("enServicio", serviceId)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    fun declinePersonalService(userId: String, originalState: String, restoreAt: Long, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        if (isReadOnly) {
            android.util.Log.w("SisBom", "Write declinePersonalService blocked: Read-Only Mode")
            onSuccess()
            return
        }
        val data = hashMapOf(
            "estado" to "NO ASISTIR",
            "previousEstado" to originalState,
            "restoreEstadoAt" to restoreAt
        )
        db.collection("personal").document(userId)
            .update(data as Map<String, Any>)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    fun updatePersonalPassword(userId: String, newPass: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        if (isReadOnly) {
            android.util.Log.w("SisBom", "Write updatePersonalPassword blocked: Read-Only Mode")
            onSuccess()
            return
        }
        db.collection("personal").document(userId)
            .update("contrasena", newPass)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    fun updateAlertPin(alertId: String, newFijar: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        if (isReadOnly) {
            android.util.Log.w("SisBom", "Write updateAlertPin blocked: Read-Only Mode")
            onSuccess()
            return
        }
        db.collection("alertas").document(alertId)
            .update("fijar", newFijar)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    fun updateAlertConforme(alertId: String, newConforme: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        if (isReadOnly) {
            android.util.Log.w("SisBom", "Write updateAlertConforme blocked: Read-Only Mode")
            onSuccess()
            return
        }
        db.collection("alertas").document(alertId)
            .update("conforme", newConforme)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    fun sendChatMessage(alertId: String, finalChatString: String, senderRadial: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        if (isReadOnly) {
            android.util.Log.w("SisBom", "Write sendChatMessage blocked: Read-Only Mode")
            onSuccess()
            return
        }
        db.collection("alertas").document(alertId)
            .update(
                "mensajeAlerta", finalChatString,
                "conforme", senderRadial
            )
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    fun createAlert(alert: Alert, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        if (isReadOnly) {
            android.util.Log.w("SisBom", "Write createAlert blocked: Read-Only Mode")
            onSuccess()
            return
        }
        db.collection("alertas").document(alert.idAlerta)
            .set(alert)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    fun setDoorOpen(onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        if (isReadOnly) {
            android.util.Log.w("SisBom", "Write setDoorOpen blocked: Read-Only Mode")
            onSuccess()
            return
        }
        db.collection("accesos").document("central")
            .update("puerta", true)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    fun updateVehicleService(vehicleId: String, enServicio: String, onSuccess: () -> Unit) {
        if (isReadOnly) {
            android.util.Log.w("SisBom", "Write updateVehicleService blocked: Read-Only Mode")
            onSuccess()
            return
        }
        db.collection("vehiculos").document(vehicleId)
            .update("enServicio", enServicio)
            .addOnSuccessListener { onSuccess() }
    }

    fun createDispatch(dispatch: Dispatch, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        if (isReadOnly) {
            android.util.Log.w("SisBom", "Write createDispatch blocked: Read-Only Mode")
            onSuccess()
            return
        }
        val data = hashMapOf(
            "clave" to dispatch.clave,
            "lugar" to dispatch.lugar,
            "preinforme" to dispatch.preinforme,
            "carros" to dispatch.carros,
            "horaDespacho" to dispatch.horaDespacho,
            "fechaDespacho" to dispatch.fechaDespacho,
            "hora67" to "",
            "quienDespacha" to dispatch.quienDespacha,
            "operadorFinal" to ""
        )
        db.collection("despachos").document(dispatch.idServicio)
            .set(data)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    fun createDispatchNew(
        idServicio: String,
        clave: String,
        lugar: String,
        preinforme: String,
        carros: List<String>,
        operadorInicial: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (isReadOnly) {
            android.util.Log.w("SisBom", "Write createDispatchNew blocked: Read-Only Mode")
            onSuccess()
            return
        }
        val now = java.util.Date()
        val startTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(now)
        val fechaDespacho = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.getDefault()).format(now)
        val carrosStr = if (carros.isNotEmpty()) carros.joinToString(" / ") else "PERSONAL"
        
        val unidadesData = hashMapOf<String, Any>()
        carros.forEach { carro ->
            unidadesData[carro] = hashMapOf(
                "estado" to "pending_departure",
                "horaSalida" to ""
            )
        }
        
        val data = hashMapOf(
            "id" to idServicio,
            "idServicio" to idServicio,
            "estado" to "activa",
            "clave" to clave,
            "lugar" to lugar,
            "preinforme" to preinforme,
            "fechaDespacho" to fechaDespacho,
            "horaDespacho" to startTime,
            "carros" to carros,
            "carrosTexto" to carrosStr,
            "obacServicio" to "",
            "informeObac" to "",
            "fecha67" to "",
            "hora67" to "",
            "fechaTermino" to "",
            "horaTermino" to "",
            "operadorInicial" to operadorInicial,
            "operadorFinal" to "",
            "observacion" to "",
            "idListaPrincipal" to null,
            "visibleMovil" to true,
            "source" to "despacho.html",
            "createdAt" to System.currentTimeMillis(),
            "unidades" to unidadesData
        )
        
        db.collection("despachos").document(idServicio)
            .set(data)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    fun addStatusHistoryEntry(userId: String, status: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        if (isReadOnly) {
            android.util.Log.w("SisBom", "Write addStatusHistoryEntry blocked: Read-Only Mode")
            onSuccess()
            return
        }
        val subColRef = db.collection("personal").document(userId).collection("estados")
        
        subColRef.get()
            .addOnSuccessListener { snap ->
                val maxId = snap.documents.mapNotNull { it.id.toIntOrNull() }.maxOrNull() ?: 0
                val nextIdStr = (maxId + 1).toString()
                
                val now = java.util.Date()
                val fecha = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.getDefault()).format(now)
                val hora = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(now)
                
                val data = hashMapOf(
                    "estado" to status,
                    "fecha" to fecha,
                    "hora" to hora,
                    "idEstado" to nextIdStr,
                    "timestamp" to System.currentTimeMillis()
                )
                
                subColRef.document(nextIdStr)
                    .set(data)
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener { onFailure(it) }
            }
            .addOnFailureListener { onFailure(it) }
    }

fun formatProfilePhotoUrl(foto: String): String {
    val clean = foto.trim().replace("'", "").replace("\"", "")
    if (clean.isEmpty() || clean.lowercase().contains("logo.png")) return ""
    if (clean.startsWith("data:") || clean.startsWith("http://") || clean.startsWith("https://")) {
        return clean
    }
    val base = "https://sisbom-central.web.app/"
    return if (clean.startsWith("/")) {
        base + clean.substring(1)
    } else {
        base + clean
    }
}

    fun mapToUserPersonal(doc: DocumentSnapshot): UserPersonal? {
        return try {
            UserPersonal(
                idRegistro = doc.getString("idRegistro") ?: doc.id,
                nombreBombero = doc.getString("nombreBombero") ?: "",
                idRadial = doc.getString("idRadial") ?: "",
                contrasena = doc.get("contrasena")?.toString() ?: "",
                activo = doc.get("activo") ?: 0,
                conductor = when (val condVal = doc.get("conductor")) {
                    is Number -> condVal.toInt()
                    is Boolean -> if (condVal) 1 else 0
                    is String -> if (condVal.equals("SI", ignoreCase = true) || condVal == "1" || condVal.equals("S", ignoreCase = true) || condVal.equals("true", ignoreCase = true)) 1 else 0
                    else -> 0
                },
                enServicio = doc.getString("enServicio") ?: "0",
                cargo = doc.getString("cargo") ?: "",
                foto = formatProfilePhotoUrl(doc.getString("foto") ?: ""),
                estado = doc.getString("estado") ?: "",
                deviceId = doc.getString("deviceId") ?: "",
                fechaSuspensionFin = doc.getString("fechaSuspensionFin") ?: "",
                licenciaMedica = doc.getString("licenciaMedica") ?: "",
                permiso = doc.get("permiso") ?: 0,
                cds = doc.get("cds") ?: 0,
                previousEstado = doc.getString("previousEstado") ?: "",
                restoreEstadoAt = doc.getLong("restoreEstadoAt") ?: 0L,
                puerta = doc.getBoolean("puerta") ?: false
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun mapToDispatch(doc: DocumentSnapshot): Dispatch? {
        return try {
            val carrosObj = doc.get("carros")
            val carrosStr = when (carrosObj) {
                is List<*> -> {
                    carrosObj.filterIsInstance<String>().joinToString(", ")
                }
                is String -> {
                    carrosObj
                }
                else -> {
                    doc.getString("carrosTexto") ?: ""
                }
            }

            val unidadesRaw = doc.get("unidades") as? Map<*, *>
            val unidadesMap = mutableMapOf<String, Map<String, Any>>()
            if (unidadesRaw != null) {
                for ((key, value) in unidadesRaw) {
                    if (key is String && value is Map<*, *>) {
                        val innerMap = mutableMapOf<String, Any>()
                        for ((k, v) in value) {
                            if (k is String && v != null) {
                                innerMap[k] = v
                            }
                        }
                        unidadesMap[key] = innerMap
                    }
                }
            }

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
                unidades = unidadesMap,
                solicitarConfirmacion = doc.getBoolean("solicitarConfirmacion") ?: false,
                estado = doc.getString("estado") ?: ""
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun mapToAlert(doc: DocumentSnapshot): Alert? {
        return try {
            Alert(
                idAlerta = doc.id,
                tipo = doc.getString("tipo") ?: "",
                gradoAlerta = doc.getString("gradoAlerta") ?: "1",
                aQuienAlerta = doc.getString("aQuienAlerta") ?: "TC",
                quienAlerta = doc.getString("quienAlerta") ?: "",
                razonAlerta = doc.getString("razonAlerta") ?: "",
                mensajeAlerta = doc.getString("mensajeAlerta") ?: "",
                fechaAlerta = doc.getString("fechaAlerta") ?: "",
                horaAlerta = doc.getString("horaAlerta") ?: "",
                duracion = doc.getString("duracion") ?: "",
                conforme = doc.getString("conforme") ?: "",
                fijar = doc.getString("fijar") ?: "",
                numeroOrden = doc.get("numeroOrden")?.toString() ?: "",
                fechaOrden = doc.getString("fechaOrden") ?: "",
                firmaNombre = doc.getString("firmaNombre") ?: "",
                firmaCargo = doc.getString("firmaCargo") ?: ""
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun mapToVehicle(doc: DocumentSnapshot): Vehicle? {
        return try {
            Vehicle(
                idCarro = doc.id,
                clave = doc.getString("clave") ?: "",
                estado = doc.getString("estado") ?: "0-8",
                enServicio = doc.getString("enServicio") ?: "0"
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun mapToAttendanceSheet(doc: DocumentSnapshot): AttendanceSheet? {
        return try {
            AttendanceSheet(
                idLista = doc.id,
                clave = doc.getString("clave") ?: "",
                tipo = doc.getString("tipo") ?: "",
                fecha = doc.getString("fecha") ?: "",
                hora = doc.getString("hora") ?: "",
                lugar = doc.getString("lugar") ?: "",
                aprobadoPor = doc.getString("aprobadoPor") ?: "",
                anulada = doc.get("anulada") ?: 0,
                userAbono = doc.get("abono") ?: "NO",
                obac = doc.getString("obac") ?: doc.getString("obacServicio") ?: "",
                detalle = doc.getString("detalle") ?: doc.getString("detalles") ?: doc.getString("observacion") ?: ""
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun loadCachedAttendance(context: android.content.Context, userId: String): List<AttendanceSheet> {
        val prefs = context.getSharedPreferences("SisBomPrefs", android.content.Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("cached_asistencias_$userId", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(jsonStr)
            val list = mutableListOf<AttendanceSheet>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    AttendanceSheet(
                        idLista = obj.optString("idLista"),
                        clave = obj.optString("clave"),
                        tipo = obj.optString("tipo"),
                        fecha = obj.optString("fecha"),
                        hora = obj.optString("hora"),
                        lugar = obj.optString("lugar"),
                        aprobadoPor = obj.optString("aprobadoPor"),
                        anulada = obj.opt("anulada") ?: 0,
                        userEstado = obj.optString("userEstado"),
                        userAbono = obj.opt("userAbono") ?: 0,
                        obac = obj.optString("obac"),
                        detalle = obj.optString("detalle")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveCachedAttendance(context: android.content.Context, userId: String, sheets: List<AttendanceSheet>) {
        val prefs = context.getSharedPreferences("SisBomPrefs", android.content.Context.MODE_PRIVATE)
        try {
            val arr = org.json.JSONArray()
            sheets.forEach { sheet ->
                val obj = org.json.JSONObject().apply {
                    put("idLista", sheet.idLista)
                    put("clave", sheet.clave)
                    put("tipo", sheet.tipo)
                    put("fecha", sheet.fecha)
                    put("hora", sheet.hora)
                    put("lugar", sheet.lugar)
                    put("aprobadoPor", sheet.aprobadoPor)
                    put("anulada", sheet.anulada)
                    put("userEstado", sheet.userEstado)
                    put("userAbono", sheet.userAbono)
                    put("obac", sheet.obac)
                    put("detalle", sheet.detalle)
                }
                arr.put(obj)
            }
            prefs.edit().putString("cached_asistencias_$userId", arr.toString()).apply()
        } catch (_: Exception) {}
    }
}

fun isAlertActive(duracion: String): Boolean {
    val durStr = duracion.replace("'", "").replace("\"", "").trim().lowercase()
    if (durStr == "i" || durStr == "c") return true
    if (durStr.isEmpty()) return false
    
    try {
        var dPart = durStr
        var tPart = "23:59:59"
        
        if (durStr.contains(" ")) {
            val parts = durStr.split(" ")
            if (parts.size >= 2) {
                dPart = parts[0]
                tPart = parts[1]
                if (tPart.count { it == ':' } == 1) {
                    tPart += ":00"
                }
            }
        }
        
        val limitDate = if (dPart.contains("-") && dPart.split("-").firstOrNull()?.length == 2) {
            val parts = dPart.split("-")
            val tParts = tPart.split(":")
            if (parts.size >= 3) {
                val year = parts[2].toIntOrNull() ?: return true
                val month = (parts[1].toIntOrNull() ?: 1) - 1
                val day = parts[0].toIntOrNull() ?: 1
                val hour = tParts.getOrNull(0)?.toIntOrNull() ?: 23
                val min = tParts.getOrNull(1)?.toIntOrNull() ?: 59
                val sec = tParts.getOrNull(2)?.toIntOrNull() ?: 59
                val cal = java.util.Calendar.getInstance()
                cal.set(year, month, day, hour, min, sec)
                cal.time
            } else {
                null
            }
        } else {
            val formats = listOf(
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US),
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US),
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            )
            var parsed: java.util.Date? = null
            for (f in formats) {
                try {
                    parsed = f.parse(durStr)
                    if (parsed != null) {
                        if (f.toPattern() == "yyyy-MM-dd") {
                            val cal = java.util.Calendar.getInstance()
                            cal.time = parsed
                            cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
                            cal.set(java.util.Calendar.MINUTE, 59)
                            cal.set(java.util.Calendar.SECOND, 59)
                            parsed = cal.time
                        }
                        break
                    }
                } catch (_: Exception) {}
            }
            parsed
        }
        
        if (limitDate == null) return true
        
        val now = java.util.Date()
        return now.before(limitDate) || now == limitDate
    } catch (e: Exception) {
        return true
    }
}
