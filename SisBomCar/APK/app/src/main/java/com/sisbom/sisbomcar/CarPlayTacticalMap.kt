package com.sisbom.sisbomcar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

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

// Capa Satelital Híbrida HD (Satelite + Calles y Nombres)
val GoogleHybridTileSource = object : XYTileSource(
    "GoogleHybridHD",
    1, 20, 256, ".jpg",
    arrayOf(
        "https://mt0.google.com/vt/lyrs=y&hl=es&",
        "https://mt1.google.com/vt/lyrs=y&hl=es&",
        "https://mt2.google.com/vt/lyrs=y&hl=es&",
        "https://mt3.google.com/vt/lyrs=y&hl=es&"
    ),
    "Google"
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
        val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
        val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
        return "${baseUrl}x=$x&y=$y&z=$zoom"
    }
}

@Composable
fun CarPlayTacticalMap(
    dispatchId: String? = null,
    emergencyLat: Double?,
    emergencyLng: Double?,
    emergencyClave: String,
    alertanteLat: Double? = null,
    alertanteLng: Double? = null,
    alertantePhone: String = "",
    centralLat: Double = -34.636743,
    centralLng: Double = -71.119915,
    vehicleLat: Double?,
    vehicleLng: Double?,
    vehicleHeading: Float = 0f,
    vehicleSpeedKmH: Float = 0f,
    unitStatusInDispatch: String = "",
    personnelList: List<PersonItem> = emptyList(),
    assignedPersonnelIds: Set<String> = emptySet(),
    onEtaCalculated: (durationSeconds: Double, distanceMeters: Double) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isSatelliteMode by remember { mutableStateOf(false) }

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

    // Selección de objetivo de navegación interactivo
    var activeNavTarget by remember(dispatchId, isRetornoToCuartel) {
        mutableStateOf(
            if (isRetornoToCuartel) NavTargetType.CENTRAL
            else if (hasIncident && !isIncidentDeleted) NavTargetType.INCIDENT
            else if (hasAlertante && !isAlertanteDeleted) NavTargetType.ALERTANTE
            else NavTargetType.CENTRAL
        )
    }

    val pinColorHex = when {
        isRetornoToCuartel -> "#10B981" // Verde Esmeralda / Cuartel Base
        cleanClave.contains("10-0") || cleanClave.contains("10-4") -> "#EF4444"
        cleanClave.contains("10-2") || cleanClave.contains("10-3") -> "#F59E0B"
        else -> "#0284C7"
    }
    val pinColorInt = try {
        android.graphics.Color.parseColor(pinColorHex)
    } catch (_: Exception) {
        android.graphics.Color.RED
    }

    val emLat = emergencyLat ?: -34.637373
    val emLng = emergencyLng ?: -71.125741
    val alLat = alertanteLat ?: emLat
    val alLng = alertanteLng ?: emLng
    val cenLat = centralLat
    val cenLng = centralLng

    val vLat = vehicleLat ?: emLat
    val vLng = vehicleLng ?: emLng

    // Coordenadas activas del destino actual
    val currentDestLat = when (activeNavTarget) {
        NavTargetType.INCIDENT -> if (hasIncident && !isIncidentDeleted) emLat else if (hasAlertante && !isAlertanteDeleted) alLat else cenLat
        NavTargetType.ALERTANTE -> if (hasAlertante && !isAlertanteDeleted) alLat else if (hasIncident && !isIncidentDeleted) emLat else cenLat
        NavTargetType.CENTRAL -> cenLat
        NavTargetType.NONE -> 0.0
    }
    val currentDestLng = when (activeNavTarget) {
        NavTargetType.INCIDENT -> if (hasIncident && !isIncidentDeleted) emLng else if (hasAlertante && !isAlertanteDeleted) alLng else cenLng
        NavTargetType.ALERTANTE -> if (hasAlertante && !isAlertanteDeleted) alLng else if (hasIncident && !isIncidentDeleted) emLng else cenLng
        NavTargetType.CENTRAL -> cenLng
        NavTargetType.NONE -> 0.0
    }

    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    val incidentMarkerRef = remember { mutableStateOf<Marker?>(null) }
    val alertanteMarkerRef = remember { mutableStateOf<Marker?>(null) }
    val centralMarkerRef = remember { mutableStateOf<Marker?>(null) }
    val vehicleMarkerRef = remember { mutableStateOf<Marker?>(null) }
    val routePolylineRef = remember { mutableStateOf<Polyline?>(null) }
    val firefighterMarkersRef = remember { mutableStateMapOf<String, Marker>() }
    var routePoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var maneuverSteps by remember { mutableStateOf<List<TacticalManeuverStep>>(emptyList()) }
    var isCameraFollowMode by remember { mutableStateOf(true) }

    var lastRoutedOrigin by remember { mutableStateOf<GeoPoint?>(null) }
    var lastRoutedDest by remember { mutableStateOf<GeoPoint?>(null) }
    var lastRouteFetchTime by remember { mutableStateOf(0L) }

    var lastFittedDispatchId by remember { mutableStateOf<String?>(null) }
    var hasZoomedInToUnit by remember { mutableStateOf(false) }

    val isDeparted = remember(unitStatusInDispatch) {
        val st = unitStatusInDispatch.trim().lowercase()
        st.contains("trayecto") || st.contains("6-0") || st.contains("lugar") || st.contains("6-3")
    }

    // Al recibir un nuevo despacho, reajustar estado para mostrar ruta completa de inicio a fin
    LaunchedEffect(dispatchId) {
        if (dispatchId != null && dispatchId != lastFittedDispatchId) {
            lastFittedDispatchId = dispatchId
            hasZoomedInToUnit = false
            lastRoutedOrigin = null
            lastRoutedDest = null
            activeNavTarget = if (isRetornoToCuartel) NavTargetType.CENTRAL else NavTargetType.INCIDENT
        }
    }

    // Cálculo de ruta OSRM hacia el destino activo con control de intervalo y respaldo multi-servidor
    LaunchedEffect(hasVehicle, vLat, vLng, currentDestLat, currentDestLng, activeNavTarget) {
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

            val distFromLastOrigin = lastRoutedOrigin?.distanceToAsDouble(curVPoint) ?: 9999.0
            val distFromLastDest = lastRoutedDest?.distanceToAsDouble(curDestPoint) ?: 9999.0

            val shouldFetch = (lastRoutedOrigin == null) ||
                    (distFromLastDest > 15.0) ||
                    (distFromLastOrigin >= 35.0 && now - lastRouteFetchTime >= 4000L)

            if (shouldFetch) {
                lastRoutedOrigin = curVPoint
                lastRoutedDest = curDestPoint
                lastRouteFetchTime = now

                withContext(Dispatchers.IO) {
                    val endpoints = listOf(
                        "https://router.project-osrm.org/route/v1/driving/$vLng,$vLat;$currentDestLng,$currentDestLat?steps=true&overview=full&geometries=geojson",
                        "https://routing.openstreetmap.de/routed-car/route/v1/driving/$vLng,$vLat;$currentDestLng,$currentDestLat?steps=true&overview=full&geometries=geojson"
                    )

                    var success = false
                    for (urlStr in endpoints) {
                        try {
                            val url = URL(urlStr)
                            val conn = (url.openConnection() as HttpURLConnection).apply {
                                requestMethod = "GET"
                                setRequestProperty("User-Agent", "SENTINEL-NAV/1.0 (Android; FireTruck Chile)")
                                connectTimeout = 4000
                                readTimeout = 4000
                            }
                            if (conn.responseCode == 200) {
                                val response = conn.inputStream.bufferedReader().readText()
                                val json = JSONObject(response)
                                val routes = json.optJSONArray("routes")
                                if (routes != null && routes.length() > 0) {
                                    val routeObj = routes.getJSONObject(0)
                                    val routeDist = routeObj.optDouble("distance", 0.0)
                                    val routeDur = routeObj.optDouble("duration", 0.0)
                                    val geometry = routeObj.getJSONObject("geometry")
                                    val coords = geometry.getJSONArray("coordinates")
                                    val pts = mutableListOf<GeoPoint>()
                                    for (i in 0 until coords.length()) {
                                        val coord = coords.getJSONArray(i)
                                        pts.add(GeoPoint(coord.getDouble(1), coord.getDouble(0)))
                                    }

                                    // Parsear pasos de maniobras tácticas Turn-by-Turn
                                    val parsedSteps = mutableListOf<TacticalManeuverStep>()
                                    val legs = routeObj.optJSONArray("legs")
                                    if (legs != null && legs.length() > 0) {
                                        val leg = legs.getJSONObject(0)
                                        val steps = leg.optJSONArray("steps")
                                        if (steps != null) {
                                            for (j in 0 until steps.length()) {
                                                val stepObj = steps.getJSONObject(j)
                                                val stName = stepObj.optString("name", "")
                                                val stDist = stepObj.optDouble("distance", 0.0)
                                                val manObj = stepObj.optJSONObject("maneuver")
                                                val manType = manObj?.optString("type", "") ?: ""
                                                val manMod = manObj?.optString("modifier", "") ?: ""
                                                val locArr = manObj?.optJSONArray("location")
                                                val stepGeo = if (locArr != null && locArr.length() >= 2) {
                                                    GeoPoint(locArr.getDouble(1), locArr.getDouble(0))
                                                } else {
                                                    GeoPoint(0.0, 0.0)
                                                }

                                                val instrText = when {
                                                    manMod.contains("sharp right") -> "Giro cerrado a la derecha"
                                                    manMod.contains("slight right") -> "Curva suave a la derecha"
                                                    manMod.contains("right") -> "Gira a la derecha"
                                                    manMod.contains("sharp left") -> "Giro cerrado a la izquierda"
                                                    manMod.contains("slight left") -> "Curva suave a la izquierda"
                                                    manMod.contains("left") -> "Gira a la izquierda"
                                                    manMod.contains("uturn") -> "Vuelta en U"
                                                    manType.contains("roundabout") || manType.contains("rotary") -> "Ingresa a rotonda"
                                                    manType.contains("arrive") -> "Llegando a destino"
                                                    manType.contains("depart") -> "Inicia marcha"
                                                    else -> if (stName.isNotEmpty()) "Continúa por $stName" else "Continúa recto"
                                                }

                                                parsedSteps.add(
                                                    TacticalManeuverStep(
                                                        instruction = instrText,
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
                        withContext(Dispatchers.Main) {
                            val directDist = curVPoint.distanceToAsDouble(curDestPoint)
                            val estTimeS = (directDist / 13.0) // ~47 km/h promedio
                            if (routePoints.isEmpty()) {
                                routePoints = listOf(curVPoint, curDestPoint)
                            }
                            onEtaCalculated(estTimeS, directDist)
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

    // Control dinámico de cámara y rotación según rumbo (bearing)
    LaunchedEffect(hasVehicle, isDeparted, vLat, vLng, vehicleHeading, isCameraFollowMode) {
        val map = mapViewRef.value ?: return@LaunchedEffect
        if (!hasVehicle || vLat == 0.0) return@LaunchedEffect

        if (isCameraFollowMode) {
            map.post {
                try {
                    if (vehicleHeading > 0f) {
                        map.mapOrientation = -vehicleHeading
                    }
                    map.controller.setCenter(GeoPoint(vLat, vLng))
                } catch (_: Exception) {}
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            try {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> mapViewRef.value?.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapViewRef.value?.onPause()
                    Lifecycle.Event.ON_DESTROY -> {
                        mapViewRef.value?.onDetach()
                        mapViewRef.value = null
                    }
                    else -> {}
                }
            } catch (_: Exception) {}
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            try {
                lifecycleOwner.lifecycle.removeObserver(observer)
                mapViewRef.value?.onDetach()
                mapViewRef.value = null
            } catch (_: Exception) {}
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF0F172A))) {
        // ==========================================
        // 1. MAPA NATIVO ANDROID OSMDROID (OFFLINE PERSISTENT CACHE 2GB)
        // ==========================================
        AndroidView<MapView>(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                try {
                    val osmConfig = org.osmdroid.config.Configuration.getInstance()
                    osmConfig.userAgentValue = "SENTINEL-NAV/1.0 (Android; FireTruck Chile)"
                    val baseDir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
                    val tileCacheDir = java.io.File(baseDir, "osmdroid/tiles")
                    if (!tileCacheDir.exists()) tileCacheDir.mkdirs()
                    osmConfig.osmdroidBasePath = java.io.File(baseDir, "osmdroid")
                    osmConfig.osmdroidTileCache = tileCacheDir
                    osmConfig.tileFileSystemCacheMaxBytes = 2048L * 1024L * 1024L // 2 GB de caché persistente
                    osmConfig.tileFileSystemCacheTrimBytes = 1800L * 1024L * 1024L
                    osmConfig.load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
                } catch (_: Exception) {}

                MapView(ctx).apply {
                    setTileSource(if (isSatelliteMode) GoogleHybridTileSource else TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    isTilesScaledToDpi = false
                    minZoomLevel = 4.0
                    maxZoomLevel = 20.0
                    controller.setZoom(15.5)
                    controller.setCenter(GeoPoint(emLat, emLng))
                    mapViewRef.value = this
                }
            },
            update = { mapView ->
                mapView.post {
                    try {
                        synchronized(mapView.overlays) {
                            val currentSource = if (isSatelliteMode) GoogleHybridTileSource else TileSourceFactory.MAPNIK
                            val currentTileName = try { mapView.tileProvider?.tileSource?.name() } catch (_: Exception) { null }
                            if (currentTileName != null && currentTileName != currentSource.name()) {
                                mapView.setTileSource(currentSource)
                            }

                            val vehPoint = GeoPoint(vLat, vLng)

                            // -------------------------------------------------------------
                            // A. Marcador de Incidente (Marcado por la Central CAD)
                            // -------------------------------------------------------------
                            if (hasIncident && !isIncidentDeleted && emLat != 0.0 && emLng != 0.0) {
                                val emPoint = GeoPoint(emLat, emLng)
                                val isSelected = (activeNavTarget == NavTargetType.INCIDENT)
                                if (incidentMarkerRef.value == null) {
                                    val marker = Marker(mapView).apply {
                                        position = emPoint
                                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                        icon = createEmergencyMarkerDrawable(context, pinColorInt, isSelected)
                                        title = "INCIDENTE (CENTRAL)"
                                        setInfoWindow(null)
                                        setOnMarkerClickListener { _, _ ->
                                            val now = System.currentTimeMillis()
                                            if (now - lastClickIncidentTime < 450L) {
                                                isIncidentDeleted = true
                                                if (activeNavTarget == NavTargetType.INCIDENT) {
                                                    activeNavTarget = if (hasAlertante && !isAlertanteDeleted) NavTargetType.ALERTANTE else if (!isCentralDeleted) NavTargetType.CENTRAL else NavTargetType.INCIDENT
                                                }
                                                android.widget.Toast.makeText(context, "Punto de Incidente eliminado", android.widget.Toast.LENGTH_SHORT).show()
                                            } else {
                                                lastClickIncidentTime = now
                                                activeNavTarget = NavTargetType.INCIDENT
                                            }
                                            true
                                        }
                                    }
                                    incidentMarkerRef.value = marker
                                    mapView.overlays.add(marker)
                                } else {
                                    incidentMarkerRef.value?.position = emPoint
                                    incidentMarkerRef.value?.icon = createEmergencyMarkerDrawable(context, pinColorInt, isSelected)
                                }
                            } else {
                                incidentMarkerRef.value?.let {
                                    mapView.overlays.remove(it)
                                    incidentMarkerRef.value = null
                                }
                            }

                            // -------------------------------------------------------------
                            // B. Marcador de Alertante (Si envió ubicación GPS por link)
                            // -------------------------------------------------------------
                            if (hasAlertante && !isAlertanteDeleted && alLat != 0.0 && alLng != 0.0) {
                                val alertPoint = GeoPoint(alLat, alLng)
                                val isSelected = (activeNavTarget == NavTargetType.ALERTANTE)
                                if (alertanteMarkerRef.value == null) {
                                    val marker = Marker(mapView).apply {
                                        position = alertPoint
                                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                        icon = createAlertanteMarkerDrawable(context, isSelected)
                                        title = "ALERTANTE: ${alertantePhone.ifEmpty { "Reporte Móvil" }}"
                                        setInfoWindow(null)
                                        setOnMarkerClickListener { _, _ ->
                                            val now = System.currentTimeMillis()
                                            if (now - lastClickAlertanteTime < 450L) {
                                                isAlertanteDeleted = true
                                                if (activeNavTarget == NavTargetType.ALERTANTE) {
                                                    activeNavTarget = if (hasIncident && !isIncidentDeleted) NavTargetType.INCIDENT else if (!isCentralDeleted) NavTargetType.CENTRAL else NavTargetType.ALERTANTE
                                                }
                                                android.widget.Toast.makeText(context, "Punto de Alertante eliminado", android.widget.Toast.LENGTH_SHORT).show()
                                            } else {
                                                lastClickAlertanteTime = now
                                                activeNavTarget = NavTargetType.ALERTANTE
                                            }
                                            true
                                        }
                                    }
                                    alertanteMarkerRef.value = marker
                                    mapView.overlays.add(marker)
                                } else {
                                    alertanteMarkerRef.value?.position = alertPoint
                                    alertanteMarkerRef.value?.icon = createAlertanteMarkerDrawable(context, isSelected)
                                }
                            } else {
                                alertanteMarkerRef.value?.let {
                                    mapView.overlays.remove(it)
                                    alertanteMarkerRef.value = null
                                }
                            }

                            // -------------------------------------------------------------
                            // C. Marcador de Cuartel / Central General
                            // -------------------------------------------------------------
                            if (!isCentralDeleted && cenLat != 0.0 && cenLng != 0.0) {
                                val centralPoint = GeoPoint(cenLat, cenLng)
                                val isSelected = (activeNavTarget == NavTargetType.CENTRAL)
                                if (centralMarkerRef.value == null) {
                                    val marker = Marker(mapView).apply {
                                        position = centralPoint
                                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                        icon = createCentralMarkerDrawable(context, isSelected)
                                        title = "CENTRAL / CUARTEL GENERAL"
                                        setInfoWindow(null)
                                        setOnMarkerClickListener { _, _ ->
                                            val now = System.currentTimeMillis()
                                            if (now - lastClickCentralTime < 450L) {
                                                isCentralDeleted = true
                                                if (activeNavTarget == NavTargetType.CENTRAL) {
                                                    activeNavTarget = if (hasIncident && !isIncidentDeleted) NavTargetType.INCIDENT else if (hasAlertante && !isAlertanteDeleted) NavTargetType.ALERTANTE else NavTargetType.CENTRAL
                                                }
                                                android.widget.Toast.makeText(context, "Punto de Central / Cuartel eliminado", android.widget.Toast.LENGTH_SHORT).show()
                                            } else {
                                                lastClickCentralTime = now
                                                activeNavTarget = NavTargetType.CENTRAL
                                            }
                                            true
                                        }
                                    }
                                    centralMarkerRef.value = marker
                                    mapView.overlays.add(marker)
                                } else {
                                    centralMarkerRef.value?.position = centralPoint
                                    centralMarkerRef.value?.icon = createCentralMarkerDrawable(context, isSelected)
                                }
                            } else {
                                centralMarkerRef.value?.let {
                                    mapView.overlays.remove(it)
                                    centralMarkerRef.value = null
                                }
                            }

                            // -------------------------------------------------------------
                            // D. Ruta OSRM dinámica hacia el objetivo activo
                            // -------------------------------------------------------------
                            if (hasVehicle && activeNavTarget != NavTargetType.NONE && currentDestLat != 0.0) {
                                val targetGeo = GeoPoint(currentDestLat, currentDestLng)
                                val liveRoutePoints = if (routePoints.size >= 2) {
                                    var closestIdx = 0
                                    var minDist = Double.MAX_VALUE
                                    for (idx in routePoints.indices) {
                                        val dist = routePoints[idx].distanceToAsDouble(vehPoint)
                                        if (dist < minDist) {
                                            minDist = dist
                                            closestIdx = idx
                                        }
                                    }
                                    if (minDist < 250.0 && closestIdx < routePoints.size) {
                                        listOf(vehPoint) + routePoints.subList(closestIdx, routePoints.size)
                                    } else {
                                        listOf(vehPoint) + routePoints
                                    }
                                } else {
                                    listOf(vehPoint, targetGeo)
                                }

                                val routeColor = when (activeNavTarget) {
                                    NavTargetType.INCIDENT -> android.graphics.Color.parseColor("#EF4444") // Rojo
                                    NavTargetType.ALERTANTE -> android.graphics.Color.parseColor("#06B6D4") // Cian
                                    NavTargetType.CENTRAL -> android.graphics.Color.parseColor("#10B981") // Verde
                                    NavTargetType.NONE -> android.graphics.Color.TRANSPARENT
                                }

                                if (routePolylineRef.value == null) {
                                    val poly = Polyline(mapView).apply {
                                        setPoints(liveRoutePoints)
                                        outlinePaint.color = routeColor
                                        outlinePaint.strokeWidth = 7.5f
                                        outlinePaint.strokeCap = Paint.Cap.ROUND
                                        outlinePaint.strokeJoin = Paint.Join.ROUND
                                        setInfoWindow(null)
                                        setOnClickListener { _, _, _ -> true }
                                    }
                                    routePolylineRef.value = poly
                                    mapView.overlays.add(poly)
                                } else {
                                    routePolylineRef.value?.apply {
                                        outlinePaint.color = routeColor
                                        outlinePaint.strokeWidth = 7.5f
                                        setPoints(liveRoutePoints)
                                        setInfoWindow(null)
                                        setOnClickListener { _, _, _ -> true }
                                    }
                                }
                            } else {
                                routePolylineRef.value?.let {
                                    mapView.overlays.remove(it)
                                    routePolylineRef.value = null
                                }
                            }

                            // -------------------------------------------------------------
                            // E. Marcador de Carro Bomba (GPS Live)
                            // -------------------------------------------------------------
                            if (hasVehicle && vLat != 0.0 && vLng != 0.0) {
                                val vehPt = GeoPoint(vLat, vLng)
                                if (vehicleMarkerRef.value == null) {
                                    val vMarker = Marker(mapView).apply {
                                        position = vehPt
                                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                        icon = createVehicleMarkerDrawable(context, vehicleHeading)
                                        setInfoWindow(null)
                                    }
                                    vehicleMarkerRef.value = vMarker
                                    mapView.overlays.add(vMarker)
                                } else {
                                    vehicleMarkerRef.value?.position = vehPt
                                    vehicleMarkerRef.value?.icon = createVehicleMarkerDrawable(context, vehicleHeading)
                                }
                            } else {
                                vehicleMarkerRef.value?.let {
                                    mapView.overlays.remove(it)
                                    vehicleMarkerRef.value = null
                                }
                            }

                            // -------------------------------------------------------------
                            // F. Marcadores de Bomberos en Asistencia (Exclusivos de la Unidad)
                            // -------------------------------------------------------------
                            val activeFighters = if (dispatchId != null && assignedPersonnelIds.isNotEmpty()) {
                                personnelList.filter { it.idRegistro in assignedPersonnelIds && it.lat != 0.0 && it.lng != 0.0 }
                            } else emptyList()

                            val currentFighterIds = activeFighters.map { it.idRegistro }.toSet()
                            val toRemove = firefighterMarkersRef.keys.filter { it !in currentFighterIds }
                            toRemove.forEach { id ->
                                firefighterMarkersRef[id]?.let { mapView.overlays.remove(it) }
                                firefighterMarkersRef.remove(id)
                            }

                            activeFighters.forEach { fighter ->
                                val pt = GeoPoint(fighter.lat, fighter.lng)
                                val existing = firefighterMarkersRef[fighter.idRegistro]
                                if (existing == null) {
                                    val marker = Marker(mapView).apply {
                                        position = pt
                                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                        icon = createFirefighterMarkerDrawable(context, fighter.idRadial.ifEmpty { "👨‍🚒" })
                                        setInfoWindow(null)
                                    }
                                    firefighterMarkersRef[fighter.idRegistro] = marker
                                    mapView.overlays.add(marker)
                                } else {
                                    existing.position = pt
                                }
                            }

                            mapView.invalidate()
                        }
                    } catch (_: Exception) {}
                }
            }
        )

        // ==========================================
        // 1.5. TARJETA FLOTANTE TÁCTICA TURN-BY-TURN (PRÓXIMA MANIOBRA)
        // ==========================================
        val activeManeuver = remember(vLat, vLng, maneuverSteps) {
            if (maneuverSteps.isEmpty()) null
            else {
                val curPt = GeoPoint(vLat, vLng)
                maneuverSteps.firstOrNull { step ->
                    curPt.distanceToAsDouble(step.location) >= 20.0
                } ?: maneuverSteps.firstOrNull()
            }
        }

        if (activeManeuver != null && activeNavTarget != NavTargetType.NONE && currentDestLat != 0.0) {
            val distToMan = GeoPoint(vLat, vLng).distanceToAsDouble(activeManeuver.location)
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

            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 110.dp, top = 16.dp)
                    .widthIn(max = 290.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF0F172A).copy(alpha = 0.94f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF06B6D4).copy(alpha = 0.50f)),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF06B6D4).copy(alpha = 0.20f))
                            .border(1.dp, Color(0xFF06B6D4).copy(alpha = 0.50f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = iconSymbol,
                            fontSize = 20.sp
                        )
                    }
                    Column {
                        Text(
                            text = "En $distText",
                            color = Color(0xFF06B6D4),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = activeManeuver.instruction.ifEmpty { activeManeuver.streetName },
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // ==========================================
        // 2. SELECTOR TÁCTICO FLOTANTE DE DESTINO (INCIDENTE / ALERTANTE / CENTRAL)
        // ==========================================
        if ((hasIncident && !isIncidentDeleted) || (hasAlertante && !isAlertanteDeleted) || !isCentralDeleted) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 110.dp, bottom = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0F172A).copy(alpha = 0.94f))
                    .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Opción 1: Incidente Central
                if (hasIncident && !isIncidentDeleted) {
                    val isSel = (activeNavTarget == NavTargetType.INCIDENT)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSel) Color(0xFFEF4444).copy(alpha = 0.25f) else Color.Transparent)
                            .border(if (isSel) 1.5.dp else 1.dp, if (isSel) Color(0xFFEF4444) else Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                            .clickable { activeNavTarget = if (isSel) NavTargetType.NONE else NavTargetType.INCIDENT }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("🚨", fontSize = 12.sp)
                            Text(
                                text = "INCIDENTE",
                                color = if (isSel) Color(0xFFEF4444) else Color.White.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }

                // Opción 2: Alertante
                if (hasAlertante && !isAlertanteDeleted) {
                    val isSel = (activeNavTarget == NavTargetType.ALERTANTE)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSel) Color(0xFF06B6D4).copy(alpha = 0.25f) else Color.Transparent)
                            .border(if (isSel) 1.5.dp else 1.dp, if (isSel) Color(0xFF06B6D4) else Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                            .clickable { activeNavTarget = if (isSel) NavTargetType.NONE else NavTargetType.ALERTANTE }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("📱", fontSize = 12.sp)
                            Text(
                                text = "ALERTANTE",
                                color = if (isSel) Color(0xFF06B6D4) else Color.White.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }

                // Opción 3: Central / Cuartel
                if (!isCentralDeleted) {
                    val isSelCentral = (activeNavTarget == NavTargetType.CENTRAL)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelCentral) Color(0xFF10B981).copy(alpha = 0.25f) else Color.Transparent)
                            .border(if (isSelCentral) 1.5.dp else 1.dp, if (isSelCentral) Color(0xFF10B981) else Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                            .clickable { activeNavTarget = if (isSelCentral) NavTargetType.NONE else NavTargetType.CENTRAL }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("🏛️", fontSize = 12.sp)
                            Text(
                                text = "CENTRAL",
                                color = if (isSelCentral) Color(0xFF10B981) else Color.White.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }

        // ==========================================
        // 3. BOTONES FLOTANTES DE CAPA Y CENTRAR
        // ==========================================
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Alternar Satélite / Calles
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0F172A).copy(alpha = 0.92f))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                    .clickable {
                        isSatelliteMode = !isSatelliteMode
                        mapViewRef.value?.setTileSource(if (isSatelliteMode) GoogleHybridTileSource else TileSourceFactory.MAPNIK)
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Layers, contentDescription = "Capa", tint = Color.White, modifier = Modifier.size(16.dp))
                    Text(
                        text = if (isSatelliteMode) "🛰️ SATÉLITE" else "🗺️ CALLES",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Centrar exclusivamente a la unidad / Alternar Vista Completa de Ruta
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0F172A).copy(alpha = 0.92f))
                    .border(1.dp, if (isCameraFollowMode) Color(0xFF06B6D4) else Color.White.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                    .clickable {
                        mapViewRef.value?.let { map ->
                            if (!isCameraFollowMode) {
                                // Modo 1: Seguimiento centrado en la unidad
                                isCameraFollowMode = true
                                map.controller.setZoom(17.5)
                                if (vehicleHeading > 0f) {
                                    map.mapOrientation = -vehicleHeading
                                }
                                map.controller.animateTo(GeoPoint(vLat, vLng), 17.5, 600L)
                            } else {
                                // Modo 2: Si ya está en seguimiento y hay ruta activa, alejar y mostrar ruta completa
                                if (routePoints.isNotEmpty() && activeNavTarget != NavTargetType.NONE && currentDestLat != 0.0) {
                                    isCameraFollowMode = false
                                    map.mapOrientation = 0f // Norte arriba
                                    val allPts = routePoints
                                    val maxLat = allPts.maxOf { it.latitude }
                                    val minLat = allPts.minOf { it.latitude }
                                    val maxLng = allPts.maxOf { it.longitude }
                                    val minLng = allPts.minOf { it.longitude }
                                    val padLat = (maxLat - minLat).coerceAtLeast(0.005) * 0.25
                                    val padLng = (maxLng - minLng).coerceAtLeast(0.005) * 0.25
                                    val box = BoundingBox(maxLat + padLat, maxLng + padLng, minLat - padLat, minLng - padLng)
                                    map.zoomToBoundingBox(box, true, 140)
                                } else {
                                    // Re-centrar unidad si no hay ruta activa
                                    map.controller.setZoom(17.5)
                                    map.controller.animateTo(GeoPoint(vLat, vLng), 17.5, 600L)
                                }
                            }
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        imageVector = Icons.Filled.MyLocation,
                        contentDescription = "Centrar",
                        tint = if (isCameraFollowMode) CarPlayColors.AccentCyan else Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isCameraFollowMode) "SEGUIMIENTO" else "VER RUTA",
                        color = if (isCameraFollowMode) CarPlayColors.AccentCyan else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Generadores Canvas nativos de alta definición
// -------------------------------------------------------------

private fun createEmergencyMarkerDrawable(context: Context, color: Int, isSelected: Boolean): BitmapDrawable {
    val size = if (isSelected) 104 else 90
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f

    // Halo exterior pulsante
    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        alpha = if (isSelected) 95 else 65
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, if (isSelected) 46f else 38f, haloPaint)

    // Borde blanco
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = if (isSelected) 6f else 4.5f
    }
    canvas.drawCircle(cx, cy, if (isSelected) 26f else 22f, borderPaint)

    // Núcleo
    val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, if (isSelected) 24f else 20f, corePaint)

    // Texto/icono táctico
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        textSize = if (isSelected) 18f else 15f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2)
    canvas.drawText("🚨", cx, textY, textPaint)

    return BitmapDrawable(context.resources, bitmap)
}

private fun createAlertanteMarkerDrawable(context: Context, isSelected: Boolean): BitmapDrawable {
    val size = if (isSelected) 104 else 90
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f

    val colorCyan = android.graphics.Color.parseColor("#06B6D4")

    // Halo exterior
    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorCyan
        alpha = if (isSelected) 90 else 60
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, if (isSelected) 46f else 38f, haloPaint)

    // Borde blanco
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = if (isSelected) 6f else 4.5f
    }
    canvas.drawCircle(cx, cy, if (isSelected) 26f else 22f, borderPaint)

    // Núcleo
    val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorCyan
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, if (isSelected) 24f else 20f, corePaint)

    // Icono Teléfono / Alertante
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = if (isSelected) 18f else 15f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2)
    canvas.drawText("📱", cx, textY, textPaint)

    return BitmapDrawable(context.resources, bitmap)
}

private fun createCentralMarkerDrawable(context: Context, isSelected: Boolean): BitmapDrawable {
    val size = if (isSelected) 100 else 86
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f

    val colorEmerald = android.graphics.Color.parseColor("#10B981")

    // Halo exterior
    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorEmerald
        alpha = if (isSelected) 85 else 55
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, if (isSelected) 44f else 36f, haloPaint)

    // Borde blanco
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = if (isSelected) 5.5f else 4f
    }
    canvas.drawCircle(cx, cy, if (isSelected) 24f else 20f, borderPaint)

    // Núcleo
    val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorEmerald
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, if (isSelected) 22f else 18f, corePaint)

    // Icono Cuartel / Base
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = if (isSelected) 17f else 14f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2)
    canvas.drawText("🏛️", cx, textY, textPaint)

    return BitmapDrawable(context.resources, bitmap)
}

private fun createVehicleMarkerDrawable(context: Context, heading: Float): BitmapDrawable {
    val size = 96
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f

    canvas.save()
    canvas.rotate(heading, cx, cy)

    // Pulso exterior
    val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#0284C7")
        alpha = 60
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, 40f, pulsePaint)

    // Círculo base
    val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#0284C7")
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, 22f, basePaint)

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    canvas.drawCircle(cx, cy, 22f, borderPaint)

    // Flecha de rumbo blanca
    val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    val path = Path().apply {
        moveTo(cx, cy - 14f)
        lineTo(cx + 10f, cy + 10f)
        lineTo(cx, cy + 5f)
        lineTo(cx - 10f, cy + 10f)
        close()
    }
    canvas.drawPath(path, arrowPaint)
    canvas.restore()

    return BitmapDrawable(context.resources, bitmap)
}

private fun createFirefighterMarkerDrawable(context: Context, label: String): BitmapDrawable {
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

    return BitmapDrawable(context.resources, bitmap)
}
