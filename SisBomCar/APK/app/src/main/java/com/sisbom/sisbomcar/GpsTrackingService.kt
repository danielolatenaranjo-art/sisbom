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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager

class GpsTrackingService : Service(), LocationListener, SensorEventListener {

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null
    private var rotationVectorSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var magSensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val repository = FirebaseRepository()
    private var lastSentLat = 0.0
    private var lastSentLng = 0.0
    private var lastSentTime = 0L
    private var lastSentDispatchId = ""

    // Variables de fusión de sensores (Giroscopio / Magnetómetro / Acelerómetro)
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)
    private val lastAccel = FloatArray(3)
    private val lastMag = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false
    private var smoothedSensorHeading = 0f
    private var lastSensorEmitTimestamp = 0L
    private var lastValidMovementHeading = 0f

    companion object {
        private const val NOTIFICATION_ID = 8844
        private const val CHANNEL_ID = "sisbom_car_gps"
        private const val UPDATE_INTERVAL_MS = 250L // 4 Hz (250ms) para movimiento local milimétrico en tiempo real
        private const val MIN_DISTANCE_M = 0f

        @Volatile
        var currentDisplayRotation: Int = Surface.ROTATION_90

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
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SisBomCar:GpsTrackingWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 24 horas seguro
            }
        } catch (_: Exception) {}

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
        startSensorUpdates()
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
            fusedLocationClient?.lastLocation?.addOnSuccessListener { loc ->
                if (loc != null) {
                    onLocationChanged(loc)
                    sendLocationToFirebase(loc, isForced = true)
                }
            }

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
        // 1. Google Play Services Fused Location Provider (GNSS multi-constelación L1/L5 + RTT + Fusión IMU)
        try {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS).apply {
                setMinUpdateIntervalMillis(100L)
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
                if (loc != null) {
                    handleLocationFix(loc, isFromGps = true)
                }
            }
        } catch (_: Exception) {}

        // 2. Android LocationManager Nativo (Motor de Respaldo Redundante con GPS_PROVIDER)
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
        if (isFromGps && location.accuracy > 0 && location.accuracy <= 45f) {
            hasRecentGpsFix = true
            lastGpsFixTimestamp = now
        }

        // Descartar únicamente lecturas con error grosero (> 45 metros) si la precisión es válida
        if (location.accuracy > 45f && location.accuracy > 0f) {
            return
        }

        // Si tenemos señal GPS reciente, ignorar completamente cualquier punto proveniente de red
        if (!isFromGps && hasRecentGpsFix && (now - lastGpsFixTimestamp < 10000L)) {
            return
        }

        val rawSpeedKmH = if (location.hasSpeed()) location.speed * 3.6f else 0f

        currentLat = location.latitude
        currentLng = location.longitude

        val distFromAnchor = FloatArray(1)
        Location.distanceBetween(stationaryAnchorLat, stationaryAnchorLng, location.latitude, location.longitude, distFromAnchor)
        val distanceFromAnchorM = distFromAnchor[0]

        // Detección fluida de movimiento vs reposo:
        val isVehicleStationary = distanceFromAnchorM < 1.8f && rawSpeedKmH < 1.2f

        if (isVehicleStationary && stationaryAnchorLat != 0.0) {
            // El vehículo está detenido (semáforos, cuartel, intersecciones o escena)
            currentSpeedKmH = 0f
            // En reposo, preservar rigurosamente el último rumbo cinemático de movimiento (no rota hacia el Norte ni gira)
            val bestHeading = when {
                smoothedSensorHeading > 0f -> smoothedSensorHeading
                lastValidMovementHeading > 0f -> lastValidMovementHeading
                currentHeading > 0f -> currentHeading
                else -> 0f
            }
            currentHeading = bestHeading
            _localLocationFlow.value = GpsLocationData(
                lat = currentLat,
                lng = currentLng,
                speedKmH = 0f,
                heading = bestHeading,
                accuracy = location.accuracy,
                timestamp = location.time
            )
            sendLocationToFirebase(location, isForced = false)
            return
        }

        // El vehículo está en movimiento: actualizar ancla y posición actual
        stationaryAnchorLat = location.latitude
        stationaryAnchorLng = location.longitude
        currentSpeedKmH = if (rawSpeedKmH >= 1.5f) rawSpeedKmH else 3.5f

        var resolvedMovementHeading: Float? = null

        // 1. Rumbo nativo de hardware GNSS/GPS en movimiento (Doppler / Carrier phase)
        if (location.hasBearing() && location.bearing != 0f) {
            resolvedMovementHeading = (location.bearing + 360f) % 360f
        }

        // 2. Rumbo cinemático matemático calculado entre puntos sucesivos (Trayectoria real GPS)
        val prevLoc = prevLocationForBearing
        if (prevLoc != null) {
            val distMoved = prevLoc.distanceTo(location)
            if (distMoved >= 1.5f) {
                val calcB = (prevLoc.bearingTo(location) + 360f) % 360f
                if (resolvedMovementHeading == null) {
                    resolvedMovementHeading = calcB
                } else {
                    // Fusión suave entre rumbo GNSS y vector de desplazamiento cinemático
                    val diff = ((calcB - resolvedMovementHeading + 540f) % 360f) - 180f
                    resolvedMovementHeading = (resolvedMovementHeading + diff * 0.35f + 360f) % 360f
                }
                prevLocationForBearing = Location(location)
            }
        } else {
            prevLocationForBearing = Location(location)
        }

        if (resolvedMovementHeading != null && resolvedMovementHeading > 0f) {
            // Suavizado exponencial del rumbo de movimiento para eliminar jitter y mantener dirección perfecta
            if (lastValidMovementHeading == 0f) {
                lastValidMovementHeading = resolvedMovementHeading
            } else {
                val diff = ((resolvedMovementHeading - lastValidMovementHeading + 540f) % 360f) - 180f
                lastValidMovementHeading = (lastValidMovementHeading + diff * 0.4f + 360f) % 360f
            }
        }

        val filteredHeading = when {
            rawSpeedKmH >= 3.5f && resolvedMovementHeading != null -> resolvedMovementHeading
            rawSpeedKmH >= 3.5f && lastValidMovementHeading > 0f -> lastValidMovementHeading
            smoothedSensorHeading > 0f -> smoothedSensorHeading
            lastValidMovementHeading > 0f -> lastValidMovementHeading
            else -> currentHeading
        }
        currentHeading = filteredHeading

        // Emisión inmediata en tiempo real local para la interfaz y mapa (fluidez 60 FPS)
        _localLocationFlow.value = GpsLocationData(
            lat = location.latitude,
            lng = location.longitude,
            speedKmH = currentSpeedKmH,
            heading = filteredHeading,
            accuracy = location.accuracy,
            timestamp = location.time
        )

        sendLocationToFirebase(location, isForced = false)
    }

    private fun startSensorUpdates() {
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val sm = sensorManager ?: return

            rotationVectorSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (rotationVectorSensor != null) {
                sm.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
            } else {
                // Fallback: Acelerómetro + Brújula Magnética
                accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                magSensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
                accelSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
                magSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            }
        } catch (_: Exception) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        try {
            var rawAzimuth: Float? = null
            val rotation = currentDisplayRotation

            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                when (rotation) {
                    Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remappedMatrix)
                    Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remappedMatrix)
                    Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remappedMatrix)
                    else -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Y, remappedMatrix)
                }

                SensorManager.getOrientation(remappedMatrix, orientationValues)
                var az = Math.toDegrees(orientationValues[0].toDouble()).toFloat()
                if (az < 0) az += 360f
                rawAzimuth = az
            } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(event.values, 0, lastAccel, 0, event.values.size)
                hasAccel = true
            } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                System.arraycopy(event.values, 0, lastMag, 0, event.values.size)
                hasMag = true
            }

            if (rawAzimuth == null && hasAccel && hasMag) {
                if (SensorManager.getRotationMatrix(rotationMatrix, null, lastAccel, lastMag)) {
                    when (rotation) {
                        Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remappedMatrix)
                        Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remappedMatrix)
                        Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remappedMatrix)
                        else -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Y, remappedMatrix)
                    }
                    SensorManager.getOrientation(remappedMatrix, orientationValues)
                    var az = Math.toDegrees(orientationValues[0].toDouble()).toFloat()
                    if (az < 0) az += 360f
                    rawAzimuth = az
                }
            }

            if (rawAzimuth != null) {
                updateSmoothedSensorHeading(rawAzimuth)
            }
        } catch (_: Exception) {}
    }

    private fun updateSmoothedSensorHeading(rawAzimuth: Float) {
        if (smoothedSensorHeading == 0f) {
            smoothedSensorHeading = rawAzimuth
        } else {
            val diff = ((rawAzimuth - smoothedSensorHeading + 540f) % 360f) - 180f
            // Filtro de banda muerta: ignorar temblores menores a 3.5° para evitar giros involuntarios de cámara por ruido magnético
            if (Math.abs(diff) > 3.5f) {
                smoothedSensorHeading = (smoothedSensorHeading + diff * 0.20f + 360f) % 360f
            }
        }

        // Si el vehículo está detenido o a baja velocidad (< 4 km/h), el giroscopio orienta el vehículo con estabilidad
        if (currentSpeedKmH < 4f && smoothedSensorHeading > 0f) {
            currentHeading = smoothedSensorHeading
            val now = System.currentTimeMillis()
            if (now - lastSensorEmitTimestamp >= 120L && currentLat != 0.0 && currentLng != 0.0) {
                lastSensorEmitTimestamp = now
                _localLocationFlow.value = GpsLocationData(
                    lat = currentLat,
                    lng = currentLng,
                    speedKmH = currentSpeedKmH,
                    heading = smoothedSensorHeading,
                    accuracy = 10f,
                    timestamp = now
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun sendLocationToFirebase(location: Location, isForced: Boolean) {
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences("SisBomCarPrefs", Context.MODE_PRIVATE)
        val unitId = prefs.getString("selected_unit_id", "") ?: ""
        val activeDispatchId = prefs.getString("active_dispatch_id", "") ?: ""
        val unitEnServicio = prefs.getString("unit_en_servicio", "0") ?: "0"
        val isEnCuartelPref = prefs.getBoolean("is_en_cuartel", true)

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

        // 3. Evaluar si la unidad está en servicio activo de emergencia
        val isInEmergencyService = isDispatched || (unitEnServicio.isNotEmpty() && unitEnServicio != "0" && unitEnServicio != "0-8" && unitEnServicio != "0-9")
        val isAtCuartel = !isInEmergencyService || isEnCuartelPref

        val timeSinceLast = now - lastSentTime

        // Criterios de transmisión a Firestore (Optimizado estrictamente contra cuotas excesivas):
        // REGLA: Cada 1 minuto (60.000 ms) o cada 100 metros, lo que ocurra primero.
        // EN CUARTEL: Si el vehículo está en cuartel, se transmite UNA sola vez al iniciar o llegar,
        // y NO se repite periódicamente en reposo a menos que se mueva >= 100 metros o cambie a despacho/forzado.
        val shouldSend = if (isForced || dispatchStateChanged || lastSentTime == 0L) {
            true
        } else if (distanceMoved >= 100f) {
            true
        } else if (isInEmergencyService && !isAtCuartel && timeSinceLast >= 60000L) {
            true
        } else {
            false
        }

        if (shouldSend) {
            lastSentTime = now
            lastSentLat = location.latitude
            lastSentLng = location.longitude
            lastSentDispatchId = activeDispatchId

            try {
                repository.updateVehicleLocation(
                    vehicleId = unitId,
                    lat = location.latitude,
                    lng = location.longitude,
                    heading = currentHeading
                )
            } catch (_: Exception) {}
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
            .setSmallIcon(R.drawable.ic_notification_sentinel)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isServiceRunning = false
        try {
            locationCallback?.let { fusedLocationClient?.removeLocationUpdates(it) }
        } catch (_: Exception) {}
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {}
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {}
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
