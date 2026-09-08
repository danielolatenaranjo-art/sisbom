package com.sisbom.sisbomtel

import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
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
            if (FirebaseApp.getApps(FirebaseApp.getInstance().applicationContext).isNotEmpty()) {
                FirebaseFirestore.getInstance()
            } else null
        } catch (_: Exception) {
            null
        }

    // 1. Verificar credenciales y cargo de Comandante (ID Radial 1)
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

            var authSuccessful = false
            try {
                val auth = FirebaseAuth.getInstance()
                val email = "$cleanId@sisbom.com"
                val securePass = "${cleanPass}_secure_sisbom"
                Tasks.await(
                    auth.signInWithEmailAndPassword(email, securePass),
                    3, TimeUnit.SECONDS
                )
                authSuccessful = (auth.currentUser != null)
            } catch (_: Exception) {}

            var docSnap: DocumentSnapshot? = null
            val collections = listOf("personal", "usuarios", "bomberos")

            for (colName in collections) {
                if (docSnap != null && docSnap.exists()) break

                // Direct doc(cleanId)
                try {
                    val directSnap = Tasks.await(firestore.collection(colName).document(cleanId).get(), 4, TimeUnit.SECONDS)
                    if (directSnap.exists()) {
                        docSnap = directSnap
                        break
                    }
                } catch (_: Exception) {}

                // Query whereEqualTo("idRegistro", cleanId)
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

                // Query whereEqualTo("idRegistro", Number)
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

                // Query whereEqualTo("idRadial", cleanId)
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
                return@withContext Result.failure(Exception("No se encontró el usuario $cleanId en la base de datos."))
            }

            val rawPass = docSnap.getString("contrasena")
                ?: docSnap.get("contrasena")?.toString()
                ?: docSnap.getString("password")
                ?: docSnap.get("password")?.toString()
                ?: ""

            if (!authSuccessful && rawPass.isNotEmpty() && rawPass.trim() != cleanPass) {
                return@withContext Result.failure(Exception("Contraseña incorrecta."))
            }

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
                    cargo.uppercase().contains("COMANDANTE") ||
                    cargo.uppercase().contains("CAPITAN") ||
                    cargo.uppercase().contains("DIRECTOR") ||
                    cargo.uppercase().contains("SUPERINTENDENTE") ||
                    cargo.uppercase().contains("CENTRAL") ||
                    isAdmin

            if (!isComandante) {
                return@withContext Result.failure(Exception("Se requiere autorización de Comandante (ID Radial 1) para enrolar el teléfono de la Central."))
            }

            val nombre = docSnap.getString("nombre") ?: docSnap.get("nombre")?.toString() ?: "Comandante"
            val regFinal = docSnap.getString("idRegistro") ?: docSnap.get("idRegistro")?.toString() ?: cleanId

            Result.success(
                ComandanteAuth(
                    idRegistro = regFinal,
                    nombre = nombre,
                    cargo = if (cargo.isNotEmpty()) cargo else "Comandante",
                    idRadial = if (idRadial.isNotEmpty()) idRadial else "1"
                )
            )
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Error al autenticar"))
        }
    }

    // 2. Accionamiento de Puerta / Portón Cuartel
    fun triggerDoorOpen(
        solicitante: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val firestore = db ?: run {
            onFailure(Exception("Base de datos no inicializada"))
            return
        }

        val payload = hashMapOf<String, Any>(
            "puerta" to true,
            "timestamp" to System.currentTimeMillis(),
            "fechaHora" to SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault()).format(Date()),
            "solicitante" to solicitante,
            "origen" to "SisBomTel"
        )

        firestore.collection("accesos").document("central")
            .set(payload, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { err -> onFailure(err) }
    }

    private var lastRegisteredCallId: String? = null
    private var lastRegisteredPhone: String = ""
    private var lastRegisteredTime: Long = 0L

    // 3. Registrar Llamada Entrante en Tiempo Real para SisBom.exe
    fun registerIncomingCall(
        rawPhone: String,
        estado: String = "TIMBRANDO",
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val firestore = db ?: return
        val cleanPhone = sanitizePhoneNumber(rawPhone)
        val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val dateNow = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date())
        val timestamp = System.currentTimeMillis()

        val callData = hashMapOf<String, Any>(
            "telefono" to cleanPhone,
            "rawTelefono" to rawPhone,
            "estado" to estado,
            "hora" to timeNow,
            "fecha" to dateNow,
            "timestamp" to timestamp,
            "origen" to "SENTINEL LINK",
            "activo" to (estado == "TIMBRANDO" || estado == "ATENDIDA")
        )

        // Documento central para que SisBom.exe lo capture inmediatamente
        firestore.collection("llamadas").document("central")
            .set(callData, com.google.firebase.firestore.SetOptions.merge())

        val isSameCall = cleanPhone == lastRegisteredPhone && (timestamp - lastRegisteredTime < 25000L) && lastRegisteredCallId != null
        val logId = if (isSameCall) {
            lastRegisteredCallId!!
        } else {
            val newId = "${dateNow}_${timeNow.replace(":", "")}_$cleanPhone"
            lastRegisteredCallId = newId
            lastRegisteredPhone = cleanPhone
            lastRegisteredTime = timestamp
            newId
        }

        firestore.collection("historial_llamadas").document(logId)
            .set(callData, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { err -> onFailure(err) }
    }

    fun updateActiveCallState(rawPhone: String, estado: String) {
        val firestore = db ?: return
        val cleanPhone = sanitizePhoneNumber(rawPhone)
        val callData = hashMapOf<String, Any>(
            "telefono" to cleanPhone,
            "rawTelefono" to rawPhone,
            "estado" to estado,
            "origen" to "SENTINEL LINK",
            "activo" to (estado == "TIMBRANDO" || estado == "ATENDIDA")
        )
        firestore.collection("llamadas").document("central")
            .set(callData, com.google.firebase.firestore.SetOptions.merge())
    }

    // 4. Finalizar llamada en Central
    fun finishIncomingCall(rawPhone: String) {
        val firestore = db ?: return
        val cleanPhone = sanitizePhoneNumber(rawPhone)
        val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val dateNow = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date())

        val updateData = hashMapOf<String, Any>(
            "estado" to "FINALIZADA",
            "activo" to false,
            "horaTermino" to timeNow,
            "fechaTermino" to dateNow,
            "timestampTermino" to System.currentTimeMillis()
        )

        firestore.collection("llamadas").document("central")
            .set(updateData, com.google.firebase.firestore.SetOptions.merge())
    }

    // 5. Escuchar flujo de llamadas recientes
    fun getRecentCallsFlow(): Flow<List<CallItem>> = callbackFlow {
        val firestore = db
        if (firestore == null) {
            trySend(emptyList())
            awaitClose {}
            return@callbackFlow
        }

        try {
            val listener = firestore.collection("historial_llamadas")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(25)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val list = snapshot?.documents?.mapNotNull { doc ->
                        CallItem(
                            id = doc.id,
                            telefono = doc.getString("telefono") ?: "",
                            fecha = doc.getString("fecha") ?: "",
                            hora = doc.getString("hora") ?: "",
                            timestamp = doc.getLong("timestamp") ?: 0L,
                            estado = doc.getString("estado") ?: "FINALIZADA",
                            nombre = doc.getString("nombre") ?: "",
                            origen = doc.getString("origen") ?: "SisBomTel"
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

    // 6. Escuchar cola de SMS pendientes desde Firestore
    fun getSmsQueueFlow(): Flow<List<SmsQueueItem>> = callbackFlow {
        val firestore = db
        if (firestore == null) {
            trySend(emptyList())
            awaitClose {}
            return@callbackFlow
        }

        try {
            val listener = firestore.collection("sms_queue")
                .whereEqualTo("estado", "PENDIENTE")
                .limit(10)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val list = snapshot?.documents?.mapNotNull { doc ->
                        SmsQueueItem(
                            id = doc.id,
                            idDespacho = doc.getString("idDespacho") ?: "",
                            telefono = doc.getString("telefono") ?: "",
                            mensaje = doc.getString("mensaje") ?: "",
                            enlace = doc.getString("enlace") ?: "",
                            estado = doc.getString("estado") ?: "PENDIENTE",
                            timestamp = doc.getLong("timestamp") ?: 0L,
                            fecha = doc.getString("fecha") ?: "",
                            hora = doc.getString("hora") ?: "",
                            canal = doc.getString("canal") ?: "sms"
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

    // 7. Actualizar estado de SMS enviado
    fun updateSmsStatus(
        smsId: String,
        idDespacho: String = "",
        estado: String, // "ENVIADO", "FALLIDO"
        errorMsg: String = "",
        onComplete: () -> Unit = {}
    ) {
        val firestore = db ?: return
        val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val dateNow = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date())

        val updateMap = hashMapOf<String, Any>(
            "estado" to estado,
            "horaEnvio" to timeNow,
            "fechaEnvio" to dateNow,
            "timestampEnvio" to System.currentTimeMillis(),
            "error" to errorMsg
        )

        // Actualizar en la cola
        if (smsId.isNotEmpty()) {
            firestore.collection("sms_queue").document(smsId)
                .update(updateMap)
                .addOnCompleteListener { onComplete() }
        }

        // Si pertenece a un despacho, actualizar el subcampo smsSolicitudGeo en despachos
        if (idDespacho.isNotEmpty()) {
            val despachoSmsUpdate = hashMapOf<String, Any>(
                "smsSolicitudGeo.estado" to estado,
                "smsSolicitudGeo.horaEnvio" to timeNow,
                "smsSolicitudGeo.fechaEnvio" to dateNow,
                "smsSolicitudGeo.timestampEnvio" to System.currentTimeMillis()
            )
            firestore.collection("despachos").document(idDespacho)
                .update(despachoSmsUpdate)
        }
    }

    // 8. Crear solicitud directa de SMS en la cola
    fun enqueueSms(
        telefono: String,
        mensaje: String,
        enlace: String = "",
        idDespacho: String = "",
        onSuccess: (String) -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val firestore = db ?: run {
            onFailure(Exception("Base de datos no inicializada"))
            return
        }

        val cleanPhone = sanitizePhoneNumber(telefono)
        val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val dateNow = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date())
        val timestamp = System.currentTimeMillis()

        val smsData = hashMapOf<String, Any>(
            "telefono" to cleanPhone,
            "mensaje" to mensaje,
            "enlace" to enlace,
            "idDespacho" to idDespacho,
            "estado" to "PENDIENTE",
            "fecha" to dateNow,
            "hora" to timeNow,
            "timestamp" to timestamp,
            "canal" to "sms",
            "origen" to "SisBomTel"
        )

        firestore.collection("sms_queue").add(smsData)
            .addOnSuccessListener { ref -> onSuccess(ref.id) }
            .addOnFailureListener { err -> onFailure(err) }
    }

    // 9. Escuchar sesión activa de Central en tiempo real
    fun getActiveCentralSessionFlow(): Flow<CentralSession?> = callbackFlow {
        val firestore = db
        if (firestore == null) {
            trySend(null)
            awaitClose {}
            return@callbackFlow
        }

        try {
            val listener = firestore.collection("accesos").document("central")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null || !snapshot.exists()) {
                        trySend(null)
                        return@addSnapshotListener
                    }
                    val estado = snapshot.getString("estado") ?: "cerrado"
                    val session = CentralSession(
                        estado = estado,
                        idInicio = snapshot.getString("idInicio") ?: "",
                        idRegistro = snapshot.getString("idRegistro") ?: "",
                        cargo = snapshot.getString("cargo") ?: "",
                        nombreBombero = snapshot.getString("nombreBombero") ?: snapshot.getString("operador") ?: "",
                        operador = snapshot.getString("operador") ?: snapshot.getString("nombreBombero") ?: "",
                        fechaIngreso = snapshot.getString("fechaIngreso") ?: "",
                        horaIngreso = snapshot.getString("horaIngreso") ?: "",
                        origen = snapshot.getString("origen") ?: "",
                        timestamp = snapshot.getLong("timestamp") ?: 0L
                    )
                    trySend(session)
                }
            awaitClose { listener.remove() }
        } catch (e: Exception) {
            trySend(null)
            awaitClose {}
        }
    }

    // 10. Iniciar sesión de operador para la Central CAD
    suspend fun startCentralSession(
        user: PersonItem,
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val firestore = db ?: run {
            withContext(Dispatchers.Main) { onFailure(Exception("Base de datos no inicializada")) }
            return@withContext
        }

        try {
            val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val dateNow = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date())
            val timestamp = System.currentTimeMillis()

            var nextSessionId = 1L
            try {
                val regsSnap = Tasks.await(firestore.collection("accesos").document("central").collection("registros").get(), 4, TimeUnit.SECONDS)
                regsSnap.documents.forEach { doc ->
                    val idVal = doc.getLong("idInicio") ?: doc.getString("idInicio")?.toLongOrNull() ?: 0L
                    if (idVal >= nextSessionId) nextSessionId = idVal + 1
                }
            } catch (_: Exception) {}

            val regPayload = hashMapOf<String, Any>(
                "idInicio" to nextSessionId.toString(),
                "idRegistro" to user.idRegistro,
                "cargo" to user.cargo.ifEmpty { "Operador" },
                "nombreBombero" to user.nombreBombero,
                "operador" to user.nombreBombero,
                "fechaIngreso" to dateNow,
                "horaIngreso" to timeNow,
                "fechaCierre" to "",
                "horaCierre" to "",
                "estado" to "activo",
                "origen" to "SENTINEL LINK",
                "timestamp" to timestamp
            )

            val centralPayload = hashMapOf<String, Any>(
                "estado" to "activo",
                "idInicio" to nextSessionId.toString(),
                "idRegistro" to user.idRegistro,
                "cargo" to user.cargo.ifEmpty { "Operador" },
                "nombreBombero" to user.nombreBombero,
                "operador" to user.nombreBombero,
                "fechaIngreso" to dateNow,
                "horaIngreso" to timeNow,
                "origen" to "SENTINEL LINK",
                "timestamp" to timestamp
            )

            Tasks.await(
                firestore.collection("accesos").document("central")
                    .collection("registros").document(nextSessionId.toString())
                    .set(regPayload, com.google.firebase.firestore.SetOptions.merge()),
                4, TimeUnit.SECONDS
            )

            Tasks.await(
                firestore.collection("accesos").document("central")
                    .set(centralPayload, com.google.firebase.firestore.SetOptions.merge()),
                4, TimeUnit.SECONDS
            )

            withContext(Dispatchers.Main) { onSuccess() }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onFailure(e) }
        }
    }

    // 11. Cerrar sesión de operador en la Central CAD
    suspend fun closeCentralSession(
        sessionId: String = "",
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val firestore = db ?: run {
            withContext(Dispatchers.Main) { onFailure(Exception("Base de datos no inicializada")) }
            return@withContext
        }

        try {
            val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val dateNow = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date())
            val timestamp = System.currentTimeMillis()

            val closePayload = hashMapOf<String, Any>(
                "estado" to "cerrado",
                "idInicio" to "",
                "idRegistro" to "",
                "cargo" to "",
                "nombreBombero" to "",
                "operador" to "",
                "fechaCierre" to dateNow,
                "horaCierre" to timeNow,
                "timestampCierre" to timestamp,
                "origen" to "SENTINEL LINK"
            )

            Tasks.await(
                firestore.collection("accesos").document("central")
                    .set(closePayload, com.google.firebase.firestore.SetOptions.merge()),
                4, TimeUnit.SECONDS
            )

            if (sessionId.isNotEmpty()) {
                val regClose = hashMapOf<String, Any>(
                    "estado" to "cerrado",
                    "fechaCierre" to dateNow,
                    "horaCierre" to timeNow,
                    "timestampCierre" to timestamp
                )
                try {
                    Tasks.await(
                        firestore.collection("accesos").document("central")
                            .collection("registros").document(sessionId)
                            .set(regClose, com.google.firebase.firestore.SetOptions.merge()),
                        3, TimeUnit.SECONDS
                    )
                } catch (_: Exception) {}
            }

            withContext(Dispatchers.Main) { onSuccess() }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onFailure(e) }
        }
    }

    // 12. Obtener lista de personal para selección de operador
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
                    if (error != null || snapshot == null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val list = snapshot.documents.mapNotNull { doc ->
                        val activoVal = doc.get("activo")
                        val isActivo = when (activoVal) {
                            is Boolean -> activoVal
                            is Number -> activoVal.toInt() == 1
                            is String -> activoVal.trim().uppercase() in listOf("SI", "1", "TRUE", "ACTIVO")
                            else -> true
                        }
                        if (!isActivo) return@mapNotNull null

                        PersonItem(
                            idRegistro = doc.getString("idRegistro") ?: doc.id,
                            nombreBombero = doc.getString("nombreBombero") ?: doc.getString("nombre") ?: "",
                            cargo = doc.getString("cargo") ?: "Bombero",
                            idRadial = doc.getString("idRadial") ?: "",
                            contrasena = doc.getString("contrasena") ?: doc.getString("password") ?: "",
                            activo = "SI",
                            foto = doc.getString("foto") ?: ""
                        )
                    }.sortedBy { it.nombreBombero }
                    trySend(list)
                }
            awaitClose { listener.remove() }
        } catch (e: Exception) {
            trySend(emptyList())
            awaitClose {}
        }
    }

    suspend fun findPersonByCredentials(user: String, pass: String): PersonItem? = withContext(Dispatchers.IO) {
        val firestore = db ?: return@withContext null
        try {
            val docSnap = Tasks.await(firestore.collection("personal").document(user).get(), 4, TimeUnit.SECONDS)
            if (docSnap.exists()) {
                val passDb = docSnap.getString("contrasena") ?: docSnap.getString("password") ?: ""
                if (passDb == pass) {
                    return@withContext PersonItem(
                        idRegistro = docSnap.getString("idRegistro") ?: docSnap.id,
                        nombreBombero = docSnap.getString("nombreBombero") ?: docSnap.getString("nombre") ?: user,
                        cargo = docSnap.getString("cargo") ?: "Bombero",
                        idRadial = docSnap.getString("idRadial") ?: "",
                        contrasena = passDb,
                        activo = docSnap.getString("activo") ?: "SI"
                    )
                }
            }
            val querySnap = Tasks.await(firestore.collection("personal").whereEqualTo("idRegistro", user).get(), 4, TimeUnit.SECONDS)
            for (doc in querySnap.documents) {
                val passDb = doc.getString("contrasena") ?: doc.getString("password") ?: ""
                if (passDb == pass) {
                    return@withContext PersonItem(
                        idRegistro = doc.getString("idRegistro") ?: doc.id,
                        nombreBombero = doc.getString("nombreBombero") ?: doc.getString("nombre") ?: user,
                        cargo = doc.getString("cargo") ?: "Bombero",
                        idRadial = doc.getString("idRadial") ?: "",
                        contrasena = passDb,
                        activo = doc.getString("activo") ?: "SI"
                    )
                }
            }
        } catch (_: Exception) {}
        null
    }

    companion object {
        fun sanitizePhoneNumber(raw: String): String {
            val digits = raw.replace(Regex("[^0-9+]"), "")
            return when {
                digits.startsWith("+569") -> digits
                digits.startsWith("569") -> "+$digits"
                digits.startsWith("9") && digits.length == 9 -> "+56$digits"
                digits.length == 8 -> "+569$digits"
                else -> if (digits.isNotEmpty()) digits else raw.trim()
            }
        }
    }
}
