package com.sisbom.sisbomcar

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GpsTrackingService : Service(), LocationListener {

    private var locationManager: LocationManager? = null
    private val repository = FirebaseRepository()
    private var lastSentLat = 0.0
    private var lastSentLng = 0.0
    private var lastSentTime = 0L
    private var lastSentDispatchId = ""

    companion object {
        private const val NOTIFICATION_ID = 8844
        private const val CHANNEL_ID = "sisbom_car_gps"
        private const val UPDATE_INTERVAL_MS = 1000L // 1 segundo para visualización fluida en tiempo real
        private const val MIN_DISTANCE_M = 0f

        var isServiceRunning = false
        var currentLat = 0.0
        var currentLng = 0.0
        var currentSpeedKmH = 0f
        var currentHeading = 0f

        private val _localLocationFlow = MutableStateFlow<GpsLocationData?>(null)
        val localLocationFlow: StateFlow<GpsLocationData?> = _localLocationFlow.asStateFlow()

        fun startService(context: Context) {
            try {
                val hasFine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val hasCoarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!hasFine && !hasCoarse) return

                val intent = Intent(context, GpsTrackingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stopService(context: Context) {
            try {
                val intent = Intent(context, GpsTrackingService::class.java)
                context.stopService(intent)
            } catch (_: Exception) {}
        }

        fun triggerImmediateLocationUpdate(context: Context) {
            try {
                val intent = Intent(context, GpsTrackingService::class.java).apply {
                    action = "ACTION_FORCE_UPDATE"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true

        try {
            createNotificationChannel()
            val notification = buildForegroundNotification("SisBom Car Activo", "Monitoreo GPS continuo de Carro Bomba")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {}

        startLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_FORCE_UPDATE") {
            sendForceUpdate()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun sendForceUpdate() {
        try {
            val lm = locationManager ?: (getSystemService(Context.LOCATION_SERVICE) as? LocationManager) ?: return
            val lastGps = try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch(_: SecurityException) { null }
            val lastNet = try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch(_: SecurityException) { null }
            val best = if (lastGps != null && lastNet != null) {
                if (lastGps.time > lastNet.time) lastGps else lastNet
            } else lastGps ?: lastNet

            if (best != null) {
                onLocationChanged(best)
                sendLocationToFirebase(best, isForced = true)
            }
        } catch (_: Exception) {}
    }

    private var hasRecentGpsFix = false
    private var lastGpsFixTimestamp = 0L
    private var stationaryAnchorLat = 0.0
    private var stationaryAnchorLng = 0.0

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
            // Solo procesar red si no hay señal GPS reciente (últimos 15 segundos)
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

        // Si es GPS y tiene precisión razonable, actualizar marca de tiempo GPS
        if (isFromGps && location.accuracy > 0 && location.accuracy <= 50f) {
            hasRecentGpsFix = true
            lastGpsFixTimestamp = now
        }

        // Descartar lecturas con pésima precisión (> 40 metros)
        if (location.accuracy > 40f) {
            return
        }

        // Si tenemos señal GPS reciente, ignorar completamente cualquier punto proveniente de red
        if (!isFromGps && hasRecentGpsFix && (now - lastGpsFixTimestamp < 15000L)) {
            return
        }

        val rawSpeedKmH = if (location.hasSpeed()) location.speed * 3.6f else 0f

        // Inicializar ancla si es la primera coordenada válida
        if (stationaryAnchorLat == 0.0 && stationaryAnchorLng == 0.0) {
            stationaryAnchorLat = location.latitude
            stationaryAnchorLng = location.longitude
            currentLat = location.latitude
            currentLng = location.longitude
        }

        val distFromAnchor = FloatArray(1)
        Location.distanceBetween(stationaryAnchorLat, stationaryAnchorLng, location.latitude, location.longitude, distFromAnchor)

        // Filtro estricto contra ruido de oficina / interiores:
        // Si no ha salido de un radio de 15 metros del ancla, o la precisión es > 20m con velocidad baja,
        // la velocidad real es estrictamente 0 km/h (elimina saltos de 11 a 14 km/h en reposo).
        val filteredSpeedKmH = when {
            distFromAnchor[0] < 15.0f -> 0f
            location.accuracy > 25f -> 0f
            location.accuracy > 15f && rawSpeedKmH < 18.0f -> 0f
            rawSpeedKmH < 5.0f -> 0f
            else -> rawSpeedKmH
        }

        // Si el vehículo está detenido o dentro del radio de reposo:
        if (filteredSpeedKmH == 0f) {
            currentSpeedKmH = 0f
            _localLocationFlow.value = GpsLocationData(
                lat = stationaryAnchorLat,
                lng = stationaryAnchorLng,
                speedKmH = 0f,
                heading = currentHeading,
                accuracy = location.accuracy,
                timestamp = location.time
            )
            return
        }

        // Si el vehículo realmente salió del reposo (movimiento genuino):
        // Actualizar ancla a la posición en movimiento
        stationaryAnchorLat = location.latitude
        stationaryAnchorLng = location.longitude

        val filteredHeading = if (location.hasBearing() && filteredSpeedKmH >= 5f) {
            location.bearing
        } else {
            currentHeading
        }

        currentLat = location.latitude
        currentLng = location.longitude
        currentSpeedKmH = filteredSpeedKmH
        currentHeading = filteredHeading

        // Emisión inmediata en tiempo real local para la interfaz y mapa
        _localLocationFlow.value = GpsLocationData(
            lat = location.latitude,
            lng = location.longitude,
            speedKmH = filteredSpeedKmH,
            heading = filteredHeading,
            accuracy = location.accuracy,
            timestamp = location.time
        )

        sendLocationToFirebase(location, isForced = false)
    }

    private fun sendLocationToFirebase(location: Location, isForced: Boolean) {
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences("SisBomCarPrefs", Context.MODE_PRIVATE)
        val unitId = prefs.getString("selected_unit_id", "") ?: ""
        val activeDispatchId = prefs.getString("active_dispatch_id", "") ?: ""

        if (unitId.isEmpty()) return

        // 1. Calcular distancia recorrida desde el último envío a Firestore
        val distResults = FloatArray(1)
        if (lastSentLat != 0.0 && lastSentLng != 0.0) {
            Location.distanceBetween(lastSentLat, lastSentLng, location.latitude, location.longitude, distResults)
        } else {
            distResults[0] = 999f
        }
        val distanceMoved = distResults[0]

        // 2. Condición de activación / cambio de despacho
        val isDispatched = activeDispatchId.isNotEmpty()
        val dispatchStateChanged = isDispatched && (lastSentDispatchId != activeDispatchId)

        // Criterios de envío a Firestore:
        // - Forzado (ej. botón o inicio)
        // - Cambio / activación de despacho
        // - Movimiento real >= 35 metros
        // - Cada 30 segundos si hay movimiento
        val isMoving = currentSpeedKmH >= 4f
        val timeSinceLast = now - lastSentTime
        val shouldSend = isForced ||
                dispatchStateChanged ||
                (distanceMoved >= 35f) ||
                (isMoving && timeSinceLast >= 30000L) ||
                lastSentTime == 0L

        if (shouldSend) {
            lastSentTime = now
            lastSentLat = location.latitude
            lastSentLng = location.longitude
            lastSentDispatchId = activeDispatchId

            repository.updateVehicleLocation(
                vehicleId = unitId,
                lat = location.latitude,
                lng = location.longitude,
                heading = currentHeading
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SisBom Car GPS",
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
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isServiceRunning = false
        locationManager?.removeUpdates(this)
        super.onDestroy()
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
