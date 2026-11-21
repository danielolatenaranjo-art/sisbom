package com.sisbom.misisbom

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONArray
import org.json.JSONObject

data class NavGpsFixData(
    val lat: Double,
    val lng: Double,
    val speedKmH: Float,
    val heading: Float,
    val accuracy: Float,
    val timestamp: Long
)

class NavGpsTrackingService : Service(), LocationListener {

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var locationManager: LocationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private var lastSentLat = 0.0
    private var lastSentLng = 0.0
    private var lastRtdbSentTime = 0L
    private var vehicleWakeListener: ListenerRegistration? = null
    private var dispatchesWakeListener: ListenerRegistration? = null
    private var bitacoraWakeListener: ListenerRegistration? = null
    private var connectivityManager: android.net.ConnectivityManager? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private val reconnectHandler = android.os.Handler(Looper.getMainLooper())
    private var lastWokenDispatchKey = ""
    private var lastWokenBitacoraKey = ""
    private var lastWokenVehicleEnServicio = "0"
    private var lastTriggeredEvacKey = ""
    private var activeWatcherSignature = ""
    private val syncBgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var lastPushedVehJson = ""
    @Volatile private var lastPushedDespJson = ""
    @Volatile private var lastPushedBitJson = ""
    private var lastWebViewGpsPushMs = 0L
    private var wasStationaryOnLastPush = false
    private var lastValidMovementHeading = 0f

