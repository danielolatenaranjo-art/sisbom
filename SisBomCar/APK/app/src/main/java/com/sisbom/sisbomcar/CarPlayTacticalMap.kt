package com.sisbom.sisbomcar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL

enum class NavTargetType {
    INCIDENT,
    ALERTANTE,
    CENTRAL,
    NONE
}

data class TacticalManeuverStep(
    val instruction: String,
    val streetName: String,
    val distanceMeters: Double,
    val modifier: String,
    val maneuverType: String,
    val location: GeoPoint
)

// Función auxiliar para calcular rumbo azimutal entre dos puntos geográficos (en grados 0-360)
fun computeBearingBetween(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Float {
    val lat1 = Math.toRadians(fromLat)
    val lat2 = Math.toRadians(toLat)
    val dLon = Math.toRadians(toLng - fromLng)
    val y = Math.sin(dLon) * Math.cos(lat2)
    val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
    var brng = Math.toDegrees(Math.atan2(y, x)).toFloat()
    if (brng < 0f) brng += 360f
    return brng
}

// Estilo Nocturno Táctico de Alto Rendimiento para Google Maps (Con POIs, parques y puntos de interés visibles)
val TacticalNightMapStyleJson = """
[
  { "elementType": "geometry", "stylers": [{ "color": "#0f172a" }] },
  { "elementType": "labels.text.fill", "stylers": [{ "color": "#94a3b8" }] },
  { "elementType": "labels.text.stroke", "stylers": [{ "color": "#020617" }] },
  { "featureType": "administrative", "elementType": "geometry", "stylers": [{ "visibility": "off" }] },
  { "featureType": "administrative.locality", "elementType": "labels.text.fill", "stylers": [{ "color": "#e2e8f0" }] },
  { "featureType": "poi", "elementType": "labels.text.fill", "stylers": [{ "color": "#cbd5e1" }] },
  { "featureType": "poi.park", "elementType": "geometry", "stylers": [{ "color": "#064e3b" }] },
  { "featureType": "road", "elementType": "geometry", "stylers": [{ "color": "#1e293b" }] },
  { "featureType": "road", "elementType": "geometry.stroke", "stylers": [{ "color": "#0f172a" }] },
  { "featureType": "road", "elementType": "labels.text.fill", "stylers": [{ "color": "#f8fafc" }] },
  { "featureType": "road.highway", "elementType": "geometry", "stylers": [{ "color": "#334155" }] },
  { "featureType": "road.highway", "elementType": "geometry.stroke", "stylers": [{ "color": "#1e293b" }] },
  { "featureType": "road.highway", "elementType": "labels.text.fill", "stylers": [{ "color": "#38bdf8" }] },
  { "featureType": "transit", "elementType": "geometry", "stylers": [{ "color": "#1e293b" }] },
  { "featureType": "water", "elementType": "geometry", "stylers": [{ "color": "#082f49" }] },
  { "featureType": "water", "elementType": "labels.text.fill", "stylers": [{ "color": "#38bdf8" }] }
]
""".trimIndent()

@Composable
fun CarPlayTacticalMap(
    dispatchId: String? = null,
    emergencyLat: Double?,
    emergencyLng: Double?,
    emergencyClave: String,
    alertanteLat: Double? = null,
    alertanteLng: Double? = null,
    alertantePhone: String = "",
    centralLat: Double = -34.636808,
    centralLng: Double = -71.119757,
    vehicleLat: Double?,
    vehicleLng: Double?,
    vehicleHeading: Float = 0f,
    vehicleSpeedKmH: Float = 0f,
    unitStatusInDispatch: String = "",
    hasActive6XButton: Boolean = false,
    isDarkMode: Boolean = true,
    personnelList: List<PersonItem> = emptyList(),
    assignedPersonnelIds: Set<String> = emptySet(),
    onEtaCalculated: (durationSeconds: Double, distanceMeters: Double) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isSatelliteMode by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            com.google.android.gms.maps.MapsInitializer.initialize(context)
        } catch (_: Exception) {}
    }

    val hasIncident = emergencyLat != null && emergencyLat != 0.0 && emergencyLng != null && emergencyLng != 0.0
    val hasAlertante = alertanteLat != null && alertanteLat != 0.0 && alertanteLng != null && alertanteLng != 0.0
    val hasVehicle = vehicleLat != null && vehicleLat != 0.0 && vehicleLng != null && vehicleLng != 0.0

    val cleanClave = emergencyClave.trim().uppercase()
    val isRetornoToCuartel = cleanClave.contains("6-9") || cleanClave.contains("RETORNO")

    // Estados de eliminación táctica de puntos por doble toque
    var isIncidentDeleted by remember(dispatchId) { mutableStateOf(false) }
    var isAlertanteDeleted by remember(dispatchId) { mutableStateOf(false) }
    var isCentralDeleted by remember(dispatchId) { mutableStateOf(false) }

    var lastClickIncidentTime by remember { mutableStateOf(0L) }
    var lastClickAlertanteTime by remember { mutableStateOf(0L) }
    var lastClickCentralTime by remember { mutableStateOf(0L) }

    // Selección de objetivo de navegación interactivo (Automático en Incidente si existe, o libre)
    var activeNavTarget by remember(dispatchId, isRetornoToCuartel) {
        mutableStateOf(
            when {
                isRetornoToCuartel -> NavTargetType.CENTRAL
                hasIncident && !isIncidentDeleted -> NavTargetType.INCIDENT
                else -> NavTargetType.NONE
            }
        )
    }

    val is615Traslado = cleanClave.contains("6-15") || cleanClave.contains("CESFAM") || cleanClave.contains("HOSPITAL")

    val pinColorHex = when {
        isRetornoToCuartel -> "#10B981" // Verde Esmeralda / Cuartel Base
        is615Traslado -> "#06B6D4" // Cian Médico / Centro Asistencial (6-15)
        cleanClave.contains("10-0") || cleanClave.contains("10-4") -> "#EF4444"
        cleanClave.contains("10-2") || cleanClave.contains("10-3") -> "#F59E0B"
        else -> "#0284C7"
    }
    val pinColorInt = try {
        android.graphics.Color.parseColor(pinColorHex)
    } catch (_: Exception) {
        android.graphics.Color.RED
    }

    val emLat = emergencyLat ?: -34.636808
    val emLng = emergencyLng ?: -71.119757
    val alLat = alertanteLat ?: 0.0
    val alLng = alertanteLng ?: 0.0
    val cenLat = centralLat
    val cenLng = centralLng

    val vLat = vehicleLat ?: emLat
    val vLng = vehicleLng ?: emLng

    val repository = remember { FirebaseRepository() }

    // Puntos de ruta y maniobras calculadas
    var routePoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var maneuverSteps by remember { mutableStateOf<List<TacticalManeuverStep>>(emptyList()) }
    var isCameraFollowMode by remember { mutableStateOf(true) }

    // Rumbo adelantado asistido por la ruta calculada (12m - 50m hacia adelante)
    val routeBearingAhead = remember(routePoints, vLat, vLng) {
        if (routePoints.size >= 2 && vLat != 0.0 && vLng != 0.0) {
            val curP = GeoPoint(vLat, vLng)
            var targetLookaheadPoint: GeoPoint? = null
            for (pt in routePoints) {
                val d = curP.distanceToAsDouble(pt)
                if (d in 12.0..60.0) {
                    targetLookaheadPoint = pt
                    break
                }
            }
            if (targetLookaheadPoint == null && routePoints.size > 1) {
                targetLookaheadPoint = routePoints[1]
            }
            if (targetLookaheadPoint != null) {
                computeBearingBetween(vLat, vLng, targetLookaheadPoint.latitude, targetLookaheadPoint.longitude)
            } else 0f
        } else 0f
    }

    // Variables de interpolación suave para movimiento fluido a 60 FPS
    var smoothLat by remember { mutableStateOf(vLat) }
    var smoothLng by remember { mutableStateOf(vLng) }
    var lastStableHeading by remember { mutableStateOf(if (vehicleHeading > 0f) vehicleHeading else 0f) }
    var smoothHeading by remember { mutableStateOf(lastStableHeading) }

    LaunchedEffect(vLat, vLng, vehicleHeading, routeBearingAhead, vehicleSpeedKmH) {
        val targetLat = if (vLat != 0.0) vLat else smoothLat
        val targetLng = if (vLng != 0.0) vLng else smoothLng

        // Si el vehículo está en marcha (> 3 km/h), priorizar el rumbo real de desplazamiento GPS o el rumbo hacia la ruta
        // Si está detenido (< 3 km/h), congelar en el último rumbo estable para evitar giros involuntarios por ruido magnético
        val targetHeading = when {
            vehicleSpeedKmH >= 3f && vehicleHeading > 0f -> {
                lastStableHeading = vehicleHeading
                vehicleHeading
            }
            vehicleSpeedKmH >= 3f && routeBearingAhead > 0f -> {
                lastStableHeading = routeBearingAhead
                routeBearingAhead
            }
            vehicleHeading > 0f && Math.abs(((vehicleHeading - lastStableHeading + 540f) % 360f) - 180f) > 22f -> {
                // Rotación intencional del dispositivo en reposo (> 22° de diferencia sostenida)
                lastStableHeading = vehicleHeading
                vehicleHeading
            }
            lastStableHeading > 0f -> lastStableHeading
            vehicleHeading > 0f -> vehicleHeading
            routeBearingAhead > 0f -> routeBearingAhead
            else -> smoothHeading
        }

        val startLat = smoothLat
        val startLng = smoothLng
        val startHeading = smoothHeading

        val diffHeading = ((targetHeading - startHeading + 540f) % 360f) - 180f

        val distP = GeoPoint(startLat, startLng).distanceToAsDouble(GeoPoint(targetLat, targetLng))
        if (distP > 100.0) {
            smoothLat = targetLat
            smoothLng = targetLng
            smoothHeading = targetHeading
            return@LaunchedEffect
        }

        val steps = 12
        for (i in 1..steps) {
            val frac = i.toFloat() / steps.toFloat()
            smoothLat = startLat + (targetLat - startLat) * frac
            smoothLng = startLng + (targetLng - startLng) * frac
            smoothHeading = (startHeading + diffHeading * frac + 360f) % 360f
            delay(16L)
        }
    }

    // Coordenadas activas del destino actual
    val currentDestLat = when (activeNavTarget) {
        NavTargetType.INCIDENT -> if (hasIncident && !isIncidentDeleted) emLat else 0.0
        NavTargetType.ALERTANTE -> if (hasAlertante && !isAlertanteDeleted) alLat else 0.0
        NavTargetType.CENTRAL -> if (!isCentralDeleted) cenLat else 0.0
        NavTargetType.NONE -> 0.0
    }
    val currentDestLng = when (activeNavTarget) {
        NavTargetType.INCIDENT -> if (hasIncident && !isIncidentDeleted) emLng else 0.0
        NavTargetType.ALERTANTE -> if (hasAlertante && !isAlertanteDeleted) alLng else 0.0
        NavTargetType.CENTRAL -> if (!isCentralDeleted) cenLng else 0.0
        NavTargetType.NONE -> 0.0
    }

    var lastRoutedOrigin by remember { mutableStateOf<GeoPoint?>(null) }
    var lastRoutedDest by remember { mutableStateOf<GeoPoint?>(null) }
    var lastRouteFetchTime by remember { mutableStateOf(0L) }
    var lastFittedDispatchId by remember { mutableStateOf<String?>(null) }

    // Al recibir un nuevo despacho, reajustar estado para activar automáticamente la ruta de emergencia
    LaunchedEffect(dispatchId) {
        if (dispatchId != null && dispatchId != lastFittedDispatchId) {
            lastFittedDispatchId = dispatchId
            lastRoutedOrigin = null
            lastRoutedDest = null
            isIncidentDeleted = false
            isAlertanteDeleted = false
            activeNavTarget = when {
                isRetornoToCuartel -> NavTargetType.CENTRAL
                hasIncident -> NavTargetType.INCIDENT
                else -> NavTargetType.NONE
            }
        }
    }

    // Cálculo y persistencia de ruta OSRM con soporte 100% offline
    LaunchedEffect(hasVehicle, vLat, vLng, currentDestLat, currentDestLng, activeNavTarget, dispatchId) {
        if (activeNavTarget == NavTargetType.NONE || currentDestLat == 0.0 || !hasVehicle) {
            routePoints = emptyList()
            maneuverSteps = emptyList()
            lastRoutedOrigin = null
            lastRoutedDest = null
            onEtaCalculated(0.0, 0.0)
            return@LaunchedEffect
        }
        if (hasVehicle && vLat != 0.0 && currentDestLat != 0.0) {
            val curVPoint = GeoPoint(vLat, vLng)
            val curDestPoint = GeoPoint(currentDestLat, currentDestLng)
            val now = System.currentTimeMillis()
            val routeCacheKey = "${dispatchId ?: "active"}_${activeNavTarget.name}"

            // Cargar ruta desde caché offline si aún no hay puntos cargados
            if (routePoints.isEmpty()) {
                val cached = repository.loadCachedRoute(context, routeCacheKey)
                if (cached != null && cached.points.isNotEmpty()) {
                    routePoints = cached.points
                    maneuverSteps = cached.maneuvers
                    onEtaCalculated(cached.duration, cached.distance)
                }
            }

            val distFromLastOrigin = lastRoutedOrigin?.distanceToAsDouble(curVPoint) ?: 9999.0
            val distFromLastDest = lastRoutedDest?.distanceToAsDouble(curDestPoint) ?: 9999.0

            val shouldFetch = (lastRoutedOrigin == null) ||
                    (distFromLastDest > 15.0) ||
                    (distFromLastOrigin >= 30.0 && now - lastRouteFetchTime >= 3500L)

            if (shouldFetch) {
                lastRoutedOrigin = curVPoint
                lastRoutedDest = curDestPoint
                lastRouteFetchTime = now

                withContext(Dispatchers.IO) {
                    val bearingsParam = if (vehicleHeading > 0f && vehicleSpeedKmH > 4f) {
                        "&bearings=${vehicleHeading.toInt().coerceIn(0, 360)},45;"
                    } else ""

                    val endpoints = listOf(
                        "https://router.project-osrm.org/route/v1/driving/$vLng,$vLat;$currentDestLng,$currentDestLat?steps=true&overview=full&geometries=geojson$bearingsParam",
                        "https://routing.openstreetmap.de/routed-car/route/v1/driving/$vLng,$vLat;$currentDestLng,$currentDestLat?steps=true&overview=full&geometries=geojson$bearingsParam",
                        "https://router.project-osrm.org/route/v1/driving/$vLng,$vLat;$currentDestLng,$currentDestLat?steps=true&overview=full&geometries=geojson"
                    )

                    var success = false
                    for (urlStr in endpoints) {
                        try {
                            val url = URL(urlStr)
                            val conn = (url.openConnection() as HttpURLConnection).apply {
                                connectTimeout = 4000
                                readTimeout = 4000
                                requestMethod = "GET"
                                setRequestProperty("User-Agent", "SisBomCar/1.0")
                            }

                            if (conn.responseCode == 200) {
                                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                                val json = JSONObject(jsonStr)
                                val routes = json.optJSONArray("routes")
                                if (routes != null && routes.length() > 0) {
                                    val firstRoute = routes.getJSONObject(0)
                                    val geometry = firstRoute.getJSONObject("geometry")
                                    val coords = geometry.getJSONArray("coordinates")

                                    val pts = mutableListOf<GeoPoint>()
                                    for (i in 0 until coords.length()) {
                                        val c = coords.getJSONArray(i)
                                        pts.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
                                    }

                                    val routeDur = firstRoute.optDouble("duration", 0.0)
                                    val routeDist = firstRoute.optDouble("distance", 0.0)

                                    val legs = firstRoute.optJSONArray("legs")
                                    val parsedSteps = mutableListOf<TacticalManeuverStep>()
                                    if (legs != null && legs.length() > 0) {
                                        val firstLeg = legs.getJSONObject(0)
                                        val stepsArr = firstLeg.optJSONArray("steps")
                                        if (stepsArr != null) {
                                            for (s in 0 until stepsArr.length()) {
                                                val stepObj = stepsArr.getJSONObject(s)
                                                val manObj = stepObj.optJSONObject("maneuver")
                                                val manType = manObj?.optString("type", "") ?: ""
                                                val manMod = manObj?.optString("modifier", "") ?: ""
                                                val manLocArr = manObj?.optJSONArray("location")
                                                val stepGeo = if (manLocArr != null && manLocArr.length() >= 2) {
                                                    GeoPoint(manLocArr.getDouble(1), manLocArr.getDouble(0))
                                                } else GeoPoint(0.0, 0.0)

                                                val stName = stepObj.optString("name", "")
                                                val stDist = stepObj.optDouble("distance", 0.0)
                                                val instruction = when (manType) {
                                                    "depart" -> "Inicie el recorrido hacia $stName"
                                                    "arrive" -> "Llegada a destino"
                                                    "turn" -> {
                                                        when (manMod) {
                                                            "sharp right" -> "Gire a la derecha pronunciada en $stName"
                                                            "right" -> "Gire a la derecha en $stName"
                                                            "slight right" -> "Gire levemente a la derecha en $stName"
                                                            "sharp left" -> "Gire a la izquierda pronunciada en $stName"
                                                            "left" -> "Gire a la izquierda en $stName"
                                                            "slight left" -> "Gire levemente a la izquierda en $stName"
                                                            "uturn" -> "Dé vuelta en U"
                                                            else -> "Gire en $stName"
                                                        }
                                                    }
                                                    "roundabout", "rotary" -> "Ingrese a la rotonda y tome la salida hacia $stName"
                                                    "continue" -> "Continúe por $stName"
                                                    else -> if (stName.isNotEmpty()) "Continúe por $stName" else "Siga la ruta"
                                                }

                                                parsedSteps.add(
                                                    TacticalManeuverStep(
                                                        instruction = instruction,
                                                        streetName = stName,
                                                        distanceMeters = stDist,
                                                        modifier = manMod,
                                                        maneuverType = manType,
                                                        location = stepGeo
                                                    )
                                                )
                                            }
                                        }
                                    }

                                    // Guardar en caché persistente offline
                                    repository.saveCachedRoute(
                                        context = context,
                                        routeKey = routeCacheKey,
                                        points = pts,
                                        maneuvers = parsedSteps,
                                        duration = routeDur,
                                        distance = routeDist
                                    )

                                    withContext(Dispatchers.Main) {
                                        routePoints = pts
                                        maneuverSteps = parsedSteps
                                        onEtaCalculated(routeDur, routeDist)
                                    }
                                    success = true
                                    break
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    if (!success) {
                        val fallbackCached = repository.loadCachedRoute(context, routeCacheKey)
                        withContext(Dispatchers.Main) {
                            if (fallbackCached != null && fallbackCached.points.isNotEmpty()) {
                                routePoints = fallbackCached.points
                                maneuverSteps = fallbackCached.maneuvers
                                onEtaCalculated(fallbackCached.duration, fallbackCached.distance)
                            } else {
                                val directDist = curVPoint.distanceToAsDouble(curDestPoint)
                                val estTimeS = (directDist / 13.0)
                                if (routePoints.isEmpty()) {
                                    routePoints = listOf(curVPoint, curDestPoint)
                                }
                                onEtaCalculated(estTimeS, directDist)
                            }
                        }
                    }
                }
            }
        } else {
            routePoints = emptyList()
            maneuverSteps = emptyList()
            lastRoutedOrigin = null
            lastRoutedDest = null
            onEtaCalculated(0.0, 0.0)
        }
    }

    // ====================================================================
    // CONFIGURACIÓN DE CÁMARA Y MOTOR GOOGLE MAPS COMPOSABLE (GPU 60 FPS)
    // ====================================================================
    val initialCameraPosition = remember {
        CameraPosition.fromLatLngZoom(LatLng(vLat, vLng), 17.5f)
    }
    val cameraPositionState = rememberCameraPositionState {
        position = initialCameraPosition
    }


    // Control dinámico de cámara en tiempo real con perspectiva 3D inclinada (Tilt 45°)
    LaunchedEffect(smoothLat, smoothLng, smoothHeading, isCameraFollowMode) {
        if (isCameraFollowMode && hasVehicle && smoothLat != 0.0) {
            val camPos = CameraPosition.builder()
                .target(LatLng(smoothLat, smoothLng))
                .zoom(17.5f)
                .tilt(45f) // Perspectiva 3D táctica tipo cockpit
                .bearing(smoothHeading) // Rotación suave continua a 60 FPS
                .build()
            try {
                cameraPositionState.animate(CameraUpdateFactory.newCameraPosition(camPos), 250)
            } catch (_: Exception) {}
        }
    }

    // Propiedades del mapa Google Maps (Tráfico en vivo, Modo Satélite y Estilos Tácticos)
    val mapProperties = remember(isSatelliteMode, isDarkMode) {
        MapProperties(
            mapType = if (isSatelliteMode) MapType.HYBRID else MapType.NORMAL,
            isTrafficEnabled = true, // Tráfico vehicular en vivo oficial de Google Maps
            mapStyleOptions = if (!isSatelliteMode && isDarkMode) MapStyleOptions(TacticalNightMapStyleJson) else null
        )
    }

    // Configuración UI del mapa (ocultar controles nativos estorbosos para mantener interfaz limpia de cabina)
    val mapUiSettings = remember {
        MapUiSettings(
            compassEnabled = false,
            indoorLevelPickerEnabled = false,
            mapToolbarEnabled = false,
            myLocationButtonEnabled = false,
            rotationGesturesEnabled = true,
            scrollGesturesEnabled = true,
            scrollGesturesEnabledDuringRotateOrZoom = true,
            tiltGesturesEnabled = true,
            zoomControlsEnabled = false,
            zoomGesturesEnabled = true
        )
    }

    // Descriptores de Marcadores Bitmap HD
    val vehicleDescriptor = remember {
        createVehicleBitmapDescriptor()
    }
    val emergencyDescriptor = remember(pinColorInt, activeNavTarget, is615Traslado) {
        createEmergencyBitmapDescriptor(pinColorInt, activeNavTarget == NavTargetType.INCIDENT)
    }
    val alertanteDescriptor = remember(activeNavTarget) {
        createAlertanteBitmapDescriptor(activeNavTarget == NavTargetType.ALERTANTE)
    }
    val centralDescriptor = remember(activeNavTarget) {
        createCentralBitmapDescriptor(activeNavTarget == NavTargetType.CENTRAL)
    }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF0F172A))) {
        // ==========================================
        // 1. MAPA NATIVO GOOGLE MAPS VECTORIAL 3D (GPU ACCELERATED)
        // ==========================================
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = mapProperties,
            uiSettings = mapUiSettings,
            contentPadding = PaddingValues(
                start = 104.dp, // Ubica el logo de Google al lado del Dock lateral
                bottom = 16.dp, // Alinea el logo y los créditos a la misma altura de la base de los botones
                end = 16.dp,
                top = 0.dp
            )
        ) {
            // -------------------------------------------------------------
            // A. Marcador de Incidente (Marcado por la Central CAD o 6-15)
            // -------------------------------------------------------------
            if (hasIncident && !isIncidentDeleted && emLat != 0.0 && emLng != 0.0) {
                Marker(
                    state = MarkerState(position = LatLng(emLat, emLng)),
                    icon = emergencyDescriptor,
                    anchor = Offset(0.5f, 0.5f),
                    title = if (is615Traslado) "CENTRO ASISTENCIAL (6-15)" else "INCIDENTE (CENTRAL)",
                    zIndex = 500f,
                    onClick = {
                        val now = System.currentTimeMillis()
                        if (now - lastClickIncidentTime < 450L) {
                            isIncidentDeleted = true
                            if (activeNavTarget == NavTargetType.INCIDENT) {
                                activeNavTarget = if (hasAlertante && !isAlertanteDeleted) NavTargetType.ALERTANTE else if (!isCentralDeleted) NavTargetType.CENTRAL else NavTargetType.INCIDENT
                            }
                            android.widget.Toast.makeText(context, "Punto de Incidente eliminado", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            lastClickIncidentTime = now
                            activeNavTarget = if (activeNavTarget == NavTargetType.INCIDENT) NavTargetType.NONE else NavTargetType.INCIDENT
                        }
                        true
                    }
                )
            }

            // -------------------------------------------------------------
            // B. Marcador de Alertante (Rastreo GPS en Vivo)
            // -------------------------------------------------------------
            if (hasAlertante && !isAlertanteDeleted && alLat != 0.0 && alLng != 0.0) {
                Marker(
                    state = MarkerState(position = LatLng(alLat, alLng)),
                    icon = alertanteDescriptor,
                    anchor = Offset(0.5f, 0.5f),
                    title = "ALERTANTE: ${alertantePhone.ifEmpty { "Reporte Móvil" }}",
                    zIndex = 400f,
                    onClick = {
                        val now = System.currentTimeMillis()
                        if (now - lastClickAlertanteTime < 450L) {
                            isAlertanteDeleted = true
                            if (activeNavTarget == NavTargetType.ALERTANTE) {
                                activeNavTarget = if (hasIncident && !isIncidentDeleted) NavTargetType.INCIDENT else if (!isCentralDeleted) NavTargetType.CENTRAL else NavTargetType.ALERTANTE
                            }
                            android.widget.Toast.makeText(context, "Punto de Alertante eliminado", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            lastClickAlertanteTime = now
                            activeNavTarget = if (activeNavTarget == NavTargetType.ALERTANTE) NavTargetType.NONE else NavTargetType.ALERTANTE
                        }
                        true
                    }
                )
            }

            // -------------------------------------------------------------
            // C. Marcador de Cuartel / Central General
            // -------------------------------------------------------------
            if (!isCentralDeleted && cenLat != 0.0 && cenLng != 0.0) {
                Marker(
                    state = MarkerState(position = LatLng(cenLat, cenLng)),
                    icon = centralDescriptor,
                    anchor = Offset(0.5f, 0.5f),
                    title = "CENTRAL / CUARTEL GENERAL",
                    zIndex = 300f,
                    onClick = {
                        val now = System.currentTimeMillis()
                        if (now - lastClickCentralTime < 450L) {
                            isCentralDeleted = true
                            if (activeNavTarget == NavTargetType.CENTRAL) {
                                activeNavTarget = if (hasIncident && !isIncidentDeleted) NavTargetType.INCIDENT else if (hasAlertante && !isAlertanteDeleted) NavTargetType.ALERTANTE else NavTargetType.CENTRAL
                            }
                            android.widget.Toast.makeText(context, "Punto de Central / Cuartel eliminado", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            lastClickCentralTime = now
                            activeNavTarget = if (activeNavTarget == NavTargetType.CENTRAL) NavTargetType.NONE else NavTargetType.CENTRAL
                        }
                        true
                    }
                )
            }

            // -------------------------------------------------------------
            // D. Ruta Vectorial Táctica hacia el Objetivo Activo
            // -------------------------------------------------------------
            if (hasVehicle && activeNavTarget != NavTargetType.NONE && currentDestLat != 0.0) {
                val vehLatLng = LatLng(smoothLat, smoothLng)
                val targetLatLng = LatLng(currentDestLat, currentDestLng)
                val liveRouteLatLngs = if (routePoints.size >= 2) {
                    var closestIdx = 0
                    var minDist = Double.MAX_VALUE
                    val curGeo = GeoPoint(smoothLat, smoothLng)
                    for (idx in routePoints.indices) {
                        val dist = routePoints[idx].distanceToAsDouble(curGeo)
                        if (dist < minDist) {
                            minDist = dist
                            closestIdx = idx
                        }
                    }
                    val subList = if (minDist < 250.0 && closestIdx < routePoints.size) {
                        routePoints.subList(closestIdx, routePoints.size)
                    } else routePoints

                    listOf(vehLatLng) + subList.map { LatLng(it.latitude, it.longitude) }
                } else {
                    listOf(vehLatLng, targetLatLng)
                }

                // Línea de navegación táctica: SIEMPRE Azul táctico semi-translúcido (#2563EB con alpha 0.65f)
                // 1. Evita cualquier confusión con las alertas de tráfico de Google Maps (Verde=despejado, Naranja=moderado, Rojo=tráfico pesado).
                // 2. Permite que el color del flujo de tráfico vehicular en tiempo real sea 100% visible a través y a los costados de la ruta.
                val routeColor = if (activeNavTarget != NavTargetType.NONE) {
                    Color(0xFF2563EB).copy(alpha = 0.65f)
                } else {
                    Color.Transparent
                }

                Polyline(
                    points = liveRouteLatLngs,
                    color = routeColor,
                    width = 11f,
                    jointType = JointType.ROUND,
                    startCap = RoundCap(),
                    endCap = RoundCap(),
                    zIndex = 20f
                )
            }

            // -------------------------------------------------------------
            // E. Marcadores de Bomberos en Asistencia
            // -------------------------------------------------------------
            val activeFighters = if (dispatchId != null && assignedPersonnelIds.isNotEmpty()) {
                personnelList.filter { it.idRegistro in assignedPersonnelIds && it.lat != 0.0 && it.lng != 0.0 }
            } else emptyList()

            activeFighters.forEach { fighter ->
                Marker(
                    state = MarkerState(position = LatLng(fighter.lat, fighter.lng)),
                    icon = remember(fighter.idRadial) { createFirefighterBitmapDescriptor(fighter.idRadial.ifEmpty { "👨‍🚒" }) },
                    anchor = Offset(0.5f, 0.5f),
                    title = "${fighter.nombreBombero} (${fighter.idRadial})",
                    zIndex = 200f
                )
            }

            // -------------------------------------------------------------
            // F. Marcador de Carro Bomba (Flecha Táctica 3D GPS con Rotación GPU 60 FPS)
            // -------------------------------------------------------------
            if (hasVehicle && smoothLat != 0.0 && smoothLng != 0.0) {
                Marker(
                    state = MarkerState(position = LatLng(smoothLat, smoothLng)),
                    icon = vehicleDescriptor,
                    anchor = Offset(0.5f, 0.5f),
                    flat = true,
                    rotation = smoothHeading,
                    zIndex = 999f,
                    onClick = { true }
                )
            }
        }

        // ==========================================
        // 1.5. TARJETA FLOTANTE TÁCTICA TURN-BY-TURN (PRÓXIMA MANIOBRA)
        // ==========================================
        val activeManeuver = remember(smoothLat, smoothLng, maneuverSteps) {
            if (maneuverSteps.isEmpty()) null
            else {
                val curPt = GeoPoint(smoothLat, smoothLng)
                maneuverSteps.firstOrNull { step ->
                    curPt.distanceToAsDouble(step.location) >= 20.0
                } ?: maneuverSteps.firstOrNull()
            }
        }

        if (activeManeuver != null && activeNavTarget != NavTargetType.NONE && currentDestLat != 0.0) {
            val distToMan = GeoPoint(smoothLat, smoothLng).distanceToAsDouble(activeManeuver.location)
            val distText = if (distToMan < 1000) "${distToMan.toInt().coerceAtLeast(10)} m" else String.format(java.util.Locale.US, "%.1f km", distToMan / 1000.0)
            val iconSymbol = when {
                activeManeuver.modifier.contains("uturn") -> "🔄"
                activeManeuver.modifier.contains("sharp right") -> "↱"
                activeManeuver.modifier.contains("slight right") -> "↗️"
                activeManeuver.modifier.contains("right") -> "➡️"
                activeManeuver.modifier.contains("sharp left") -> "↰"
                activeManeuver.modifier.contains("slight left") -> "↖️"
                activeManeuver.modifier.contains("left") -> "⬅️"
                activeManeuver.maneuverType.contains("roundabout") || activeManeuver.maneuverType.contains("rotary") -> "🔄"
                activeManeuver.maneuverType.contains("arrive") -> "🏁"
                else -> "⬆️"
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 84.dp)
                    .height(58.dp)
                    .widthIn(max = 320.dp)
                    .carPlayGlassCard(
                        cornerRadius = 16.dp,
                        tintColor = Color(0xFF0F172A),
                        tintAlpha = 0.90f,
                        borderColor = Color(0xFF06B6D4).copy(alpha = 0.60f)
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFF06B6D4).copy(alpha = 0.35f),
                                        Color(0xFF0891B2).copy(alpha = 0.15f)
                                    )
                                )
                            )
                            .border(
                                1.dp,
                                Brush.verticalGradient(
                                    listOf(Color.White.copy(alpha = 0.50f), Color(0xFF06B6D4), Color.Transparent)
                                ),
                                RoundedCornerShape(10.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = iconSymbol,
                            fontSize = 18.sp
                        )
                    }
                    Column(verticalArrangement = Arrangement.Center) {
                        Text(
                            text = "En $distText",
                            color = Color(0xFF38BDF8),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = activeManeuver.instruction.ifEmpty { activeManeuver.streetName },
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // ==========================================
        // 2. BOTONES TÁCTICOS DE RUTA EN COLUMNA AL COSTADO DERECHO (ANIMADOS AL TOP SI NO HAY 6-X)
        // ==========================================
        val targetTopPadding = if (hasActive6XButton) 86.dp else 16.dp
        val columnTopPadding by animateDpAsState(
            targetValue = targetTopPadding,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "NavButtonsTopPaddingAnim"
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 16.dp, top = columnTopPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End
        ) {
            // Tarjeta 1: EMERGENCIA
            if (hasIncident && !isIncidentDeleted) {
                val isSelIncident = (activeNavTarget == NavTargetType.INCIDENT)
                val bgBrush = if (isSelIncident) {
                    Brush.verticalGradient(
                        listOf(Color(0xFFEF4444), Color(0xFFDC2626), Color(0xFF991B1B))
                    )
                } else {
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF1E293B).copy(alpha = 0.85f),
                            Color(0xFF0F172A).copy(alpha = 0.92f),
                            Color(0xFF030712).copy(alpha = 0.96f)
                        )
                    )
                }
                val borderBrush = if (isSelIncident) {
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.75f), Color(0xFFFCA5A5), Color.Transparent)
                    )
                } else {
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.50f),
                            Color(0xFFEF4444).copy(alpha = 0.80f),
                            Color(0xFFEF4444).copy(alpha = 0.20f),
                            Color.Transparent
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .height(58.dp)
                        .width(185.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(bgBrush)
                        .border(if (isSelIncident) 1.5.dp else 1.2.dp, borderBrush, RoundedCornerShape(16.dp))
                        .clickable {
                            isIncidentDeleted = false
                            activeNavTarget = if (activeNavTarget == NavTargetType.INCIDENT) NavTargetType.NONE else NavTargetType.INCIDENT
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text("🚨", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "EMERGENCIA",
                            color = if (isSelIncident) Color.White else Color(0xFFF87171),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            // Tarjeta 2: ALERTANTE
            if (hasAlertante && !isAlertanteDeleted && alLat != 0.0) {
                val isSelAlertante = (activeNavTarget == NavTargetType.ALERTANTE)
                val bgBrush = if (isSelAlertante) {
                    Brush.verticalGradient(
                        listOf(Color(0xFF0891B2), Color(0xFF0E7490), Color(0xFF155E75))
                    )
                } else {
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF1E293B).copy(alpha = 0.85f),
                            Color(0xFF0F172A).copy(alpha = 0.92f),
                            Color(0xFF030712).copy(alpha = 0.96f)
                        )
                    )
                }
                val borderBrush = if (isSelAlertante) {
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.75f), Color(0xFFA5F3FC), Color.Transparent)
                    )
                } else {
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.50f),
                            Color(0xFF06B6D4).copy(alpha = 0.80f),
                            Color(0xFF06B6D4).copy(alpha = 0.20f),
                            Color.Transparent
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .height(58.dp)
                        .width(185.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(bgBrush)
                        .border(if (isSelAlertante) 1.5.dp else 1.2.dp, borderBrush, RoundedCornerShape(16.dp))
                        .clickable {
                            isAlertanteDeleted = false
                            activeNavTarget = if (activeNavTarget == NavTargetType.ALERTANTE) NavTargetType.NONE else NavTargetType.ALERTANTE
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text("📱", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ALERTANTE",
                            color = if (isSelAlertante) Color.White else Color(0xFF38BDF8),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            // Tarjeta 3: CENTRAL (Cuartel General)
            val isSelCentral = (activeNavTarget == NavTargetType.CENTRAL)
            val bgCentral = if (isSelCentral) {
                Brush.verticalGradient(
                    listOf(Color(0xFF059669), Color(0xFF047857), Color(0xFF065F46))
                )
            } else {
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF1E293B).copy(alpha = 0.85f),
                        Color(0xFF0F172A).copy(alpha = 0.92f),
                        Color(0xFF030712).copy(alpha = 0.96f)
                    )
                )
            }
            val borderCentral = if (isSelCentral) {
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.75f), Color(0xFF6EE7B7), Color.Transparent)
                )
            } else {
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.50f),
                        Color(0xFF10B981).copy(alpha = 0.80f),
                        Color(0xFF10B981).copy(alpha = 0.20f),
                        Color.Transparent
                    )
                )
            }

            Box(
                modifier = Modifier
                    .height(58.dp)
                    .width(185.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(bgCentral)
                    .border(if (isSelCentral) 1.5.dp else 1.2.dp, borderCentral, RoundedCornerShape(16.dp))
                .clickable {
                    isCentralDeleted = false
                    activeNavTarget = if (activeNavTarget == NavTargetType.CENTRAL) NavTargetType.NONE else NavTargetType.CENTRAL
                }
                .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("🏛️", fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CENTRAL",
                        color = if (isSelCentral) Color.White else Color(0xFF34D399),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }

        // ==========================================
        // 3. BOTONES FLOTANTES DE CAPA Y CENTRAR (ABAJO A LA DERECHA, UNO ENCIMA DEL OTRO)
        // ==========================================
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 82.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Alternar Satélite / Calles con Glass (Botón Cuadrado)
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF1E293B).copy(alpha = 0.85f),
                                Color(0xFF0F172A).copy(alpha = 0.92f),
                                Color(0xFF030712).copy(alpha = 0.96f)
                            )
                        )
                    )
                    .border(
                        width = 1.3.dp,
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.50f),
                                if (isSatelliteMode) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.15f),
                                Color.Transparent
                            )
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable {
                        isSatelliteMode = !isSatelliteMode
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Layers,
                    contentDescription = "Capa",
                    tint = if (isSatelliteMode) Color(0xFF38BDF8) else Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            // Centrar a la Unidad / Alternar Vista de Ruta Completa con Glass (Botón Cuadrado)
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF1E293B).copy(alpha = 0.85f),
                                Color(0xFF0F172A).copy(alpha = 0.92f),
                                Color(0xFF030712).copy(alpha = 0.96f)
                            )
                        )
                    )
                    .border(
                        width = 1.3.dp,
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.55f),
                                if (isCameraFollowMode) Color(0xFF06B6D4) else Color.White.copy(alpha = 0.20f),
                                if (isCameraFollowMode) Color(0xFF06B6D4).copy(alpha = 0.25f) else Color.Transparent,
                                Color.Transparent
                            )
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable {
                        coroutineScope.launch {
                            if (!isCameraFollowMode) {
                                // Modo 1: Seguimiento centrado en la unidad con inclinación 3D (Tilt 45°)
                                isCameraFollowMode = true
                                val camPos = CameraPosition.builder()
                                    .target(LatLng(smoothLat, smoothLng))
                                    .zoom(17.5f)
                                    .tilt(45f)
                                    .bearing(smoothHeading)
                                    .build()
                                cameraPositionState.animate(CameraUpdateFactory.newCameraPosition(camPos), 500)
                            } else {
                                // Modo 2: Si ya está en seguimiento y hay ruta activa, alejar y mostrar ruta completa (Vista General)
                                if (activeNavTarget != NavTargetType.NONE && currentDestLat != 0.0) {
                                    isCameraFollowMode = false
                                    val builder = LatLngBounds.builder()
                                    builder.include(LatLng(currentDestLat, currentDestLng))
                                    if (hasVehicle && smoothLat != 0.0) {
                                        builder.include(LatLng(smoothLat, smoothLng))
                                    }
                                    if (routePoints.isNotEmpty()) {
                                        routePoints.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
                                    }
                                    try {
                                        val bounds = builder.build()
                                        // Margen amplio (240px) para que destino y unidad queden completamente despejados de la barra lateral y botones
                                        cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 240), 600)
                                    } catch (_: Exception) {}
                                } else {
                                    val camPos = CameraPosition.builder()
                                        .target(LatLng(smoothLat, smoothLng))
                                        .zoom(17.5f)
                                        .tilt(45f)
                                        .bearing(smoothHeading)
                                        .build()
                                    cameraPositionState.animate(CameraUpdateFactory.newCameraPosition(camPos), 500)
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.MyLocation,
                    contentDescription = if (isCameraFollowMode) "Seguimiento" else "Centrar",
                    tint = if (isCameraFollowMode) Color(0xFF06B6D4) else Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

// -------------------------------------------------------------
// Generadores de Iconos BitmapDescriptor de Alta Definición
// -------------------------------------------------------------

private fun createEmergencyBitmapDescriptor(color: Int, isSelected: Boolean): BitmapDescriptor {
    return try {
        val size = if (isSelected) 104 else 90
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size / 2f
        val cy = size / 2f

        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            alpha = if (isSelected) 95 else 65
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, if (isSelected) 46f else 38f, haloPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = if (isSelected) 6f else 4.5f
        }
        canvas.drawCircle(cx, cy, if (isSelected) 26f else 22f, borderPaint)

        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, if (isSelected) 24f else 20f, corePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            textSize = if (isSelected) 18f else 15f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2)
        canvas.drawText("🚨", cx, textY, textPaint)

        BitmapDescriptorFactory.fromBitmap(bitmap)
    } catch (_: Exception) {
        BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
    }
}

