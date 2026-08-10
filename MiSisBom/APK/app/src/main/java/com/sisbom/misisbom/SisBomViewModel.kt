package com.sisbom.misisbom

import android.content.ComponentName
import android.content.pm.PackageManager
import android.app.Application
import android.content.Context
import android.app.NotificationManager
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppScreen {
    Setup,
    Login,
    Main,
    Chat
}

enum class MainTab {
    Actividad,
    Despacho,
    Ordenes,
    Alertas,
    Asistencia,
    Disponibles
}

class SisBomViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val prefs: SharedPreferences = context.getSharedPreferences("SisBomPrefs", Context.MODE_PRIVATE)
    private val repository = FirebaseRepository()

    // SaaS reactive states
    var saasLicenseKey by mutableStateOf("")
    var isActivatingLicense by mutableStateOf(false)
    var saasActivationError by mutableStateOf("")
    var saasClientName by mutableStateOf("")
    var saasLogoUrl by mutableStateOf("")
    val isReadOnly: Boolean
        get() = prefs.getString("saas_read_only", "0") == "1"

    var logoChangeTrigger by mutableStateOf(0)

    fun getClientLogoModel(): Any {
        logoChangeTrigger
        val file = java.io.File(context.filesDir, "client_logo.png")
        if (file.exists() && file.length() > 0) {
            return file
        }
        if (saasLogoUrl.isNotEmpty()) {
            return saasLogoUrl
        }
        val key = prefs.getString("saas_license_key", "") ?: ""
        if (key.isNotEmpty()) {
            val formattedKey = key.lowercase().replace("-", "_")
            val resId = context.resources.getIdentifier("logo_$formattedKey", "drawable", context.packageName)
            if (resId != 0) {
                return resId
            }
        }
        return R.drawable.logo
    }

    fun downloadClientLogo(urlStr: String) {
        if (urlStr.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL(urlStr)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                
                if (conn.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    val file = java.io.File(context.filesDir, "client_logo.png")
                    conn.inputStream.use { input ->
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        logoChangeTrigger++
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateLauncherIcon() {
        val key = prefs.getString("saas_license_key", "") ?: ""
        val clientName = prefs.getString("saas_client_name", "") ?: ""
        val usePlacilla = key.lowercase().contains("placilla") || key.lowercase().contains("cbpl") || clientName.lowercase().contains("placilla")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val defaultAlias = ComponentName(context, "com.sisbom.misisbom.MainActivityDefault")
                val placillaAlias = ComponentName(context, "com.sisbom.misisbom.MainActivityPlacilla")

                if (usePlacilla) {
                    pm.setComponentEnabledSetting(
                        placillaAlias,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    pm.setComponentEnabledSetting(
                        defaultAlias,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                } else {
                    pm.setComponentEnabledSetting(
                        defaultAlias,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    pm.setComponentEnabledSetting(
                        placillaAlias,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Estados de UI reactivos
    var currentScreen by mutableStateOf(AppScreen.Login)
    var currentTab by mutableStateOf(MainTab.Actividad)
    var currentUser by mutableStateOf<UserPersonal?>(null)
    var isSyncing by mutableStateOf(false)
    var isLoggingIn by mutableStateOf(false)
    var isDarkMode by mutableStateOf(false)
    var isAirplaneMode by mutableStateOf(false)
        private set
    var showChangelogDialog by mutableStateOf(false)
    val appVersionName: String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.14"
    } catch (e: Exception) {
        "1.0.14"
    }

    // Estados para actualización OTA a distancia
    var showUpdateDialog by mutableStateOf(false)
    var updateApkUrl by mutableStateOf("")
    var isDownloadingUpdate by mutableStateOf(false)
    var downloadProgress by mutableStateOf(0f)
    var updateErrorMsg by mutableStateOf("")

    // Listas cargadas (inicialmente desde la caché local SharedPreferences)
    var personnelList by mutableStateOf<List<UserPersonal>>(emptyList())
    var dispatchesList by mutableStateOf<List<Dispatch>>(emptyList())
    var alertsList by mutableStateOf<List<Alert>>(emptyList())
    var vehiclesList by mutableStateOf<List<Vehicle>>(emptyList())
    var attendanceList by mutableStateOf<List<AttendanceSheet>>(emptyList())
    var isCentralActive by mutableStateOf(false)
    var isSyncingAttendance by mutableStateOf(false)
    var centralOperatorName by mutableStateOf("")

    // Navegación local de sub-detalles
    var selectedDispatchId by mutableStateOf<String?>(null)
    var fullscreenDispatchId by mutableStateOf<String?>(null)
    var activeChatId by mutableStateOf<String?>(null)
    var activeChatAlert by mutableStateOf<Alert?>(null)
    var selectedOrdenId by mutableStateOf<String?>(null)
    var pendingChatId: String? = null

    // Estados temporales para diálogos
    var changePasswordError by mutableStateOf("")
    var changePasswordSuccess by mutableStateOf("")

    // Historial para control de notificaciones y sonidos ya reproducidos
    private val knownDispatchIds = mutableSetOf<String>()
    private val knownAlertIds = mutableSetOf<String>()
    private var isFirstCheck = true

    // Bloqueo temporal para evitar efecto rebote (race conditions de Firestore)
    private var lastStatusChangeTime: Long = 0
    private var pendingStatus: String? = null
    private var lastServiceChangeTime: Long = 0
    private var pendingService: String? = null

    fun setDarkModeEnabled(enabled: Boolean) {
        isDarkMode = enabled
        prefs.edit().putBoolean("app_dark_mode", enabled).apply()
    }

    fun setAirplaneModeEnabled(enabled: Boolean) {
        if (isCentralActive) {
            showSystemToast("No puedes activar el Modo Avión mientras seas operador central activo")
            return
        }
        isAirplaneMode = enabled
        prefs.edit().putBoolean("MODO_AVION", enabled).apply()
        if (enabled) {
            changeStatus("0-8")
        } else {
            changeStatus("0-9")
        }
    }

    fun dismissChangelog() {
        showChangelogDialog = false
        prefs.edit().putString("last_seen_version", appVersionName).apply()
    }

    fun openChatRoom(chatId: String) {
        currentScreen = AppScreen.Main
        currentTab = MainTab.Alertas
        val alert = alertsList.find { it.idAlerta == chatId }
        if (alert != null) {
            activeChatId = chatId
            activeChatAlert = alert
            pendingChatId = null
            registerConforme(alert)
        } else {
            pendingChatId = chatId
        }
    }

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key == "fire_user") {
            p.getString("fire_user", null)?.let { jsonStr ->
                try {
                    val freshUser = deserializeUser(jsonStr)
                    if (freshUser.idRegistro.isNotEmpty()) {
                        currentUser = freshUser
                    }
                } catch (_: Exception) {}
            }
        }
        if (key == "cache_dispatches") {
            p.getString("cache_dispatches", null)?.let { jsonStr ->
                try {
                    dispatchesList = deserializeDispatches(jsonStr)
                } catch (_: Exception) {}
            }
        }
    }

    init {
        // 0. Cargar preferencia de tema visual
        val hasDarkModeKey = prefs.contains("app_dark_mode")
        isDarkMode = if (hasDarkModeKey) {
            prefs.getBoolean("app_dark_mode", false)
        } else {
            val nightModeFlags = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        isAirplaneMode = prefs.getBoolean("MODO_AVION", false)

        // 1. Verificar Activación de Licencia SaaS
        val saasLicense = prefs.getString("saas_license_key", null)
        val saasConfig = prefs.getString("saas_firebase_config", null)
        saasClientName = prefs.getString("saas_client_name", "Bomberos") ?: "Bomberos"
        saasLogoUrl = prefs.getString("saas_logo_url", "") ?: ""

        if (saasLicense == null || saasConfig == null) {
            currentScreen = AppScreen.Setup
            updateLauncherIcon()
        } else {
            // Re-verificar licencia de forma asíncrona y silenciosa
            checkLicenseStatus()
            updateLauncherIcon()

            // Cargar sesión guardada localmente
            val cachedUser = prefs.getString("fire_user", null)
            if (cachedUser != null) {
                val user = deserializeUser(cachedUser)
                currentUser = user
                if (user.cargo.trim().uppercase() == "BOMBERO HONORARIO") {
                    currentTab = MainTab.Ordenes
                }
                currentScreen = AppScreen.Main
                prefs.edit().putString("USER_ID", user.idRegistro).apply()
                
                val estado = user.estado.trim().uppercase()
                val isSpecial = estado.contains("SUSPENDIDO") || estado == "CDS" || estado.contains("LICENCIA") || estado == "PERMISO"
                val isUnavailable = (estado == "0-8" || isSpecial)
                prefs.edit().putString("IS_UNAVAILABLE", if (isUnavailable) "true" else "false").apply()
                DispatchForegroundService.startService(context)

                // Asynchronously re-authenticate Firebase Auth on startup/restore to prevent background expiry/unauth status writes
                val email = user.idRegistro.trim() + "@sisbom.com"
                val securePass = user.contrasena.trim() + "_secure_sisbom"
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
                        if (auth.currentUser == null) {
                            com.google.android.gms.tasks.Tasks.await(
                                auth.signInWithEmailAndPassword(email, securePass)
                            )
                            android.util.Log.d("SisBom", "Sesión de Firebase autocompletada con éxito al iniciar.")
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("SisBom", "Auto-login fallido en inicio: ${e.message}")
                    } finally {
                        withContext(Dispatchers.Main) {
                            startFirebaseSync(user.idRegistro)
                        }
                    }
                }
            } else {
                currentScreen = AppScreen.Login
            }
        }

        // 2. Cargar listas de la caché para mostrar información instantáneamente offline
        loadCache()

        // Timer para quitar el indicador de primer inicio y no reproducir sonidos de despachos históricos
        Handler(Looper.getMainLooper()).postDelayed({
            isFirstCheck = false
        }, 5000)

        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
    }

    fun refreshFromCacheAndFirebase() {
        prefs.getString("fire_user", null)?.let { jsonStr ->
            try {
                val freshUser = deserializeUser(jsonStr)
                if (freshUser.idRegistro.isNotEmpty()) {
                    currentUser = freshUser
                }
            } catch (_: Exception) {}
        }
        prefs.getString("cache_dispatches", null)?.let { jsonStr ->
            try {
                dispatchesList = deserializeDispatches(jsonStr)
            } catch (_: Exception) {}
        }
        val userId = currentUser?.idRegistro ?: ""
        if (userId.isNotEmpty()) {
            startFirebaseSync(userId)
        }
    }

    fun activateLicense(key: String, onComplete: (Boolean) -> Unit = {}) {
        val trimmedKey = key.trim()
        if (trimmedKey.isEmpty()) {
            saasActivationError = "Debe proporcionar una clave de licencia válida."
            return
        }

        isActivatingLicense = true
        saasActivationError = ""

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL("https://validatelicense-3kkeukidtq-uc.a.run.app")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", "SisBom-APK/1.1.1")

                val body = JSONObject().apply {
                    put("licenseKey", trimmedKey)
                    put("module", "apk")
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
                            val status = resJson.getString("status")
                            val clientName = resJson.getString("clientName")
                            val firebaseConfig = resJson.getJSONObject("firebaseConfig")
                            val logoUrl = resJson.optString("logoUrl", "")

                            // Save to SharedPreferences
                            prefs.edit().apply {
                                putString("saas_license_key", trimmedKey)
                                putString("saas_firebase_config", firebaseConfig.toString())
                                putString("saas_read_only", if (status == "read_only") "1" else "0")
                                putString("saas_client_name", clientName)
                                putString("saas_logo_url", logoUrl)
                            }.commit()

                            saasClientName = clientName
                            saasLogoUrl = logoUrl
                            
                            // Download custom logo and update launcher icon
                            downloadClientLogo(logoUrl)
                            updateLauncherIcon()

                            // Initialize dynamic Firebase
                            MainActivity.initializeDynamicFirebase(context, firebaseConfig.toString())

                            // Route to Login
                            currentScreen = AppScreen.Login
                            Toast.makeText(context, "Licencia Activada: $clientName", Toast.LENGTH_LONG).show()
                            onComplete(true)
                        } else {
                            val reason = resJson.optString("reason", "Error de validación desconocido.")
                            saasActivationError = reason
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
                    saasActivationError = "No se pudo conectar al servidor de licencias. Verifique su conexión."
                    isActivatingLicense = false
                    onComplete(false)
                }
            }
        }
    }

    fun clearLicense() {
        try {
            DispatchForegroundService.stopService(context)
        } catch (_: Exception) {}
        val file = java.io.File(context.filesDir, "client_logo.png")
        if (file.exists()) {
            file.delete()
        }
        prefs.edit().apply {
            remove("saas_license_key")
            remove("saas_firebase_config")
            remove("saas_read_only")
            remove("saas_client_name")
            remove("saas_logo_url")
            remove("fire_user")
            remove("USER_ID")
        }.commit()
        currentUser = null
        saasLogoUrl = ""
        currentScreen = AppScreen.Setup
        logoChangeTrigger++
        updateLauncherIcon()
        Toast.makeText(context, "Licencia removida. Ingrese una nueva clave.", Toast.LENGTH_SHORT).show()
    }

    fun checkLicenseStatus(onComplete: (Boolean) -> Unit = {}) {
        val licenseKey = prefs.getString("saas_license_key", null) ?: return
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL("https://validatelicense-3kkeukidtq-uc.a.run.app")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", "SisBom-APK/1.1.1")

                val body = JSONObject().apply {
                    put("licenseKey", licenseKey)
                    put("module", "apk")
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
                            val status = resJson.getString("status")
                            val clientName = resJson.getString("clientName")
                            val firebaseConfig = resJson.getJSONObject("firebaseConfig")
                            val logoUrl = resJson.optString("logoUrl", "")

                            // Update preferences
                            prefs.edit().apply {
                                putString("saas_firebase_config", firebaseConfig.toString())
                                putString("saas_read_only", if (status == "read_only") "1" else "0")
                                putString("saas_client_name", clientName)
                                putString("saas_logo_url", logoUrl)
                            }.commit()
                            saasClientName = clientName
                            saasLogoUrl = logoUrl
                            
                            // Download custom logo and update launcher icon
                            downloadClientLogo(logoUrl)
                            updateLauncherIcon()

                            onComplete(true)
                        } else {
                            // De-authorize!
                            val reason = resJson.optString("reason", "Licencia revocada o vencida.")
                            Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
                            clearLicense()
                            onComplete(false)
                        }
                    } catch (e: Exception) {
                        // Keep current if JSON parse error to prevent locking out on server glitches
                        onComplete(true)
                    }
                }
            } catch (e: Exception) {
                // Ignore network errors on background checks to allow offline mode!
                withContext(Dispatchers.Main) {
                    onComplete(true)
                }
            }
        }
    }

    fun downloadAndInstallApk(urlString: String) {
        if (isDownloadingUpdate) return
        isDownloadingUpdate = true
        updateErrorMsg = ""
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var url = java.net.URL(urlString)
                var connection = url.openConnection() as java.net.HttpURLConnection
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.instanceFollowRedirects = false
                
                var redirectCount = 0
                var status = connection.responseCode
                while (status == java.net.HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == java.net.HttpURLConnection.HTTP_MOVED_PERM ||
                    status == 307 || status == 308) {
                    
                    if (redirectCount > 5) break
                    val newUrl = connection.getHeaderField("Location") ?: break
                    url = java.net.URL(url, newUrl)
                    connection = url.openConnection() as java.net.HttpURLConnection
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    connection.instanceFollowRedirects = false
                    status = connection.responseCode
                    redirectCount++
                }

                val fileLength = connection.contentLength

                val cacheDir = context.cacheDir
                val apkFile = java.io.File(cacheDir, "update.apk")
                if (apkFile.exists()) {
                    apkFile.delete()
                }

                val input = java.io.BufferedInputStream(connection.inputStream, 8192)
                val output = java.io.FileOutputStream(apkFile)

                val data = ByteArray(1024)
                var total: Long = 0
                var count: Int
                while (input.read(data).also { count = it } != -1) {
                    total += count
                    if (fileLength > 0) {
                        downloadProgress = total.toFloat() / fileLength.toFloat()
                    }
                    output.write(data, 0, count)
                }

                output.flush()
                output.close()
                input.close()

                // Lanzar instalador
                launchInstallIntent(apkFile)
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isDownloadingUpdate = false
                    updateErrorMsg = e.localizedMessage ?: "Error de descarga"
                    Toast.makeText(context, "Error al descargar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun launchInstallIntent(file: java.io.File) {
        try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            viewModelScope.launch(Dispatchers.Main) {
                isDownloadingUpdate = false
                updateErrorMsg = "Error al iniciar instalador: ${e.message}"
            }
        }
    }

    // CARGA DE CACHÉ LOCAL DESDE SHAREDPREFS
    private fun loadCache() {
        try {
            prefs.getString("cache_personnel", null)?.let {
                personnelList = deserializePersonnel(it)
            }
            prefs.getString("cache_dispatches", null)?.let {
                dispatchesList = deserializeDispatches(it)
                dispatchesList.forEach { knownDispatchIds.add(it.idServicio) }
            }
            prefs.getString("cache_alerts", null)?.let {
                alertsList = deserializeAlerts(it)
                alertsList.forEach { knownAlertIds.add(it.idAlerta) }
            }
            prefs.getString("cache_vehicles", null)?.let {
                vehiclesList = deserializeVehicles(it)
            }
            prefs.getString("cache_attendance", null)?.let {
                attendanceList = deserializeAttendance(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // INICIAR SINCRONIZACIÓN CON FIREBASE
    fun startFirebaseSync(userId: String) {
        isSyncing = true
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().subscribeToTopic("alertas_generales")
            com.google.firebase.messaging.FirebaseMessaging.getInstance().subscribeToTopic("despachos")
            com.google.firebase.messaging.FirebaseMessaging.getInstance().subscribeToTopic("usuario_" + userId)
            if (currentUser?.conductor == 1) {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().subscribeToTopic("conductores")
            } else {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().unsubscribeFromTopic("conductores")
            }
        } catch (_: Exception) {}

        ensureFreshSession {
            viewModelScope.launch {
                // Sincronizar central
                repository.getCentralStateFlow()
                    .catch { e -> e.printStackTrace() }
                    .collectLatest { data ->
                    val estado = data["estado"]?.toString() ?: ""
                    val idReg = data["idRegistro"]?.toString() ?: ""
                    val opName = data["nombreBombero"]?.toString() ?: data["operador"]?.toString() ?: ""

                    val myId = currentUser?.idRegistro ?: ""
                    val myName = currentUser?.nombreBombero ?: ""

                    val isMeActive = estado.trim().lowercase() == "activo" &&
                            ((myId.isNotEmpty() && idReg.trim().lowercase() == myId.lowercase()) ||
                                    (myName.isNotEmpty() && opName.trim().lowercase() == myName.lowercase()))

                    isCentralActive = isMeActive
                    prefs.edit().putBoolean("IS_CENTRAL_MODE", isMeActive).apply()
                    centralOperatorName = opName

                    if (isMeActive) {
                        if (currentUser?.estado != "0-9") {
                            changeStatus("0-9")
                        }
                        currentTab = MainTab.Despacho
                    }
                }
            }

            viewModelScope.launch {
                // Sincronizar personal reactivamente según si es comandante o el operador está activo
                val user = currentUser ?: return@launch
                val isComandante = (user.cargo.trim().uppercase() == "COMANDANTE" && listOf("1", "01", "2", "02", "3", "03").contains(user.idRadial.trim()))
                
                var currentJob: kotlinx.coroutines.Job? = null
                
                androidx.compose.runtime.snapshotFlow { isCentralActive }
                    .collectLatest { activeOperator ->
                        currentJob?.cancel()
                        currentJob = launch {
                            if (isComandante || activeOperator) {
                                repository.getPersonnelFlow()
                                    .catch { e -> e.printStackTrace() }
                                    .collectLatest { list ->
                                        personnelList = list
                                        saveStringToPrefs("cache_personnel", serializePersonnel(list))
                                        val fresh = list.find { it.idRegistro == user.idRegistro }
                                        if (fresh != null) {
                                            updateCurrentUserData(fresh)
                                        }
                                    }
                            } else {
                                repository.getPersonnelSelfFlow(userId)
                                    .catch { e -> e.printStackTrace() }
                                    .collectLatest { selfUser ->
                                        if (selfUser != null) {
                                            personnelList = listOf(selfUser)
                                            saveStringToPrefs("cache_personnel", serializePersonnel(personnelList))
                                            updateCurrentUserData(selfUser)
                                        }
                                    }
                            }
                        }
                    }
            }

            viewModelScope.launch {
                // Sincronizar despachos
                repository.getDispatchesFlow()
                    .catch { e -> e.printStackTrace() }
                    .collectLatest { list ->
                    val oldList = dispatchesList
                    dispatchesList = list
                    saveStringToPrefs("cache_dispatches", serializeDispatches(list))

                    list.forEach { d ->
                        if (!knownDispatchIds.contains(d.idServicio)) {
                            knownDispatchIds.add(d.idServicio)
                            val hasCDS = currentUser?.hasActiveCDS() == true
                            if (!isFirstCheck && d.operadorFinal.isEmpty() && currentUser?.estado != "0-8" && !hasCDS && !isCentralActive) {
                                if (d.idServicio.isNotEmpty()) {
                                    if (!PlayedSoundsTracker.hasPlayed(d.idServicio)) {
                                        PlayedSoundsTracker.markPlayed(d.idServicio)
                                        val isTooOld = TimeValidation.isTooOld(d.fechaDespacho, d.horaDespacho)
                                        val inService = currentUser?.let { it.enServicio.isNotEmpty() && it.enServicio != "0" && !it.enServicio.startsWith("-") } ?: false
                                        if (!isTooOld && !isAirplaneMode && !inService) {
                                            var cleanClave = d.clave.trim().replace("-", "_").replace(" ", "_").lowercase()
                                            val soundToPlay = if (cleanClave.contains("llamado") || cleanClave.contains("comandancia")) {
                                                "llamado_comandancia"
                                            } else {
                                                val possibleSound = if (cleanClave == "9_0" || cleanClave == "9.0") "c9_0" else "c$cleanClave"
                                                val resId = context.resources.getIdentifier(possibleSound, "raw", context.packageName)
                                                if (resId != 0) possibleSound else if (cleanClave == "9_0" || cleanClave == "9.0") "c10_9" else "despacho"
                                            }
                                            
                                            SoundPlayer.playSound(context, soundToPlay)
                                            SoundPlayer.triggerVibration(context, true)
                                            NotificationHelper.scheduleRepeatAlert(context, d.idServicio, d.clave, true)

                                            if (MainActivity.isAppInForeground) {
                                                fullscreenDispatchId = d.idServicio
                                            }
                                        }
                                    }
                                }
                                showSystemToast("NUEVO DESPACHO: ${d.clave}")
                            }
                        }
                        if (d.operadorFinal.isNotEmpty()) {
                            try {
                                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                                nm.cancel(d.idServicio.hashCode())
                            } catch (_: Exception) {}
                        }
                    }

                    currentUser?.let { my ->
                        val miSvcId = my.enServicio.trim()
                        if (miSvcId.isNotEmpty() && miSvcId != "0" && !miSvcId.startsWith("-")) {
                            val svc = list.find { it.idServicio == miSvcId }
                            if (svc == null || svc.operadorFinal.isNotEmpty()) {
                                try {
                                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                                    nm.cancel(miSvcId.hashCode())
                                } catch (_: Exception) {}
                                changePersonalService("0")
                            }
                        }
                    }
                }
            }

            viewModelScope.launch {
                // Sincronizar alertas
                repository.getAlertsFlow()
                    .catch { e -> e.printStackTrace() }
                    .collectLatest { list ->
                    alertsList = list
                    saveStringToPrefs("cache_alerts", serializeAlerts(list))
                    activeChatAlert?.let { active ->
                        list.find { it.idAlerta == active.idAlerta }?.let { fresh ->
                            activeChatAlert = fresh
                            val myRadial = currentUser?.idRadial ?: ""
                            if (myRadial.isNotEmpty()) {
                                val isConf = fresh.conforme.split(",").map { it.trim().uppercase() }.contains(myRadial.uppercase())
                                if (!isConf) {
                                    registerConforme(fresh)
                                }
                            }
                        }
                    }

                    pendingChatId?.let { pendingId ->
                        list.find { it.idAlerta == pendingId }?.let { alert ->
                            activeChatId = pendingId
                            activeChatAlert = alert
                            pendingChatId = null
                        }
                    }

                    list.forEach { a ->
                        if (!knownAlertIds.contains(a.idAlerta)) {
                            knownAlertIds.add(a.idAlerta)
                            if (!isFirstCheck) {
                                val dateStr = a.fechaAlerta.ifEmpty { a.fechaOrden }
                                val isTooOld = TimeValidation.isTooOld(dateStr, a.horaAlerta)
                                val hasCDS = currentUser?.hasActiveCDS() == true
                                val is08 = currentUser?.estado == "0-8" || currentUser?.estado == "10-8"
                                if (!isTooOld && !isAirplaneMode && !is08 && !hasCDS) {
                                    if (a.duracion == "C") {
                                        SoundPlayer.playSound(context, "alerta")
                                    } else {
                                        SoundPlayer.playSound(context, "alerta")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            viewModelScope.launch {
                // Sincronizar vehículos
                repository.getVehiclesFlow()
                    .catch { e -> e.printStackTrace() }
                    .collectLatest { list ->
                    vehiclesList = list
                    saveStringToPrefs("cache_vehicles", serializeVehicles(list))
                }
            }

            viewModelScope.launch {
                // Sincronizar asistencia: sólo si la caché está vacía
                val cached = prefs.getString("cache_attendance", null)
                if (cached.isNullOrEmpty()) {
                    syncAttendanceFromFirebase(userId)
                }
            }
        }
    }

    fun syncAttendanceFromFirebase(userId: String) {
        if (userId.isEmpty() || isSyncingAttendance) return
        isSyncingAttendance = true
        
        // Clear cached attendance so that fetchAttendanceOnce fetches fresh from Firestore
        prefs.edit().apply {
            remove("cache_attendance")
            remove("cached_asistencias_$userId")
            apply()
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.fetchAttendanceOnce(userId) { list ->
                    viewModelScope.launch(Dispatchers.Main) {
                        attendanceList = list
                        saveStringToPrefs("cache_attendance", serializeAttendance(list))
                        isSyncingAttendance = false
                        showSystemToast("Asistencias sincronizadas con éxito")
                    }
                }
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isSyncingAttendance = false
                }
            }
        }
    }

    // ACCIÓN DE LOGIN
    fun performLogin(idReg: String, pass: String) {
        if (idReg.isEmpty() || pass.isEmpty()) {
            showSystemToast("Ingrese ID de Registro y Contraseña")
            return
        }

        isLoggingIn = true
        viewModelScope.launch {
            val email = idReg.trim() + "@sisbom.com"
            val securePass = pass.trim() + "_secure_sisbom"
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            
            var user: UserPersonal? = null
            var loginErrorMsg: String? = null
            
            try {
                withContext(Dispatchers.IO) {
                    com.google.android.gms.tasks.Tasks.await(
                        auth.signInWithEmailAndPassword(email, securePass)
                    )
                    val docRef = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                        .collection("personal").document(idReg.trim())
                    val docSnap = com.google.android.gms.tasks.Tasks.await(docRef.get())
                    if (docSnap.exists()) {
                        val dbDeviceId = docSnap.getString("deviceId") ?: ""
                        val androidId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
                        if (dbDeviceId.isEmpty() || dbDeviceId == "0") {
                            // Register the device
                            com.google.android.gms.tasks.Tasks.await(docRef.update("deviceId", androidId))
                            val updatedDocSnap = com.google.android.gms.tasks.Tasks.await(docRef.get())
                            user = repository.mapToUserPersonal(updatedDocSnap)
                        } else if (dbDeviceId != androidId) {
                            loginErrorMsg = "Dispositivo no autorizado. Comuníquese con el Comandante."
                        } else {
                            user = repository.mapToUserPersonal(docSnap)
                        }
                    } else {
                        loginErrorMsg = "No se encontraron datos del usuario en el sistema"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                loginErrorMsg = e.localizedMessage ?: "Error de autenticación"
            }
            
            if (user == null) {
                if (loginErrorMsg == "Dispositivo no autorizado. Comuníquese con el Comandante.") {
                    showSystemToast(loginErrorMsg!!)
                    isLoggingIn = false
                    return@launch
                }
                val matchLocal = personnelList.find {
                    it.idRegistro.trim().lowercase() == idReg.trim().lowercase() && it.contrasena == pass
                }
                if (matchLocal != null) {
                    if (!matchLocal.isUserActive()) {
                        showSystemToast("Usuario inactivo en el sistema")
                        isLoggingIn = false
                        return@launch
                    }
                    val androidId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
                    if (matchLocal.deviceId.isNotEmpty() && matchLocal.deviceId != "0" && matchLocal.deviceId != androidId) {
                        showSystemToast("Dispositivo no autorizado. Comuníquese con el Comandante.")
                        isLoggingIn = false
                        return@launch
                    }
                    user = matchLocal
                    showSystemToast("Modo sin conexión: Sesión iniciada localmente")
                } else {
                    showSystemToast(loginErrorMsg ?: "ID o Clave incorrectos")
                    isLoggingIn = false
                    return@launch
                }
            }
            
            val finalUser = user
            if (finalUser != null) {
                currentUser = finalUser
                saveStringToPrefs("fire_user", serializeUser(finalUser))
                if (finalUser.cargo.trim().uppercase() == "BOMBERO HONORARIO") {
                    currentTab = MainTab.Ordenes
                }
                currentScreen = AppScreen.Main
                isLoggingIn = false
                startFirebaseSync(finalUser.idRegistro)
            }
        }
    }

    // ACCIÓN DE LOGOUT
    fun logout() {
        val oldUserId = currentUser?.idRegistro ?: ""
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().unsubscribeFromTopic("alertas_generales")
            com.google.firebase.messaging.FirebaseMessaging.getInstance().unsubscribeFromTopic("despachos")
            com.google.firebase.messaging.FirebaseMessaging.getInstance().unsubscribeFromTopic("conductores")
            if (oldUserId.isNotEmpty()) {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().unsubscribeFromTopic("usuario_" + oldUserId)
            }
        } catch (_: Exception) {}
        try {
            com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
        } catch (_: Exception) {}
        try {
            DispatchForegroundService.stopService(context)
        } catch (_: Exception) {}

        prefs.edit().clear().apply()
        currentUser = null
        currentScreen = AppScreen.Login
        currentTab = MainTab.Actividad
        isCentralActive = false
        personnelList = emptyList()
        dispatchesList = emptyList()
        alertsList = emptyList()
        vehiclesList = emptyList()
        attendanceList = emptyList()
    }

    // CAMBIR DISPONIBILIDAD
    fun changeStatus(newStatus: String) {
        val user = currentUser ?: return

        if (isCentralActive && newStatus != "0-9") {
            showSystemToast("No puedes cambiar tu estado mientras seas operador central activo")
            return
        }

        // If user explicitly changes state to 0-9 availability, airplane mode must automatically turn off!
        if (newStatus == "0-9" && isAirplaneMode) {
            isAirplaneMode = false
            prefs.edit().putBoolean("MODO_AVION", false).apply()
        }
        
        // Actualización optimista
        val updated = user.copy(estado = newStatus)
        currentUser = updated
        saveStringToPrefs("fire_user", serializeUser(updated))
        
        pendingStatus = newStatus
        lastStatusChangeTime = System.currentTimeMillis()
        
        prefs.edit()
            .putString("LAST_SELF_STATUS_CHANGE", newStatus)
            .putLong("LAST_SELF_STATUS_TIME", System.currentTimeMillis())
            .apply()

        ensureFreshSession {
            repository.updatePersonalStatus(user.idRegistro, newStatus,
                onSuccess = {
                    repository.addStatusHistoryEntry(user.idRegistro, newStatus,
                        onSuccess = {
                            showSystemToast("Estado cambiado a $newStatus")
                        },
                        onFailure = { err ->
                            err.printStackTrace()
                            showSystemToast("Fallo al registrar historial de estado")
                        }
                    )
                },
                onFailure = {
                    showSystemToast("Fallo al actualizar estado")
                }
            )
        }
    }

    // TRIPULAR O CANCELAR SERVICIO DESPACHO
    fun attendService(serviceId: String, isAttending: Boolean) {
        val user = currentUser ?: return
        val finalId = if (isAttending) serviceId else "0"

        if (isAttending) {
            if (serviceId != "0") {
                val isActive = dispatchesList.any { it.idServicio == serviceId }
                if (!isActive) {
                    showSystemToast("El despacho ya no está activo.")
                    return
                }
            }
            try {
                SoundPlayer.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Actualización optimista
        val updated = user.copy(enServicio = finalId)
        currentUser = updated
        saveStringToPrefs("fire_user", serializeUser(updated))
        
        fullscreenDispatchId = null
        pendingService = finalId
        lastServiceChangeTime = System.currentTimeMillis()

        ensureFreshSession {
            repository.updatePersonalService(user.idRegistro, finalId,
                onSuccess = {
                    showSystemToast(if (isAttending) "Asistencia registrada" else "Asistencia cancelada")
                },
                onFailure = {
                    showSystemToast("Error en Firestore")
                }
            )
        }
    }

    fun declineService(serviceId: String) {
        val user = currentUser ?: return
        val finalService = "-$serviceId"
        
        // Actualización optimista
        val updated = user.copy(enServicio = finalService)
        currentUser = updated
        saveStringToPrefs("fire_user", serializeUser(updated))
        
        fullscreenDispatchId = null
        pendingService = finalService
        lastServiceChangeTime = System.currentTimeMillis()

        ensureFreshSession {
            repository.updatePersonalService(user.idRegistro, finalService,
                onSuccess = {
                    showSystemToast("No asistencia registrada")
                },
                onFailure = {
                    showSystemToast("Error en Firestore")
                }
            )
        }
    }

    private fun changePersonalService(serviceId: String) {
        val user = currentUser ?: return
        
        // Actualización optimista
        val updated = user.copy(enServicio = serviceId)
        currentUser = updated
        saveStringToPrefs("fire_user", serializeUser(updated))
        
        pendingService = serviceId
        lastServiceChangeTime = System.currentTimeMillis()

        ensureFreshSession {
            repository.updatePersonalService(user.idRegistro, serviceId,
                onSuccess = {},
                onFailure = {}
            )
        }
    }

    // CAMBIAR CONTRASEÑA
    fun changePassword(currentPass: String, newPass: String, confirmPass: String) {
        val user = currentUser ?: return
        changePasswordError = ""
        changePasswordSuccess = ""

        if (user.contrasena != currentPass) {
            changePasswordError = "Contraseña actual incorrecta"
            return
        }
        if (newPass != confirmPass) {
            changePasswordError = "Las contraseñas nuevas no coinciden"
            return
        }
        if (currentPass == newPass) {
            changePasswordError = "La nueva contraseña debe ser distinta"
            return
        }

        ensureFreshSession {
            repository.updatePersonalPassword(user.idRegistro, newPass,
                onSuccess = {
                    changePasswordSuccess = "Contraseña modificada correctamente"
                    val updated = user.copy(contrasena = newPass)
                    currentUser = updated
                    saveStringToPrefs("fire_user", serializeUser(updated))
                },
                onFailure = {
                    changePasswordError = "Error al actualizar contraseña"
                }
            )
        }
    }

    // ANCLAR / DESANCLAR ALERTA
    fun toggleAlertPin(alert: Alert) {
        val myRadial = currentUser?.idRadial ?: return
        val currentPined = alert.fijar.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

        if (currentPined.contains(myRadial)) {
            currentPined.remove(myRadial)
        } else {
            currentPined.add(myRadial)
        }

        val finalString = currentPined.joinToString(",")
        ensureFreshSession {
            repository.updateAlertPin(alert.idAlerta, finalString, {}, {})
        }
    }

    // REGISTRAR VISTO / CONFORME
    fun registerConforme(alert: Alert) {
        try {
            val notificationManager = getApplication<Application>().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(alert.idAlerta.hashCode())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val myRadial = currentUser?.idRadial ?: return
        val currentConf = alert.conforme.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

        if (!currentConf.contains(myRadial)) {
            currentConf.add(myRadial)
            val finalString = currentConf.joinToString(",")
            ensureFreshSession {
                repository.updateAlertConforme(alert.idAlerta, finalString, {}, {})
            }
        }
    }

    fun openDoor(onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        ensureFreshSession {
            repository.setDoorOpen({
                onSuccess()
            }, {
                onFailure(it)
            })
        }
    }

    fun publishAlert(razon: String, mensaje: String, grado: String, duracion: String, aQuien: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val user = currentUser ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                val snapshot = com.google.android.gms.tasks.Tasks.await(db.collection("alertas").get())
                var maxId = 0
                for (doc in snapshot.documents) {
                    val idNum = doc.id.toIntOrNull()
                    if (idNum != null && idNum > maxId) {
                        maxId = idNum
                    }
                }
                val newId = maxId + 1

                val now = Date()
                val dStr = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(now)
                val tStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)

                val oficial = "${user.cargo.trim()} - ${user.nombreBombero.trim()}"

                var finalMensaje = mensaje
                if (duracion == "C") {
                    finalMensaje = "$dStr/$tStr/${user.idRegistro.trim()}: $mensaje |"
                }

                val newAlert = Alert(
                    idAlerta = newId.toString(),
                    tipo = "alerta",
                    gradoAlerta = grado,
                    aQuienAlerta = aQuien,
                    quienAlerta = oficial,
                    razonAlerta = razon,
                    mensajeAlerta = finalMensaje,
                    fechaAlerta = "'$dStr",
                    horaAlerta = "'$tStr",
                    duracion = duracion,
                    conforme = "",
                    fijar = ""
                )

                ensureFreshSession {
                    repository.createAlert(newAlert, {
                        viewModelScope.launch(Dispatchers.Main) {
                            onSuccess()
                        }
                    }, {
                        viewModelScope.launch(Dispatchers.Main) {
                            onFailure(it)
                        }
                    })
                }
            } catch (e: Exception) {
                viewModelScope.launch(Dispatchers.Main) {
                    onFailure(e)
                }
            }
        }
    }

    fun publishOrder(numero: String, fecha: String, cuerpo: String, fNombre: String, fCargo: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val user = currentUser ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                val snapshot = com.google.android.gms.tasks.Tasks.await(db.collection("alertas").get())
                var maxId = 0
                for (doc in snapshot.documents) {
                    val idNum = doc.id.toIntOrNull()
                    if (idNum != null && idNum > maxId) {
                        maxId = idNum
                    }
                }
                val newId = maxId + 1

                val now = Date()
                val dStr = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(now)
                val tStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)

                val newAlert = Alert(
                    idAlerta = newId.toString(),
                    tipo = "orden",
                    gradoAlerta = "1",
                    aQuienAlerta = "TC",
                    quienAlerta = "${user.cargo.trim()} - ${user.nombreBombero.trim()}",
                    razonAlerta = "ORDEN DEL DIA $numero",
                    mensajeAlerta = cuerpo,
                    fechaAlerta = "'$dStr",
                    horaAlerta = "'$tStr",
                    duracion = "i",
                    conforme = "",
                    fijar = "",
                    numeroOrden = numero,
                    fechaOrden = "'$fecha",
                    firmaNombre = fNombre,
                    firmaCargo = fCargo
                )

                ensureFreshSession {
                    repository.createAlert(newAlert, {
                        viewModelScope.launch(Dispatchers.Main) {
                            onSuccess()
                        }
                    }, {
                        viewModelScope.launch(Dispatchers.Main) {
                            onFailure(it)
                        }
                    })
                }
            } catch (e: Exception) {
                viewModelScope.launch(Dispatchers.Main) {
                    onFailure(e)
                }
            }
        }
    }

    fun sendChatMessage(alert: Alert, text: String) {
        if (text.isEmpty()) return
        val user = currentUser ?: return
        val timeStamp = SimpleDateFormat("dd-MM-yyyy/HH:mm", Locale.getDefault()).format(Date())
        val sanitizedText = text.replace("|", " ").trim()
        if (sanitizedText.isEmpty()) return
        val formattedMsg = "$timeStamp/${user.idRegistro.trim()}: $sanitizedText"

        val finalChatString = if (alert.mensajeAlerta.isEmpty()) {
            formattedMsg
        } else {
            "${alert.mensajeAlerta}|$formattedMsg"
        }

        ensureFreshSession {
            repository.sendChatMessage(alert.idAlerta, finalChatString, user.idRadial.trim(),
                onSuccess = {
                    if (activeChatAlert?.idAlerta == alert.idAlerta) {
                        activeChatAlert = alert.copy(mensajeAlerta = finalChatString, conforme = user.idRadial.trim())
                    }
                },
                onFailure = {
                    showSystemToast("Error al enviar mensaje")
                }
            )
        }
    }

    fun dispatchFromCentral(clave: String, lugar: String, preinforme: String, selectedVehicles: List<String>) {
        val vehiclesCopy = selectedVehicles.toList()
        if (clave.isEmpty() || lugar.isEmpty()) {
            showSystemToast("Faltan campos obligatorios")
            return
        }

        val user = currentUser ?: return
        
        // Calcular el id correlativo a partir de dispatchesList
        val maxId = dispatchesList.mapNotNull { d ->
            val idStr = d.idServicio.trim()
            val parsed = idStr.toIntOrNull()
            if (parsed != null && parsed < 100000) parsed else null
        }.maxOrNull() ?: 0
        val nextId = (maxId + 1).toString()

        val operatorName = if (centralOperatorName.isNotEmpty()) centralOperatorName else user.nombreBombero

        ensureFreshSession {
            repository.createDispatchNew(
                idServicio = nextId,
                clave = clave,
                lugar = lugar,
                preinforme = preinforme,
                carros = vehiclesCopy,
                operadorInicial = operatorName,
                onSuccess = {
                    showSystemToast("DESPACHO EFECTUADO CON ÉXITO")
                    vehiclesCopy.forEach { carId ->
                        repository.updateVehicleService(carId, nextId) {}
                    }
                },
                onFailure = {
                    showSystemToast("Error al efectuar despacho")
                }
            )
        }
    }

    // UTILS SERIALIZACIÓN
    private fun saveStringToPrefs(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        if (key == "fire_user") {
            try {
                val j = JSONObject(value)
                val idRegistro = j.getString("idRegistro")
                prefs.edit().putString("USER_ID", idRegistro).apply()
                val estado = j.optString("estado", "").trim().uppercase()
                val isSpecial = estado.contains("SUSPENDIDO") || estado == "CDS" || estado.contains("LICENCIA") || estado == "PERMISO"
                val isUnavailable = (estado == "0-8" || isSpecial)
                prefs.edit().putString("IS_UNAVAILABLE", if (isUnavailable) "true" else "false").apply()
                DispatchForegroundService.startService(context)
            } catch (_: Exception) {}
        }
    }

    private fun serializeUser(u: UserPersonal): String {
        val j = JSONObject()
        j.put("idRegistro", u.idRegistro)
        j.put("nombreBombero", u.nombreBombero)
        j.put("idRadial", u.idRadial)
        j.put("contrasena", u.contrasena)
        j.put("activo", u.activo)
        j.put("conductor", u.conductor)
        j.put("enServicio", u.enServicio)
        j.put("cargo", u.cargo)
        j.put("foto", u.foto)
        j.put("estado", u.estado)
        j.put("deviceId", u.deviceId)
        j.put("fechaSuspensionFin", u.fechaSuspensionFin)
        j.put("licenciaMedica", u.licenciaMedica)
        j.put("permiso", u.permiso)
        j.put("cds", u.cds)
        j.put("puerta", u.puerta)
        return j.toString()
    }

    private fun deserializeUser(json: String): UserPersonal {
        val j = JSONObject(json)
        return UserPersonal(
            idRegistro = j.getString("idRegistro"),
            nombreBombero = j.getString("nombreBombero"),
            idRadial = j.getString("idRadial"),
            contrasena = j.getString("contrasena"),
            activo = j.get("activo"),
            conductor = j.optInt("conductor", 0),
            enServicio = j.optString("enServicio", "0"),
            cargo = j.optString("cargo", ""),
            foto = j.optString("foto", ""),
            estado = j.optString("estado", ""),
            deviceId = j.optString("deviceId", ""),
            fechaSuspensionFin = j.optString("fechaSuspensionFin", ""),
            licenciaMedica = j.optString("licenciaMedica", ""),
            permiso = j.optget("permiso", 0),
            cds = j.optget("cds", 0),
            puerta = j.optBoolean("puerta", false)
        )
    }

    private fun serializePersonnel(list: List<UserPersonal>): String {
        val arr = JSONArray()
        list.forEach { u -> arr.put(JSONObject(serializeUser(u))) }
        return arr.toString()
    }

    private fun deserializePersonnel(json: String): List<UserPersonal> {
        val list = mutableListOf<UserPersonal>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            list.add(deserializeUser(arr.getJSONObject(i).toString()))
        }
        return list
    }

    private fun serializeDispatches(list: List<Dispatch>): String {
        val arr = JSONArray()
        list.forEach { d ->
            val j = JSONObject()
            j.put("idServicio", d.idServicio)
            j.put("clave", d.clave)
            j.put("lugar", d.lugar)
            j.put("preinforme", d.preinforme)
            j.put("carros", d.carros)
            j.put("horaDespacho", d.horaDespacho)
            j.put("fechaDespacho", d.fechaDespacho)
            j.put("hora67", d.hora67)
            j.put("quienDespacha", d.quienDespacha)
            j.put("operadorFinal", d.operadorFinal)
            
            val unidadesObj = JSONObject()
            d.unidades.forEach { (carro, innerMap) ->
                val innerObj = JSONObject()
                innerMap.forEach { (k, v) ->
                    innerObj.put(k, v)
                }
                unidadesObj.put(carro, innerObj)
            }
            j.put("unidades", unidadesObj)

            arr.put(j)
        }
        return arr.toString()
    }

    private fun deserializeDispatches(json: String): List<Dispatch> {
        val list = mutableListOf<Dispatch>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            
            val unidadesMap = mutableMapOf<String, Map<String, Any>>()
            val unidadesObj = j.optJSONObject("unidades")
            if (unidadesObj != null) {
                val keys = unidadesObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val innerObj = unidadesObj.optJSONObject(key)
                    if (innerObj != null) {
                        val innerMap = mutableMapOf<String, Any>()
                        val innerKeys = innerObj.keys()
                        while (innerKeys.hasNext()) {
                            val innerKey = innerKeys.next()
                            innerMap[innerKey] = innerObj.get(innerKey)
                        }
                        unidadesMap[key] = innerMap
                    }
                }
            }

            list.add(Dispatch(
                idServicio = j.getString("idServicio"),
                clave = j.optString("clave", ""),
                lugar = j.optString("lugar", ""),
                preinforme = j.optString("preinforme", ""),
                carros = j.optString("carros", ""),
                horaDespacho = j.optString("horaDespacho", ""),
                fechaDespacho = j.optString("fechaDespacho", ""),
                hora67 = j.optString("hora67", ""),
                quienDespacha = j.optString("quienDespacha", ""),
                operadorFinal = j.optString("operadorFinal", ""),
                unidades = unidadesMap
            ))
        }
        return list
    }

    private fun serializeAlerts(list: List<Alert>): String {
        val arr = JSONArray()
        list.forEach { a ->
            val j = JSONObject()
            j.put("idAlerta", a.idAlerta)
            j.put("tipo", a.tipo)
            j.put("gradoAlerta", a.gradoAlerta)
            j.put("aQuienAlerta", a.aQuienAlerta)
            j.put("quienAlerta", a.quienAlerta)
            j.put("razonAlerta", a.razonAlerta)
            j.put("mensajeAlerta", a.mensajeAlerta)
            j.put("fechaAlerta", a.fechaAlerta)
            j.put("horaAlerta", a.horaAlerta)
            j.put("duracion", a.duracion)
            j.put("conforme", a.conforme)
            j.put("fijar", a.fijar)
            j.put("numeroOrden", a.numeroOrden)
            j.put("fechaOrden", a.fechaOrden)
            j.put("firmaNombre", a.firmaNombre)
            j.put("firmaCargo", a.firmaCargo)
            arr.put(j)
        }
        return arr.toString()
    }

    private fun deserializeAlerts(json: String): List<Alert> {
        val list = mutableListOf<Alert>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            val dur = j.optString("duracion", "")
            if (isAlertActive(dur)) {
                list.add(Alert(
                    idAlerta = j.getString("idAlerta"),
                    tipo = j.optString("tipo", ""),
                    gradoAlerta = j.optString("gradoAlerta", "1"),
                    aQuienAlerta = j.optString("aQuienAlerta", "TC"),
                    quienAlerta = j.optString("quienAlerta", ""),
                    razonAlerta = j.optString("razonAlerta", ""),
                    mensajeAlerta = j.optString("mensajeAlerta", ""),
                    fechaAlerta = j.optString("fechaAlerta", ""),
                    horaAlerta = j.optString("horaAlerta", ""),
                    duracion = dur,
                    conforme = j.optString("conforme", ""),
                    fijar = j.optString("fijar", ""),
                    numeroOrden = j.optString("numeroOrden", ""),
                    fechaOrden = j.optString("fechaOrden", ""),
                    firmaNombre = j.optString("firmaNombre", ""),
                    firmaCargo = j.optString("firmaCargo", "")
                ))
            }
        }
        return list
    }

    private fun serializeVehicles(list: List<Vehicle>): String {
        val arr = JSONArray()
        list.forEach { v ->
            val j = JSONObject()
            j.put("idCarro", v.idCarro)
            j.put("clave", v.clave)
            j.put("estado", v.estado)
            j.put("enServicio", v.enServicio)
            arr.put(j)
        }
        return arr.toString()
    }

    private fun deserializeVehicles(json: String): List<Vehicle> {
        val list = mutableListOf<Vehicle>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            list.add(Vehicle(
                idCarro = j.getString("idCarro"),
                clave = j.optString("clave", ""),
                estado = j.optString("estado", "0-8"),
                enServicio = j.optString("enServicio", "0")
            ))
        }
        return list
    }

    private fun serializeAttendance(list: List<AttendanceSheet>): String {
        val arr = JSONArray()
        list.forEach { a ->
            val j = JSONObject()
            j.put("idLista", a.idLista)
            j.put("clave", a.clave)
            j.put("tipo", a.tipo)
            j.put("fecha", a.fecha)
            j.put("hora", a.hora)
            j.put("lugar", a.lugar)
            j.put("aprobadoPor", a.aprobadoPor)
            j.put("anulada", a.anulada)
            j.put("userEstado", a.userEstado)
            j.put("userAbono", a.userAbono)
            j.put("obac", a.obac)
            j.put("detalle", a.detalle)
            arr.put(j)
        }
        return arr.toString()
    }

    private fun deserializeAttendance(json: String): List<AttendanceSheet> {
        val list = mutableListOf<AttendanceSheet>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            val sheet = AttendanceSheet(
                idLista = j.getString("idLista"),
                clave = j.optString("clave", ""),
                tipo = j.optString("tipo", ""),
                fecha = j.optString("fecha", ""),
                hora = j.optString("hora", ""),
                lugar = j.optString("lugar", ""),
                aprobadoPor = j.optString("aprobadoPor", ""),
                anulada = j.get("anulada"),
                userEstado = j.optString("userEstado", ""),
                userAbono = j.optget("userAbono", 0),
                obac = j.optString("obac", ""),
                detalle = j.optString("detalle", "")
            )
            list.add(sheet)
        }
        return list
    }

    private fun ensureFreshSession(onComplete: () -> Unit) {
        val user = currentUser
        if (user == null) {
            onComplete()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
                val firebaseUser = auth.currentUser
                if (firebaseUser != null) {
                    val task = firebaseUser.getIdToken(true)
                    com.google.android.gms.tasks.Tasks.await(task, 4, java.util.concurrent.TimeUnit.SECONDS)
                    android.util.Log.d("SisBom", "Token de Firebase refrescado con éxito.")
                } else {
                    val email = user.idRegistro.trim() + "@sisbom.com"
                    val securePass = user.contrasena.trim() + "_secure_sisbom"
                    val task = auth.signInWithEmailAndPassword(email, securePass)
                    com.google.android.gms.tasks.Tasks.await(task, 5, java.util.concurrent.TimeUnit.SECONDS)
                    android.util.Log.d("SisBom", "Re-autenticación exitosa.")
                }
            } catch (e: Exception) {
                android.util.Log.w("SisBom", "Error al asegurar sesión fresca, intentando re-login: ${e.message}")
                try {
                    val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
                    val email = user.idRegistro.trim() + "@sisbom.com"
                    val securePass = user.contrasena.trim() + "_secure_sisbom"
                    val task = auth.signInWithEmailAndPassword(email, securePass)
                    com.google.android.gms.tasks.Tasks.await(task, 5, java.util.concurrent.TimeUnit.SECONDS)
                } catch (ex: Exception) {
                    android.util.Log.e("SisBom", "Re-login fallido en ensureFreshSession: ${ex.message}")
                }
            }
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    private fun showSystemToast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    private fun updateCurrentUserData(fresh: UserPersonal) {
        val my = currentUser ?: return
        var needsUpdate = false
        var targetEstado = my.estado
        var targetEnServicio = my.enServicio
        
        // Validar estado contra bloqueo temporal
        val timeStatusElapsed = System.currentTimeMillis() - lastStatusChangeTime
        if (timeStatusElapsed >= 4000) {
            pendingStatus = null
        }
        if (pendingStatus != null && timeStatusElapsed < 4000) {
            if (fresh.estado == pendingStatus) {
                pendingStatus = null
            }
        } else {
            if (fresh.estado != my.estado) {
                targetEstado = fresh.estado
                needsUpdate = true
            }
        }

        // Validar enServicio contra bloqueo temporal
        val timeServiceElapsed = System.currentTimeMillis() - lastServiceChangeTime
        if (timeServiceElapsed >= 4000) {
            pendingService = null
        }
        if (pendingService != null && timeServiceElapsed < 4000) {
            if (fresh.enServicio == pendingService) {
                pendingService = null
            }
        } else {
            if (fresh.enServicio != my.enServicio) {
                targetEnServicio = fresh.enServicio
                needsUpdate = true
            }
        }

        // Validar activo
        var targetActivo = my.activo
        if (fresh.activo != my.activo) {
            targetActivo = fresh.activo
            needsUpdate = true
        }

        // Validar conductor
        var targetConductor = my.conductor
        if (fresh.conductor != my.conductor) {
            targetConductor = fresh.conductor
            needsUpdate = true
            try {
                if (fresh.conductor == 1) {
                    com.google.firebase.messaging.FirebaseMessaging.getInstance().subscribeToTopic("conductores")
                } else {
                    com.google.firebase.messaging.FirebaseMessaging.getInstance().unsubscribeFromTopic("conductores")
                }
            } catch (_: Exception) {}
        }

        // Validar variables de situación
        var targetSusp = my.fechaSuspensionFin
        if (fresh.fechaSuspensionFin != my.fechaSuspensionFin) {
            targetSusp = fresh.fechaSuspensionFin
            needsUpdate = true
        }

        var targetLic = my.licenciaMedica
        if (fresh.licenciaMedica != my.licenciaMedica) {
            targetLic = fresh.licenciaMedica
            needsUpdate = true
        }

        var targetPermiso = my.permiso
        if (fresh.permiso != my.permiso) {
            targetPermiso = fresh.permiso
            needsUpdate = true
        }

        var targetCDS = my.cds
        if (fresh.cds != my.cds) {
            targetCDS = fresh.cds
            needsUpdate = true
        }

        var targetPuerta = my.puerta
        if (fresh.puerta != my.puerta) {
            targetPuerta = fresh.puerta
            needsUpdate = true
        }

        if (needsUpdate) {
            val updated = my.copy(
                estado = targetEstado,
                enServicio = targetEnServicio,
                activo = targetActivo,
                conductor = targetConductor,
                fechaSuspensionFin = targetSusp,
                licenciaMedica = targetLic,
                permiso = targetPermiso,
                cds = targetCDS,
                puerta = targetPuerta
            )
            currentUser = updated
            saveStringToPrefs("fire_user", serializeUser(updated))
        }
    }
}

fun JSONObject.optget(name: String, fallback: Any): Any {
    return if (this.has(name)) this.get(name) else fallback
}
