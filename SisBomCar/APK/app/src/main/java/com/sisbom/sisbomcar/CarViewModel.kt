package com.sisbom.sisbomcar

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CarViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val prefs: SharedPreferences = context.getSharedPreferences("SisBomCarPrefs", Context.MODE_PRIVATE)
    private val repository = FirebaseRepository()

    // Estado SaaS
    var saasLicenseKey by mutableStateOf(prefs.getString("saas_license_key", "") ?: "")
    var saasClientName by mutableStateOf(prefs.getString("saas_client_name", "") ?: "")
    var saasLogoUrl by mutableStateOf(prefs.getString("saas_logo_url", "") ?: "")
    var isLicenseValid by mutableStateOf(prefs.getString("saas_license_key", "")?.isNotEmpty() == true)
    var isActivatingLicense by mutableStateOf(false)
    var saasActivationError by mutableStateOf("")

    // Unidad Seleccionada
    var selectedUnitId by mutableStateOf(prefs.getString("selected_unit_id", "") ?: "")
    var selectedUnitLabel by mutableStateOf(prefs.getString("selected_unit_label", "") ?: "")
    var selectedUnitPatente by mutableStateOf(prefs.getString("selected_unit_patente", "") ?: "")
    var selectedUnitTipo by mutableStateOf(prefs.getString("selected_unit_tipo", "") ?: "")
    var currentUnitVehicle by mutableStateOf<Vehicle?>(null)

    // Autorización de Comandante (idRadial 1)
    var isComandanteAuthenticated by mutableStateOf(prefs.getString("auth_comandante_id", "")?.isNotEmpty() == true)
    var authorizedComandanteName by mutableStateOf(prefs.getString("auth_comandante_name", "") ?: "")
    var isVerifyingComandante by mutableStateOf(false)
    var comandanteAuthError by mutableStateOf("")

    // Listas en tiempo real
    var availableVehicles = mutableStateListOf<Vehicle>()
    var dispatchesList = mutableStateListOf<Dispatch>()
    var personalList = mutableStateListOf<PersonItem>()
    var activeDispatch by mutableStateOf<Dispatch?>(null)
    var activeBitacoraTrip by mutableStateOf<BitacoraTrip?>(null)

    // UI state
    var isAppDrawerOpen by mutableStateOf(false)
    var isFleetDrawerOpen by mutableStateOf(false)
    var isUnitStatus08ModalOpen by mutableStateOf(false)
    var isBitacoraModalOpen by mutableStateOf(false)
    var isCrewModalOpen by mutableStateOf(false)
    var currentSpeedKmH by mutableStateOf(0f)
    var currentTimeString by mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
    var currentDateString by mutableStateOf(SimpleDateFormat("EEE, dd MMM yyyy", Locale("es", "CL")).format(Date()).uppercase())

    private var vehicleSelfJob: kotlinx.coroutines.Job? = null
    private var dispatchesJob: kotlinx.coroutines.Job? = null
    private var personalJob: kotlinx.coroutines.Job? = null
    private var bitacoraSelfJob: kotlinx.coroutines.Job? = null

    init {
        // Reloj y velocímetro en vivo
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000L)
                currentTimeString = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                currentDateString = SimpleDateFormat("EEE, dd MMM yyyy", Locale("es", "CL")).format(Date()).uppercase()
                currentSpeedKmH = GpsTrackingService.currentSpeedKmH.takeIf { it > 0f } ?: (currentUnitVehicle?.speed ?: 0f)
            }
        }

        val fbConfig = prefs.getString("saas_firebase_config", null)
        if (fbConfig != null) {
            initializeDynamicFirebase(context, fbConfig)
        }

        if (isLicenseValid) {
            fetchVehiclesList()
            subscribeToData()
        }
    }

    fun verifyComandante(idReg: String, pass: String, onResult: (Boolean) -> Unit) {
        if (idReg.isBlank() || pass.isBlank()) {
            comandanteAuthError = "Ingrese ID de Registro y Contraseña"
            onResult(false)
            return
        }

        isVerifyingComandante = true
        comandanteAuthError = ""

        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.verifyComandanteCredentials(idReg.trim(), pass.trim())
            isVerifyingComandante = false
            if (result.isSuccess) {
                val auth = result.getOrNull()
                isComandanteAuthenticated = true
                authorizedComandanteName = auth?.nombre ?: "Comandante"
                prefs.edit().apply {
                    putString("auth_comandante_id", auth?.idRegistro ?: idReg)
                    putString("auth_comandante_name", auth?.nombre ?: "Comandante")
                }.apply()
                onResult(true)
            } else {
                comandanteAuthError = result.exceptionOrNull()?.message ?: "Error de autenticación"
                onResult(false)
            }
        }
    }

    fun selectVehicle(unitId: String, unitLabel: String, patente: String = "", tipo: String = "") {
        selectedUnitId = unitId
        selectedUnitLabel = unitLabel
        selectedUnitPatente = patente
        selectedUnitTipo = tipo
        prefs.edit().apply {
            putString("selected_unit_id", unitId)
            putString("selected_unit_label", unitLabel)
            putString("selected_unit_patente", patente)
            putString("selected_unit_tipo", tipo)
        }.apply()

        currentUnitVehicle = availableVehicles.find { it.idCarro == unitId }
        activeDispatch = filterDispatchForUnit(dispatchesList, unitId, unitLabel)

        subscribeToData()
        GpsTrackingService.startService(context)
    }

    fun fetchVehiclesList() {
        viewModelScope.launch {
            repository.getVehiclesFlow()
                .catch { e -> e.printStackTrace() }
                .collectLatest { list ->
                    availableVehicles.clear()
                    availableVehicles.addAll(list)
                    if (selectedUnitId.isNotEmpty()) {
                        currentUnitVehicle = list.find { it.idCarro == selectedUnitId }
                    }
                }
        }
    }

    private fun filterDispatchForUnit(list: List<Dispatch>, unitId: String, unitLabel: String): Dispatch? {
        if (unitId.isEmpty() && unitLabel.isEmpty()) return null

        val v = currentUnitVehicle
        val idClean = unitId.replace("-", "").replace(" ", "").trim().uppercase()
        val labelClean = unitLabel.replace("-", "").replace(" ", "").trim().uppercase()

        // 1. Si el vehículo tiene asignado un ID de servicio en su campo enServicio
        if (v != null && v.enServicio.isNotEmpty() && v.enServicio != "0" && v.enServicio != "0-8" && v.enServicio != "6-13" && v.enServicio != "6-14") {
            val directMatch = list.find { d ->
                (d.idServicio == v.enServicio || d.idServicio == v.enServicio.trim()) &&
                d.operadorFinal.isEmpty() &&
                d.estado.trim().lowercase() != "finalizada" &&
                d.estado.trim().lowercase() != "cancelada"
            }
            if (directMatch != null) {
                val unitEntry = directMatch.unidades.entries.find { (uKey, _) ->
                    val cleanKey = uKey.replace("-", "").replace(" ", "").trim().uppercase()
                    (idClean.isNotEmpty() && cleanKey == idClean) ||
                    (labelClean.isNotEmpty() && cleanKey == labelClean)
                }
                if (unitEntry != null) {
                    val uData = unitEntry.value
                    val estado = ((uData["estado"] ?: uData["status"]) as? String ?: "").lowercase()
                    val hora68 = (uData["hora68"] ?: uData["regreso68At"]) as? String ?: ""
                    val isInactive = estado == "cancelado" || estado == "6-8" || hora68.isNotEmpty()
                    if (!isInactive) {
                        return directMatch
                    }
                } else {
                    return directMatch
                }
            }
        }

        // 2. Buscar en despachos donde la unidad esté activamente en `d.unidades`
        return list.find { d ->
            val matchUnit = d.unidades.entries.find { (uKey, _) ->
                val cleanKey = uKey.replace("-", "").replace(" ", "").trim().uppercase()
                (idClean.isNotEmpty() && cleanKey == idClean) ||
                (labelClean.isNotEmpty() && cleanKey == labelClean)
            }
            if (matchUnit != null && d.operadorFinal.isEmpty() && d.estado.trim().lowercase() != "finalizada") {
                val uData = matchUnit.value
                val estado = ((uData["estado"] ?: uData["status"]) as? String ?: "").lowercase()
                val hora68 = (uData["hora68"] ?: uData["regreso68At"]) as? String ?: ""
                val isInactive = estado == "cancelado" || estado == "6-8" || hora68.isNotEmpty()
                !isInactive
            } else false
        }
    }

    private fun subscribeToData() {
        vehicleSelfJob?.cancel()
        dispatchesJob?.cancel()
        personalJob?.cancel()
        bitacoraSelfJob?.cancel()

        if (selectedUnitId.isEmpty()) return

        vehicleSelfJob = viewModelScope.launch {
            repository.getVehiclesFlow()
                .catch { e -> e.printStackTrace() }
                .collectLatest { list ->
                    availableVehicles.clear()
                    availableVehicles.addAll(list)
                    val match = list.find { it.idCarro == selectedUnitId }
                    currentUnitVehicle = match
                    if (match != null) {
                        val prevId = activeDispatch?.idServicio ?: ""
                        val fresh = filterDispatchForUnit(dispatchesList, selectedUnitId, selectedUnitLabel)
                        activeDispatch = fresh
                        val nextId = fresh?.idServicio ?: ""
                        if (prevId != nextId) {
                            prefs.edit().putString("active_dispatch_id", nextId).apply()
                            if (nextId.isNotEmpty()) {
                                GpsTrackingService.triggerImmediateLocationUpdate(context)
                            }
                        }
                    }
                }
        }

        bitacoraSelfJob = viewModelScope.launch {
            repository.getActiveBitacoraFlow(selectedUnitId)
                .catch { e -> e.printStackTrace() }
                .collectLatest { trip ->
                    activeBitacoraTrip = trip
                }
        }

        dispatchesJob = viewModelScope.launch {
            repository.getDispatchesFlow()
                .catch { e -> e.printStackTrace() }
                .collectLatest { list ->
                    dispatchesList.clear()
                    dispatchesList.addAll(list)
                    val prevId = activeDispatch?.idServicio ?: ""
                    val fresh = filterDispatchForUnit(list, selectedUnitId, selectedUnitLabel)
                    activeDispatch = fresh
                    val nextId = fresh?.idServicio ?: ""
                    if (prevId != nextId) {
                        prefs.edit().putString("active_dispatch_id", nextId).apply()
                        if (nextId.isNotEmpty()) {
                            GpsTrackingService.triggerImmediateLocationUpdate(context)
                        }
                    }
                }
        }

        personalJob = viewModelScope.launch {
            repository.getPersonalFlow()
                .catch { e -> e.printStackTrace() }
                .collectLatest { list ->
                    personalList.clear()
                    personalList.addAll(list)
                }
        }
    }

    // Acciones Tácticas del Carro Bomba (6-0, 6-3, 6-15, 6-13, 6-9, 6-10, 6-8, 6-7)
    fun markSalida60() {
        if (selectedUnitId.isEmpty()) return
        val dispatchId = activeDispatch?.idServicio ?: ""
        val bitacoraId = activeBitacoraTrip?.idSalida ?: ""
        val actualUnitKey = activeDispatch?.unidades?.keys?.find {
            it.replace("-", "").equals(selectedUnitId.replace("-", ""), ignoreCase = true) ||
            it.replace("-", "").equals(selectedUnitLabel.replace("-", ""), ignoreCase = true)
        } ?: selectedUnitId

        repository.recordDispatchMilestone(
            dispatchId = dispatchId,
            vehicleId = actualUnitKey,
            milestoneKey = "salida60At",
            bitacoraId = bitacoraId
        )
    }

    fun markLlegada63() {
        if (selectedUnitId.isEmpty()) return
        val dispatchId = activeDispatch?.idServicio ?: ""
        val bitacoraId = activeBitacoraTrip?.idSalida ?: ""
        val actualUnitKey = activeDispatch?.unidades?.keys?.find {
            it.replace("-", "").equals(selectedUnitId.replace("-", ""), ignoreCase = true) ||
            it.replace("-", "").equals(selectedUnitLabel.replace("-", ""), ignoreCase = true)
        } ?: selectedUnitId

        repository.recordDispatchMilestone(
            dispatchId = dispatchId,
            vehicleId = actualUnitKey,
            milestoneKey = "llegada63At",
            bitacoraId = bitacoraId
        )
    }

    fun markTraslado615() {
        if (selectedUnitId.isEmpty()) return
        val dispatchId = activeDispatch?.idServicio ?: ""
        val bitacoraId = activeBitacoraTrip?.idSalida ?: ""
        val actualUnitKey = activeDispatch?.unidades?.keys?.find {
            it.replace("-", "").equals(selectedUnitId.replace("-", ""), ignoreCase = true) ||
            it.replace("-", "").equals(selectedUnitLabel.replace("-", ""), ignoreCase = true)
        } ?: selectedUnitId

        repository.recordDispatchMilestone(
            dispatchId = dispatchId,
            vehicleId = actualUnitKey,
            milestoneKey = "traslado615At",
            bitacoraId = bitacoraId
        )
    }

    fun markLlegada63Salud() {
        if (selectedUnitId.isEmpty()) return
        val dispatchId = activeDispatch?.idServicio ?: ""
        val bitacoraId = activeBitacoraTrip?.idSalida ?: ""
        val actualUnitKey = activeDispatch?.unidades?.keys?.find {
            it.replace("-", "").equals(selectedUnitId.replace("-", ""), ignoreCase = true) ||
            it.replace("-", "").equals(selectedUnitLabel.replace("-", ""), ignoreCase = true)
        } ?: selectedUnitId

        repository.recordDispatchMilestone(
            dispatchId = dispatchId,
            vehicleId = actualUnitKey,
            milestoneKey = "llegada63SaludAt",
            bitacoraId = bitacoraId
        )
    }

    fun markRetornoEmergencia613() {
        if (selectedUnitId.isEmpty()) return
        val dispatchId = activeDispatch?.idServicio ?: ""
        val bitacoraId = activeBitacoraTrip?.idSalida ?: ""
        val actualUnitKey = activeDispatch?.unidades?.keys?.find {
            it.replace("-", "").equals(selectedUnitId.replace("-", ""), ignoreCase = true) ||
            it.replace("-", "").equals(selectedUnitLabel.replace("-", ""), ignoreCase = true)
        } ?: selectedUnitId

        repository.recordDispatchMilestone(
            dispatchId = dispatchId,
            vehicleId = actualUnitKey,
            milestoneKey = "retornoEmergencia613At",
            bitacoraId = bitacoraId
        )
    }

    fun markRetorno69() {
        if (selectedUnitId.isEmpty()) return
        val dispatchId = activeDispatch?.idServicio ?: ""
        val bitacoraId = activeBitacoraTrip?.idSalida ?: ""
        val actualUnitKey = activeDispatch?.unidades?.keys?.find {
            it.replace("-", "").equals(selectedUnitId.replace("-", ""), ignoreCase = true) ||
            it.replace("-", "").equals(selectedUnitLabel.replace("-", ""), ignoreCase = true)
        } ?: selectedUnitId

        repository.recordDispatchMilestone(
            dispatchId = dispatchId,
            vehicleId = actualUnitKey,
            milestoneKey = "retorno69At",
            bitacoraId = bitacoraId
        )
    }

    fun markLlegadaCuartel610() {
        if (selectedUnitId.isEmpty()) return
        val dispatchId = activeDispatch?.idServicio ?: ""
        val bitacoraId = activeBitacoraTrip?.idSalida ?: ""
        val actualUnitKey = activeDispatch?.unidades?.keys?.find {
            it.replace("-", "").equals(selectedUnitId.replace("-", ""), ignoreCase = true) ||
            it.replace("-", "").equals(selectedUnitLabel.replace("-", ""), ignoreCase = true)
        } ?: selectedUnitId

        repository.recordDispatchMilestone(
            dispatchId = dispatchId,
            vehicleId = actualUnitKey,
            milestoneKey = "llegada610At",
            bitacoraId = bitacoraId
        )
    }

    fun markDisponible68(km: String = "") {
        if (selectedUnitId.isEmpty()) return
        val dispatchId = activeDispatch?.idServicio ?: ""
        val bitacoraId = activeBitacoraTrip?.idSalida ?: ""
        val actualUnitKey = activeDispatch?.unidades?.keys?.find {
            it.replace("-", "").equals(selectedUnitId.replace("-", ""), ignoreCase = true) ||
            it.replace("-", "").equals(selectedUnitLabel.replace("-", ""), ignoreCase = true)
        } ?: selectedUnitId

        repository.recordDispatchMilestone(
            dispatchId = dispatchId,
            vehicleId = actualUnitKey,
            milestoneKey = "disponible68At",
            kmValue = km,
            bitacoraId = bitacoraId,
            onSuccess = {
                activeDispatch = null
                activeBitacoraTrip = null
                prefs.edit().remove("active_dispatch_id").apply()
                GpsTrackingService.triggerImmediateLocationUpdate(context)
            }
        )
    }

    fun markControl67() {
        val dispatch = activeDispatch ?: return
        if (selectedUnitId.isEmpty()) return
        val actualUnitKey = dispatch.unidades.keys.find {
            it.replace("-", "").equals(selectedUnitId.replace("-", ""), ignoreCase = true) ||
            it.replace("-", "").equals(selectedUnitLabel.replace("-", ""), ignoreCase = true)
        } ?: selectedUnitId
        repository.recordDispatchMilestone(
            dispatchId = dispatch.idServicio,
            vehicleId = actualUnitKey,
            milestoneKey = "control67At"
        )
    }

    fun assignCrewMembers(
        tripulantes: List<PersonItem> = emptyList(),
        driverRadText: String = "",
        driverNameText: String = "",
        driverPerson: PersonItem? = null,
        obacRadText: String = "",
        obacNameText: String = "",
        obacPerson: PersonItem? = null,
        crewCountText: String = ""
    ) {
        if (selectedUnitId.isEmpty()) return
        val dispatch = activeDispatch
        val dispatchId = dispatch?.idServicio ?: ""
        val bitacoraId = activeBitacoraTrip?.idSalida ?: ""
        val actualUnitKey = dispatch?.unidades?.keys?.find {
            it.replace("-", "").equals(selectedUnitId.replace("-", ""), ignoreCase = true) ||
            it.replace("-", "").equals(selectedUnitLabel.replace("-", ""), ignoreCase = true)
        } ?: selectedUnitId

        repository.assignTripulantesToDispatch(
            dispatchId = dispatchId,
            vehicleId = actualUnitKey,
            bitacoraId = bitacoraId,
            tripulantes = tripulantes,
            driverRadText = driverRadText,
            driverNameText = driverNameText,
            driverPerson = driverPerson,
            obacRadText = obacRadText,
            obacNameText = obacNameText,
            obacPerson = obacPerson,
            crewCountText = crewCountText,
            onSuccess = {
                Toast.makeText(context, "Dotación y tripulación actualizada", Toast.LENGTH_SHORT).show()
            }
        )
    }

    fun solicitarGpsBombero(userId: String) {
        val serviceId = activeDispatch?.idServicio ?: activeBitacoraTrip?.idSalida ?: "1"
        repository.solicitarUbicacionBombero(userId, serviceId)
    }

    fun setVehicleFueraServicio08(motivo: String = "Fuera de servicio") {
        if (selectedUnitId.isEmpty()) return
        repository.updateVehicleStatus(
            vehicleId = selectedUnitId,
            newStatus = "0",
            enServicio = "0",
            notas = motivo.trim()
        )
    }

    fun setVehicleDisponible09() {
        if (selectedUnitId.isEmpty()) return
        repository.updateVehicleStatus(
            vehicleId = selectedUnitId,
            newStatus = "1",
            enServicio = "0",
            notas = "",
            onSuccess = { Toast.makeText(context, "$selectedUnitLabel DISPONIBLE (0-9)", Toast.LENGTH_SHORT).show() }
        )
    }

    fun registerSpecialExit613_614(
        type: String,
        lugar: String,
        motivo: String,
        conductor: String,
        obac: String,
        tripulantes: String,
        tripulantesList: List<PersonItem> = emptyList(),
        onComplete: (Boolean) -> Unit
    ) {
        if (selectedUnitId.isEmpty()) {
            onComplete(false)
            return
        }
        repository.registerSpecialExit(
            vehicleId = selectedUnitId,
            type = type,
            lugar = lugar,
            motivo = motivo,
            conductor = conductor,
            obac = obac,
            tripulantesCount = tripulantes,
            tripulantesList = tripulantesList,
            onSuccess = {
                Toast.makeText(context, "$selectedUnitLabel SALIDA $type REGISTRADA", Toast.LENGTH_SHORT).show()
                onComplete(true)
            },
            onFailure = {
                Toast.makeText(context, "Error al registrar salida $type", Toast.LENGTH_SHORT).show()
                onComplete(false)
            }
        )
    }

    // Activación SaaS Central
    fun activateLicense(key: String, onComplete: (Boolean) -> Unit) {
        val trimmedKey = key.trim().uppercase()
        if (trimmedKey.isEmpty()) return

        isActivatingLicense = true
        saasActivationError = ""

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://validatelicense-3kkeukidtq-uc.a.run.app")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.setRequestProperty("User-Agent", "SisBomCar/1.0.0")

                val body = JSONObject().apply {
                    put("licenseKey", trimmedKey)
                    put("module", "material")
                }.toString()

                conn.outputStream.use { os ->
                    os.write(body.toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode
                val responseText = if (responseCode in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader().use { it?.readText() } ?: ""
                }

                withContext(Dispatchers.Main) {
                    try {
                        val resJson = JSONObject(responseText)
                        val authorized = resJson.optBoolean("authorized", false)
                        if (authorized && responseCode in 200..299) {
                            val clientName = resJson.getString("clientName")
                            val firebaseConfig = resJson.getJSONObject("firebaseConfig")
                            val logoUrl = resJson.optString("logoUrl", "")

                            prefs.edit().apply {
                                putString("saas_license_key", trimmedKey)
                                putString("saas_firebase_config", firebaseConfig.toString())
                                putString("saas_client_name", clientName)
                                putString("saas_logo_url", logoUrl)
                            }.commit()

                            saasLicenseKey = trimmedKey
                            saasClientName = clientName
                            saasLogoUrl = logoUrl
                            isLicenseValid = true

                            initializeDynamicFirebase(context, firebaseConfig.toString())
                            fetchVehiclesList()
                            onComplete(true)
                        } else {
                            saasActivationError = resJson.optString("reason", "Licencia inválida o no autorizada.")
                            onComplete(false)
                        }
                    } catch (e: Exception) {
                        saasActivationError = "Error al procesar la respuesta de la nube."
                        onComplete(false)
                    } finally {
                        isActivatingLicense = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    saasActivationError = "No se pudo contactar al servidor de licencias."
                    isActivatingLicense = false
                    onComplete(false)
                }
            }
        }
    }

    fun clearLicense() {
        prefs.edit().apply {
            remove("saas_license_key")
            remove("saas_firebase_config")
            remove("saas_client_name")
            remove("saas_logo_url")
            remove("selected_unit_id")
            remove("selected_unit_label")
        }.commit()
        saasLicenseKey = ""
        saasClientName = ""
        isLicenseValid = false
        selectedUnitId = ""
        selectedUnitLabel = ""
        GpsTrackingService.stopService(context)
    }

    fun getClientLogoModel(): Any {
        if (saasLogoUrl.isNotEmpty()) return saasLogoUrl
        val key = saasLicenseKey.lowercase().replace("-", "_")
        val resId = context.resources.getIdentifier("logo_$key", "drawable", context.packageName)
        return if (resId != 0) resId else R.drawable.logo
    }

    companion object {
        fun initializeDynamicFirebase(context: Context, configJsonStr: String) {
            try {
                if (configJsonStr.isBlank()) return
                val config = JSONObject(configJsonStr)
                val apiKey = config.optString("apiKey")
                val projectId = config.optString("projectId")
                val storageBucket = config.optString("storageBucket")
                val messagingSenderId = config.optString("messagingSenderId")
                val appId = config.optString("appId")

                if (projectId.isBlank() || apiKey.isBlank()) return

                val existingApps = FirebaseApp.getApps(context)
                if (existingApps.isNotEmpty()) {
                    try {
                        val defaultApp = FirebaseApp.getInstance()
                        if (defaultApp.options.projectId == projectId) {
                            return
                        }
                    } catch (_: Exception) {}
                }

                val options = FirebaseOptions.Builder()
                    .setApiKey(apiKey)
                    .setApplicationId(if (appId.isNotEmpty()) appId else "1:123456789:android:default")
                    .setProjectId(projectId)
                    .setGcmSenderId(messagingSenderId)
                    .setStorageBucket(storageBucket)
                    .build()

                if (existingApps.isEmpty()) {
                    FirebaseApp.initializeApp(context, options)
                } else {
                    try {
                        FirebaseApp.getInstance().delete()
                    } catch (_: Exception) {}
                    FirebaseApp.initializeApp(context, options)
                }
            } catch (e: Exception) {
                android.util.Log.e("SisBomCar", "Error initializing Firebase: ${e.message}")
            }
        }

        fun initializeFallbackFirebase(context: Context) {
            try {
                if (FirebaseApp.getApps(context).isNotEmpty()) return
                val options = FirebaseOptions.Builder()
                    .setApiKey("AIzaSyBjjSBwmUevGC0vT0GdxtQUw-yvawdrH44")
                    .setApplicationId("1:432594591351:android:99a442ea6ae05276beb072")
                    .setProjectId("sisbom-central")
                    .setStorageBucket("sisbom-central.firebasestorage.app")
                    .setGcmSenderId("432594591351")
                    .build()
                FirebaseApp.initializeApp(context, options)
            } catch (_: Exception) {}
        }
    }
}