private fun createAlertanteBitmapDescriptor(isSelected: Boolean): BitmapDescriptor {
    return try {
        val size = if (isSelected) 104 else 90
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size / 2f
        val cy = size / 2f

        val colorCyan = android.graphics.Color.parseColor("#06B6D4")

        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorCyan
            alpha = if (isSelected) 90 else 60
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, if (isSelected) 46f else 38f, haloPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = if (isSelected) 6f else 4.5f
        }
        canvas.drawCircle(cx, cy, if (isSelected) 26f else 22f, borderPaint)

        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorCyan
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, if (isSelected) 24f else 20f, corePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = if (isSelected) 18f else 15f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2)
        canvas.drawText("📱", cx, textY, textPaint)

        BitmapDescriptorFactory.fromBitmap(bitmap)
    } catch (_: Exception) {
        BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_CYAN)
    }
}

private fun createCentralBitmapDescriptor(isSelected: Boolean): BitmapDescriptor {
    return try {
        val size = if (isSelected) 100 else 86
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size / 2f
        val cy = size / 2f

        val colorEmerald = android.graphics.Color.parseColor("#10B981")

        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorEmerald
            alpha = if (isSelected) 85 else 55
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, if (isSelected) 44f else 36f, haloPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = if (isSelected) 5.5f else 4f
        }
        canvas.drawCircle(cx, cy, if (isSelected) 24f else 20f, borderPaint)

        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorEmerald
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, if (isSelected) 22f else 18f, corePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = if (isSelected) 17f else 14f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2)
        canvas.drawText("🏛️", cx, textY, textPaint)

        BitmapDescriptorFactory.fromBitmap(bitmap)
    } catch (_: Exception) {
        BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
    }
}