    companion object {
        private const val NOTIFICATION_ID = 8844
        private const val CHANNEL_ID = "sisbom_car_gps"
        private const val UPDATE_INTERVAL_MS = 50L
        private const val MIN_DISTANCE_M = 0f

        @Volatile
        var isServiceRunning = false
        var currentLat = 0.0
        var currentLng = 0.0
        var currentSpeedKmH = 0f
        var currentHeading = 0f

        @Volatile
        var onNativeGpsFixListener: ((NavGpsFixData) -> Unit)? = null

        fun startService(context: Context) {
            try {
                if (MainActivity.getEcosystemAppMode(context) != "NAV") return
                val hasFine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val hasCoarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!hasFine && !hasCoarse) return

                val intent = Intent(context, NavGpsTrackingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stopService(context: Context) {
            try {
                context.stopService(Intent(context, NavGpsTrackingService::class.java))
            } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (MainActivity.getEcosystemAppMode(this) != "NAV") {
            stopSelf()
            return
        }
        isServiceRunning = true

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SisBomCar:NavGpsWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {}

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SisBomCar:NavWifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}

        try {
            createNotificationChannel()
            val notification = buildForegroundNotification("SENTINEL NAV Activo", "Monitoreo GPS continuo de Carro Bomba")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {}

        startLocationUpdates()
        startNativeDispatchWakeWatcher()
        registerCellularAndWifiNetworkMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (MainActivity.getEcosystemAppMode(this) != "NAV") {
            stopSelf()
            return START_NOT_STICKY
        }
        startNativeDispatchWakeWatcher()
        if (intent?.action == "ACTION_FORCE_UPDATE") {
            sendForceUpdate()
        }
        return START_STICKY
    }

    private var hadNetworkInternet = true
    private var lastNetworkReconnectNotifyMs = 0L

    private fun registerCellularAndWifiNetworkMonitor() {
        try {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
            connectivityManager = cm
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    val now = System.currentTimeMillis()
                    val shouldNotify = !hadNetworkInternet || (now - lastNetworkReconnectNotifyMs > 60_000L)
                    hadNetworkInternet = true
                    reconnectHandler.post {
                        try { MainActivity.getActiveRtdb(applicationContext)?.goOnline() } catch (_: Exception) {}
                        try { MainActivity.getActiveFirestore(applicationContext).enableNetwork() } catch (_: Exception) {}
                        startNativeDispatchWakeWatcher(forceRestart = false)
                        if (shouldNotify) {
                            lastNetworkReconnectNotifyMs = now
                            MainActivity.notifyNavWebViewNetworkReconnected()
                        }
                        sendForceUpdate()
                    }
                }

                override fun onLost(network: android.net.Network) {
                    hadNetworkInternet = false
                }
            }
            networkCallback = cb
            cm.registerDefaultNetworkCallback(cb)
        } catch (_: Exception) {}
    }

    private fun scheduleNativeWakeWatcherRetry() {
        activeWatcherSignature = ""
        reconnectHandler.removeCallbacksAndMessages("NAV_WAKE_RETRY")
        reconnectHandler.postAtTime(
            { startNativeDispatchWakeWatcher(forceRestart = true) },
            "NAV_WAKE_RETRY",
            android.os.SystemClock.uptimeMillis() + 4000L
        )
    }

    private fun ensureNavFirebaseAuthenticated(onReady: () -> Unit) {
        try {
            val auth = MainActivity.getActiveAuth(applicationContext)
            val cur = auth.currentUser
            if (cur != null && !cur.isAnonymous) {
                onReady()
                return
            }
            val prefs = getSharedPreferences("SisBomCarPrefs", Context.MODE_PRIVATE)
            val storedId = (prefs.getString("auth_comandante_id", "") ?: "").trim()
            val storedPass = (prefs.getString("auth_comandante_pass", "") ?: "").trim()
            if (storedId.isNotEmpty() && storedPass.isNotEmpty()) {
                val email = if (storedId.contains("@")) storedId else "$storedId@sisbom.com"
                val cleanRawPass = storedPass.removeSuffix("_secure_sisbom")
                val securePass = "${cleanRawPass}_secure_sisbom"
                auth.signInWithEmailAndPassword(email, securePass)
                    .addOnSuccessListener { onReady() }
                    .addOnFailureListener {
                        auth.signInWithEmailAndPassword(email, cleanRawPass)
                            .addOnSuccessListener { onReady() }
                            .addOnFailureListener {
                                auth.signInWithEmailAndPassword("v304.app@sisbom.com", "SentinelOne_V304_Auth!2026")
                                    .addOnCompleteListener { onReady() }
                            }
                    }
            } else {
                auth.signInWithEmailAndPassword("v304.app@sisbom.com", "SentinelOne_V304_Auth!2026")
                    .addOnCompleteListener { onReady() }
            }
        } catch (_: Exception) {}
    }

    private fun toSafeJsonElement(value: Any?): Any? {
        return when (value) {
            null -> JSONObject.NULL
            is Boolean, is Int, is Long, is Double, is Float, is String -> value
            is com.google.firebase.Timestamp -> value.toDate().time
            is java.util.Date -> value.time
            is Map<*, *> -> {
                val obj = JSONObject()
                for ((k, v) in value.entries) {
                    if (k != null) obj.put(k.toString(), toSafeJsonElement(v))
                }
                obj
            }
            is Iterable<*> -> {
                val arr = JSONArray()
                for (item in value) arr.put(toSafeJsonElement(item))
                arr
            }
            else -> value.toString()
        }
    }

    private fun startNativeDispatchWakeWatcher(forceRestart: Boolean = false) {
        try {
            if (MainActivity.getEcosystemAppMode(this) != "NAV") return
            val prefs = getSharedPreferences("SisBomCarPrefs", Context.MODE_PRIVATE)
            val fbConfig = prefs.getString("saas_firebase_config", "") ?: ""
            val unitId = (prefs.getString("selected_unit_id", "") ?: "").trim().uppercase()
            if (fbConfig.isNotEmpty()) {
                MainActivity.initializeDynamicFirebase(applicationContext, fbConfig)
            }
            if (unitId.isEmpty()) return

            val watcherSignature = "${fbConfig.hashCode()}|$unitId"
            if (
                !forceRestart &&
                watcherSignature == activeWatcherSignature &&
                vehicleWakeListener != null &&
                dispatchesWakeListener != null &&
                bitacoraWakeListener != null
            ) {
                return
            }

            ensureNavFirebaseAuthenticated {
                try {
                    val firestore = MainActivity.getActiveFirestore(applicationContext)
                    val cleanTarget = unitId.replace("-", "").replace(" ", "").uppercase()

                    if (
                        !forceRestart &&
                        watcherSignature == activeWatcherSignature &&
                        vehicleWakeListener != null &&
                        dispatchesWakeListener != null &&
                        bitacoraWakeListener != null
                    ) {
                        return@ensureNavFirebaseAuthenticated
                    }
                    activeWatcherSignature = watcherSignature

                    vehicleWakeListener?.remove()
                    vehicleWakeListener = firestore.collection("vehiculos")
                        .addSnapshotListener { snap, err ->
                            if (err != null) {
                                scheduleNativeWakeWatcherRetry()
                                return@addSnapshotListener
                            }
                            if (snap == null) return@addSnapshotListener
                            val docsSnapshot = snap.documents.map { Pair(it.id, it.data ?: emptyMap<String, Any?>()) }
                            syncBgExecutor.execute {
                                try {
                                    val vehArr = JSONArray()
                                    for ((docId, dataMap) in docsSnapshot) {
                                        val vJson = toSafeJsonElement(dataMap) as? JSONObject ?: JSONObject()
                                        vJson.put("_docId", docId)
                                        vehArr.put(vJson)

                                        val docCarro = ((dataMap["idCarro"]?.toString() ?: docId)).replace("-", "").replace(" ", "").trim().uppercase()
                                        if (docCarro == cleanTarget) {
                                            val rawServ = (dataMap["enServicio"]?.toString() ?: "0").trim()
                                            val isActiveServ = rawServ.isNotEmpty() &&
                                                rawServ != "0" &&
                                                rawServ != "0-9" &&
                                                rawServ != "0-8" &&
                                                rawServ != "6-8" &&
                                                !rawServ.equals("false", ignoreCase = true)

                                            if (isActiveServ && rawServ != lastWokenVehicleEnServicio) {
                                                lastWokenVehicleEnServicio = rawServ
                                                prefs.edit()
                                                    .putString("unit_en_servicio", rawServ)
                                                    .putBoolean("is_en_cuartel", false)
                                                    .apply()
                                                MainActivity.wakeUpNavScreenAndStayAwake(applicationContext)
                                                reconnectHandler.post { sendForceUpdate() }
                                            } else if (!isActiveServ) {
                                                lastWokenVehicleEnServicio = "0"
                                                prefs.edit()
                                                    .putString("unit_en_servicio", "0")
                                                    .putBoolean("is_en_cuartel", true)
                                                    .apply()
                                            }
                                        }
                                    }
                                    val syncMsg = JSONObject()
                                    syncMsg.put("type", "vehiculos")
                                    syncMsg.put("docs", vehArr)
                                    val payloadStr = syncMsg.toString()
                                    if (payloadStr != lastPushedVehJson) {
                                        lastPushedVehJson = payloadStr
                                        MainActivity.pushNativeNavFirestoreSyncToWebView(payloadStr)
                                    }
                                } catch (_: Exception) {}
                            }
                        }

                    dispatchesWakeListener?.remove()
                    dispatchesWakeListener = firestore.collection("despachos")
                        .whereIn("estado", listOf("activa", "ACTIVA", "activo", "ACTIVO", "pre-despacho", "PRE-DESPACHO", "en curso", "EN CURSO"))
                        .addSnapshotListener { snap, err ->
                            if (err != null) {
                                scheduleNativeWakeWatcherRetry()
                                return@addSnapshotListener
                            }
                            if (snap == null) return@addSnapshotListener
                            val docsSnapshot = snap.documents.map { Pair(it.id, it.data ?: emptyMap<String, Any?>()) }
                            syncBgExecutor.execute {
                                try {
                                    var foundActiveKey = ""
                                    var foundEvacKey = ""
                                    val despArr = JSONArray()
                                    for ((docId, dataMap) in docsSnapshot) {
                                        val dJson = toSafeJsonElement(dataMap) as? JSONObject ?: JSONObject()
                                        dJson.put("_docId", docId)
                                        despArr.put(dJson)

                                        val estado = (dataMap["estado"]?.toString() ?: "activa").trim().lowercase()
                                        if (estado in listOf("finalizado", "finalizada", "cancelado", "cancelada", "cerrado")) continue
                                        val h67 = (dataMap["h67"]?.toString() ?: "").trim()
                                        if (h67.isNotEmpty() && h67 != "0" && !h67.equals("null", ignoreCase = true)) continue

                                        val unidadesMap = dataMap["unidades"] as? Map<*, *>
                                        val carrosRaw = dataMap["carros"]
                                        var unitMatched = false
                                        var unitClosed = false
                                        var idSalida = ""

                                        if (unidadesMap != null) {
                                            for ((k, v) in unidadesMap.entries) {
                                                val kNorm = (k?.toString() ?: "").replace("-", "").replace(" ", "").trim().uppercase()
                                                if (kNorm == cleanTarget) {
                                                    unitMatched = true
                                                    val vMap = v as? Map<*, *>
                                                    idSalida = (vMap?.get("idSalida")?.toString() ?: "").trim()
                                                    val st = (vMap?.get("status")?.toString() ?: "").trim().lowercase()
                                                    val h68 = (vMap?.get("hora68")?.toString() ?: vMap?.get("h68")?.toString() ?: "").trim()
                                                    if (st == "6-8" || st == "finalizado" || h68.isNotEmpty()) {
                                                        unitClosed = true
                                                    }
                                                    break
                                                }
                                            }
                                        }

                                        if (!unitMatched && carrosRaw != null) {
                                            val carrosList = when (carrosRaw) {
                                                is List<*> -> carrosRaw.mapNotNull { it?.toString() }
                                                is String -> carrosRaw.split(",", ";", "/", "|", " ")
                                                else -> emptyList()
                                            }
                                            unitMatched = carrosList.any {
                                                it.replace("-", "").replace(" ", "").trim().uppercase() == cleanTarget
                                            }
                                        }

                                        if (unitMatched && !unitClosed) {
                                            val idServ = (dataMap["idServicio"]?.toString() ?: docId).trim()
                                            if (foundActiveKey.isEmpty()) {
                                                foundActiveKey = "$idServ:$idSalida"
                                            }
                                            val evacMap = dataMap["evacuar"] as? Map<*, *>
                                            val isEvacActive = (evacMap?.get("evacuacion") as? Boolean) == true
                                            if (isEvacActive && foundEvacKey.isEmpty()) {
                                                val trigTs = (evacMap?.get("triggeredAt") as? Number)?.toLong()
                                                    ?: (evacMap?.get("updatedAt") as? Number)?.toLong()
                                                    ?: 1L
                                                foundEvacKey = "$idServ:$trigTs"
                                            }
                                        }
                                    }

                                    val syncMsg = JSONObject()
                                    syncMsg.put("type", "despachos")
                                    syncMsg.put("docs", despArr)
                                    val payloadStr = syncMsg.toString()
                                    if (payloadStr != lastPushedDespJson) {
                                        lastPushedDespJson = payloadStr
                                        MainActivity.pushNativeNavFirestoreSyncToWebView(payloadStr)
                                    }

                                    if (foundActiveKey.isNotEmpty() && foundActiveKey != lastWokenDispatchKey) {
                                        lastWokenDispatchKey = foundActiveKey
                                        MainActivity.wakeUpNavScreenAndStayAwake(applicationContext)
                                        reconnectHandler.post { sendForceUpdate() }
                                    } else if (foundActiveKey.isEmpty()) {
                                        lastWokenDispatchKey = ""
                                    }

                                    if (foundEvacKey.isNotEmpty() && foundEvacKey != lastTriggeredEvacKey) {
                                        lastTriggeredEvacKey = foundEvacKey
                                        MainActivity.wakeUpNavScreenAndStayAwake(applicationContext)
                                        SoundPlayer.playEvacuationMaxVolume(applicationContext)
                                    } else if (foundEvacKey.isEmpty() && lastTriggeredEvacKey.isNotEmpty()) {
                                        lastTriggeredEvacKey = ""
                                        SoundPlayer.stop(applicationContext)
                                    }
                                } catch (_: Exception) {}
                            }
                        }

                    bitacoraWakeListener?.remove()
                    bitacoraWakeListener = firestore.collection("bitacora")
                        .whereEqualTo("carro", unitId)
                        .addSnapshotListener { snap, err ->
                            if (err != null || snap == null) return@addSnapshotListener
                            val docsSnapshot = snap.documents.map { Pair(it.id, it.data ?: emptyMap<String, Any?>()) }
                            syncBgExecutor.execute {
                                try {
                                    val bitArr = JSONArray()
                                    var openSalidaId = ""
                                    val recentCutoffMs = System.currentTimeMillis() - 12L * 3600L * 1000L
                                    for ((docId, dataMap) in docsSnapshot) {
                                        val tiemposMap = dataMap["tiempos"] as? Map<*, *>
                                        val h68 = (tiemposMap?.get("h68")?.toString() ?: dataMap["h68"]?.toString() ?: dataMap["hora68"]?.toString() ?: "").trim()
                                        val isOpen = h68.isEmpty() || h68 == "0" || h68.equals("null", ignoreCase = true)
                                        val metaTs = (dataMap["_metaTimestamp"] as? Number)?.toLong() ?: 0L
                                        if (isOpen) {
                                            openSalidaId = (dataMap["idSalida"]?.toString() ?: docId).trim()
                                        }
                                        if (isOpen || metaTs >= recentCutoffMs) {
                                            val bJson = toSafeJsonElement(dataMap) as? JSONObject ?: JSONObject()
                                            bJson.put("_docId", docId)
                                            bitArr.put(bJson)
                                        }
                                    }
                                    val syncMsg = JSONObject()
                                    syncMsg.put("type", "bitacora")
                                    syncMsg.put("docs", bitArr)
                                    val payloadStr = syncMsg.toString()
                                    if (payloadStr != lastPushedBitJson) {
                                        lastPushedBitJson = payloadStr
                                        MainActivity.pushNativeNavFirestoreSyncToWebView(payloadStr)
                                    }

                                    if (openSalidaId.isNotEmpty() && openSalidaId != lastWokenBitacoraKey) {
                                        lastWokenBitacoraKey = openSalidaId
                                        MainActivity.wakeUpNavScreenAndStayAwake(applicationContext)
                                        reconnectHandler.post { sendForceUpdate() }
                                    } else if (openSalidaId.isEmpty()) {
                                        lastWokenBitacoraKey = ""
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                } catch (_: Exception) {
                    activeWatcherSignature = ""
                    scheduleNativeWakeWatcherRetry()
                }
            }
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun sendForceUpdate() {
        try {
            fusedLocationClient?.lastLocation?.addOnSuccessListener { loc ->
                if (loc != null) {
                    onLocationChanged(loc)
                    sendLocationToFirebase(loc, isForced = true)
                } else {
                    val lm = locationManager ?: (getSystemService(Context.LOCATION_SERVICE) as? LocationManager) ?: return@addOnSuccessListener
                    val lastGps = try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: SecurityException) { null }
                    val lastNet = try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (_: SecurityException) { null }
                    val best = if (lastGps != null && lastNet != null) {
                        if (lastGps.time > lastNet.time) lastGps else lastNet
                    } else lastGps ?: lastNet
                    if (best != null) {
                        onLocationChanged(best)
                        sendLocationToFirebase(best, isForced = true)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private var hasRecentGpsFix = false
    private var lastGpsFixTimestamp = 0L
    private var stationaryAnchorLat = 0.0
    private var stationaryAnchorLng = 0.0
    private var prevLocationForBearing: Location? = null

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocationFix(location, isFromGps = true)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val networkListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val now = System.currentTimeMillis()
            if (!hasRecentGpsFix || (now - lastGpsFixTimestamp > 15000L)) {
                handleLocationFix(location, isFromGps = false)
            }
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS).apply {
                setMinUpdateIntervalMillis(25L)
                setMaxUpdateDelayMillis(0L)
                setMinUpdateDistanceMeters(MIN_DISTANCE_M)
                setGranularity(Granularity.GRANULARITY_FINE)
                setWaitForAccurateLocation(false)
            }.build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    handleLocationFix(loc, isFromGps = true)
                }
            }

            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            fusedLocationClient?.lastLocation?.addOnSuccessListener { loc ->
                if (loc != null) handleLocationFix(loc, isFromGps = true)
            }
        } catch (_: Exception) {}

        try {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val lm = locationManager ?: return
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    UPDATE_INTERVAL_MS,
                    MIN_DISTANCE_M,
                    gpsListener,
                    Looper.getMainLooper()
                )
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { handleLocationFix(it, isFromGps = true) }
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    UPDATE_INTERVAL_MS,
                    MIN_DISTANCE_M,
                    networkListener,
                    Looper.getMainLooper()
                )
                if (currentLat == 0.0) {
                    lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { handleLocationFix(it, isFromGps = false) }
                }
            }
        } catch (_: Exception) {}
    }

    override fun onLocationChanged(location: Location) {
        handleLocationFix(location, isFromGps = location.provider == LocationManager.GPS_PROVIDER)
    }

    private fun handleLocationFix(location: Location, isFromGps: Boolean) {
        val now = System.currentTimeMillis()
        if (isFromGps && location.accuracy > 0 && location.accuracy <= 45f) {
            hasRecentGpsFix = true
            lastGpsFixTimestamp = now
        }
        if (location.accuracy > 45f && location.accuracy > 0f) return
        if (!isFromGps && hasRecentGpsFix && (now - lastGpsFixTimestamp < 10000L)) return

        val rawSpeedKmH = if (location.hasSpeed()) location.speed * 3.6f else 0f
        val distFromAnchor = FloatArray(1)
        if (stationaryAnchorLat != 0.0 && stationaryAnchorLng != 0.0) {
            Location.distanceBetween(stationaryAnchorLat, stationaryAnchorLng, location.latitude, location.longitude, distFromAnchor)
        } else {
            stationaryAnchorLat = location.latitude
            stationaryAnchorLng = location.longitude
            distFromAnchor[0] = 0f
        }
        val distanceFromAnchorM = distFromAnchor[0]
        val isVehicleStationary = rawSpeedKmH < 0.8f && distanceFromAnchorM < 1.2f

        if (isVehicleStationary && stationaryAnchorLat != 0.0) {
            currentLat = stationaryAnchorLat
            currentLng = stationaryAnchorLng
            currentSpeedKmH = 0f
            val stableHeading = when {
                lastValidMovementHeading > 0f -> lastValidMovementHeading
                currentHeading > 0f -> currentHeading
                else -> 0f
            }
            currentHeading = stableHeading

            val fixData = NavGpsFixData(
                lat = stationaryAnchorLat,
                lng = stationaryAnchorLng,
                speedKmH = 0f,
                heading = stableHeading,
                accuracy = location.accuracy,
                timestamp = now
            )
            if (!wasStationaryOnLastPush || now - lastWebViewGpsPushMs >= 5000L) {
                wasStationaryOnLastPush = true
                lastWebViewGpsPushMs = now
                try { onNativeGpsFixListener?.invoke(fixData) } catch (_: Exception) {}
            }
            if (now - lastRtdbSentTime >= 30000L) {
                val anchorLoc = Location(location).apply {
                    latitude = stationaryAnchorLat
                    longitude = stationaryAnchorLng
                    speed = 0f
                    bearing = stableHeading
                }
                sendLocationToFirebase(anchorLoc, isForced = false)
            }
            return
        }

        stationaryAnchorLat = location.latitude
        stationaryAnchorLng = location.longitude
        currentSpeedKmH = rawSpeedKmH

        var resolvedMovementHeading: Float? = null
        if (location.hasBearing() && location.bearing != 0f && rawSpeedKmH >= 2.2f) {
            resolvedMovementHeading = (location.bearing + 360f) % 360f
        }
        val prevLoc = prevLocationForBearing
        if (prevLoc != null) {
            val distMoved = prevLoc.distanceTo(location)
            if (distMoved >= 1.5f) {
                val calcB = (prevLoc.bearingTo(location) + 360f) % 360f
                resolvedMovementHeading = if (resolvedMovementHeading == null) {
                    calcB
                } else {
                    val diff = ((calcB - resolvedMovementHeading + 540f) % 360f) - 180f
                    (resolvedMovementHeading + diff * 0.45f + 360f) % 360f
                }
                prevLocationForBearing = Location(location)
            }
        } else {
            prevLocationForBearing = Location(location)
        }

        if (resolvedMovementHeading != null && resolvedMovementHeading > 0f) {
            lastValidMovementHeading = if (lastValidMovementHeading == 0f) {
                resolvedMovementHeading
            } else {
                val diff = ((resolvedMovementHeading - lastValidMovementHeading + 540f) % 360f) - 180f
                (lastValidMovementHeading + diff * 0.55f + 360f) % 360f
            }
        }

        val filteredHeading = when {
            rawSpeedKmH >= 2.2f && resolvedMovementHeading != null -> resolvedMovementHeading
            lastValidMovementHeading > 0f -> lastValidMovementHeading
            else -> currentHeading
        }
        currentHeading = filteredHeading
        currentLat = location.latitude
        currentLng = location.longitude

        val fixData = NavGpsFixData(
            lat = location.latitude,
            lng = location.longitude,
            speedKmH = currentSpeedKmH,
            heading = filteredHeading,
            accuracy = location.accuracy,
            timestamp = now
        )
        if (wasStationaryOnLastPush || now - lastWebViewGpsPushMs >= 100L) {
            wasStationaryOnLastPush = false
            lastWebViewGpsPushMs = now
            try { onNativeGpsFixListener?.invoke(fixData) } catch (_: Exception) {}
        }

        sendLocationToFirebase(location, isForced = false)
    }

    private fun sendLocationToFirebase(location: Location, isForced: Boolean) {
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences("SisBomCarPrefs", Context.MODE_PRIVATE)
        val unitId = (prefs.getString("selected_unit_id", "") ?: "").trim().uppercase()
        if (unitId.isEmpty() || location.latitude == 0.0 || location.longitude == 0.0) return

        val distResults = FloatArray(1)
        if (lastSentLat != 0.0 && lastSentLng != 0.0) {
            Location.distanceBetween(lastSentLat, lastSentLng, location.latitude, location.longitude, distResults)
        } else {
            distResults[0] = 999f
        }
        val distanceMoved = distResults[0]
        val timeSinceLastRtdb = now - lastRtdbSentTime

        val shouldSendRtdb = when {
            isForced -> true
            lastRtdbSentTime == 0L -> true
            (currentSpeedKmH >= 1.2f || distanceMoved >= 1.2f) && timeSinceLastRtdb >= 450L -> true
            timeSinceLastRtdb >= 15000L -> true
            else -> false
        }

        if (shouldSendRtdb) {
            lastRtdbSentTime = now
            lastSentLat = location.latitude
            lastSentLng = location.longitude

            try {
                val roundedLat = kotlin.math.round(location.latitude * 1000000.0) / 1000000.0
                val roundedLng = kotlin.math.round(location.longitude * 1000000.0) / 1000000.0
                val roundedHeading = kotlin.math.round(currentHeading).toInt()
                val roundedSpeed = kotlin.math.round(currentSpeedKmH).toInt()
                val unitEnServicio = (prefs.getString("unit_en_servicio", "0") ?: "0").trim()

                val cleanKey = unitId.replace(Regex("[.#$/\\[\\]]"), "_")
                val telemetria = hashMapOf<String, Any>(
                    "idCarro" to unitId,
                    "lat" to roundedLat,
                    "lng" to roundedLng,
                    "speed" to roundedSpeed,
                    "heading" to roundedHeading,
                    "accuracy" to kotlin.math.round(location.accuracy).toInt(),
                    "enServicio" to unitEnServicio,
                    "timestamp" to now,
                    "updatedAt" to now
                )
                MainActivity.getActiveRtdb(applicationContext)
                    ?.getReference("telemetria/vehiculos")
                    ?.child(cleanKey)
                    ?.setValue(telemetria)
            } catch (_: Exception) {}
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SENTINEL NAV GPS",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Transmisión GPS de carro bomba"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(title: String, text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.logo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isServiceRunning = false
        try {
            reconnectHandler.removeCallbacksAndMessages(null)
            networkCallback?.let { cb -> connectivityManager?.unregisterNetworkCallback(cb) }
            networkCallback = null
        } catch (_: Exception) {}
        try {
            activeWatcherSignature = ""
            vehicleWakeListener?.remove()
            vehicleWakeListener = null
            dispatchesWakeListener?.remove()
            dispatchesWakeListener = null
            bitacoraWakeListener?.remove()
            bitacoraWakeListener = null
            syncBgExecutor.shutdownNow()
        } catch (_: Exception) {}
        try {
            locationCallback?.let { fusedLocationClient?.removeLocationUpdates(it) }
        } catch (_: Exception) {}
        try {
            locationManager?.removeUpdates(gpsListener)
            locationManager?.removeUpdates(networkListener)
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {}
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (_: Exception) {}
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
