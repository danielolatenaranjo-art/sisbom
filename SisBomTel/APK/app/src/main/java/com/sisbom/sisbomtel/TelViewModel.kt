package com.sisbom.sisbomtel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

class TelViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val prefs: SharedPreferences = context.getSharedPreferences("SisBomTelPrefs", Context.MODE_PRIVATE)
    private val repository = FirebaseRepository()

    // Estado SaaS Licencia
    var saasLicenseKey by mutableStateOf(prefs.getString("saas_license_key", "") ?: "")
    var saasClientName by mutableStateOf(prefs.getString("saas_client_name", "") ?: "")
    var saasLogoUrl by mutableStateOf(prefs.getString("saas_logo_url", "") ?: "")
    var isLicenseValid by mutableStateOf(prefs.getString("saas_license_key", "")?.isNotEmpty() == true)
    var isActivatingLicense by mutableStateOf(false)
    var saasActivationError by mutableStateOf("")

    // Autorización de Comandante (ID Radial 1)
    var isComandanteAuthenticated by mutableStateOf(prefs.getString("auth_comandante_id", "")?.isNotEmpty() == true)
    var authorizedComandanteName by mutableStateOf(prefs.getString("auth_comandante_name", "") ?: "")
    var isVerifyingComandante by mutableStateOf(false)
    var comandanteAuthError by mutableStateOf("")

    // Estado de Puerta
    var isDoorOpening by mutableStateOf(false)
    var doorStatusMessage by mutableStateOf("")

    // Listas en tiempo real
    var recentCallsList = mutableStateListOf<CallItem>()
    var smsQueueList = mutableStateListOf<SmsQueueItem>()
    var activeIncomingCallNumber by mutableStateOf("")
    var activeIncomingCallStatus by mutableStateOf("DISPONIBLE")

    // UI state
    var currentTimeString by mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
    var currentDateString by mutableStateOf(SimpleDateFormat("EEE, dd MMM yyyy", Locale("es", "CL")).format(Date()).uppercase())

    init {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000L)
                currentTimeString = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                currentDateString = SimpleDateFormat("EEE, dd MMM yyyy", Locale("es", "CL")).format(Date()).uppercase()
                activeIncomingCallNumber = SisBomTelService.activeIncomingCallNumber
                activeIncomingCallStatus = SisBomTelService.activeIncomingCallStatus
            }
        }

        val fbConfig = prefs.getString("saas_firebase_config", null)
        if (fbConfig != null) {
            SisBomTelApp.initializeDynamicFirebase(context, fbConfig)
        }

        if (isLicenseValid) {
            subscribeToData()
        }
    }

    fun subscribeToData() {
        viewModelScope.launch {
            repository.getRecentCallsFlow()
                .catch { it.printStackTrace() }
                .collectLatest { list ->
                    val deduplicated = mutableListOf<CallItem>()
                    for (item in list) {
                        val isDuplicate = deduplicated.any { existing ->
                            existing.telefono == item.telefono && Math.abs(existing.timestamp - item.timestamp) < 20000L
                        }
                        if (!isDuplicate) {
                            deduplicated.add(item)
                        }
                    }
                    recentCallsList.clear()
                    recentCallsList.addAll(deduplicated)
                }
        }

        viewModelScope.launch {
            repository.getSmsQueueFlow()
                .catch { it.printStackTrace() }
                .collectLatest { list ->
                    smsQueueList.clear()
                    smsQueueList.addAll(list)
                }
        }
    }

    fun activateLicense(licenseKey: String, onComplete: (Boolean) -> Unit) {
        val trimmedKey = licenseKey.trim().uppercase()
        if (trimmedKey.isBlank()) {
            saasActivationError = "Ingrese una clave de licencia válida."
            onComplete(false)
            return
        }

        isActivatingLicense = true
        saasActivationError = ""

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val endpoints = listOf(
                    "https://us-central1-sisbom-central.cloudfunctions.net/validateLicense"
                )

                var responseText = ""
                var responseCode = -1

                for (endpoint in endpoints) {
                    try {
                        val url = URL(endpoint)
                        val conn = url.openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        conn.setRequestProperty("Accept", "application/json")
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        conn.doOutput = true

                        val body = JSONObject().apply {
                            put("licenseKey", trimmedKey)
                            put("platform", "android_tel")
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

                            SisBomTelApp.initializeDynamicFirebase(context, firebaseConfig.toString())
                            subscribeToData()
                            onComplete(true)
                        } else {
                            saasActivationError = resJson.optString("reason", "Licencia inválida o no autorizada.")
                            onComplete(false)
                        }
                    } catch (e: Exception) {
                        saasActivationError = "Error al procesar la respuesta del servidor."
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

    fun verifyComandante(idReg: String, pass: String, onResult: (Boolean) -> Unit) {
        if (idReg.isBlank() || pass.isBlank()) {
            comandanteAuthError = "Ingrese Usuario y Contraseña"
            onResult(false)
            return
        }

        isVerifyingComandante = true
        comandanteAuthError = ""

        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.verifyComandanteCredentials(idReg.trim(), pass.trim())
            withContext(Dispatchers.Main) {
                isVerifyingComandante = false
                if (result.isSuccess) {
                    val auth = result.getOrNull()
                    isComandanteAuthenticated = true
                    authorizedComandanteName = auth?.nombre ?: "Comandante"
                    prefs.edit().apply {
                        putString("auth_comandante_id", auth?.idRegistro ?: idReg)
                        putString("auth_comandante_name", auth?.nombre ?: "Comandante")
                    }.apply()
                    SisBomTelService.startService(context)
                    onResult(true)
                } else {
                    comandanteAuthError = result.exceptionOrNull()?.message ?: "Error de autenticación"
                    onResult(false)
                }
            }
        }
    }

    fun triggerDoor() {
        if (isDoorOpening) return
        isDoorOpening = true
        doorStatusMessage = "ABRIENDO PORTÓN..."

        repository.triggerDoorOpen(
            solicitante = "$authorizedComandanteName (Central Tel)",
            onSuccess = {
                doorStatusMessage = "¡PORTÓN ACTIVADO CON ÉXITO!"
                viewModelScope.launch {
                    kotlinx.coroutines.delay(3500L)
                    isDoorOpening = false
                    doorStatusMessage = ""
                }
            },
            onFailure = { err ->
                doorStatusMessage = "Error: ${err.message ?: "Fallo de conexión"}"
                viewModelScope.launch {
                    kotlinx.coroutines.delay(4000L)
                    isDoorOpening = false
                    doorStatusMessage = ""
                }
            }
        )
    }

    fun sendDirectSms(phone: String, message: String, onResult: (Boolean, String) -> Unit) {
        if (phone.isBlank() || message.isBlank()) {
            onResult(false, "Ingrese teléfono y mensaje")
            return
        }
        repository.enqueueSms(
            telefono = phone.trim(),
            mensaje = message.trim(),
            onSuccess = { onResult(true, "SMS puesto en cola de salida") },
            onFailure = { err -> onResult(false, err.message ?: "Error al encolar SMS") }
        )
    }

    fun clearLicense() {
        prefs.edit().apply {
            remove("saas_license_key")
            remove("saas_firebase_config")
            remove("saas_client_name")
            remove("saas_logo_url")
            remove("auth_comandante_id")
            remove("auth_comandante_name")
        }.commit()
        saasLicenseKey = ""
        saasClientName = ""
        isLicenseValid = false
        isComandanteAuthenticated = false
        authorizedComandanteName = ""
        recentCallsList.clear()
        smsQueueList.clear()
    }
}