private fun createVehicleBitmapDescriptor(): BitmapDescriptor {
    return try {
        val width = 110
        val height = 110
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = width / 2f
        val cy = height / 2f

        // Sombra suave proyectada para efecto 3D sobre cualquier fondo de mapa
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(120, 0, 0, 0)
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
        }
        val shadowPath = Path().apply {
            moveTo(cx, cy - 38f + 4f)
            lineTo(cx + 30f, cy + 34f + 4f)
            lineTo(cx, cy + 20f + 4f)
            lineTo(cx - 30f, cy + 34f + 4f)
            close()
        }
        canvas.drawPath(shadowPath, shadowPaint)

        // Trazo exterior oscuro para máximo contraste en mapa diurno y nocturno
        val outerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#0F172A")
            style = Paint.Style.STROKE
            strokeWidth = 10f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        val arrowPath = Path().apply {
            moveTo(cx, cy - 38f) // Punta superior
            lineTo(cx + 30f, cy + 34f) // Ala derecha
            lineTo(cx, cy + 20f) // Muesca central trasera
            lineTo(cx - 30f, cy + 34f) // Ala izquierda
            close()
        }
        canvas.drawPath(arrowPath, outerStrokePaint)

        // Borde blanco nítido
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 5.5f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawPath(arrowPath, borderPaint)

        // Cuerpo de la flecha con efecto 3D bi-tono (Mitad izquierda Cian brillante #38BDF8, mitad derecha Azul Táctico #0284C7)
        val leftWingPath = Path().apply {
            moveTo(cx, cy - 38f)
            lineTo(cx - 30f, cy + 34f)
            lineTo(cx, cy + 20f)
            close()
        }
        val leftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#38BDF8") // Cian brillante
            style = Paint.Style.FILL
        }
        canvas.drawPath(leftWingPath, leftPaint)

        val rightWingPath = Path().apply {
            moveTo(cx, cy - 38f)
            lineTo(cx + 30f, cy + 34f)
            lineTo(cx, cy + 20f)
            close()
        }
        val rightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#0284C7") // Azul cobalto táctico
            style = Paint.Style.FILL
        }
        canvas.drawPath(rightWingPath, rightPaint)

        BitmapDescriptorFactory.fromBitmap(bitmap)
    } catch (_: Exception) {
        BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
    }
}

private fun createFirefighterBitmapDescriptor(label: String): BitmapDescriptor {
    return try {
        val size = 70
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size / 2f
        val cy = size / 2f

        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#0284C7")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, 22f, basePaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(cx, cy, 22f, borderPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val safeLabel = if (label.isBlank() || label.any { Character.isSurrogate(it) }) "B" else label.take(4)
        val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2)
        canvas.drawText(safeLabel, cx, textY, textPaint)

        BitmapDescriptorFactory.fromBitmap(bitmap)
    } catch (_: Exception) {
        BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)
    }
}
