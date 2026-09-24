package com.sisbom.sisbomcar

import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun CarPlayDashboard(
    viewModel: CarViewModel,
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val unit = viewModel.currentUnitVehicle
    val unitLabel = viewModel.selectedUnitLabel.ifEmpty { viewModel.selectedUnitId }
    val enServRaw = unit?.enServicio?.trim() ?: "0"

    // Resolver despacho activo: estrictamente asignado a esta unidad por el ViewModel
    val dispatch = viewModel.activeDispatch

    LaunchedEffect(enServRaw, dispatch) {
        if (dispatch == null && enServRaw.isNotEmpty() && enServRaw != "0" && enServRaw != "0-8" && enServRaw != "0-9" && enServRaw != "6-13" && enServRaw != "6-14") {
            viewModel.fetchDispatchById(enServRaw)
        }
    }

    // Estado 0-8 / 0-9 / 6-13 / 6-14
    val rawEstado = unit?.estado?.trim() ?: "1"
    val isFueraServicio = rawEstado == "0" || rawEstado == "0-8" || rawEstado.equals("false", ignoreCase = true)
    val is613 = rawEstado == "6-13" || unit?.enServicio == "6-13"
    val is614 = rawEstado == "6-14" || unit?.enServicio == "6-14"
    val isInEmergency = dispatch != null || (unit?.enServicio != null && unit.enServicio != "0" && unit.enServicio.isNotEmpty() && !is613 && !is614)

    val statusColor = when {
        isInEmergency -> CarPlayColors.PrimaryRed
        is613 -> CarPlayColors.PrimaryBlue
        is614 -> CarPlayColors.PrimaryAmber
        isFueraServicio -> CarPlayColors.PrimaryAmber
        else -> CarPlayColors.PrimaryGreen
    }

    val statusText = when {
        isInEmergency -> "EN SERVICIO ACTIVO"
        is613 -> "6-13 TRÁMITES"
        is614 -> "6-14 COMBUSTIBLE"
        isFueraServicio -> "FUERA DE SERVICIO (0-8)"
        else -> "DISPONIBLE (0-9)"
    }

    var showUnitStatusMenu by remember { mutableStateOf(false) }
    var showMotiveDialog by remember { mutableStateOf(false) }
    var motiveText by remember { mutableStateOf("") }

    // Ticker en tiempo real de 1 segundo para cuentas regresivas de solicitudes (12-10, 6-6, etc.)
    var nowTickerTs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            nowTickerTs = System.currentTimeMillis()
        }
    }

    // Modal para Tripulación
    var showCrewDialog by remember { mutableStateOf(false) }
    var crewSearchQuery by remember { mutableStateOf("") }
    val selectedCrewMembers = remember { mutableStateListOf<PersonItem>() }

    // Detección en tiempo real del nombre de calle actual
    var currentStreetName by remember { mutableStateOf("") }
    val curLat = GpsTrackingService.currentLat.takeIf { it != 0.0 } ?: unit?.lat ?: 0.0
    val curLng = GpsTrackingService.currentLng.takeIf { it != 0.0 } ?: unit?.lng ?: 0.0

    LaunchedEffect(curLat, curLng) {
        if (curLat != 0.0 && curLng != 0.0) {
            withContext(Dispatchers.IO) {
                try {
                    val geocoder = android.location.Geocoder(context, Locale("es", "CL"))
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(curLat, curLng, 1)
                    val addr = addresses?.firstOrNull()
                    val street = addr?.thoroughfare ?: addr?.featureName ?: addr?.subLocality ?: addr?.locality ?: ""
                    if (street.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            currentStreetName = street
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // Modal para 6-13 / 6-14 y 6-15
    var showSpecialExitDialog by remember { mutableStateOf(false) }
    var showKmDialog by remember { mutableStateOf(false) }
    var show615Dialog by remember { mutableStateOf(false) }
    var specialExitType by remember { mutableStateOf("6-13") }
    var specialLugar by remember { mutableStateOf("") }
    var specialMotivo by remember { mutableStateOf("") }
    var specialConductor by remember { mutableStateOf("") }
    var specialObac by remember { mutableStateOf("") }
    var specialTripulantes by remember { mutableStateOf("") }
    var specialError by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CarPlayColors.Background)
    ) {
        // =========================================================================
        // 1. MAPA TÁCTICO OSMDROID 100% NATIVO (FULL SCREEN BACKGROUND)
        // =========================================================================
        val localGps by GpsTrackingService.localLocationFlow.collectAsState()
        val liveLat = localGps?.lat?.takeIf { it != 0.0 } ?: unit?.lat
        val liveLng = localGps?.lng?.takeIf { it != 0.0 } ?: unit?.lng
        val liveHeading = localGps?.heading ?: unit?.heading ?: 0f
        val liveSpeed = localGps?.speedKmH ?: 0f

        var etaMinutes by remember { mutableStateOf(0) }
        var etaDistanceKm by remember { mutableStateOf(0f) }
        var etaArrivalTime by remember { mutableStateOf("") }

        val unitStatusInDispatch = remember(dispatch, unitLabel) {
            if (dispatch == null) ""
            else {
                val entry = dispatch.unidades.entries.find { (k, _) ->
                    k.replace("-", "").equals(unitLabel.replace("-", ""), ignoreCase = true)
                }
                (entry?.value?.get("estado") as? String) ?: (entry?.value?.get("status") as? String) ?: ""
            }
        }

        val unitEntry = dispatch?.unidades?.entries?.find {
            it.key.replace("-", "").equals(unitLabel.replace("-", ""), ignoreCase = true)
        }
        val uData = unitEntry?.value

        val assignedPersonnelIds = remember(uData, viewModel.personalList) {
            val ids = mutableSetOf<String>()
            if (uData != null) {
                val driverRad = (uData["driverRad"] ?: uData["conductor"])?.toString()?.trim() ?: ""
                val obacRad = (uData["obacRad"] ?: uData["obac"])?.toString()?.trim() ?: ""
                val tripulantesDetalle = uData["tripulantesDetalle"] as? List<*>
                val tripulacionList = uData["tripulacion"] as? List<*>

                if (driverRad.isNotEmpty()) {
                    viewModel.personalList.find {
                        it.idRadial.equals(driverRad, ignoreCase = true) ||
                        it.idRegistro == driverRad ||
                        it.nombreBombero.contains(driverRad, ignoreCase = true)
                    }?.let { ids.add(it.idRegistro) }
                }

                if (obacRad.isNotEmpty()) {
                    viewModel.personalList.find {
                        it.idRadial.equals(obacRad, ignoreCase = true) ||
                        it.idRegistro == obacRad ||
                        it.nombreBombero.contains(obacRad, ignoreCase = true)
                    }?.let { ids.add(it.idRegistro) }
                }

                tripulantesDetalle?.forEach { item ->
                    when (item) {
                        is Map<*, *> -> {
                            val idReg = item["idRegistro"]?.toString() ?: ""
                            val rad = item["idRadial"]?.toString() ?: ""
                            if (idReg.isNotEmpty()) ids.add(idReg)
                            else if (rad.isNotEmpty()) {
                                viewModel.personalList.find { it.idRadial.equals(rad, ignoreCase = true) }?.let { ids.add(it.idRegistro) }
                            }
                        }
                        is String -> {
                            viewModel.personalList.find { it.idRegistro == item || it.idRadial.equals(item, ignoreCase = true) || it.nombreBombero.contains(item, ignoreCase = true) }?.let { ids.add(it.idRegistro) }
                        }
                    }
                }

                tripulacionList?.forEach { item ->
                    if (item is String) {
                        viewModel.personalList.find { it.idRegistro == item || it.idRadial.equals(item, ignoreCase = true) || it.nombreBombero.contains(item, ignoreCase = true) }?.let { ids.add(it.idRegistro) }
                    }
                }
            }
            ids
        }

        val activeTrip = viewModel.activeBitacoraTrip
        val unitEntryForMap = dispatch?.unidades?.entries?.find {
            it.key.replace("-", "").equals(unitLabel.replace("-", ""), ignoreCase = true)
        }
        val uDataForMap = unitEntryForMap?.value
        val uEstadoForMap = ((uDataForMap?.get("estado") ?: uDataForMap?.get("status") ?: activeTrip?.estadoMovil ?: unit?.estado) as? String ?: "").lowercase()
        val hora69ForMap = (uDataForMap?.get("hora69") ?: uDataForMap?.get("retorno69At") ?: activeTrip?.hora69)?.toString()?.trim() ?: ""
        val hora610ForMap = (uDataForMap?.get("hora610") ?: uDataForMap?.get("llegada610At") ?: activeTrip?.hora610)?.toString()?.trim() ?: ""
        val hora68ForMap = (uDataForMap?.get("hora68") ?: uDataForMap?.get("disponible68At") ?: activeTrip?.hora68)?.toString()?.trim() ?: ""

        val navPrefs = remember { context.getSharedPreferences("sentinel_nav_prefs", android.content.Context.MODE_PRIVATE) }
        var isDarkMode by remember {
            mutableStateOf(navPrefs.getBoolean("pref_dark_mode", true))
        }

        val hasActiveDispatch = dispatch != null && dispatch.operadorFinal.isEmpty()
        val hasActiveSalida = activeTrip != null && activeTrip.hora68.isEmpty() && activeTrip.estadoMovil != "en cuartel"

        val isRetornoForMap = (uEstadoForMap == "retorno" || uEstadoForMap == "6-9" || (hora69ForMap.isNotEmpty() && hora610ForMap.isEmpty()))
        val isEnCuartelForMap = (!hasActiveDispatch && !hasActiveSalida) || uEstadoForMap == "finalizado" || uEstadoForMap == "6-8" || hora68ForMap.isNotEmpty()
        val hasActive6XButton = !isEnCuartelForMap && (dispatch != null || activeTrip != null || (unit?.enServicio != null && unit.enServicio != "0" && unit.enServicio != "0-8" && unit.enServicio != "0-9" && unit.enServicio.isNotEmpty()))

        // Geocodificación de respaldo en segundo plano si dispatch.lat o dispatch.lng no vienen definidos
        var geocodedLat by remember(dispatch?.idServicio) { mutableStateOf<Double?>(null) }
        var geocodedLng by remember(dispatch?.idServicio) { mutableStateOf<Double?>(null) }

        LaunchedEffect(dispatch?.idServicio, dispatch?.lugar, dispatch?.lat, dispatch?.lng) {
            if (dispatch != null && (dispatch.lat == null || dispatch.lat == 0.0) && dispatch.lugar.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    try {
                        val geocoder = android.location.Geocoder(context, Locale("es", "CL"))
                        val rawLoc = dispatch.lugar.trim()
                        val query = if (!rawLoc.contains("Placilla", ignoreCase = true) && !rawLoc.contains("Chile", ignoreCase = true)) {
                            "$rawLoc, Placilla, O'Higgins, Chile"
                        } else rawLoc
                        val list = geocoder.getFromLocationName(query, 1)
                        if (!list.isNullOrEmpty()) {
                            geocodedLat = list[0].latitude
                            geocodedLng = list[0].longitude
                        }
                    } catch (_: Exception) {}
                }
            } else {
                geocodedLat = null
                geocodedLng = null
            }
        }

        // Coordenadas fijas estratégicas
        val CUARTEL_GENERAL_LAT = -34.636808
        val CUARTEL_GENERAL_LNG = -71.119757
        val CESFAM_PLACILLA_LAT = -34.6393245
        val CESFAM_PLACILLA_LNG = -71.1172894
        val HOSPITAL_SAN_FERNANDO_LAT = -34.5768069
        val HOSPITAL_SAN_FERNANDO_LNG = -70.9929463

        // Detección de traslado 6-15 activo
        val is615Active = (uDataForMap?.get("is615") as? Boolean == true) ||
                uEstadoForMap == "6-15" ||
                uEstadoForMap.contains("traslado") ||
                viewModel.active615DestinoNombre.isNotEmpty()

        val rawDestNombre = (uDataForMap?.get("destinoSalud") as? String)?.ifEmpty { null }
            ?: (uDataForMap?.get("lugarSalud") as? String)?.ifEmpty { null }
            ?: viewModel.active615DestinoNombre.ifEmpty { null }
            ?: "CESFAM PLACILLA"

        val rawDestLat = (uDataForMap?.get("destinoSaludLat") as? Number)?.toDouble()?.takeIf { it != 0.0 }
            ?: viewModel.active615DestinoLat.takeIf { it != 0.0 }
            ?: if (rawDestNombre.contains("FERNANDO", ignoreCase = true) || rawDestNombre.contains("SNFDO", ignoreCase = true)) HOSPITAL_SAN_FERNANDO_LAT else CESFAM_PLACILLA_LAT

        val rawDestLng = (uDataForMap?.get("destinoSaludLng") as? Number)?.toDouble()?.takeIf { it != 0.0 }
            ?: viewModel.active615DestinoLng.takeIf { it != 0.0 }
            ?: if (rawDestNombre.contains("FERNANDO", ignoreCase = true) || rawDestNombre.contains("SNFDO", ignoreCase = true)) HOSPITAL_SAN_FERNANDO_LNG else CESFAM_PLACILLA_LNG

        // Si la unidad está en cuartel (6-8 / finalizada o sin despacho activo), no se traza ruta
        // Si la unidad está en retorno (6-9), la ruta apunta hacia el Cuartel General
        // Si la unidad está en traslado 6-15, la ruta apunta hacia el Centro Asistencial seleccionado
        // Si la unidad está despachada, la ruta apunta directamente hacia la emergencia
        val mapTargetLat = when {
            isEnCuartelForMap -> null
            isRetornoForMap -> CUARTEL_GENERAL_LAT
            is615Active -> rawDestLat
            else -> dispatch?.lat?.takeIf { it != 0.0 } ?: geocodedLat
        }
        val mapTargetLng = when {
            isEnCuartelForMap -> null
            isRetornoForMap -> CUARTEL_GENERAL_LNG
            is615Active -> rawDestLng
            else -> dispatch?.lng?.takeIf { it != 0.0 } ?: geocodedLng
        }
        val mapTargetClave = when {
            isEnCuartelForMap -> ""
            isRetornoForMap -> "6-9 RETORNO CUARTEL"
            is615Active -> "6-15 $rawDestNombre"
            else -> (dispatch?.clave ?: "")
        }
        val mapDispatchId = when {
            isEnCuartelForMap -> null
            isRetornoForMap -> "retorno_${dispatch?.idServicio ?: activeTrip?.idSalida ?: "cuartel"}"
            is615Active -> "615_${rawDestNombre}_${dispatch?.idServicio ?: activeTrip?.idSalida ?: "salud"}"
            else -> dispatch?.idServicio
        }

        // Observador de geolocalización en vivo del alertante
        var liveAlertanteLat by remember(dispatch?.idServicio) { mutableStateOf<Double?>(null) }
        var liveAlertanteLng by remember(dispatch?.idServicio) { mutableStateOf<Double?>(null) }

        LaunchedEffect(dispatch?.idServicio) {
            val dId = dispatch?.idServicio?.trim() ?: ""
            if (dId.isNotEmpty()) {
                viewModel.repository.getAlertanteLocationFlow(dId)
                    .catch { }
                    .collectLatest { coords: Pair<Double, Double>? ->
                        liveAlertanteLat = coords?.first
                        liveAlertanteLng = coords?.second
                    }
            } else {
                liveAlertanteLat = null
                liveAlertanteLng = null
            }
        }

        CarPlayTacticalMap(
            dispatchId = mapDispatchId,
            emergencyLat = mapTargetLat,
            emergencyLng = mapTargetLng,
            emergencyClave = mapTargetClave,
            alertanteLat = liveAlertanteLat ?: dispatch?.alertanteLat,
            alertanteLng = liveAlertanteLng ?: dispatch?.alertanteLng,
            alertantePhone = dispatch?.telefono ?: "",
            centralLat = CUARTEL_GENERAL_LAT,
            centralLng = CUARTEL_GENERAL_LNG,
            vehicleLat = liveLat,
            vehicleLng = liveLng,
            vehicleHeading = liveHeading,
            vehicleSpeedKmH = liveSpeed,
            unitStatusInDispatch = unitStatusInDispatch,
            hasActive6XButton = hasActive6XButton,
            isDarkMode = isDarkMode,
            personnelList = viewModel.personalList,
            assignedPersonnelIds = assignedPersonnelIds,
            onEtaCalculated = { durS, distM ->
                if (durS > 0) {
                    val mins = (durS / 60).toInt().coerceAtLeast(1)
                    etaMinutes = mins
                    etaDistanceKm = (distM / 1000f).toFloat()
                    val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, mins) }
                    etaArrivalTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time)
                } else {
                    etaMinutes = 0
                    etaDistanceKm = 0f
                    etaArrivalTime = ""
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // =========================================================================
        // TARJETA SUPERIOR CENTRAL: LOGO OFICIAL SENTINEL NAV (AL TAMAÑO DEL VELOCÍMETRO: 58dp)
        // =========================================================================
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .height(58.dp)
                .carPlayGlassCard(
                    cornerRadius = 16.dp,
                    tintColor = Color(0xFF0F172A),
                    tintAlpha = 0.85f,
                    borderColor = Color.White.copy(alpha = 0.25f)
                )
                .padding(horizontal = 18.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.sentinel_nav_logo),
                contentDescription = "SENTINEL NAV",
                modifier = Modifier
                    .fillMaxHeight()
                    .wrapContentWidth(),
                contentScale = ContentScale.Fit
            )
        }

        // =========================================================================
        // 2. BARRA LATERAL IZQUIERDA FLOTANTE CARPLAY (FLOATING DOCK CARD)
        // =========================================================================
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp)
                .width(82.dp)
                .fillMaxHeight()
                .carPlayGlassCard(
                    cornerRadius = 24.dp,
                    tintColor = Color(0xFF090E1A),
                    tintAlpha = 0.88f,
                    borderColor = CarPlayColors.AccentCyan.copy(alpha = 0.35f)
                )
                .padding(vertical = 14.dp, horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // SECCIÓN SUPERIOR: BOTÓN UNIDAD + HORA + LÍNEA SEPARADORA
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Botón Unidad (ej. B-1) estándar (58dp x 58dp) con brillo de vidrio
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        statusColor.copy(alpha = 0.35f),
                                        statusColor.copy(alpha = 0.18f),
                                        Color(0xFF070F1E).copy(alpha = 0.85f)
                                    )
                                )
                            )
                            .border(
                                width = 1.3.dp,
                                brush = Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.65f),
                                        statusColor,
                                        statusColor.copy(alpha = 0.30f),
                                        Color.Transparent
                                    )
                                ),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable {
                                if (dispatch == null) {
                                    showUnitStatusMenu = !showUnitStatusMenu
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = unitLabel,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1
                            )
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(statusColor, CircleShape)
                            )
                        }
                    }

                    // Reloj Digital y Fecha
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = viewModel.currentTimeString,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = viewModel.currentDateString.take(7),
                            color = CarPlayColors.TextMuted,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Línea Separadora que no toca los bordes
                    HorizontalDivider(
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            .fillMaxWidth(),
                        thickness = 1.dp,
                        color = Color.White.copy(alpha = 0.18f)
                    )

                    // Botón Salidas Especiales (6-13 / 6-14) - Bloqueado si hay despacho activo
                    CarPlayDockSquareButton(
                        icon = if (is614) Icons.Filled.LocalGasStation else Icons.Filled.Route,
                        label = "Salidas 6-13/6-14",
                        isActive = is613 || is614,
                        activeColor = if (is614) CarPlayColors.PrimaryAmber else CarPlayColors.PrimaryBlue,
                        onClick = {
                            if (dispatch != null) return@CarPlayDockSquareButton
                            specialExitType = if (is614) "6-14" else "6-13"
                            specialLugar = if (specialExitType == "6-14") "SERVICENTRO" else ""
                            specialMotivo = if (specialExitType == "6-14") "CARGA DE COMBUSTIBLE" else ""
                            specialConductor = ""
                            specialObac = ""
                            specialTripulantes = ""
                            specialError = ""
                            showSpecialExitDialog = true
                        }
                    )
                }

                // SECCIÓN INFERIOR: BOTÓN MODO OSCURO/CLARO (ARRIBA DEL VELOCÍMETRO) + VELOCÍMETRO DIGITAL
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Botón Cambio de Modo (Oscuro / Claro) ubicado ARRIBA del velocímetro (58dp x 58dp)
                    CarPlayDockSquareButton(
                        icon = if (isDarkMode) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                        label = if (isDarkMode) "Modo Oscuro" else "Modo Claro",
                        isActive = isDarkMode,
                        activeColor = Color(0xFF6366F1), // Glow índigo cuando activo
                        onClick = {
                            isDarkMode = !isDarkMode
                            navPrefs.edit().putBoolean("pref_dark_mode", isDarkMode).apply()
                        }
                    )

                    // VELOCÍMETRO DIGITAL (58dp x 58dp) CON ESTILO COCKPIT GLASS
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFF0F172A).copy(alpha = 0.92f),
                                        Color(0xFF070F1E).copy(alpha = 0.96f),
                                        Color(0xFF020617).copy(alpha = 0.98f)
                                    )
                                )
                            )
                            .border(
                                width = 1.3.dp,
                                brush = Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.50f),
                                        CarPlayColors.AccentCyan.copy(alpha = 0.60f),
                                        CarPlayColors.AccentCyan.copy(alpha = 0.15f),
                                        Color.Transparent
                                    )
                                ),
                                shape = RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${liveSpeed.toInt()}",
                                color = CarPlayColors.AccentCyan,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                lineHeight = 20.sp
                            )
                            Text(
                                text = "KM/H",
                                color = CarPlayColors.TextMuted,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 3. MENÚ FLOTANTE AL TOCAR LA UNIDAD (CAMBIO 0-8 / 0-9)
        // =========================================================================
        AnimatedVisibility(
            visible = showUnitStatusMenu,
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { -40 }),
            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { -40 }),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 110.dp, top = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .carPlayCard(cornerRadius = 20.dp, bgAlpha = 0.96f)
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Header con botón cerrar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(10.dp).background(statusColor, CircleShape))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "UNIDAD $unitLabel",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        IconButton(
                            onClick = { showUnitStatusMenu = false },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Cerrar", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }

                    // Estado actual
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(statusColor.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                            .border(1.dp, statusColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Column {
                            Text("ESTADO OPERATIVO:", color = CarPlayColors.TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(statusText, color = statusColor, fontSize = 13.sp, fontWeight = FontWeight.Black)
                            if (isFueraServicio && unit?.notas?.isNotEmpty() == true) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Motivo: ${unit.notas}", color = Color.White, fontSize = 11.sp)
                            }
                        }
                    }

                    // Acciones de Cambio de Estado
                    if (isFueraServicio || is613 || is614) {
                        Button(
                            onClick = {
                                viewModel.setVehicleDisponible09()
                                showUnitStatusMenu = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CarPlayColors.PrimaryGreen),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(46.dp)
                        ) {
                            Text("✅ DAR DISPONIBLE (0-9)", fontWeight = FontWeight.Black, fontSize = 12.sp)
                        }
                    } else {
                        Button(
                            onClick = {
                                motiveText = ""
                                showMotiveDialog = true
                                showUnitStatusMenu = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CarPlayColors.PrimaryRed),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(46.dp)
                        ) {
                            Text("🚨 DEJAR FUERA DE SERVICIO (0-8)", fontWeight = FontWeight.Black, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 4. DIÁLOGO MOTIVO FUERA DE SERVICIO (0-8) - SOLO FALLA MECÁNICA / MANTENCIÓN
        // =========================================================================
        if (showMotiveDialog) {
            Dialog(
                onDismissRequest = { showMotiveDialog = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(400.dp)
                            .carPlayCard(cornerRadius = 24.dp, bgAlpha = 0.98f)
                            .padding(20.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Warning, contentDescription = null, tint = CarPlayColors.PrimaryAmber, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "MOTIVO FUERA DE SERVICIO (0-8)",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }

                            Text(
                                text = "Indique la razón por la que la unidad $unitLabel quedará fuera de servicio:",
                                color = CarPlayColors.TextSecondary,
                                fontSize = 12.sp
                            )

                            // Opciones rápidas (ÚNICAMENTE FALLA MECÁNICA Y MANTENCIÓN)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf("Falla Mecánica", "Mantención").forEach { quickMotive ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (motiveText == quickMotive) CarPlayColors.PrimaryAmber else Color.White.copy(alpha = 0.08f))
                                            .clickable { motiveText = quickMotive }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = quickMotive,
                                            color = if (motiveText == quickMotive) Color.Black else Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }

                            // Campo de detalle libre
                            OutlinedTextField(
                                value = motiveText,
                                onValueChange = { motiveText = it },
                                label = { Text("Detalle / Notas adicionales") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CarPlayColors.PrimaryAmber,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { showMotiveDialog = false },
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("CANCELAR", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        val finalMotive = motiveText.ifBlank { "Fuera de servicio" }
                                        viewModel.setVehicleFueraServicio08(finalMotive)
                                        showMotiveDialog = false
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CarPlayColors.PrimaryRed),
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("CONFIRMAR 0-8", fontWeight = FontWeight.Black, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 5. MODAL SALIDAS ESPECIALES 6-13 / 6-14 (TRÁMITES Y COMBUSTIBLE)
        // =========================================================================
        TacticalSpecialExitDialog(
            show = showSpecialExitDialog,
            unitLabel = unitLabel,
            viewModel = viewModel,
            onDismiss = { showSpecialExitDialog = false }
        )

        // =========================================================================
        // 5. TARJETA FLOTANTE DE CALLE / UBICACIÓN ACTUAL DEL CARRO (ABAJO AL CENTRO: 58dp / 16dp)
        // =========================================================================
        if (currentStreetName.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .height(58.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF1E293B).copy(alpha = 0.88f),
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
                                CarPlayColors.AccentCyan.copy(alpha = 0.50f),
                                CarPlayColors.AccentCyan.copy(alpha = 0.15f),
                                Color.Transparent
                            )
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = "Calle",
                        tint = CarPlayColors.AccentCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = currentStreetName.uppercase(),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }

        val uEstado = ((uData?.get("estado") ?: uData?.get("status") ?: activeTrip?.estadoMovil ?: unit?.estado) as? String ?: "").lowercase()
        val uStatus = ((uData?.get("status") ?: "") as? String ?: "").lowercase()
        val hora60 = (uData?.get("hora60") ?: uData?.get("salida60At") ?: uData?.get("horaSalida") ?: activeTrip?.hora60)?.toString()?.trim() ?: ""
        val hora63 = (uData?.get("hora63") ?: uData?.get("llegada63At") ?: uData?.get("horaLlegada") ?: activeTrip?.hora63)?.toString()?.trim() ?: ""
        val hora69 = (uData?.get("hora69") ?: uData?.get("retorno69At") ?: uData?.get("horaRetorno") ?: activeTrip?.hora69)?.toString()?.trim() ?: ""
        val hora610 = (uData?.get("hora610") ?: uData?.get("llegada610At") ?: uData?.get("horaCuartel") ?: activeTrip?.hora610)?.toString()?.trim() ?: ""
        val hora68 = (uData?.get("hora68") ?: uData?.get("disponible68At") ?: activeTrip?.hora68)?.toString()?.trim() ?: ""

        val hora615 = (uData?.get("hora615") ?: uData?.get("traslado615At"))?.toString()?.trim() ?: ""
        val hora63Salud = (uData?.get("hora63Salud") ?: uData?.get("llegada63SaludAt"))?.toString()?.trim() ?: ""
        val is615Flag = (uData?.get("is615") as? Boolean == true) || viewModel.active615DestinoNombre.isNotEmpty() || uEstado == "6-15" || uStatus == "6-15" || uEstado.contains("traslado")

        val isFinalizado68 = uStatus == "6-8" || uEstado == "6-8" || uEstado == "finalizado" || hora68.isNotEmpty()
        val isEnCuartel = !isFinalizado68 && (uEstado == "6-10" || uEstado == "en_cuartel" || uEstado.contains("cuartel") || (hora610.isNotEmpty() && hora68.isEmpty()))
        val isRetorno = !isFinalizado68 && !isEnCuartel && (uEstado == "retorno" || uEstado == "6-9" || uEstado.contains("retorno") || (hora69.isNotEmpty() && hora610.isEmpty()))
        
        val is615Trayecto = !isFinalizado68 && !isEnCuartel && !isRetorno && is615Flag && hora63Salud.isEmpty()
        val is615EnSalud = !isFinalizado68 && !isEnCuartel && !isRetorno && !is615Trayecto && (hora63Salud.isNotEmpty() || (is615Flag && uEstado == "en_lugar" && hora615.isNotEmpty()))

        val isEnLugar = !isFinalizado68 && !isEnCuartel && !isRetorno && !is615Trayecto && !is615EnSalud && (uEstado == "en_lugar" || uEstado == "6-3" || uEstado.contains("lugar") || (hora63.isNotEmpty() && hora69.isEmpty()))
        val isEnTrayecto = !isFinalizado68 && !isEnCuartel && !isRetorno && !is615Trayecto && !is615EnSalud && !isEnLugar && (uEstado == "en_trayecto" || uEstado == "6-0" || uEstado.contains("trayecto") || (hora60.isNotEmpty() && hora63.isEmpty()))
        val isWaitingDeparture = !isFinalizado68 && !isEnCuartel && !isRetorno && !is615Trayecto && !is615EnSalud && !isEnLugar && !isEnTrayecto

        val hasActiveTrip = !isFinalizado68 && (dispatch != null || activeTrip != null || (unit?.enServicio != null && unit.enServicio != "0" && unit.enServicio != "0-8" && unit.enServicio != "0-9" && unit.enServicio.isNotEmpty()))

        if (hasActiveTrip) {
            val isVehicleMoving = liveSpeed > 4f

            // Estado de tarjeta colapsada / achicada persistida en SharedPreferences
            var isCardManuallyCollapsed by remember {
                mutableStateOf(navPrefs.getBoolean("pref_dispatch_card_collapsed", false))
            }
            var isCardTempExpandedWhileDriving by remember { mutableStateOf(false) }
            var lastManualExpandTrigger by remember { mutableStateOf(0L) }

            // Si el bombero la abre temporalmente mientras el carro está en marcha
            LaunchedEffect(isCardTempExpandedWhileDriving, lastManualExpandTrigger, isVehicleMoving) {
                if (isCardTempExpandedWhileDriving && isVehicleMoving) {
                    delay(8000L)
                    isCardTempExpandedWhileDriving = false
                }
            }

            // La tarjeta se muestra expandida si el usuario NO la tiene achicada (o si la abrió temporalmente en marcha)
            val isCardExpanded = !isCardManuallyCollapsed && (!isVehicleMoving || isCardTempExpandedWhileDriving)

            val enServRaw = unit?.enServicio?.trim() ?: "0"
            val isNumericEnServ = enServRaw.matches(Regex("^\\d+$"))

            val rawClave = when {
                isRetorno -> "6-9 RETORNO A CUARTEL"
                is615Trayecto || is615EnSalud -> {
                    val dest = viewModel.active615DestinoNombre.takeIf { it.isNotBlank() }
                        ?: (uData?.get("destinoSalud") ?: uData?.get("lugarSalud"))?.toString()
                        ?: "CENTRO ASISTENCIAL"
                    "6-15 • TRASLADO $dest"
                }
                !dispatch?.clave.isNullOrBlank() -> dispatch!!.clave.trim()
                !activeTrip?.clave.isNullOrBlank() -> activeTrip!!.clave.trim()
                enServRaw.isNotEmpty() && !isNumericEnServ && enServRaw != "0" && enServRaw != "0-8" && enServRaw != "0-9" -> enServRaw
                else -> "EMERGENCIA ACTIVA"
            }

            val cleanClave = rawClave.trim().uppercase()
            val displayClave = when {
                cleanClave.contains("•") -> rawClave
                cleanClave.startsWith("10-0-1") -> "10-0-1 • CASA HABITACIÓN"
                cleanClave.startsWith("10-0-2") -> "10-0-2 • COMERCIO / BODEGA"
                cleanClave.startsWith("10-0-3") -> "10-0-3 • EDIFICIO"
                cleanClave.startsWith("10-0") -> "10-0 • INCENDIO ESTRUCTURAL"
                cleanClave.startsWith("10-1") -> "10-1 • INCENDIO VEHICULAR"
                cleanClave.startsWith("10-2") -> "10-2 • PASTIZAL / FORESTAL"
                cleanClave.startsWith("10-3") -> "10-3 • RESCATE DE PERSONAS"
                cleanClave.startsWith("10-4-1") -> "10-4-1 • RESCATE VEHICULAR"
                cleanClave.startsWith("10-4-2") -> "10-4-2 • RESCATE PESADO"
                cleanClave.startsWith("10-4") -> "10-4 • RESCATE VEHICULAR"
                cleanClave.startsWith("10-5") -> "10-5 • HAZMAT / QUÍMICOS"
                cleanClave.startsWith("10-6") -> "10-6 • EMANACIÓN DE GAS"
                cleanClave.startsWith("10-7") -> "10-7 • INCENDIO ELÉCTRICO"
                cleanClave.startsWith("10-8") -> "10-8 • NO CLASIFICADO"
                cleanClave.startsWith("10-9") -> "10-9 • OTROS SERVICIOS"
                cleanClave.startsWith("10-10") -> "10-10 • REBROTE DE FUEGO"
                cleanClave.startsWith("10-11") -> "10-11 • ACCIDENTE AÉREO"
                cleanClave.startsWith("10-12") -> "10-12 • APOYO EXTERNO"
                cleanClave.startsWith("10-30") -> "10-30 • INCENDIO DECLARADO"
                cleanClave.startsWith("9-0") -> "9-0 • ACUARTELAMIENTO"
                cleanClave.startsWith("6-13") -> "6-13 • SALIDA A TRÁMITES"
                cleanClave.startsWith("6-14") -> "6-14 • CARGA COMBUSTIBLE"
                cleanClave.matches(Regex("^\\d+$")) -> "EMERGENCIA EN PROCESO"
                else -> rawClave
            }
            val claveApoyo = dispatch?.claveApoyo?.trim() ?: ""
            val fullDisplayClave = if (claveApoyo.isNotEmpty()) "$displayClave (+ $claveApoyo)" else displayClave

            val displayHoraDespacho: String = dispatch?.horaDespacho?.takeIf { it.isNotEmpty() } ?: activeTrip?.hora60?.takeIf { it.isNotEmpty() } ?: ""
            val rawLugar = dispatch?.lugar?.takeIf { it.isNotBlank() && !it.contains("OBTENIENDO DIRECCIÓN", ignoreCase = true) }
                ?: activeTrip?.lugar?.takeIf { it.isNotBlank() }
                ?: unit?.notas?.takeIf { it.isNotBlank() && !it.contains("OBTENIENDO", ignoreCase = true) }
                ?: if (currentStreetName.isNotBlank()) "CERCANÍAS DE $currentStreetName" else "Dirección no especificada"
            
            val destino615Nombre = viewModel.active615DestinoNombre.takeIf { it.isNotBlank() }
                ?: (uData?.get("destinoSalud") ?: uData?.get("lugarSalud"))?.toString()

            val displayLugar: String = when {
                is615Trayecto || is615EnSalud -> {
                    if (!destino615Nombre.isNullOrBlank()) {
                        destino615Nombre
                    } else {
                        "CENTRO DE SALUD ASISTENCIAL"
                    }
                }
                isRetorno -> "CUARTEL GENERAL (RETORNO)"
                else -> rawLugar
            }
            val displayPreinforme: String = dispatch?.preinforme?.takeIf { it.isNotEmpty() } ?: activeTrip?.preInforme?.takeIf { it.isNotEmpty() } ?: unit?.notas ?: ""
            val displayIdSalida: String = activeTrip?.idSalida ?: ""

            val driverRad = (uData?.get("driverRad") ?: uData?.get("conductor") ?: activeTrip?.conductor)?.toString()?.trim() ?: ""
            val obacRad = (uData?.get("obacRad") ?: uData?.get("obac") ?: activeTrip?.obac)?.toString()?.trim() ?: ""

            val conductorPerson = remember(driverRad, viewModel.personalList) {
                if (driverRad.isEmpty()) null
                else viewModel.personalList.find {
                    it.idRadial.equals(driverRad, ignoreCase = true) ||
                    it.idRegistro == driverRad ||
                    driverRad.startsWith("${it.idRadial} -") ||
                    driverRad.contains(it.nombreBombero, ignoreCase = true) ||
                    it.nombreBombero.contains(driverRad, ignoreCase = true)
                }
            }
            val conductorName = conductorPerson?.nombreBombero ?: driverRad.ifEmpty { "Sin asignar" }

            val obacPerson = remember(obacRad, viewModel.personalList) {
                if (obacRad.isEmpty()) null
                else viewModel.personalList.find {
                    it.idRadial.equals(obacRad, ignoreCase = true) ||
                    it.idRegistro == obacRad ||
                    obacRad.startsWith("${it.idRadial} -") ||
                    obacRad.contains(it.nombreBombero, ignoreCase = true) ||
                    it.nombreBombero.contains(obacRad, ignoreCase = true)
                }
            }
            val obacName = obacPerson?.nombreBombero ?: obacRad.ifEmpty { "Sin asignar" }

            val tripulantesDetalle = (uData?.get("tripulantesDetalle") as? List<*>) ?: activeTrip?.tripulantesDetalle
            val assignedCrewPersons = remember(tripulantesDetalle, viewModel.personalList, uData, activeTrip) {
                val list = mutableListOf<PersonItem>()
                if (!tripulantesDetalle.isNullOrEmpty()) {
                    tripulantesDetalle.forEach { item ->
                        when (item) {
                            is PersonItem -> list.add(item)
                            is Map<*, *> -> {
                                val idReg = item["idRegistro"]?.toString() ?: ""
                                val rad = item["idRadial"]?.toString() ?: ""
                                val nom = (item["nombre"] ?: item["nombreBombero"])?.toString() ?: ""
                                val pMatch = viewModel.personalList.find {
                                    (idReg.isNotEmpty() && it.idRegistro == idReg) ||
                                    (rad.isNotEmpty() && it.idRadial.equals(rad, ignoreCase = true))
                                }
                                if (pMatch != null) list.add(pMatch)
                                else if (nom.isNotEmpty()) list.add(PersonItem(idRegistro = idReg, nombreBombero = nom, idRadial = rad))
                            }
                            is String -> {
                                val pMatch = viewModel.personalList.find {
                                    it.idRegistro == item || it.idRadial.equals(item, ignoreCase = true) || it.nombreBombero.contains(item, ignoreCase = true)
                                }
                                if (pMatch != null) list.add(pMatch)
                                else list.add(PersonItem(idRegistro = item, nombreBombero = item))
                            }
                        }
                    }
                }
                list
            }

            val assignedCrewCount = (uData?.get("count") as? Number)?.toInt()
                ?: (uData?.get("cuantosBomberos") as? Number)?.toInt()
                ?: (uData?.get("count") as? String)?.toIntOrNull()
                ?: (uData?.get("cuantosBomberos") as? String)?.toIntOrNull()
                ?: activeTrip?.cuantosBomberos?.toIntOrNull()
                ?: if (assignedCrewPersons.isNotEmpty()) assignedCrewPersons.size else (unit?.numTripulantes ?: 0)

            val cardWidth by animateDpAsState(
                targetValue = if (isCardExpanded) 390.dp else 320.dp,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "CardWidthAnim"
            )

            AnimatedVisibility(
                visible = hasActiveTrip,
                enter = slideInHorizontally(
                    initialOffsetX = { -it - 150 },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                ) + fadeIn(
                    animationSpec = spring(stiffness = Spring.StiffnessLow)
                ),
                exit = slideOutHorizontally(
                    targetOffsetX = { -it - 150 },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeOut(
                    animationSpec = spring(stiffness = Spring.StiffnessMedium)
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 110.dp, top = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(cardWidth)
                        .animateContentSize(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        )
                        .carPlayCard(
                            cornerRadius = 20.dp,
                            bgAlpha = 0.92f,
                            accentBorderColor = if (isRetorno) Color(0xFFF59E0B) else if (is615Trayecto || is615EnSalud) Color(0xFF06B6D4) else if (dispatch != null) CarPlayColors.PrimaryRed else CarPlayColors.PrimaryBlue
                        )
                        .clickable {
                            if (isVehicleMoving && !isCardManuallyCollapsed) {
                                isCardTempExpandedWhileDriving = !isCardTempExpandedWhileDriving
                                if (isCardTempExpandedWhileDriving) {
                                    lastManualExpandTrigger = System.currentTimeMillis()
                                }
                            }
                        }
                        .padding(if (isCardExpanded) 16.dp else 12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(if (isCardExpanded) 10.dp else 6.dp)) {
                        // Encabezado de Despacho / Salida Extraordinaria
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Badge de Estado con brillo de vidrio
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                (if (isRetorno) Color(0xFFF59E0B) else if (is615Trayecto || is615EnSalud) Color(0xFF06B6D4) else if (dispatch != null) CarPlayColors.PrimaryRed else CarPlayColors.PrimaryBlue).copy(alpha = 0.25f),
                                                Color(0xFF030712).copy(alpha = 0.60f)
                                            )
                                        )
                                    )
                                    .border(
                                        width = 1.dp,
                                        brush = Brush.verticalGradient(
                                            listOf(
                                                Color.White.copy(alpha = 0.50f),
                                                (if (isRetorno) Color(0xFFF59E0B) else if (is615Trayecto || is615EnSalud) Color(0xFF06B6D4) else if (dispatch != null) CarPlayColors.PrimaryRed else CarPlayColors.PrimaryBlue).copy(alpha = 0.70f),
                                                Color.Transparent
                                            )
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(if (isRetorno) Color(0xFFF59E0B) else if (is615Trayecto || is615EnSalud) Color(0xFF06B6D4) else if (dispatch != null) CarPlayColors.PrimaryRed else CarPlayColors.PrimaryBlue, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isRetorno) "RETORNO A CUARTEL" else if (is615Trayecto || is615EnSalud) "TRASLADO ASISTENCIAL (6-15)" else if (dispatch != null) "DESPACHO ACTIVO" else "EMERGENCIA EN CURSO",
                                        color = if (isRetorno) Color(0xFFF59E0B) else if (is615Trayecto || is615EnSalud) Color(0xFF67E8F9) else if (dispatch != null) Color(0xFFFCA5A5) else Color(0xFF7DD3FC),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (displayHoraDespacho.isNotEmpty()) {
                                    Text(
                                        text = displayHoraDespacho,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // Botón Táctil Glass para Achicar / Expandir y guardar preferencia en SharedPreferences
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    Color.White.copy(alpha = 0.18f),
                                                    Color(0xFF0F172A).copy(alpha = 0.85f)
                                                )
                                            )
                                        )
                                        .border(
                                            width = 1.dp,
                                            brush = Brush.verticalGradient(
                                                listOf(
                                                    Color.White.copy(alpha = 0.60f),
                                                    CarPlayColors.AccentCyan.copy(alpha = 0.40f),
                                                    Color.Transparent
                                                )
                                            ),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            isCardManuallyCollapsed = !isCardManuallyCollapsed
                                            navPrefs.edit().putBoolean("pref_dispatch_card_collapsed", isCardManuallyCollapsed).apply()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isCardManuallyCollapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                                        contentDescription = if (isCardManuallyCollapsed) "Expandir tarjeta" else "Achicar tarjeta",
                                        tint = if (isCardManuallyCollapsed) CarPlayColors.AccentCyan else Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        // Clave de Emergencia / Salida (Destacada en 22sp Black cuando expandida, 17sp cuando compacta con transición suave)
                        val claveFontSize by animateFloatAsState(
                            targetValue = if (isCardExpanded) 22f else 17f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessLow
                            ),
                            label = "ClaveFontSizeAnim"
                        )
                        Text(
                            text = fullDisplayClave,
                            color = Color.White,
                            fontSize = claveFontSize.sp,
                            fontWeight = FontWeight.Black,
                            lineHeight = if (isCardExpanded) 24.sp else 20.sp
                        )

                        // Resumen compacto de ubicación mostrando SOLO la clave y la dirección (con animación suave)
                        AnimatedVisibility(
                            visible = !isCardExpanded,
                            enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessLow)) + expandVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)),
                            exit = fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium)) + shrinkVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.LocationOn,
                                    contentDescription = "Ubicación",
                                    tint = CarPlayColors.AccentCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = displayLugar.uppercase(),
                                    color = Color.White.copy(alpha = 0.95f),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Black,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // DETALLES COMPLETOS (SOLO CUANDO ESTÁ EXPANDIDA) CON ANIMACIÓN SUAVE
                        AnimatedVisibility(
                            visible = isCardExpanded,
                            enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessLow)) + expandVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)),
                            exit = fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium)) + shrinkVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium))
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                // Tarjeta Destacada de Dirección / Ubicación con Estilo Glass
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    Color(0xFF0C243B).copy(alpha = 0.88f),
                                                    Color(0xFF071828).copy(alpha = 0.94f),
                                                    Color(0xFF030A12).copy(alpha = 0.96f)
                                                )
                                            )
                                        )
                                        .border(
                                            width = 1.2.dp,
                                            brush = Brush.verticalGradient(
                                                listOf(
                                                    Color.White.copy(alpha = 0.55f),
                                                    CarPlayColors.AccentCyan.copy(alpha = 0.65f),
                                                    CarPlayColors.AccentCyan.copy(alpha = 0.15f),
                                                    Color.Transparent
                                                )
                                            ),
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(CarPlayColors.AccentCyan.copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.LocationOn,
                                        contentDescription = "Ubicación",
                                        tint = CarPlayColors.AccentCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "UBICACIÓN / DIRECCIÓN",
                                        color = CarPlayColors.AccentCyan.copy(alpha = 0.85f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 0.5.sp
                                    )
                                    Text(
                                        text = displayLugar.uppercase(),
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Black,
                                        lineHeight = 20.sp,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // Preinforme / Detalles Tácticos de la Emergencia con Estilo Glass
                        if (displayPreinforme.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color(0xFF2D1B06).copy(alpha = 0.88f),
                                                Color(0xFF1C1103).copy(alpha = 0.94f),
                                                Color(0xFF0A0601).copy(alpha = 0.96f)
                                            )
                                        )
                                    )
                                    .border(
                                        width = 1.2.dp,
                                        brush = Brush.verticalGradient(
                                            listOf(
                                                Color.White.copy(alpha = 0.55f),
                                                Color(0xFFF59E0B).copy(alpha = 0.70f),
                                                Color(0xFFF59E0B).copy(alpha = 0.15f),
                                                Color.Transparent
                                            )
                                        ),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text("📋", fontSize = 13.sp)
                                        Text(
                                            text = "PREINFORME DE EMERGENCIA",
                                            color = Color(0xFFF59E0B),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Black,
                                            letterSpacing = 0.5.sp
                                        )
                                    }
                                    Text(
                                        text = displayPreinforme.trim(),
                                        color = Color.White.copy(alpha = 0.95f),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }

                        // Solicitante / Alertante y Teléfono de contacto con Estilo Glass
                        val displaySolicitante = dispatch?.solicitante?.takeIf { it.isNotEmpty() } ?: ""
                        val displayPhone = dispatch?.telefono?.takeIf { it.isNotEmpty() } ?: ""
                        if (displaySolicitante.isNotEmpty() || displayPhone.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color(0xFF1E293B).copy(alpha = 0.75f),
                                                Color(0xFF0F172A).copy(alpha = 0.85f)
                                            )
                                        )
                                    )
                                    .border(
                                        width = 1.dp,
                                        brush = Brush.verticalGradient(
                                            listOf(
                                                Color.White.copy(alpha = 0.35f),
                                                Color(0xFF334155),
                                                Color.Transparent
                                            )
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                if (displaySolicitante.isNotEmpty()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Filled.Person,
                                            contentDescription = "Solicitante",
                                            tint = CarPlayColors.PrimaryAmber,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = displaySolicitante.uppercase(),
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                if (displayPhone.isNotEmpty()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Filled.Phone,
                                            contentDescription = "Teléfono",
                                            tint = CarPlayColors.PrimaryGreen,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = displayPhone,
                                            color = CarPlayColors.PrimaryGreen,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Black
                                        )
                                    }
                                }
                            }
                        }

                        // Carros Despachados (si aplica)
                        if (dispatch != null && dispatch.carros.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.DirectionsCar,
                                    contentDescription = "Carros",
                                    tint = CarPlayColors.TextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Carros: ${dispatch.carros}",
                                    color = CarPlayColors.TextSecondary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // TIEMPO ESTIMADO DE LLEGADA (ETA) & DISTANCIA con Estilo Glass
                        val currentEtaArrivalTime = remember(etaMinutes, viewModel.currentTimeString) {
                            if (etaMinutes > 0) {
                                val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, etaMinutes) }
                                SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time)
                            } else ""
                        }
                        if ((isWaitingDeparture || isEnTrayecto || isRetorno) && (etaMinutes > 0 || etaDistanceKm > 0f)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color(0xFF07273D).copy(alpha = 0.88f),
                                                Color(0xFF071524).copy(alpha = 0.94f),
                                                Color(0xFF02070D).copy(alpha = 0.96f)
                                            )
                                        )
                                    )
                                    .border(
                                        width = 1.2.dp,
                                        brush = Brush.verticalGradient(
                                            listOf(
                                                Color.White.copy(alpha = 0.55f),
                                                CarPlayColors.AccentCyan.copy(alpha = 0.70f),
                                                CarPlayColors.AccentCyan.copy(alpha = 0.15f),
                                                Color.Transparent
                                            )
                                        ),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(
                                        imageVector = Icons.Filled.Timer,
                                        contentDescription = "ETA",
                                        tint = CarPlayColors.AccentCyan,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column {
                                        Text(
                                            text = if (isRetorno) "RETORNO A CUARTEL" else "TIEMPO ESTIMADO",
                                            color = CarPlayColors.TextMuted,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "$etaMinutes min (${String.format(Locale.US, "%.1f", etaDistanceKm)} km)",
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Black
                                        )
                                    }
                                }
                                if (currentEtaArrivalTime.isNotEmpty()) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "HORA APROX.",
                                            color = CarPlayColors.TextMuted,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = currentEtaArrivalTime,
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Black
                                        )
                                    }
                                }
                            }
                        }

                        // FICHA DE PERSONAL ASIGNADO (CONDUCTOR, OBAC, TRIPULACIÓN)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.Black.copy(alpha = 0.40f),
                                            Color(0xFF0F172A).copy(alpha = 0.50f)
                                        )
                                    )
                                )
                                .border(
                                    width = 1.dp,
                                    brush = Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.25f),
                                            Color.White.copy(alpha = 0.05f)
                                        )
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("👤 CONDUCTOR: ", color = CarPlayColors.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(conductorName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🎖️ OBAC: ", color = CarPlayColors.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(obacName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            if (assignedCrewPersons.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Text("👥 DOTACIÓN: ", color = CarPlayColors.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        assignedCrewPersons.joinToString(", ") { "${it.idRadial} ${it.nombreBombero}".trim() },
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // Botón para Gestionar Tripulación con Estilo Glass (58dp altura / 16dp curva / 15sp Black)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(58.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            CarPlayColors.PrimaryBlue.copy(alpha = 0.95f),
                                            Color(0xFF0284C7).copy(alpha = 0.85f),
                                            Color(0xFF0369A1).copy(alpha = 0.95f)
                                        )
                                    )
                                )
                                .border(
                                    width = 1.3.dp,
                                    brush = Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.65f),
                                            CarPlayColors.AccentCyan,
                                            CarPlayColors.AccentCyan.copy(alpha = 0.20f),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable {
                                    selectedCrewMembers.clear()
                                    selectedCrewMembers.addAll(assignedCrewPersons)
                                    showCrewDialog = true
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.Group, contentDescription = "Tripulación", tint = Color.White, modifier = Modifier.size(20.dp))
                                Text(
                                    text = "TRIPULACIÓN ($assignedCrewCount BOMBEROS)",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                }
            }
        }
    }

            // =========================================================================
            // 7. BOTONES TÁCTICOS FLOTANTES SUPERIOR DERECHO (6-0 / 6-3 / 6-15 / 6-9 / 6-10 / 6-8: 58dp / 16dp / 15sp Black)
            // =========================================================================
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp, top = 16.dp)
            ) {
                if (isWaitingDeparture) {
                    val hasConductorAndObac = driverRad.isNotBlank() && obacRad.isNotBlank()
                    val bgGradient = if (hasConductorAndObac) {
                        listOf(Color(0xFF10B981), Color(0xFF059669), Color(0xFF047857))
                    } else {
                        listOf(Color(0xFFFBBF24), Color(0xFFEAB308), Color(0xFFCA8A04))
                    }
                    val borderTop = if (hasConductorAndObac) Color(0xFF34D399) else Color(0xFFFEF08A)

                    Box(
                        modifier = Modifier
                            .size(width = 92.dp, height = 58.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.verticalGradient(bgGradient))
                            .border(
                                width = 1.3.dp,
                                brush = Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.70f),
                                        borderTop,
                                        borderTop.copy(alpha = 0.25f),
                                        Color.Transparent
                                    )
                                ),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable {
                                if (hasConductorAndObac) {
                                    viewModel.markSalida60()
                                } else {
                                    Toast.makeText(context, "⚠️ DEBE ASIGNAR AL MENOS CONDUCTOR Y OBAC PARA DAR 6-0", Toast.LENGTH_LONG).show()
                                    showCrewDialog = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "6-0",
                            fontWeight = FontWeight.Black,
                            fontSize = 16.sp,
                            color = if (hasConductorAndObac) Color.White else Color.Black
                        )
                    }
                } else if (isEnTrayecto) {
                    // Unidad en trayecto -> Botón 6-3 flotante con Glass
                    Box(
                        modifier = Modifier
                            .size(width = 92.dp, height = 58.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF0284C7), Color(0xFF0369A1), Color(0xFF075985))
                                )
                            )
                            .border(
                                width = 1.3.dp,
                                brush = Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.70f),
                                        CarPlayColors.AccentCyan,
                                        CarPlayColors.AccentCyan.copy(alpha = 0.25f),
                                        Color.Transparent
                                    )
                                ),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable { viewModel.markLlegada63() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("6-3", color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
                    }
                } else if (isEnLugar) {
                    // Unidad en el lugar -> 6-15 y 6-9
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .size(width = 92.dp, height = 58.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(0xFFF59E0B), Color(0xFFD97706), Color(0xFFB45309))
                                    )
                                )
                                .border(
                                    width = 1.3.dp,
                                    brush = Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.75f),
                                            Color(0xFFFBBF24),
                                            Color(0xFFFBBF24).copy(alpha = 0.25f),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable { show615Dialog = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("6-15", fontWeight = FontWeight.Black, fontSize = 16.sp, color = Color.Black)
                        }

                        Box(
                            modifier = Modifier
                                .size(width = 92.dp, height = 58.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(0xFFEF4444), Color(0xFFDC2626), Color(0xFF991B1B))
                                    )
                                )
                                .border(
                                    width = 1.3.dp,
                                    brush = Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.70f),
                                            Color(0xFFF87171),
                                            Color(0xFFF87171).copy(alpha = 0.25f),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable { viewModel.markRetorno69() }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("6-9", color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
                        }
                    }
                } else if (is615Trayecto) {
                    // Traslado asistencial en trayecto -> Botones 6-3 (Llegada a Centro Asistencial) y 6-9 (Retorno a Cuartel)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .size(width = 92.dp, height = 58.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(0xFF0284C7), Color(0xFF0369A1), Color(0xFF075985))
                                    )
                                )
                                .border(
                                    width = 1.3.dp,
                                    brush = Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.70f),
                                            CarPlayColors.AccentCyan,
                                            CarPlayColors.AccentCyan.copy(alpha = 0.25f),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable { viewModel.markLlegada63Salud() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("6-3", color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
                        }

                        Box(
                            modifier = Modifier
                                .size(width = 92.dp, height = 58.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(0xFFEF4444), Color(0xFFDC2626), Color(0xFF991B1B))
                                    )
                                )
                                .border(
                                    width = 1.3.dp,
                                    brush = Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.70f),
                                            Color(0xFFF87171),
                                            Color(0xFFF87171).copy(alpha = 0.25f),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable { viewModel.markRetorno69() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("6-9", color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
                        }
                    }
                } else if (is615EnSalud) {
                    // Unidad en Centro Asistencial (Salud) -> 6-9 Retorno y 6-13
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .size(width = 92.dp, height = 58.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(0xFFEF4444), Color(0xFFDC2626), Color(0xFF991B1B))
                                    )
                                )
                                .border(
                                    width = 1.3.dp,
                                    brush = Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.70f),
                                            Color(0xFFF87171),
                                            Color(0xFFF87171).copy(alpha = 0.25f),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable { viewModel.markRetorno69() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("6-9", color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
                        }

                        Box(
                            modifier = Modifier
                                .size(width = 92.dp, height = 58.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(0xFF0D9488), Color(0xFF0F766E), Color(0xFF115E59))
                                    )
                                )
                                .border(
                                    width = 1.3.dp,
                                    brush = Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.70f),
                                            Color(0xFF2DD4BF),
                                            Color(0xFF2DD4BF).copy(alpha = 0.25f),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable { viewModel.markRetornoEmergencia613() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("6-13", color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
                        }
                    }
                } else if (isRetorno) {
                    // Unidad en retorno a cuartel -> Botón 6-10 Llegada a Cuartel con Glass
                    Box(
                        modifier = Modifier
                            .size(width = 92.dp, height = 58.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF0284C7), Color(0xFF0369A1), Color(0xFF075985))
                                )
                            )
                            .border(
                                width = 1.3.dp,
                                brush = Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.70f),
                                        CarPlayColors.AccentCyan,
                                        CarPlayColors.AccentCyan.copy(alpha = 0.25f),
                                        Color.Transparent
                                    )
                                ),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable {
                                viewModel.markLlegadaCuartel610()
                                showKmDialog = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("6-10", color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
                    }
                } else if (isEnCuartel) {
                    // Unidad en cuartel (post 6-10) -> Botón 6-8 Disponible que abre modal de kilometraje
                    Box(
                        modifier = Modifier
                            .size(width = 92.dp, height = 58.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF10B981), Color(0xFF059669), Color(0xFF047857))
                                )
                            )
                            .border(
                                width = 1.3.dp,
                                brush = Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.70f),
                                        Color(0xFF34D399),
                                        Color(0xFF34D399).copy(alpha = 0.25f),
                                        Color.Transparent
                                    )
                                ),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable {
                                showKmDialog = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("6-8", color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
                    }
                }
            }

            // =========================================================================
            // 7.5 BOTONES DE SOLICITUD OPERATIVA FLOTANTES (12-10 CONDUCTOR / 6-6 PERSONAL: 58dp)
            // =========================================================================
            if (isWaitingDeparture) {
                val condTs = (uData?.get("solicitudConductorTimestamp") as? Number)?.toLong() ?: unit?.solicitudConductorTimestamp ?: 0L
                val persTs = (uData?.get("solicitudPersonalTimestamp") as? Number)?.toLong() ?: unit?.solicitudPersonalTimestamp ?: 0L
                val condRemainingSec = ((condTs + 60000L - nowTickerTs) / 1000).coerceAtLeast(0)
                val persRemainingSec = ((persTs + 60000L - nowTickerTs) / 1000).coerceAtLeast(0)

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 110.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // BOTÓN 12-10: SOLICITAR CONDUCTOR (92dp x 58dp)
                    Box(
                        modifier = Modifier
                            .size(width = 92.dp, height = 58.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (condRemainingSec > 0) {
                                    Brush.verticalGradient(
                                        listOf(
                                            Color(0xFF334155).copy(alpha = 0.85f),
                                            Color(0xFF1E293B).copy(alpha = 0.90f)
                                        )
                                    )
                                } else {
                                    Brush.verticalGradient(
                                        listOf(
                                            Color(0xFFEF4444),
                                            Color(0xFFDC2626),
                                            Color(0xFF991B1B)
                                        )
                                    )
                                }
                            )
                            .border(
                                width = 1.3.dp,
                                brush = Brush.verticalGradient(
                                    if (condRemainingSec > 0) {
                                        listOf(
                                            Color.White.copy(alpha = 0.35f),
                                            Color(0xFFEAB308).copy(alpha = 0.50f),
                                            Color.Transparent
                                        )
                                    } else {
                                        listOf(
                                            Color.White.copy(alpha = 0.75f),
                                            Color(0xFFF87171),
                                            Color(0xFFF87171).copy(alpha = 0.25f),
                                            Color.Transparent
                                        )
                                    }
                                ),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable(enabled = condRemainingSec <= 0) {
                                viewModel.request1210Conductor()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (condRemainingSec > 0) "12-10 (${condRemainingSec}s)" else "12-10",
                            color = if (condRemainingSec > 0) Color(0xFFFDE047) else Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = if (condRemainingSec > 0) 12.sp else 16.sp
                        )
                    }

                    // BOTÓN 6-6: SOLICITAR PERSONAL (92dp x 58dp)
                    Box(
                        modifier = Modifier
                            .size(width = 92.dp, height = 58.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (persRemainingSec > 0) {
                                    Brush.verticalGradient(
                                        listOf(
                                            Color(0xFF334155).copy(alpha = 0.85f),
                                            Color(0xFF1E293B).copy(alpha = 0.90f)
                                        )
                                    )
                                } else {
                                    Brush.verticalGradient(
                                        listOf(
                                            Color(0xFF0284C7),
                                            Color(0xFF0369A1),
                                            Color(0xFF075985)
                                        )
                                    )
                                }
                            )
                            .border(
                                width = 1.3.dp,
                                brush = Brush.verticalGradient(
                                    if (persRemainingSec > 0) {
                                        listOf(
                                            Color.White.copy(alpha = 0.35f),
                                            Color(0xFF38BDF8).copy(alpha = 0.50f),
                                            Color.Transparent
                                        )
                                    } else {
                                        listOf(
                                            Color.White.copy(alpha = 0.70f),
                                            CarPlayColors.AccentCyan,
                                            CarPlayColors.AccentCyan.copy(alpha = 0.25f),
                                            Color.Transparent
                                        )
                                    }
                                ),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable(enabled = persRemainingSec <= 0) {
                                viewModel.request66Personal()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (persRemainingSec > 0) "6-6 (${persRemainingSec}s)" else "6-6",
                            color = if (persRemainingSec > 0) Color(0xFF7DD3FC) else Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = if (persRemainingSec > 0) 12.sp else 16.sp
                        )
                    }
                }
            }
        }

        // =========================================================================
        // 6.5 MODAL TECLADO NUMÉRICO TÁCTICO PARA INGRESO DE KILOMETRAJE FINAL (6-8)
        // =========================================================================
        TacticalKmDialog(
            show = showKmDialog,
            unit = unit,
            viewModel = viewModel,
            onDismiss = { showKmDialog = false }
        )

        // =========================================================================
        // 7. MODAL GESTIÓN DE TRIPULACIÓN Y SOLICITUD DE GPS INDIVIDUAL
        // =========================================================================
        TacticalCrewDialog(
            show = showCrewDialog,
            dispatch = dispatch,
            unit = unit,
            unitLabel = unitLabel,
            viewModel = viewModel,
            nowTickerTs = nowTickerTs,
            onDismiss = { showCrewDialog = false }
        )

        // =========================================================================
        // 8. MODAL DESTINO TRASLADO ASISTENCIAL 6-15 (CESFAM PLACILLA / HOSPITAL SNFDO)
        // =========================================================================
        Tactical615DestinationDialog(
            show = show615Dialog,
            unitLabel = unitLabel,
            currentVehicleLat = liveLat,
            currentVehicleLng = liveLng,
            viewModel = viewModel,
            onDismiss = { show615Dialog = false }
        )
    }
}

@Composable
private fun CarPlayDockSquareButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color = CarPlayColors.PrimaryBlue,
    onClick: () -> Unit
) {
    val bgBrush = if (isActive) {
        Brush.verticalGradient(
            listOf(
                activeColor.copy(alpha = 0.92f),
                activeColor.copy(alpha = 0.75f),
                Color(0xFF030712).copy(alpha = 0.90f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.12f),
                Color(0xFF1E293B).copy(alpha = 0.60f),
                Color(0xFF0F172A).copy(alpha = 0.80f)
            )
        )
    }

    val borderBrush = if (isActive) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.70f),
                activeColor,
                activeColor.copy(alpha = 0.25f),
                Color.Transparent
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.35f),
                Color.White.copy(alpha = 0.08f),
                Color.Transparent
            )
        )
    }

    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bgBrush)
            .border(1.2.dp, borderBrush, RoundedCornerShape(16.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(26.dp)
        )
    }
}
