package com.sisbom.sisbomcar

import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val dispatch = viewModel.activeDispatch
    val unit = viewModel.currentUnitVehicle
    val unitLabel = viewModel.selectedUnitLabel.ifEmpty { viewModel.selectedUnitId }

    // Estado 0-8 / 0-9 / 6-13 / 6-14
    val rawEstado = unit?.estado?.trim() ?: "0-9"
    val isFueraServicio = rawEstado == "0-8" || rawEstado == "0"
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

    // Modal para 6-13 / 6-14
    var showSpecialExitDialog by remember { mutableStateOf(false) }
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

        val hasActiveDispatch = dispatch != null && dispatch.operadorFinal.isEmpty()
        val hasActiveSalida = activeTrip != null && activeTrip.hora68.isEmpty() && activeTrip.estadoMovil != "en cuartel"

        val isRetornoForMap = (uEstadoForMap == "retorno" || uEstadoForMap == "6-9" || (hora69ForMap.isNotEmpty() && hora610ForMap.isEmpty()))
        val isEnCuartelForMap = (!hasActiveDispatch && !hasActiveSalida) || uEstadoForMap == "finalizado" || uEstadoForMap == "6-8" || hora68ForMap.isNotEmpty()

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

        // Coordenadas del Cuartel General de Bomberos (Miraflores 697, Placilla)
        val CUARTEL_GENERAL_LAT = -34.636743
        val CUARTEL_GENERAL_LNG = -71.119915

        // Si la unidad está en cuartel (6-8 / finalizada o sin despacho activo), no se traza ruta
        // Si la unidad está en retorno (6-9), la ruta apunta hacia el Cuartel General
        // Si la unidad está despachada, la ruta apunta directamente hacia la emergencia
        val mapTargetLat = when {
            isEnCuartelForMap -> null
            isRetornoForMap -> CUARTEL_GENERAL_LAT
            else -> dispatch?.lat ?: geocodedLat
        }
        val mapTargetLng = when {
            isEnCuartelForMap -> null
            isRetornoForMap -> CUARTEL_GENERAL_LNG
            else -> dispatch?.lng ?: geocodedLng
        }
        val mapTargetClave = when {
            isEnCuartelForMap -> ""
            isRetornoForMap -> "6-9 RETORNO CUARTEL"
            else -> (dispatch?.clave ?: "")
        }
        val mapDispatchId = when {
            isEnCuartelForMap -> null
            isRetornoForMap -> "retorno_${dispatch?.idServicio ?: activeTrip?.idSalida ?: "cuartel"}"
            else -> dispatch?.idServicio
        }

        CarPlayTacticalMap(
            dispatchId = mapDispatchId,
            emergencyLat = mapTargetLat,
            emergencyLng = mapTargetLng,
            emergencyClave = mapTargetClave,
            vehicleLat = liveLat,
            vehicleLng = liveLng,
            vehicleHeading = liveHeading,
            vehicleSpeedKmH = liveSpeed,
            unitStatusInDispatch = unitStatusInDispatch,
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
        // 2. BARRA LATERAL IZQUIERDA FLOTANTE CARPLAY (FLOATING DOCK CARD)
        // =========================================================================
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp)
                .width(82.dp)
                .fillMaxHeight()
                .carPlayCard(cornerRadius = 24.dp, bgAlpha = 0.92f)
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
                    // Botón Unidad (ej. B-1) estándar (58dp x 58dp)
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(statusColor.copy(alpha = 0.2f))
                            .border(1.5.dp, statusColor, RoundedCornerShape(16.dp))
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

                // SECCIÓN INFERIOR: VELOCÍMETRO DIGITAL (58dp x 58dp)
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF070F1E).copy(alpha = 0.95f))
                        .border(1.dp, CarPlayColors.AccentCyan.copy(alpha = 0.35f), RoundedCornerShape(16.dp)),
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
        if (showSpecialExitDialog) {
            Dialog(
                onDismissRequest = { showSpecialExitDialog = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .imePadding(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .width(460.dp)
                            .carPlayCard(cornerRadius = 24.dp, bgAlpha = 0.98f)
                            .padding(20.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Header Salida
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (specialExitType == "6-14") Icons.Filled.LocalGasStation else Icons.Filled.Route,
                                        contentDescription = null,
                                        tint = if (specialExitType == "6-14") CarPlayColors.PrimaryAmber else CarPlayColors.PrimaryBlue,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = if (specialExitType == "6-14") "6-14 CARGA DE COMBUSTIBLE" else "6-13 SE DIRIGE A (TRÁMITES)",
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Black
                                        )
                                        Text(
                                            text = "Unidad $unitLabel",
                                            color = CarPlayColors.TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = { showSpecialExitDialog = false },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "Cerrar", tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }

                            // Selector de tipo (6-13 o 6-14)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (specialExitType == "6-13") CarPlayColors.PrimaryBlue else Color.White.copy(alpha = 0.08f))
                                        .clickable {
                                            specialExitType = "6-13"
                                            specialLugar = ""
                                            specialMotivo = ""
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("6-13 TRÁMITES", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (specialExitType == "6-14") CarPlayColors.PrimaryAmber else Color.White.copy(alpha = 0.08f))
                                        .clickable {
                                            specialExitType = "6-14"
                                            specialLugar = "SERVICENTRO"
                                            specialMotivo = "CARGA DE COMBUSTIBLE"
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("6-14 COMBUSTIBLE", color = if (specialExitType == "6-14") Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            // Inputs
                            OutlinedTextField(
                                value = specialLugar,
                                onValueChange = { specialLugar = it },
                                label = { Text("Lugar de Destino *") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CarPlayColors.AccentCyan,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )

                            OutlinedTextField(
                                value = specialMotivo,
                                onValueChange = { specialMotivo = it },
                                label = { Text("Motivo / Informe *") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CarPlayColors.AccentCyan,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = specialConductor,
                                    onValueChange = { specialConductor = it },
                                    label = { Text("Conductor") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = CarPlayColors.AccentCyan,
                                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    )
                                )

                                OutlinedTextField(
                                    value = specialObac,
                                    onValueChange = { specialObac = it },
                                    label = { Text("A Cargo (OBAC)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = CarPlayColors.AccentCyan,
                                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    )
                                )

                                OutlinedTextField(
                                    value = specialTripulantes,
                                    onValueChange = { specialTripulantes = it },
                                    label = { Text("Tripulantes") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = CarPlayColors.AccentCyan,
                                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    )
                                )
                            }

                            if (specialError.isNotEmpty()) {
                                Text(specialError, color = CarPlayColors.PrimaryRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { showSpecialExitDialog = false },
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("CANCELAR", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        if (specialLugar.isBlank() || specialMotivo.isBlank()) {
                                            specialError = "Lugar y Motivo son obligatorios."
                                            return@Button
                                        }

                                        val resolvedConductor = viewModel.personalList.find {
                                            it.idRadial.equals(specialConductor.trim(), ignoreCase = true) || it.idRegistro == specialConductor.trim()
                                        }?.let { "${it.idRadial} - ${it.nombreBombero}" } ?: specialConductor.trim().uppercase()

                                        val resolvedObac = viewModel.personalList.find {
                                            it.idRadial.equals(specialObac.trim(), ignoreCase = true) || it.idRegistro == specialObac.trim()
                                        }?.let { "${it.idRadial} - ${it.nombreBombero}" } ?: specialObac.trim().uppercase()

                                        viewModel.registerSpecialExit613_614(
                                            type = specialExitType,
                                            lugar = specialLugar.trim().uppercase(),
                                            motivo = specialMotivo.trim().uppercase(),
                                            conductor = resolvedConductor,
                                            obac = resolvedObac,
                                            tripulantes = specialTripulantes.trim().ifEmpty { "0" },
                                            onComplete = { success ->
                                                if (success) {
                                                    showSpecialExitDialog = false
                                                }
                                            }
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (specialExitType == "6-14") CarPlayColors.PrimaryAmber else CarPlayColors.PrimaryBlue
                                    ),
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = "CONFIRMAR SALIDA",
                                        color = if (specialExitType == "6-14") Color.Black else Color.White,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 5. TARJETA FLOTANTE DE CALLE / UBICACIÓN ACTUAL DEL CARRO
        // =========================================================================
        if (currentStreetName.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 110.dp, bottom = 16.dp)
                    .carPlayCard(cornerRadius = 14.dp, bgAlpha = 0.94f)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = "Calle",
                        tint = CarPlayColors.AccentCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = currentStreetName.uppercase(),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }

        var kmFinalInput by remember { mutableStateOf("") }
        var showCrewDialog by remember { mutableStateOf(false) }
        var isEditingCrewMode by remember { mutableStateOf(false) }

        val hasActiveTrip = dispatch != null || activeTrip != null || (unit?.enServicio != null && unit.enServicio != "0" && unit.enServicio != "0-8" && unit.enServicio.isNotEmpty())

        if (hasActiveTrip) {
            val unitEntry = dispatch?.unidades?.entries?.find {
                it.key.replace("-", "").equals(unitLabel.replace("-", ""), ignoreCase = true)
            }
            val uData = unitEntry?.value
            val uEstado = ((uData?.get("estado") ?: uData?.get("status") ?: activeTrip?.estadoMovil ?: unit?.estado) as? String ?: "").lowercase()
            val hora60 = (uData?.get("hora60") ?: uData?.get("salida60At") ?: uData?.get("horaSalida") ?: activeTrip?.hora60)?.toString()?.trim() ?: ""
            val hora63 = (uData?.get("hora63") ?: uData?.get("llegada63At") ?: uData?.get("horaLlegada") ?: activeTrip?.hora63)?.toString()?.trim() ?: ""
            val hora69 = (uData?.get("hora69") ?: uData?.get("retorno69At") ?: uData?.get("horaRetorno") ?: activeTrip?.hora69)?.toString()?.trim() ?: ""
            val hora610 = (uData?.get("hora610") ?: uData?.get("llegada610At") ?: uData?.get("horaCuartel") ?: activeTrip?.hora610)?.toString()?.trim() ?: ""
            val hora68 = (uData?.get("hora68") ?: uData?.get("disponible68At") ?: activeTrip?.hora68)?.toString()?.trim() ?: ""

            val isEnCuartel = uEstado == "finalizado" || uEstado == "6-10" || uEstado.contains("cuartel") || (hora610.isNotEmpty() && hora68.isEmpty())
            val isRetorno = !isEnCuartel && (uEstado == "retorno" || uEstado == "6-9" || uEstado.contains("retorno") || (hora69.isNotEmpty() && hora610.isEmpty()))
            val isEnLugar = !isEnCuartel && !isRetorno && (uEstado == "en_lugar" || uEstado == "6-3" || uEstado.contains("lugar") || (hora63.isNotEmpty() && hora69.isEmpty()))
            val isEnTrayecto = !isEnCuartel && !isRetorno && !isEnLugar && (uEstado == "en_trayecto" || uEstado == "6-0" || uEstado.contains("trayecto") || (hora60.isNotEmpty() && hora63.isEmpty()))
            val isWaitingDeparture = !isEnCuartel && !isRetorno && !isEnLugar && !isEnTrayecto

            val displayClave: String = if (isRetorno) "6-9 RETORNO A CUARTEL" else (dispatch?.clave?.takeIf { it.isNotEmpty() } ?: activeTrip?.clave?.takeIf { it.isNotEmpty() } ?: unit?.enServicio?.takeIf { it.isNotEmpty() } ?: "6-13")
            val displayHoraDespacho: String = dispatch?.horaDespacho?.takeIf { it.isNotEmpty() } ?: activeTrip?.hora60?.takeIf { it.isNotEmpty() } ?: ""
            val displayLugar: String = if (isRetorno) "CUARTEL GENERAL - MIRAFLORES 697, PLACILLA" else (dispatch?.lugar?.takeIf { it.isNotEmpty() } ?: activeTrip?.lugar?.takeIf { it.isNotEmpty() } ?: unit?.notas?.takeIf { it.isNotEmpty() } ?: "Sin dirección")
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

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 110.dp, top = 16.dp)
                    .width(380.dp)
                    .carPlayCard(cornerRadius = 20.dp, bgAlpha = 0.94f)
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Encabezado de Despacho / Salida Extraordinaria
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(if (dispatch != null) CarPlayColors.PrimaryRed else CarPlayColors.PrimaryBlue, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isRetorno) "RETORNO A CUARTEL" else if (dispatch != null) "DESPACHO ACTIVO" else "SALIDA $displayClave",
                                color = if (isRetorno) Color(0xFFF59E0B) else if (dispatch != null) CarPlayColors.PrimaryRed else CarPlayColors.PrimaryBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (displayIdSalida.isNotEmpty()) {
                                Text(
                                    text = "#$displayIdSalida",
                                    color = CarPlayColors.TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (displayHoraDespacho.isNotEmpty()) {
                                Text(
                                    text = displayHoraDespacho,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Clave de Emergencia / Salida
                    Text(
                        text = displayClave,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = 24.sp
                    )

                    // Dirección / Lugar
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.LocationOn,
                            contentDescription = "Lugar",
                            tint = CarPlayColors.AccentCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = displayLugar,
                            color = CarPlayColors.AccentCyan,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Preinforme / Motivo
                    if (displayPreinforme.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            Text(
                                text = "ℹ️ $displayPreinforme",
                                color = Color.White,
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Solicitante / Alertante y Teléfono de contacto
                    val displaySolicitante = dispatch?.solicitante?.takeIf { it.isNotEmpty() } ?: ""
                    val displayPhone = dispatch?.telefono?.takeIf { it.isNotEmpty() } ?: ""
                    if (displaySolicitante.isNotEmpty() || displayPhone.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1E293B).copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (displaySolicitante.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.Person,
                                        contentDescription = "Solicitante",
                                        tint = CarPlayColors.PrimaryAmber,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = displaySolicitante.uppercase(),
                                        color = Color.White,
                                        fontSize = 11.sp,
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
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = displayPhone,
                                        color = CarPlayColors.PrimaryGreen,
                                        fontSize = 11.sp,
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
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Carros: ${dispatch.carros}",
                                color = CarPlayColors.TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // TIEMPO ESTIMADO DE LLEGADA (ETA) & DISTANCIA (para despachos en camino o retorno a cuartel)
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
                                .background(Color(0xFF071326).copy(alpha = 0.95f), RoundedCornerShape(12.dp))
                                .border(1.dp, CarPlayColors.AccentCyan.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
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
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "$etaMinutes min (${String.format(Locale.US, "%.1f", etaDistanceKm)} km)",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                            if (currentEtaArrivalTime.isNotEmpty()) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "HORA APROX.",
                                        color = CarPlayColors.TextMuted,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = currentEtaArrivalTime,
                                        color = Color.White,
                                        fontSize = 14.sp,
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
                            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("👤 CONDUCTOR: ", color = CarPlayColors.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(conductorName, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🎖️ OBAC: ", color = CarPlayColors.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(obacName, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        if (assignedCrewPersons.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.Top) {
                                Text("👥 DOTACIÓN: ", color = CarPlayColors.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    assignedCrewPersons.joinToString(", ") { "${it.idRadial} ${it.nombreBombero}".trim() },
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Botón para Gestionar Tripulación
                    Button(
                        onClick = {
                            selectedCrewMembers.clear()
                            selectedCrewMembers.addAll(assignedCrewPersons)
                            showCrewDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CarPlayColors.PrimaryBlue.copy(alpha = 0.85f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.Group, contentDescription = "Tripulación", modifier = Modifier.size(16.dp))
                            Text(
                                text = "TRIPULACIÓN ($assignedCrewCount BOMBEROS)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // 7. BOTÓN TÁCTICO FLOTANTE SUPERIOR DERECHO (6-0 / 6-3 / 6-15 / 6-9 / 6-10 / 6-8)
            // =========================================================================
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp, top = 16.dp)
                    .carPlayCard(cornerRadius = 18.dp, bgAlpha = 0.95f)
                    .padding(10.dp)
            ) {
                if (isWaitingDeparture) {
                    val hasConductorAndObac = driverRad.isNotBlank() && obacRad.isNotBlank()
                    Button(
                        onClick = {
                            if (hasConductorAndObac) {
                                viewModel.markSalida60()
                            } else {
                                Toast.makeText(context, "⚠️ DEBE ASIGNAR AL MENOS CONDUCTOR Y OBAC PARA DAR 6-0", Toast.LENGTH_LONG).show()
                                isEditingCrewMode = true
                                showCrewDialog = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (hasConductorAndObac) CarPlayColors.PrimaryGreen else Color(0xFFEAB308)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(54.dp).padding(horizontal = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                imageVector = if (hasConductorAndObac) Icons.Filled.DirectionsCar else Icons.Filled.Warning,
                                contentDescription = "6-0",
                                tint = if (hasConductorAndObac) Color.White else Color.Black,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = if (hasConductorAndObac) "🚒 6-0 EN TRAYECTO" else "⚠️ 6-0 (FALTA COND/OBAC)",
                                fontWeight = FontWeight.Black,
                                fontSize = 14.sp,
                                color = if (hasConductorAndObac) Color.White else Color.Black
                            )
                        }
                    }
                } else if (isEnTrayecto) {
                    // Unidad en trayecto -> Botón 6-3 en el lugar grande arriba a la derecha
                    Button(
                        onClick = { viewModel.markLlegada63() },
                        colors = ButtonDefaults.buttonColors(containerColor = CarPlayColors.PrimaryBlue),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(54.dp).padding(horizontal = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                imageVector = Icons.Filled.LocationOn,
                                contentDescription = "6-3",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                            Text("📍 6-3 EN EL LUGAR", fontWeight = FontWeight.Black, fontSize = 14.sp)
                        }
                    }
                } else if (isEnLugar && (uData?.get("is615") as? Boolean) != true) {
                    // Unidad en el lugar -> 6-15 y 6-9
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.markTraslado615() },
                            colors = ButtonDefaults.buttonColors(containerColor = CarPlayColors.PrimaryAmber),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("🏥 6-15 TRASLADO", fontWeight = FontWeight.Black, fontSize = 12.sp, color = Color.Black)
                        }
                        Button(
                            onClick = { viewModel.markRetorno69() },
                            colors = ButtonDefaults.buttonColors(containerColor = CarPlayColors.PrimaryRed),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("🔄 6-9 RETORNO", fontWeight = FontWeight.Black, fontSize = 12.sp)
                        }
                    }
                } else if (!isEnLugar && (uData?.get("is615") as? Boolean) == true) {
                    // Traslado asistencial en trayecto -> Botón 6-3 SALUD
                    Button(
                        onClick = { viewModel.markLlegada63Salud() },
                        colors = ButtonDefaults.buttonColors(containerColor = CarPlayColors.PrimaryBlue),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(54.dp).padding(horizontal = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                imageVector = Icons.Filled.LocationOn,
                                contentDescription = "6-3 Salud",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                            Text("🏥 6-3 SALUD", fontWeight = FontWeight.Black, fontSize = 14.sp)
                        }
                    }
                } else if (isEnLugar && (uData?.get("is615") as? Boolean) == true) {
                    // Unidad en Centro Asistencial (Salud) -> 6-9 Retorno y 6-13 10-X
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.markRetorno69() },
                            colors = ButtonDefaults.buttonColors(containerColor = CarPlayColors.PrimaryRed),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("🔄 6-9 RETORNO", fontWeight = FontWeight.Black, fontSize = 12.sp)
                        }
                        Button(
                            onClick = { viewModel.markRetornoEmergencia613() },
                            colors = ButtonDefaults.buttonColors(containerColor = CarPlayColors.PrimaryTeal),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("🚒 6-13 10-X", fontWeight = FontWeight.Black, fontSize = 12.sp)
                        }
                    }
                } else if (isRetorno) {
                    // Unidad en retorno a cuartel -> Botón 6-10 Llegada a Cuartel
                    Button(
                        onClick = { viewModel.markLlegadaCuartel610() },
                        colors = ButtonDefaults.buttonColors(containerColor = CarPlayColors.PrimaryBlue),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(54.dp).padding(horizontal = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                imageVector = Icons.Filled.DirectionsCar,
                                contentDescription = "6-10",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                            Text("🚒 6-10 EN CUARTEL", fontWeight = FontWeight.Black, fontSize = 14.sp)
                        }
                    }
                } else if (isEnCuartel) {
                    // Unidad en cuartel (post 6-10) -> Ingreso de Kilometraje y 6-8 Disponible
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = kmFinalInput,
                            onValueChange = { kmFinalInput = it },
                            placeholder = { Text("KM", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.width(90.dp).height(50.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CarPlayColors.AccentCyan,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        Button(
                            onClick = { viewModel.markDisponible68(kmFinalInput) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(50.dp)
                        ) {
                            Text("✅ 6-8 DISPONIBLE", fontWeight = FontWeight.Black, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 7. MODAL GESTIÓN DE TRIPULACIÓN Y SOLICITUD DE GPS INDIVIDUAL
        // =========================================================================
        if (showCrewDialog) {
            val unitEntry = dispatch?.unidades?.entries?.find {
                it.key.replace("-", "").equals(unitLabel.replace("-", ""), ignoreCase = true)
            }
            val uData = unitEntry?.value
            val assignedCount = (uData?.get("count") as? Number)?.toInt()
                ?: (uData?.get("cuantosBomberos") as? Number)?.toInt()
                ?: (uData?.get("count") as? String)?.toIntOrNull()
                ?: (uData?.get("cuantosBomberos") as? String)?.toIntOrNull()
                ?: (unit?.numTripulantes ?: 0)

            val maxTripulantes = remember(unit, assignedCount) {
                if (assignedCount > 0) assignedCount
                else (unit?.numTripulantes?.takeIf { it > 0 } ?: 6)
            }

            val tripulantesDetalle = (uData?.get("tripulantesDetalle") as? List<*>)
            val assignedCrewPersons = remember(tripulantesDetalle, viewModel.personalList, uData) {
                val list = mutableListOf<PersonItem>()
                if (!tripulantesDetalle.isNullOrEmpty()) {
                    tripulantesDetalle.forEach { item ->
                        when (item) {
                            is Map<*, *> -> {
                                val idReg = item["idRegistro"]?.toString() ?: ""
                                val rad = item["idRadial"]?.toString() ?: ""
                                val nom = item["nombre"]?.toString() ?: item["nombreBombero"]?.toString() ?: ""
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

            val selectedCrewMembers = remember(assignedCrewPersons) {
                mutableStateListOf<PersonItem>().apply { addAll(assignedCrewPersons) }
            }
            var crewSearchQuery by remember { mutableStateOf("") }
            val requestedGpsIds = remember { mutableStateMapOf<String, Long>() }

            val driverRad = (uData?.get("driverRad") ?: uData?.get("conductor") ?: activeTrip?.conductor)?.toString()?.trim() ?: ""
            val obacRad = (uData?.get("obacRad") ?: uData?.get("obac") ?: activeTrip?.obac)?.toString()?.trim() ?: ""

            // Estado de edición de dotación táctica
            var inputDriverRad by remember(driverRad) { mutableStateOf(driverRad) }
            var inputObacRad by remember(obacRad) { mutableStateOf(obacRad) }
            var inputCrewCount by remember {
                val raw = (uData?.get("count") ?: uData?.get("cuantosBomberos") ?: activeTrip?.cuantosBomberos)?.toString()?.trim() ?: ""
                mutableStateOf(if (raw.isNotEmpty()) raw else "0")
            }

            val conductorPerson = remember(inputDriverRad, viewModel.personalList) {
                if (inputDriverRad.isBlank()) null
                else viewModel.personalList.find {
                    it.idRadial.equals(inputDriverRad.trim(), ignoreCase = true) ||
                    it.idRegistro == inputDriverRad.trim() ||
                    inputDriverRad.startsWith("${it.idRadial} -") ||
                    inputDriverRad.contains(it.nombreBombero, ignoreCase = true)
                }
            }
            val resolvedDriverPerson = conductorPerson

            val obacPerson = remember(inputObacRad, viewModel.personalList) {
                if (inputObacRad.isBlank()) null
                else viewModel.personalList.find {
                    it.idRadial.equals(inputObacRad.trim(), ignoreCase = true) ||
                    it.idRegistro == inputObacRad.trim() ||
                    inputObacRad.startsWith("${it.idRadial} -") ||
                    inputObacRad.contains(it.nombreBombero, ignoreCase = true)
                }
            }
            val resolvedObacPerson = obacPerson

            // Excluir conductor y OBAC de la lista de tripulantes
            val excludedIds = remember(resolvedDriverPerson, resolvedObacPerson, inputDriverRad, inputObacRad) {
                setOfNotNull(
                    resolvedDriverPerson?.idRegistro,
                    resolvedObacPerson?.idRegistro,
                    inputDriverRad.trim().takeIf { it.isNotEmpty() },
                    inputObacRad.trim().takeIf { it.isNotEmpty() }
                )
            }

            val filteredPersonal = remember(crewSearchQuery, viewModel.personalList, excludedIds) {
                viewModel.personalList.filter { p ->
                    crewSearchQuery.isBlank() ||
                    p.nombreBombero.contains(crewSearchQuery, ignoreCase = true) ||
                    p.idRadial.contains(crewSearchQuery, ignoreCase = true) ||
                    p.compania.contains(crewSearchQuery, ignoreCase = true)
                }
            }

            Dialog(
                onDismissRequest = { showCrewDialog = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .imePadding(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .width(580.dp)
                            .fillMaxHeight(0.92f)
                            .carPlayCard(cornerRadius = 24.dp, bgAlpha = 0.98f)
                            .padding(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Header
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = if (isEditingCrewMode) "➕ ASIGNAR DOTACIÓN Y TRIPULACIÓN" else "👥 DOTACIÓN A BORDO",
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Text(
                                        text = if (isEditingCrewMode) "Conductor, OBAC e identificación de tripulantes" else "Unidad $unitLabel • ${selectedCrewMembers.size + (if (conductorPerson != null) 1 else 0) + (if (obacPerson != null) 1 else 0)} Ocupantes",
                                        color = CarPlayColors.AccentCyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                IconButton(
                                    onClick = { showCrewDialog = false },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "Cerrar", tint = Color.White)
                                }
                            }

                            if (!isEditingCrewMode) {
                                // ==========================================
                                // VISTA 1: LISTADO DE DOTACIÓN ACTUAL + BOTONES PEDIR GPS
                                // ==========================================
                                LazyColumn(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // 1. CONDUCTOR
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(Color(0xFF0284C7).copy(alpha = 0.15f))
                                                .border(1.dp, Color(0xFF0284C7).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                                .padding(12.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    Box(
                                                        modifier = Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF0284C7)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text("🛞", fontSize = 16.sp)
                                                    }
                                                    Column {
                                                        Text(
                                                            text = conductorPerson?.nombreBombero ?: driverRad.ifEmpty { "Conductor sin asignar" },
                                                            color = Color.White,
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Black
                                                        )
                                                        Text("CONDUCTOR • ${conductorPerson?.idRadial ?: driverRad}", color = CarPlayColors.AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }

                                                if (conductorPerson != null) {
                                                    val isGpsReq = requestedGpsIds.containsKey(conductorPerson.idRegistro)
                                                    Button(
                                                        onClick = {
                                                            viewModel.solicitarGpsBombero(conductorPerson.idRegistro)
                                                            requestedGpsIds[conductorPerson.idRegistro] = System.currentTimeMillis()
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = if (isGpsReq) CarPlayColors.PrimaryGreen else Color.White.copy(alpha = 0.15f)),
                                                        shape = RoundedCornerShape(8.dp),
                                                        modifier = Modifier.height(34.dp)
                                                    ) {
                                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                            Icon(Icons.Filled.GpsFixed, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                                            Text(if (isGpsReq) "GPS ACTIVO" else "PEDIR GPS", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // 2. OBAC
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(Color(0xFFF59E0B).copy(alpha = 0.15f))
                                                .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                                .padding(12.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    Box(
                                                        modifier = Modifier.size(34.dp).clip(CircleShape).background(Color(0xFFF59E0B)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text("🎖️", fontSize = 16.sp)
                                                    }
                                                    Column {
                                                        Text(
                                                            text = obacPerson?.nombreBombero ?: obacRad.ifEmpty { "OBAC sin asignar" },
                                                            color = Color.White,
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Black
                                                        )
                                                        Text("A CARGO (OBAC) • ${obacPerson?.idRadial ?: obacRad}", color = CarPlayColors.PrimaryAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }

                                                if (obacPerson != null) {
                                                    val isGpsReq = requestedGpsIds.containsKey(obacPerson.idRegistro)
                                                    Button(
                                                        onClick = {
                                                            viewModel.solicitarGpsBombero(obacPerson.idRegistro)
                                                            requestedGpsIds[obacPerson.idRegistro] = System.currentTimeMillis()
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = if (isGpsReq) CarPlayColors.PrimaryGreen else Color.White.copy(alpha = 0.15f)),
                                                        shape = RoundedCornerShape(8.dp),
                                                        modifier = Modifier.height(34.dp)
                                                    ) {
                                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                            Icon(Icons.Filled.GpsFixed, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                                            Text(if (isGpsReq) "GPS ACTIVO" else "PEDIR GPS", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // 3. TRIPULANTES A BORDO
                                    if (selectedCrewMembers.isEmpty()) {
                                        item {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 14.dp)
                                                    .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
                                                    .padding(14.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                val crewCountDisp = (uData?.get("count") ?: uData?.get("cuantosBomberos"))?.toString() ?: "0"
                                                Text(
                                                    text = if (crewCountDisp != "0") "Dotación tripulación: $crewCountDisp bomberos." else "No hay bomberos tripulantes adicionales asignados (0).",
                                                    color = CarPlayColors.TextMuted,
                                                    fontSize = 12.sp,
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        }
                                    } else {
                                        items(selectedCrewMembers) { person ->
                                            val isGpsReq = requestedGpsIds.containsKey(person.idRegistro)
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(Color.White.copy(alpha = 0.06f))
                                                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                                                    .padding(12.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                                                        Box(
                                                            modifier = Modifier.size(32.dp).clip(CircleShape).background(CarPlayColors.PrimaryBlue.copy(alpha = 0.3f)),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Text("👨‍🚒", fontSize = 14.sp)
                                                        }
                                                        Column {
                                                            Text(
                                                                text = "${person.idRadial.ifEmpty { "" }} ${person.nombreBombero}".trim(),
                                                                color = Color.White,
                                                                fontSize = 13.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                            Text(
                                                                text = listOfNotNull(person.compania.takeIf { it.isNotEmpty() }, person.cargo.takeIf { it.isNotEmpty() }).joinToString(" • ").ifEmpty { "Tripulante" },
                                                                color = CarPlayColors.TextMuted,
                                                                fontSize = 11.sp
                                                            )
                                                        }
                                                    }

                                                    Button(
                                                        onClick = {
                                                            viewModel.solicitarGpsBombero(person.idRegistro)
                                                            requestedGpsIds[person.idRegistro] = System.currentTimeMillis()
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = if (isGpsReq) CarPlayColors.PrimaryGreen else Color.White.copy(alpha = 0.15f)),
                                                        shape = RoundedCornerShape(8.dp),
                                                        modifier = Modifier.height(34.dp)
                                                    ) {
                                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                            Icon(Icons.Filled.GpsFixed, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                                            Text(if (isGpsReq) "GPS ACTIVO" else "PEDIR GPS", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Botones Pie
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { showCrewDialog = false },
                                        modifier = Modifier.weight(1f).height(46.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("CERRAR", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }

                                    Button(
                                        onClick = { isEditingCrewMode = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = CarPlayColors.PrimaryBlue),
                                        modifier = Modifier.weight(1.3f).height(46.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("➕ ASIGNAR / MODIFICAR", fontWeight = FontWeight.Black, fontSize = 11.sp)
                                    }
                                }
                            } else {
                                // ==========================================
                                // VISTA 2: ASIGNACIÓN DE CONDUCTOR, OBAC Y TRIPULACIÓN (ESTILO SISBOM)
                                // ==========================================
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    // Campo CONDUCTOR
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("🛞 CONDUCTOR (RADIAL):", fontSize = 10.sp, fontWeight = FontWeight.Black, color = CarPlayColors.AccentCyan)
                                        Spacer(Modifier.height(3.dp))
                                        OutlinedTextField(
                                            value = inputDriverRad,
                                            onValueChange = { inputDriverRad = it },
                                            placeholder = { Text("Radial...", fontSize = 11.sp) },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth().height(52.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = CarPlayColors.AccentCyan,
                                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White
                                            )
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = conductorPerson?.nombreBombero ?: if (inputDriverRad.isNotBlank()) "ID: $inputDriverRad" else "Sin conductor",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (conductorPerson != null) CarPlayColors.PrimaryGreen else CarPlayColors.TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    // Campo OBAC
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("🎖️ OBAC (RADIAL):", fontSize = 10.sp, fontWeight = FontWeight.Black, color = CarPlayColors.PrimaryAmber)
                                        Spacer(Modifier.height(3.dp))
                                        OutlinedTextField(
                                            value = inputObacRad,
                                            onValueChange = { inputObacRad = it },
                                            placeholder = { Text("Radial...", fontSize = 11.sp) },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth().height(52.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = CarPlayColors.PrimaryAmber,
                                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White
                                            )
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = obacPerson?.nombreBombero ?: if (inputObacRad.isNotBlank()) "ID: $inputObacRad" else "Sin OBAC",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (obacPerson != null) CarPlayColors.PrimaryGreen else CarPlayColors.TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    // Campo TRIPULACIÓN (Texto, defecto "0")
                                    Column(modifier = Modifier.weight(0.9f)) {
                                        Text("👥 TRIPULACIÓN:", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.White)
                                        Spacer(Modifier.height(3.dp))
                                        OutlinedTextField(
                                            value = inputCrewCount,
                                            onValueChange = { inputCrewCount = it },
                                            placeholder = { Text("0", fontSize = 11.sp) },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth().height(52.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = Color.White,
                                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White
                                            )
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = if (inputCrewCount.isNotBlank() && inputCrewCount != "0") "$inputCrewCount bomberos" else "0 bomberos",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = CarPlayColors.TextMuted,
                                            maxLines = 1
                                        )
                                    }
                                }

                                // Buscador de Bomberos
                                OutlinedTextField(
                                    value = crewSearchQuery,
                                    onValueChange = { crewSearchQuery = it },
                                    placeholder = { Text("Buscar bombero para asignar...", fontSize = 12.sp) },
                                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Buscar", tint = Color.White.copy(alpha = 0.5f)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = CarPlayColors.AccentCyan,
                                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    )
                                )

                                LazyColumn(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(filteredPersonal) { person ->
                                        val isSelectedCrew = selectedCrewMembers.any { it.idRegistro == person.idRegistro }
                                        val isDriver = conductorPerson?.idRegistro == person.idRegistro || inputDriverRad.trim() == person.idRadial
                                        val isObac = obacPerson?.idRegistro == person.idRegistro || inputObacRad.trim() == person.idRadial

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(
                                                    when {
                                                        isDriver && isObac -> Color(0xFF10B981).copy(alpha = 0.25f)
                                                        isDriver -> Color(0xFF0284C7).copy(alpha = 0.25f)
                                                        isObac -> Color(0xFFF59E0B).copy(alpha = 0.25f)
                                                        isSelectedCrew -> CarPlayColors.PrimaryBlue.copy(alpha = 0.35f)
                                                        else -> Color.White.copy(alpha = 0.05f)
                                                    }
                                                )
                                                .border(
                                                    width = 1.dp,
                                                    color = when {
                                                        isDriver && isObac -> Color(0xFF10B981)
                                                        isDriver -> Color(0xFF0284C7)
                                                        isObac -> Color(0xFFF59E0B)
                                                        isSelectedCrew -> CarPlayColors.AccentCyan
                                                        else -> Color.White.copy(alpha = 0.1f)
                                                    },
                                                    shape = RoundedCornerShape(10.dp)
                                                )
                                                .clickable {
                                                    if (isSelectedCrew) {
                                                        selectedCrewMembers.removeAll { it.idRegistro == person.idRegistro }
                                                    } else {
                                                        selectedCrewMembers.add(person)
                                                    }
                                                    if (inputCrewCount.isBlank() || inputCrewCount == "0") {
                                                        inputCrewCount = selectedCrewMembers.size.toString()
                                                    }
                                                }
                                                .padding(10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(24.dp)
                                                            .clip(CircleShape)
                                                            .background(
                                                                when {
                                                                    isDriver && isObac -> Color(0xFF10B981)
                                                                    isDriver -> Color(0xFF0284C7)
                                                                    isObac -> Color(0xFFF59E0B)
                                                                    isSelectedCrew -> CarPlayColors.AccentCyan
                                                                    else -> Color.White.copy(alpha = 0.1f)
                                                                }
                                                            ),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        if (isSelectedCrew || isDriver || isObac) {
                                                            Icon(Icons.Filled.Check, contentDescription = "OK", tint = Color.Black, modifier = Modifier.size(16.dp))
                                                        }
                                                    }

                                                    Column {
                                                        Text(
                                                            text = "${person.idRadial.ifEmpty { "" }} ${person.nombreBombero}".trim(),
                                                            color = Color.White,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Text(
                                                            text = listOfNotNull(person.compania.takeIf { it.isNotEmpty() }, person.cargo.takeIf { it.isNotEmpty() }).joinToString(" • "),
                                                            color = CarPlayColors.TextMuted,
                                                            fontSize = 10.sp
                                                        )
                                                    }
                                                }

                                                // Botones rápidos para asignar como Conductor u OBAC
                                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Button(
                                                        onClick = {
                                                            inputDriverRad = person.idRadial.ifEmpty { person.idRegistro }
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = if (isDriver) Color(0xFF0284C7) else Color.White.copy(alpha = 0.12f)),
                                                        shape = RoundedCornerShape(6.dp),
                                                        modifier = Modifier.height(28.dp),
                                                        contentPadding = PaddingValues(horizontal = 6.dp)
                                                    ) {
                                                        Text("🛞 COND", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                    }

                                                    Button(
                                                        onClick = {
                                                            inputObacRad = person.idRadial.ifEmpty { person.idRegistro }
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = if (isObac) Color(0xFFF59E0B) else Color.White.copy(alpha = 0.12f)),
                                                        shape = RoundedCornerShape(6.dp),
                                                        modifier = Modifier.height(28.dp),
                                                        contentPadding = PaddingValues(horizontal = 6.dp)
                                                    ) {
                                                        Text("🎖️ OBAC", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (isObac) Color.Black else Color.White)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { isEditingCrewMode = false },
                                        modifier = Modifier.weight(1f).height(46.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("VOLVER", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }

                                    Button(
                                        onClick = {
                                            viewModel.assignCrewMembers(
                                                tripulantes = selectedCrewMembers.toList(),
                                                driverRadText = inputDriverRad,
                                                driverPerson = resolvedDriverPerson,
                                                obacRadText = inputObacRad,
                                                obacPerson = resolvedObacPerson,
                                                crewCountText = if (inputCrewCount.isNotBlank()) inputCrewCount else (if (selectedCrewMembers.isNotEmpty()) selectedCrewMembers.size.toString() else "0")
                                            )
                                            isEditingCrewMode = false
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CarPlayColors.PrimaryGreen),
                                        modifier = Modifier.weight(1.3f).height(46.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("GUARDAR DOTACIÓN", fontWeight = FontWeight.Black, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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
    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isActive) activeColor else Color.White.copy(alpha = 0.06f))
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
