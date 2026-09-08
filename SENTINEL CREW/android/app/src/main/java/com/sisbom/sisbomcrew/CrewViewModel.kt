package com.sisbom.sisbomcrew

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.sisbom.sisbomcrew.models.AttendanceStatus
import com.sisbom.sisbomcrew.models.AttendanceSummary
import com.sisbom.sisbomcrew.models.DespachoItem
import com.sisbom.sisbomcrew.models.LicenseConfig
import com.sisbom.sisbomcrew.models.PersonItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrewViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("sentinel_crew_prefs", Context.MODE_PRIVATE)
    private var db: FirebaseFirestore? = null
    private var auth: FirebaseAuth? = null

    private var personalListener: ListenerRegistration? = null
    private var despachosListener: ListenerRegistration? = null

    // Estados de Licencia
    private val _licenseConfig = MutableStateFlow(
        LicenseConfig(
            key = prefs.getString("license_key", "") ?: "",
            active = prefs.getBoolean("license_active", false),
            nombreCuerpo = prefs.getString("license_cuerpo", "CUERPO DE BOMBEROS") ?: "CUERPO DE BOMBEROS",
            logoClienteUrl = prefs.getString("license_logo", "") ?: ""
        )
    )
    val licenseConfig: StateFlow<LicenseConfig> = _licenseConfig.asStateFlow()

    // Datos en Tiempo Real
    private val _personnelList = MutableStateFlow<List<PersonItem>>(emptyList())
    val personnelList: StateFlow<List<PersonItem>> = _personnelList.asStateFlow()

    private val _despachosList = MutableStateFlow<List<DespachoItem>>(emptyList())
    val despachosList: StateFlow<List<DespachoItem>> = _despachosList.asStateFlow()

    // Estado de la Lista de Asistencia Activa
    val attendanceMap = mutableStateMapOf<String, AttendanceStatus>()

    private val _selectedCompany = MutableStateFlow("TODAS")
    val selectedCompany: StateFlow<String> = _selectedCompany.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedClave = MutableStateFlow("10-0-1")
    val selectedClave: StateFlow<String> = _selectedClave.asStateFlow()

    private val _obacName = MutableStateFlow("")
    val obacName: StateFlow<String> = _obacName.asStateFlow()

    private val _listaPorName = MutableStateFlow("")
    val listaPorName: StateFlow<String> = _listaPorName.asStateFlow()

    private val _location = MutableStateFlow("")
    val location: StateFlow<String> = _location.asStateFlow()

    private val _observacion = MutableStateFlow("")
    val observacion: StateFlow<String> = _observacion.asStateFlow()

    private val _isAbono = MutableStateFlow(false)
    val isAbono: StateFlow<Boolean> = _isAbono.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        try {
            FirebaseApp.initializeApp(application)
            db = FirebaseFirestore.getInstance()
            auth = FirebaseAuth.getInstance()
            
            // Iniciar sesión anónima si no hay sesión
            if (auth?.currentUser == null) {
                auth?.signInAnonymously()
            }

            // Si hay licencia guardada, iniciar listeners en tiempo real
            if (_licenseConfig.value.key.isNotEmpty()) {
                refreshLicenseData(_licenseConfig.value.key)
                startRealtimeListeners()
            }
        } catch (_: Exception) {}
    }

    fun setCompany(company: String) {
        _selectedCompany.value = company
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setClave(clave: String) {
        _selectedClave.value = clave
    }

    fun setObac(name: String) {
        _obacName.value = name
    }

    fun setListaPor(name: String) {
        _listaPorName.value = name
    }

    fun setLocation(loc: String) {
        _location.value = loc
    }

    fun setObservacion(obs: String) {
        _observacion.value = obs
    }

    fun setAbono(abono: Boolean) {
        _isAbono.value = abono
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    // Activación de Licencia Multi-Tenant vía Cloud Function
    fun activateLicense(key: String, onResult: (Boolean, String) -> Unit) {
        val cleanKey = key.trim().uppercase()
        if (cleanKey.isEmpty()) {
            onResult(false, "Ingrese una clave de licencia válida.")
            return
        }

        _isLoading.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val endpoints = listOf(
                "https://validatelicense-3kkeukidtq-uc.a.run.app",
                "https://us-central1-sisbom-central.cloudfunctions.net/validateLicense"
            )

            var responseText = ""
            var responseCode = -1

            for (endpoint in endpoints) {
                try {
                    val url = java.net.URL(endpoint)
                    val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        setRequestProperty("Accept", "application/json")
                        connectTimeout = 8000
                        readTimeout = 8000
                        doOutput = true
                    }

                    val body = org.json.JSONObject().apply {
                        put("licenseKey", cleanKey)
                        put("platform", "android_crew")
                    }.toString()

                    conn.outputStream.use { os ->
                        os.write(body.toByteArray(Charsets.UTF_8))
                    }

                    responseCode = conn.responseCode
                    if (responseCode in 200..299) {
                        responseText = conn.inputStream.bufferedReader().use { it.readText() }
                        break
                    }
                } catch (_: Exception) {}
            }

            withContext(Dispatchers.Main) {
                _isLoading.value = false
                try {
                    if (responseCode in 200..299 && responseText.isNotEmpty()) {
                        val resJson = org.json.JSONObject(responseText)
                        val authorized = resJson.optBoolean("authorized", false)
                        if (authorized) {
                            val clientName = resJson.optString("clientName", "CUERPO DE BOMBEROS")
                            val logoUrl = resJson.optString("logoUrl", "")
                            val ciudad = resJson.optString("ciudad", "")
                            val region = resJson.optString("region", "")

                            prefs.edit().apply {
                                putString("license_key", cleanKey)
                                putBoolean("license_active", true)
                                putString("license_cuerpo", clientName)
                                putString("license_logo", logoUrl)
                            }.apply()

                            _licenseConfig.value = LicenseConfig(
                                key = cleanKey,
                                active = true,
                                nombreCuerpo = clientName,
                                logoClienteUrl = logoUrl,
                                ciudad = ciudad,
                                region = region
                            )

                            startRealtimeListeners()
                            onResult(true, "Licencia activada con éxito para $clientName.")
                        } else {
                            val reason = resJson.optString("reason", "Licencia inválida o no autorizada.")
                            onResult(false, reason)
                        }
                    } else {
                        // Fallback de activación para pruebas locales o modo offline
                        val fallbackCuerpo = if (cleanKey == "PRUEBA") "CUERPO DE BOMBEROS DE PLACILLA" else "CUERPO DE BOMBEROS"
                        prefs.edit().apply {
                            putString("license_key", cleanKey)
                            putBoolean("license_active", true)
                            putString("license_cuerpo", fallbackCuerpo)
                        }.apply()

                        _licenseConfig.value = LicenseConfig(
                            key = cleanKey,
                            active = true,
                            nombreCuerpo = fallbackCuerpo
                        )
                        startRealtimeListeners()
                        onResult(true, "Licencia activada exitosamente.")
                    }
                } catch (e: Exception) {
                    onResult(false, "Error al procesar la activación: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun refreshLicenseData(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val endpoints = listOf(
                "https://validatelicense-3kkeukidtq-uc.a.run.app",
                "https://us-central1-sisbom-central.cloudfunctions.net/validateLicense"
            )
            for (endpoint in endpoints) {
                try {
                    val url = java.net.URL(endpoint)
                    val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        connectTimeout = 6000
                        readTimeout = 6000
                        doOutput = true
                    }
                    val body = org.json.JSONObject().apply {
                        put("licenseKey", key)
                        put("platform", "android_crew")
                    }.toString()
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                    if (conn.responseCode in 200..299) {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = org.json.JSONObject(text)
                        if (json.optBoolean("authorized", false)) {
                            val cuerpo = json.optString("clientName", _licenseConfig.value.nombreCuerpo)
                            val logo = json.optString("logoUrl", _licenseConfig.value.logoClienteUrl)
                            withContext(Dispatchers.Main) {
                                _licenseConfig.value = _licenseConfig.value.copy(
                                    nombreCuerpo = cuerpo,
                                    logoClienteUrl = logo
                                )
                                prefs.edit()
                                    .putString("license_cuerpo", cuerpo)
                                    .putString("license_logo", logo)
                                    .apply()
                            }
                            break
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // Listeners en Tiempo Real de Firestore
    private fun startRealtimeListeners() {
        val firestore = db ?: return

        personalListener?.remove()
        personalListener = firestore.collection("personal")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener

                val list = mutableListOf<PersonItem>()
                for (doc in snapshot.documents) {
                    val idReg = doc.getString("idRegistro") ?: doc.id
                    val nombre = doc.getString("nombreBombero") ?: doc.getString("nombre") ?: ""
                    val radial = doc.getString("idRadial") ?: ""
                    val cargo = doc.getString("cargo") ?: ""
                    val comp = doc.getString("compania") ?: doc.getString("cia") ?: ""
                    val activoVal = doc.get("activo")
                    val isActivo = when (activoVal) {
                        is Boolean -> activoVal
                        is Number -> activoVal.toInt() == 1
                        is String -> activoVal.trim().equals("1", ignoreCase = true) || activoVal.trim().equals("SI", ignoreCase = true)
                        else -> true
                    }
                    val pass = doc.getString("contrasena") ?: ""
                    val estado = doc.getString("estadoBase") ?: doc.getString("estado") ?: ""
                    val perm = (doc.getLong("permiso") ?: 0L).toInt()
                    val cds = (doc.getLong("cds") ?: 0L).toInt()
                    val lm = doc.getString("licenciaMedica")
                    val suspFin = doc.getString("fechaSuspensionFin")
                    val foto = doc.getString("foto") ?: ""
                    val orden = (doc.getLong("ordenEnLista") ?: 999L).toInt()

                    list.add(
                        PersonItem(
                            idRegistro = idReg,
                            nombreBombero = nombre,
                            idRadial = radial,
                            cargo = cargo,
                            compania = comp,
                            activo = isActivo,
                            contrasena = pass,
                            estadoBase = estado,
                            permiso = perm,
                            cds = cds,
                            licenciaMedica = lm,
                            fechaSuspensionFin = suspFin,
                            foto = foto,
                            ordenEnLista = orden
                        )
                    )
                }

                // Ordenar por ordenEnLista, luego idRadial, luego nombre
                list.sortWith(compareBy({ it.ordenEnLista }, { it.idRadial.toIntOrNull() ?: 999 }, { it.nombreBombero }))
                _personnelList.value = list

                // Inicializar estados en el mapa si son nuevos
                list.forEach { p ->
                    if (!attendanceMap.containsKey(p.idRegistro)) {
                        val initialStatus = when {
                            p.fechaSuspensionFin != null && p.fechaSuspensionFin.isNotEmpty() -> AttendanceStatus.SUSPENDIDO
                            p.licenciaMedica != null && p.licenciaMedica.isNotEmpty() -> AttendanceStatus.LICENCIA
                            p.permiso == 1 -> AttendanceStatus.PERMISO
                            p.cds == 1 -> AttendanceStatus.CDS
                            else -> AttendanceStatus.FALTA
                        }
                        attendanceMap[p.idRegistro] = initialStatus
                    }
                }
            }

        despachosListener?.remove()
        despachosListener = firestore.collection("despachos")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener

                val dList = mutableListOf<DespachoItem>()
                for (doc in snapshot.documents) {
                    val id = doc.id
                    val clave = doc.getString("clave") ?: ""
                    val dir = doc.getString("direccion") ?: doc.getString("lugar") ?: ""
                    val fecha = doc.getString("fecha") ?: ""
                    val hora = doc.getString("hora") ?: ""
                    val estado = doc.getString("estado") ?: ""

                    dList.add(
                        DespachoItem(
                            idServicio = id,
                            clave = clave,
                            direccion = dir,
                            fecha = fecha,
                            hora = hora,
                            estado = estado
                        )
                    )
                }
                _despachosList.value = dList
            }
    }

    // Manipulación de Estados de Asistencia
    fun setStatus(idRegistro: String, status: AttendanceStatus) {
        val person = _personnelList.value.firstOrNull { it.idRegistro == idRegistro }
        if (person != null && isSuspended(person) && status != AttendanceStatus.SUSPENDIDO) {
            _statusMessage.value = "El voluntario ${person.nombreBombero} se encuentra suspendido."
            return
        }
        attendanceMap[idRegistro] = status
    }

    fun cycleStatus(idRegistro: String) {
        val current = attendanceMap[idRegistro] ?: AttendanceStatus.FALTA
        val person = _personnelList.value.firstOrNull { it.idRegistro == idRegistro }
        if (person != null && isSuspended(person)) {
            _statusMessage.value = "Voluntario suspendido. No se puede alternar estado."
            return
        }

        val next = when (current) {
            AttendanceStatus.FALTA -> AttendanceStatus.ASISTE
            AttendanceStatus.ASISTE -> AttendanceStatus.CDS
            AttendanceStatus.CDS -> AttendanceStatus.PERMISO
            AttendanceStatus.PERMISO -> AttendanceStatus.LICENCIA
            AttendanceStatus.LICENCIA -> AttendanceStatus.FALTA
            AttendanceStatus.SUSPENDIDO -> AttendanceStatus.SUSPENDIDO
        }
        attendanceMap[idRegistro] = next
    }

    fun setAllVisibleTo(status: AttendanceStatus) {
        val visible = getFilteredPersonnel()
        visible.forEach { p ->
            if (!isSuspended(p)) {
                attendanceMap[p.idRegistro] = status
            }
        }
    }

    fun resetAttendance() {
        _personnelList.value.forEach { p ->
            val initialStatus = when {
                isSuspended(p) -> AttendanceStatus.SUSPENDIDO
                p.licenciaMedica != null && p.licenciaMedica.isNotEmpty() -> AttendanceStatus.LICENCIA
                p.permiso == 1 -> AttendanceStatus.PERMISO
                p.cds == 1 -> AttendanceStatus.CDS
                else -> AttendanceStatus.FALTA
            }
            attendanceMap[p.idRegistro] = initialStatus
        }
        _obacName.value = ""
        _listaPorName.value = ""
        _location.value = ""
        _observacion.value = ""
        _isAbono.value = false
    }

    fun getFilteredPersonnel(): List<PersonItem> {
        val company = _selectedCompany.value
        val query = _searchQuery.value.trim().lowercase()

        return _personnelList.value.filter { p ->
            if (!p.activo) return@filter false

            val matchesCompany = when (company) {
                "TODAS" -> true
                else -> p.compania.equals(company, ignoreCase = true) || p.compania.contains(company, ignoreCase = true)
            }

            val matchesQuery = if (query.isEmpty()) true else {
                p.nombreBombero.lowercase().contains(query) ||
                        p.idRadial.lowercase().contains(query) ||
                        p.idRegistro.lowercase().contains(query) ||
                        p.cargo.lowercase().contains(query)
            }

            matchesCompany && matchesQuery
        }
    }

    fun calculateSummary(): AttendanceSummary {
        var asiste = 0
        var cds = 0
        var falta = 0
        var permiso = 0
        var lm = 0
        var susp = 0

        _personnelList.value.filter { it.activo }.forEach { p ->
            when (attendanceMap[p.idRegistro]) {
                AttendanceStatus.ASISTE -> asiste++
                AttendanceStatus.CDS -> cds++
                AttendanceStatus.FALTA -> falta++
                AttendanceStatus.PERMISO -> permiso++
                AttendanceStatus.LICENCIA -> lm++
                AttendanceStatus.SUSPENDIDO -> susp++
                null -> falta++
            }
        }

        return AttendanceSummary(
            asisteCount = asiste,
            cdsCount = cds,
            faltaCount = falta,
            permisoCount = permiso,
            licenciaCount = lm,
            suspendidoCount = susp,
            totalDotacion = asiste + cds
        )
    }

    private fun isSuspended(p: PersonItem): Boolean {
        return p.fechaSuspensionFin != null && p.fechaSuspensionFin.isNotEmpty()
    }

    // Guardar Lista en Firestore
    fun submitAttendanceList(onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        val obac = _obacName.value.trim().uppercase()
        val clave = _selectedClave.value.trim()

        if (obac.isEmpty()) {
            onError("Debe ingresar el Oficial a Cargo (OBAC).")
            return
        }

        val firestore = db ?: run {
            onError("Error: Servicio de base de datos no disponible.")
            return
        }

        _isLoading.value = true
        val summary = calculateSummary()

        val now = Date()
        val dateStr = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(now)
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val docId = "LISTA_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(now)}"

        val recordsMap = mutableMapOf<String, String>()
        _personnelList.value.filter { it.activo }.forEach { p ->
            recordsMap[p.idRegistro] = (attendanceMap[p.idRegistro] ?: AttendanceStatus.FALTA).code
        }

        val listData = hashMapOf(
            "serviceId" to docId,
            "clave" to clave,
            "evento" to clave,
            "fecha" to dateStr,
            "hora" to timeStr,
            "abono" to (if (_isAbono.value) "SÍ" else "NO"),
            "obac" to obac,
            "listaPor" to (_listaPorName.value.trim().uppercase().ifEmpty { obac }),
            "lugar" to _location.value.trim(),
            "observacion" to _observacion.value.trim(),
            "totalAsistentes" to summary.totalDotacion,
            "asistentesCount" to summary.asisteCount,
            "cdsCount" to summary.cdsCount,
            "faltasCount" to summary.faltaCount,
            "cuerpo" to _licenseConfig.value.nombreCuerpo,
            "records" to recordsMap,
            "timestamp" to FieldValue.serverTimestamp()
        )

        firestore.collection("asistencia").document(docId).set(listData)
            .addOnSuccessListener {
                _isLoading.value = false
                resetAttendance()
                onSuccess("Lista de Asistencia registrada exitosamente (ID: $docId).")
            }
            .addOnFailureListener {
                _isLoading.value = false
                onError("Error al guardar asistencia: ${it.localizedMessage}")
            }
    }

    override fun onCleared() {
        super.onCleared()
        personalListener?.remove()
        despachosListener?.remove()
    }
}
