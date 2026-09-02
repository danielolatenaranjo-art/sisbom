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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
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

    val hasEmergency = emergencyLat != null && emergencyLat != 0.0 && emergencyLng != null && emergencyLng != 0.0
    val hasVehicle = vehicleLat != null && vehicleLat != 0.0 && vehicleLng != null && vehicleLng != 0.0

    val cleanClave = emergencyClave.trim().uppercase()
    val isRetornoToCuartel = cleanClave.contains("6-9") || cleanClave.contains("RETORNO")
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
    val vLat = vehicleLat ?: emLat
    val vLng = vehicleLng ?: emLng

    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    val emergencyMarkerRef = remember { mutableStateOf<Marker?>(null) }
    val vehicleMarkerRef = remember { mutableStateOf<Marker?>(null) }
    val routePolylineRef = remember { mutableStateOf<Polyline?>(null) }
    val firefighterMarkersRef = remember { mutableStateMapOf<String, Marker>() }
    var routePoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }

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
        }
    }

    // Cálculo de ruta OSRM con control de intervalo y respaldo multi-servidor
    LaunchedEffect(hasEmergency, hasVehicle, emLat, emLng, vLat, vLng) {
        if (hasEmergency && hasVehicle && emLat != 0.0 && vLat != 0.0) {
            val curVPoint = GeoPoint(vLat, vLng)
            val curEmPoint = GeoPoint(emLat, emLng)
            val now = System.currentTimeMillis()

            val distFromLastOrigin = lastRoutedOrigin?.distanceToAsDouble(curVPoint) ?: 9999.0
            val distFromLastDest = lastRoutedDest?.distanceToAsDouble(curEmPoint) ?: 9999.0

            val shouldFetch = (lastRoutedOrigin == null) ||
                    (distFromLastDest > 20.0) ||
                    (distFromLastOrigin >= 35.0 && now - lastRouteFetchTime >= 5000L)

            if (shouldFetch) {
                lastRoutedOrigin = curVPoint
                lastRoutedDest = curEmPoint
                lastRouteFetchTime = now

                withContext(Dispatchers.IO) {
                    val endpoints = listOf(
                        "https://router.project-osrm.org/route/v1/driving/$vLng,$vLat;$emLng,$emLat?overview=full&geometries=geojson",
                        "https://routing.openstreetmap.de/routed-car/route/v1/driving/$vLng,$vLat;$emLng,$emLat?overview=full&geometries=geojson"
                    )

                    var success = false
                    for (urlStr in endpoints) {
                        try {
                            val url = URL(urlStr)
                            val conn = (url.openConnection() as HttpURLConnection).apply {
                                requestMethod = "GET"
                                setRequestProperty("User-Agent", "SisBomCar/1.0 (Android; FireTruck Chile)")
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
                                    withContext(Dispatchers.Main) {
                                        routePoints = pts
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
                            val directDist = curVPoint.distanceToAsDouble(curEmPoint)
                            val estTimeS = (directDist / 13.0) // ~47 km/h promedio
                            if (routePoints.isEmpty()) {
                                routePoints = listOf(curVPoint, curEmPoint)
                            }
                            onEtaCalculated(estTimeS, directDist)
                        }
                    }
                }
            }
        } else {
            routePoints = emptyList()
            lastRoutedOrigin = null
            lastRoutedDest = null
            onEtaCalculated(0.0, 0.0)
        }
    }

    // Control dinámico de cámara:
    // 1. Al recibir despacho: Ver mapa completo de inicio a fin
    // 2. Al salir de cuartel (en trayecto / en movimiento): Acercarse a la unidad y seguirla
    LaunchedEffect(routePoints, hasEmergency, hasVehicle, isDeparted, vLat, vLng) {
        val map = mapViewRef.value ?: return@LaunchedEffect
        if (!hasEmergency || !hasVehicle || emLat == 0.0 || vLat == 0.0) return@LaunchedEffect

        if (!isDeparted && !hasZoomedInToUnit) {
            // Mostrar ruta completa de inicio a fin
            val allPts = if (routePoints.isNotEmpty()) routePoints else listOf(GeoPoint(vLat, vLng), GeoPoint(emLat, emLng))
            val maxLat = allPts.maxOf { it.latitude }
            val minLat = allPts.minOf { it.latitude }
            val maxLng = allPts.maxOf { it.longitude }
            val minLng = allPts.minOf { it.longitude }
            val padLat = (maxLat - minLat).coerceAtLeast(0.005) * 0.25
            val padLng = (maxLng - minLng).coerceAtLeast(0.005) * 0.25
            val box = BoundingBox(maxLat + padLat, maxLng + padLng, minLat - padLat, minLng - padLng)
            map.post {
                try {
                    map.zoomToBoundingBox(box, true, 120)
                } catch (_: Exception) {}
            }
        } else if (isDeparted) {
            // Salida de cuartel: Acercarse al punto de la unidad
            if (!hasZoomedInToUnit) {
                hasZoomedInToUnit = true
                map.post {
                    try {
                        map.controller.setZoom(17.5)
                        map.controller.animateTo(GeoPoint(vLat, vLng), 17.5, 900L)
                    } catch (_: Exception) {}
                }
            } else {
                map.post {
                    try {
                        map.controller.animateTo(GeoPoint(vLat, vLng))
                    } catch (_: Exception) {}
                }
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
                    osmConfig.userAgentValue = "SisBomCar/1.0 (Android; FireTruck Chile)"
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

                            // 1. Marcador de Emergencia
                            if (hasEmergency && emLat != 0.0 && emLng != 0.0) {
                                val emPoint = GeoPoint(emLat, emLng)
                                if (emergencyMarkerRef.value == null) {
                                    val marker = Marker(mapView).apply {
                                        position = emPoint
                                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                        icon = createEmergencyMarkerDrawable(context, pinColorInt)
                                        setInfoWindow(null)
                                    }
                                    emergencyMarkerRef.value = marker
                                    mapView.overlays.add(marker)
                                } else {
                                    emergencyMarkerRef.value?.position = emPoint
                                    emergencyMarkerRef.value?.icon = createEmergencyMarkerDrawable(context, pinColorInt)
                                }
                            } else {
                                emergencyMarkerRef.value?.let {
                                    mapView.overlays.remove(it)
                                    emergencyMarkerRef.value = null
                                }
                            }

                            // 2. Ruta de navegación en tiempo real en color ROJO (#EF4444) fino y nítido (7f)
                            if (hasEmergency && hasVehicle) {
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
                                    // Si estamos dentro de 250 metros de la ruta calculada, recortar lo ya recorrido y conectar a la unidad
                                    if (minDist < 250.0 && closestIdx < routePoints.size) {
                                        listOf(vehPoint) + routePoints.subList(closestIdx, routePoints.size)
                                    } else {
                                        listOf(vehPoint) + routePoints
                                    }
                                } else {
                                    listOf(vehPoint, GeoPoint(emLat, emLng))
                                }

                                if (routePolylineRef.value == null) {
                                    val poly = Polyline(mapView).apply {
                                        setPoints(liveRoutePoints)
                                        outlinePaint.color = android.graphics.Color.parseColor("#EF4444")
                                        outlinePaint.strokeWidth = 7f
                                        outlinePaint.strokeCap = Paint.Cap.ROUND
                                        outlinePaint.strokeJoin = Paint.Join.ROUND
                                    }
                                    routePolylineRef.value = poly
                                    mapView.overlays.add(poly)
                                } else {
                                    routePolylineRef.value?.outlinePaint?.strokeWidth = 7f
                                    routePolylineRef.value?.setPoints(liveRoutePoints)
                                }
                            } else {
                                routePolylineRef.value?.let {
                                    mapView.overlays.remove(it)
                                    routePolylineRef.value = null
                                }
                            }

                            // 3. Marcador de Carro Bomba (GPS Live)
                            if (hasVehicle && vLat != 0.0 && vLng != 0.0) {
                                val vehPoint = GeoPoint(vLat, vLng)
                                if (vehicleMarkerRef.value == null) {
                                    val vMarker = Marker(mapView).apply {
                                        position = vehPoint
                                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                        icon = createVehicleMarkerDrawable(context, vehicleHeading)
                                        setInfoWindow(null)
                                    }
                                    vehicleMarkerRef.value = vMarker
                                    mapView.overlays.add(vMarker)
                                } else {
                                    vehicleMarkerRef.value?.position = vehPoint
                                    vehicleMarkerRef.value?.icon = createVehicleMarkerDrawable(context, vehicleHeading)
                                }
                            } else {
                                vehicleMarkerRef.value?.let {
                                    mapView.overlays.remove(it)
                                    vehicleMarkerRef.value = null
                                }
                            }

                            // 4. Marcadores de Bomberos en Asistencia (Exclusivos de esta Unidad)
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

                            // Forzar redibujado inmediato
                            mapView.invalidate()
                        }
                    } catch (_: Exception) {}
                }
            }
        )

        // ==========================================
        // 2. BOTONES FLOTANTES DE CAPA Y CENTRAR
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

            // Centrar
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0F172A).copy(alpha = 0.92f))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                    .clickable {
                        mapViewRef.value?.let { map ->
                            if (isDeparted && hasVehicle) {
                                map.controller.setZoom(17.5)
                                map.controller.animateTo(GeoPoint(vLat, vLng), 17.5, 600L)
                            } else if (hasEmergency && hasVehicle) {
                                val allPts = if (routePoints.isNotEmpty()) routePoints else listOf(GeoPoint(vLat, vLng), GeoPoint(emLat, emLng))
                                val maxLat = allPts.maxOf { it.latitude }
                                val minLat = allPts.minOf { it.latitude }
                                val maxLng = allPts.maxOf { it.longitude }
                                val minLng = allPts.minOf { it.longitude }
                                val padLat = (maxLat - minLat).coerceAtLeast(0.005) * 0.25
                                val padLng = (maxLng - minLng).coerceAtLeast(0.005) * 0.25
                                val box = BoundingBox(maxLat + padLat, maxLng + padLng, minLat - padLat, minLng - padLng)
                                map.zoomToBoundingBox(box, true, 120)
                            } else if (hasEmergency) {
                                map.controller.animateTo(GeoPoint(emLat, emLng), 16.5, 800L)
                            } else if (hasVehicle) {
                                map.controller.animateTo(GeoPoint(vLat, vLng), 17.0, 800L)
                            }
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "Centrar", tint = CarPlayColors.AccentCyan, modifier = Modifier.size(16.dp))
                    Text(
                        text = "CENTRAR",
                        color = CarPlayColors.AccentCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// Generadores Canvas nativos de alta definición
private fun createEmergencyMarkerDrawable(context: Context, color: Int): BitmapDrawable {
    val size = 90
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f

    // Halo exterior
    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        alpha = 70
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, 38f, haloPaint)

    // Borde blanco
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    canvas.drawCircle(cx, cy, 22f, borderPaint)

    // Núcleo
    val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, 20f, corePaint)

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
