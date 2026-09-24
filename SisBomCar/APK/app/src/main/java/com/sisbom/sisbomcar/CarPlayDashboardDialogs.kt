package com.sisbom.sisbomcar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Dialog táctico para el registro de salidas especiales 6-13 (Trámites) y 6-14 (Carga de Combustible).
 */
@Composable
fun TacticalSpecialExitDialog(
    show: Boolean,
    unitLabel: String,
    viewModel: CarViewModel,
    onDismiss: () -> Unit
) {
    if (!show) return

    var specialExitType by remember { mutableStateOf("6-13") }
    var specialLugar by remember { mutableStateOf("") }
    var specialMotivo by remember { mutableStateOf("") }
    var specialConductor by remember { mutableStateOf("") }
    var specialObac by remember { mutableStateOf("") }
    var specialTripulantes by remember { mutableStateOf("") }
    var specialError by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
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
                            onClick = onDismiss,
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
                            Text("6-13", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                            Text("6-14", color = if (specialExitType == "6-14") Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
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
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
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
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
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
                            onClick = onDismiss,
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
                                            onDismiss()
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
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Modal táctico con teclado numérico en pantalla para el ingreso de kilometraje final (6-8).
 */
@Composable
fun TacticalKmDialog(
    show: Boolean,
    unit: Vehicle?,
    viewModel: CarViewModel,
    onDismiss: () -> Unit
) {
    if (!show) return
    var kmFinalInput by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.80f))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(420.dp)
                    .carPlayCard(cornerRadius = 24.dp, bgAlpha = 0.98f)
                    .padding(20.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier.size(36.dp).clip(CircleShape).background(CarPlayColors.AccentCyan.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = CarPlayColors.AccentCyan, modifier = Modifier.size(20.dp))
                            }
                            Column {
                                Text("KILOMETRAJE FINAL", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black)
                                Text("Odómetro de retorno para 6-8", color = CarPlayColors.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Cerrar", tint = Color.White)
                        }
                    }

                    // Pantalla Digital de KM
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF030712))
                            .border(1.5.dp, CarPlayColors.AccentCyan, RoundedCornerShape(16.dp))
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = kmFinalInput.ifEmpty { "0" },
                                color = if (kmFinalInput.isNotEmpty()) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.3f),
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 2.sp
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "KM",
                                color = CarPlayColors.AccentCyan,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                    }

                    // Grid Numérico Táctil
                    val kmKeys = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("LIMPIAR", "0", "⌫")
                    )

                    kmKeys.forEach { rowKeys ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowKeys.forEach { key ->
                                val isSpecial = key == "LIMPIAR" || key == "⌫"
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            when (key) {
                                                "⌫" -> Color(0xFFDC2626).copy(alpha = 0.75f)
                                                "LIMPIAR" -> Color(0xFF475569).copy(alpha = 0.75f)
                                                else -> Color.White.copy(alpha = 0.10f)
                                            }
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = when (key) {
                                                "⌫" -> Color(0xFFEF4444)
                                                "LIMPIAR" -> Color(0xFF64748B)
                                                else -> Color.White.copy(alpha = 0.15f)
                                            },
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable {
                                            when (key) {
                                                "⌫" -> if (kmFinalInput.isNotEmpty()) kmFinalInput = kmFinalInput.dropLast(1)
                                                "LIMPIAR" -> kmFinalInput = ""
                                                else -> {
                                                    if (kmFinalInput.length < 8) kmFinalInput += key
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = key,
                                        fontSize = if (isSpecial) 11.sp else 20.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    // Botones de Acción
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("CANCELAR", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                val kmToSave = kmFinalInput.ifBlank { unit?.kmActual ?: unit?.km ?: "0" }
                                viewModel.markDisponible68(kmToSave)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CarPlayColors.PrimaryGreen),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1.5f).height(50.dp)
                        ) {
                            Text("6-8", fontWeight = FontWeight.Black, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Modal táctico completo de gestión de tripulación y dotación en cabina (Conductor, OBAC, Bomberos).
 */
@Composable
fun TacticalCrewDialog(
    show: Boolean,
    dispatch: Dispatch?,
    unit: Vehicle?,
    unitLabel: String,
    viewModel: CarViewModel,
    nowTickerTs: Long,
    activeTrip: BitacoraTrip? = null,
    onDismiss: () -> Unit
) {
    if (!show) return

    val unitEntry = dispatch?.unidades?.entries?.find {
        it.key.replace("-", "").equals(unitLabel.replace("-", ""), ignoreCase = true)
    }
    val uData = unitEntry?.value

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
    var selectedCompaniaFilter by remember { mutableStateOf("TODOS") }
    val requestedGpsIds = remember { mutableStateMapOf<String, Long>() }

    val driverRad = (uData?.get("driverRad") ?: uData?.get("conductor") ?: unit?.conductor ?: activeTrip?.conductor)?.toString()?.trim() ?: ""
    val obacRad = (uData?.get("obacRad") ?: uData?.get("obac") ?: unit?.obac ?: activeTrip?.obac)?.toString()?.trim() ?: ""

    var inputDriverRad by remember(driverRad) { mutableStateOf(driverRad) }
    var inputObacRad by remember(obacRad) { mutableStateOf(obacRad) }
    var inputCrewCount by remember {
        val raw = (uData?.get("count") ?: uData?.get("cuantosBomberos") ?: (if ((unit?.numTripulantes ?: 0) > 0) unit?.numTripulantes.toString() else null) ?: activeTrip?.cuantosBomberos)?.toString()?.trim() ?: ""
        mutableStateOf(if (raw.isNotEmpty()) raw else (if (assignedCrewPersons.isNotEmpty()) assignedCrewPersons.size.toString() else "0"))
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

    val obacPerson = remember(inputObacRad, viewModel.personalList) {
        if (inputObacRad.isBlank()) null
        else viewModel.personalList.find {
            it.idRadial.equals(inputObacRad.trim(), ignoreCase = true) ||
            it.idRegistro == inputObacRad.trim() ||
            inputObacRad.startsWith("${it.idRadial} -") ||
            inputObacRad.contains(it.nombreBombero, ignoreCase = true)
        }
    }

    val filteredPersonal = remember(crewSearchQuery, selectedCompaniaFilter, viewModel.personalList) {
        val q = crewSearchQuery.trim()
        viewModel.personalList.filter { p ->
            val matchQuery = if (q.isEmpty()) true else {
                p.idRadial.contains(q, ignoreCase = true) ||
                p.nombreBombero.contains(q, ignoreCase = true) ||
                p.cargo.contains(q, ignoreCase = true) ||
                p.compania.contains(q, ignoreCase = true)
            }
            val matchCia = if (selectedCompaniaFilter == "TODOS") true else {
                p.compania.contains(selectedCompaniaFilter, ignoreCase = true)
            }
            matchQuery && matchCia
        }
    }

    val hasDriver = conductorPerson != null || inputDriverRad.isNotBlank()
    val hasObac = obacPerson != null || inputObacRad.isNotBlank()
    val isSameDriverAndObac = hasDriver && hasObac && (
        (conductorPerson != null && obacPerson != null && conductorPerson.idRegistro == obacPerson.idRegistro) ||
        (inputDriverRad.isNotBlank() && inputDriverRad.trim().equals(inputObacRad.trim(), ignoreCase = true))
    )

    val totalOccupants =
        (if (isSameDriverAndObac) 1 else ((if (hasDriver) 1 else 0) + (if (hasObac) 1 else 0))) +
        (inputCrewCount.toIntOrNull()?.coerceAtLeast(selectedCrewMembers.size) ?: selectedCrewMembers.size)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.80f))
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .imePadding(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.92f)
                    .carPlayCard(cornerRadius = 24.dp, bgAlpha = 0.98f)
                    .padding(18.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. Header Táctico
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(CarPlayColors.PrimaryBlue.copy(alpha = 0.25f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🚒", fontSize = 18.sp)
                            }
                            Column {
                                Text(
                                    text = "DOTACIÓN Y TRIPULACIÓN • UNIDAD $unitLabel",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = "👤 $totalOccupants Ocupantes a bordo (Conductor + OBAC + ${inputCrewCount.toIntOrNull() ?: selectedCrewMembers.size} Bomberos)",
                                    color = CarPlayColors.AccentCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val condTs = (uData?.get("solicitudConductorTimestamp") as? Number)?.toLong() ?: unit?.solicitudConductorTimestamp ?: 0L
                            val persTs = (uData?.get("solicitudPersonalTimestamp") as? Number)?.toLong() ?: unit?.solicitudPersonalTimestamp ?: 0L
                            val condRemainingSec = ((condTs + 60000L - nowTickerTs) / 1000).coerceAtLeast(0)
                            val persRemainingSec = ((persTs + 60000L - nowTickerTs) / 1000).coerceAtLeast(0)

                            // Botón Rápido 12-10 Conductor
                            Button(
                                onClick = { viewModel.request1210Conductor() },
                                enabled = condRemainingSec <= 0,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (condRemainingSec > 0) Color(0xFF334155) else Color(0xFFDC2626),
                                    disabledContainerColor = Color(0xFF334155)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (condRemainingSec > 0) "12-10 (${condRemainingSec}s)" else "12-10",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (condRemainingSec > 0) Color(0xFFFDE047) else Color.White
                                )
                            }

                            // Botón Rápido 6-6 Personal
                            Button(
                                onClick = { viewModel.request66Personal() },
                                enabled = persRemainingSec <= 0,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (persRemainingSec > 0) Color(0xFF334155) else Color(0xFF0284C7),
                                    disabledContainerColor = Color(0xFF334155)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (persRemainingSec > 0) "6-6 (${persRemainingSec}s)" else "6-6",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (persRemainingSec > 0) Color(0xFF7DD3FC) else Color.White
                                )
                            }

                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Cerrar", tint = Color.White)
                            }
                        }
                    }

                    // 2. Cuerpo Principal en 2 Columnas
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // ==========================================
                        // COLUMNA IZQUIERDA (42%): SLOTS DE CABINA Y GUARDAR
                        // ==========================================
                        Column(
                            modifier = Modifier
                                .weight(0.42f)
                                .fillMaxHeight()
                                .background(Color(0xFF0F172A).copy(alpha = 0.65f), RoundedCornerShape(18.dp))
                                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(18.dp))
                                .padding(14.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // SLOT CONDUCTOR
                                Text(
                                    text = "🛞 CONDUCTOR AL VOLANTE",
                                    color = CarPlayColors.AccentCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.5.sp
                                )
                                val hasDriver = conductorPerson != null || inputDriverRad.isNotBlank()
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (hasDriver) Color(0xFF0284C7).copy(alpha = 0.20f) else Color.White.copy(alpha = 0.04f))
                                        .border(1.dp, if (hasDriver) Color(0xFF0284C7) else Color.White.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Surface(
                                                color = if (hasDriver) Color(0xFF0284C7) else Color.White.copy(alpha = 0.10f),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    text = conductorPerson?.idRadial?.takeIf { it.isNotEmpty() } ?: inputDriverRad.takeIf { it.isNotEmpty() } ?: "-",
                                                    color = Color.White,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Black,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                            Column {
                                                Text(
                                                    text = conductorPerson?.nombreBombero ?: if (inputDriverRad.isNotBlank()) "Radial $inputDriverRad" else "Sin conductor asignado",
                                                    color = if (hasDriver) Color.White else CarPlayColors.TextMuted,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = conductorPerson?.compania ?: if (hasDriver) "Asignado" else "Toca [🛞 COND] en la lista",
                                                    color = if (hasDriver) CarPlayColors.AccentCyan else Color.White.copy(alpha = 0.40f),
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }

                                        if (hasDriver) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                if (conductorPerson != null) {
                                                    val isGpsReq = requestedGpsIds.containsKey(conductorPerson.idRegistro)
                                                    IconButton(
                                                        onClick = {
                                                            viewModel.solicitarGpsBombero(conductorPerson.idRegistro)
                                                            requestedGpsIds[conductorPerson.idRegistro] = System.currentTimeMillis()
                                                        },
                                                        modifier = Modifier.size(30.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Filled.GpsFixed,
                                                            contentDescription = "GPS",
                                                            tint = if (isGpsReq) CarPlayColors.PrimaryGreen else Color.White,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }
                                                IconButton(
                                                    onClick = {
                                                        inputDriverRad = ""
                                                    },
                                                    modifier = Modifier.size(30.dp)
                                                ) {
                                                    Icon(Icons.Filled.Close, contentDescription = "Quitar", tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }
                                }

                                // SLOT OBAC
                                Text(
                                    text = "🎖️ OFICIAL / BOMBERO A CARGO (OBAC)",
                                    color = Color(0xFFFBBF24),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.5.sp
                                )
                                val hasObac = obacPerson != null || inputObacRad.isNotBlank()
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (hasObac) Color(0xFFF59E0B).copy(alpha = 0.20f) else Color.White.copy(alpha = 0.04f))
                                        .border(1.dp, if (hasObac) Color(0xFFF59E0B) else Color.White.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Surface(
                                                color = if (hasObac) Color(0xFFF59E0B) else Color.White.copy(alpha = 0.10f),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    text = obacPerson?.idRadial?.takeIf { it.isNotEmpty() } ?: inputObacRad.takeIf { it.isNotEmpty() } ?: "-",
                                                    color = if (hasObac) Color.Black else Color.White,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Black,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                            Column {
                                                Text(
                                                    text = obacPerson?.nombreBombero ?: if (inputObacRad.isNotBlank()) "Radial $inputObacRad" else "Sin OBAC asignado",
                                                    color = if (hasObac) Color.White else CarPlayColors.TextMuted,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = obacPerson?.cargo?.ifEmpty { obacPerson.compania } ?: if (hasObac) "A Cargo" else "Toca [🎖️ OBAC] en la lista",
                                                    color = if (hasObac) Color(0xFFFBBF24) else Color.White.copy(alpha = 0.40f),
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }

                                        if (hasObac) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                if (obacPerson != null) {
                                                    val isGpsReq = requestedGpsIds.containsKey(obacPerson.idRegistro)
                                                    IconButton(
                                                        onClick = {
                                                            viewModel.solicitarGpsBombero(obacPerson.idRegistro)
                                                            requestedGpsIds[obacPerson.idRegistro] = System.currentTimeMillis()
                                                        },
                                                        modifier = Modifier.size(30.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Filled.GpsFixed,
                                                            contentDescription = "GPS",
                                                            tint = if (isGpsReq) CarPlayColors.PrimaryGreen else Color.White,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }
                                                IconButton(
                                                    onClick = {
                                                        inputObacRad = ""
                                                    },
                                                    modifier = Modifier.size(30.dp)
                                                ) {
                                                    Icon(Icons.Filled.Close, contentDescription = "Quitar", tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }
                                }

                                // SECCIÓN TRIPULACIÓN / BOMBEROS EN CABINA
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "👥 BOMBEROS EN CABINA (${selectedCrewMembers.size})",
                                        color = CarPlayColors.AccentCyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black
                                    )

                                    // Input manual rápido de dotación numérica
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Cant:", fontSize = 11.sp, color = CarPlayColors.TextMuted)
                                        OutlinedTextField(
                                            value = inputCrewCount,
                                            onValueChange = { inputCrewCount = it },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            modifier = Modifier.width(60.dp).height(38.dp),
                                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color.White),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = CarPlayColors.AccentCyan,
                                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White
                                            )
                                        )
                                    }
                                }

                                // Lista de Bomberos Tripulantes Seleccionados
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.Black.copy(alpha = 0.30f))
                                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                                        .padding(6.dp)
                                ) {
                                    if (selectedCrewMembers.isEmpty()) {
                                        Box(
                                            modifier = Modifier.fillMaxSize().padding(12.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "Toca [+ TRIP] en cualquier bombero para asignarlo con nombre y radial",
                                                color = CarPlayColors.TextMuted,
                                                fontSize = 11.sp,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            items(selectedCrewMembers) { person ->
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(Color.White.copy(alpha = 0.06f))
                                                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                            modifier = Modifier.weight(1f)
                                                        ) {
                                                            Surface(
                                                                color = CarPlayColors.PrimaryBlue.copy(alpha = 0.4f),
                                                                shape = RoundedCornerShape(6.dp)
                                                            ) {
                                                                Text(
                                                                    text = person.idRadial.ifEmpty { "•" },
                                                                    color = Color.White,
                                                                    fontSize = 11.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                                )
                                                            }
                                                            Text(
                                                                text = person.nombreBombero,
                                                                color = Color.White,
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }

                                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                            val isGpsReq = requestedGpsIds.containsKey(person.idRegistro)
                                                            IconButton(
                                                                onClick = {
                                                                    viewModel.solicitarGpsBombero(person.idRegistro)
                                                                    requestedGpsIds[person.idRegistro] = System.currentTimeMillis()
                                                                },
                                                                modifier = Modifier.size(26.dp)
                                                            ) {
                                                                Icon(
                                                                    Icons.Filled.GpsFixed,
                                                                    contentDescription = "GPS",
                                                                    tint = if (isGpsReq) CarPlayColors.PrimaryGreen else Color.White,
                                                                    modifier = Modifier.size(14.dp)
                                                                )
                                                            }
                                                            IconButton(
                                                                onClick = {
                                                                    selectedCrewMembers.removeAll { it.idRegistro == person.idRegistro }
                                                                    inputCrewCount = selectedCrewMembers.size.toString()
                                                                },
                                                                modifier = Modifier.size(26.dp)
                                                            ) {
                                                                Icon(Icons.Filled.Close, contentDescription = "Quitar", tint = Color(0xFFEF4444), modifier = Modifier.size(14.dp))
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Botón de Guardar en la columna izquierda
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    viewModel.assignCrewMembers(
                                        tripulantes = selectedCrewMembers.toList(),
                                        driverRadText = inputDriverRad,
                                        driverPerson = conductorPerson,
                                        obacRadText = inputObacRad,
                                        obacPerson = obacPerson,
                                        crewCountText = if (inputCrewCount.isNotBlank()) inputCrewCount else (if (selectedCrewMembers.isNotEmpty()) selectedCrewMembers.size.toString() else "0")
                                    )
                                    onDismiss()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CarPlayColors.PrimaryGreen),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                            ) {
                                Text("✅ GUARDAR Y CONFIRMAR", fontWeight = FontWeight.Black, fontSize = 14.sp, color = Color.Black)
                            }
                        }

                        // ==========================================
                        // COLUMNA DERECHA (58%): BUSCADOR + LISTA DE RESULTADOS DIRECTA
                        // ==========================================
                        Column(
                            modifier = Modifier
                                .weight(0.58f)
                                .fillMaxHeight()
                                .background(Color(0xFF0F172A).copy(alpha = 0.50f), RoundedCornerShape(18.dp))
                                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(18.dp))
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 1. Buscador Táctico en la parte superior
                            OutlinedTextField(
                                value = crewSearchQuery,
                                onValueChange = { crewSearchQuery = it },
                                placeholder = { Text("🔍 Escribe radial o nombre...", fontSize = 13.sp) },
                                trailingIcon = {
                                    if (crewSearchQuery.isNotEmpty()) {
                                        IconButton(onClick = { crewSearchQuery = "" }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Filled.Close, contentDescription = "Limpiar", tint = Color.White)
                                        }
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CarPlayColors.AccentCyan,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )

                            // 2. Filtro Rápido por Compañía (Chips)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("TODOS", "1ª", "2ª", "3ª", "COMANDANCIA").forEach { ciaTag ->
                                    val isSel = selectedCompaniaFilter == ciaTag
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSel) CarPlayColors.AccentCyan else Color.White.copy(alpha = 0.08f))
                                            .clickable { selectedCompaniaFilter = ciaTag }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = ciaTag,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSel) FontWeight.Black else FontWeight.Bold,
                                            color = if (isSel) Color.Black else Color.White
                                        )
                                    }
                                }
                            }

                            // 3. LISTA DIRECTA DE RESULTADOS DE BOMBEROS (A LA VISTA INMEDIATA)
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (filteredPersonal.isEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 24.dp)
                                                .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
                                                .padding(16.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("⚠️ No se encontró ningún bombero con \"$crewSearchQuery\"", color = CarPlayColors.TextMuted, fontSize = 12.sp)
                                                if (crewSearchQuery.isNotBlank()) {
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        Button(
                                                            onClick = {
                                                                inputDriverRad = crewSearchQuery.trim()
                                                                crewSearchQuery = ""
                                                            },
                                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                                            shape = RoundedCornerShape(8.dp),
                                                            modifier = Modifier.height(34.dp)
                                                        ) {
                                                            Text("Asignar $crewSearchQuery como Conductor", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                        Button(
                                                            onClick = {
                                                                inputObacRad = crewSearchQuery.trim()
                                                                crewSearchQuery = ""
                                                            },
                                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                                                            shape = RoundedCornerShape(8.dp),
                                                            modifier = Modifier.height(34.dp)
                                                        ) {
                                                            Text("Asignar $crewSearchQuery como OBAC", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    items(filteredPersonal) { person ->
                                        val isDriver = conductorPerson?.idRegistro == person.idRegistro || inputDriverRad.trim().equals(person.idRadial, ignoreCase = true)
                                        val isObac = obacPerson?.idRegistro == person.idRegistro || inputObacRad.trim().equals(person.idRadial, ignoreCase = true)
                                        val isTrip = selectedCrewMembers.any { it.idRegistro == person.idRegistro }

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(
                                                    when {
                                                        isDriver && isObac -> Color(0xFF0284C7).copy(alpha = 0.35f)
                                                        isDriver -> Color(0xFF0284C7).copy(alpha = 0.22f)
                                                        isObac -> Color(0xFFF59E0B).copy(alpha = 0.22f)
                                                        isTrip -> CarPlayColors.PrimaryBlue.copy(alpha = 0.22f)
                                                        else -> Color.White.copy(alpha = 0.05f)
                                                    }
                                                )
                                                .border(
                                                    width = 1.dp,
                                                    color = when {
                                                        isDriver && isObac -> Color(0xFF38BDF8)
                                                        isDriver -> Color(0xFF0284C7)
                                                        isObac -> Color(0xFFF59E0B)
                                                        isTrip -> CarPlayColors.AccentCyan
                                                        else -> Color.White.copy(alpha = 0.10f)
                                                    },
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Datos del Bombero
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Surface(
                                                        color = when {
                                                            isDriver && isObac -> Color(0xFF0284C7)
                                                            isDriver -> Color(0xFF0284C7)
                                                            isObac -> Color(0xFFF59E0B)
                                                            isTrip -> CarPlayColors.PrimaryBlue
                                                            else -> Color.White.copy(alpha = 0.15f)
                                                        },
                                                        shape = RoundedCornerShape(8.dp)
                                                    ) {
                                                        Text(
                                                            text = person.idRadial.ifEmpty { "•" },
                                                            color = if (isObac && !isDriver) Color.Black else Color.White,
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Black,
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                        )
                                                    }

                                                    Column {
                                                        Text(
                                                            text = person.nombreBombero,
                                                            color = Color.White,
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            text = listOfNotNull(person.compania.takeIf { it.isNotEmpty() }, person.cargo.takeIf { it.isNotEmpty() }).joinToString(" • ").ifEmpty { "Bombero Voluntario" },
                                                            color = CarPlayColors.TextMuted,
                                                            fontSize = 10.sp
                                                        )
                                                    }
                                                }

                                                // Botones de Asignación Directa de 1 Toque
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    // Botón Conductor
                                                    Button(
                                                        onClick = {
                                                            if (isDriver) {
                                                                inputDriverRad = ""
                                                            } else {
                                                                inputDriverRad = person.idRadial.ifEmpty { person.idRegistro }
                                                                selectedCrewMembers.removeAll { it.idRegistro == person.idRegistro }
                                                            }
                                                        },
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = if (isDriver) Color(0xFF0284C7) else Color.White.copy(alpha = 0.12f)
                                                        ),
                                                        shape = RoundedCornerShape(8.dp),
                                                        modifier = Modifier.height(34.dp),
                                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                                    ) {
                                                        Text("🛞 COND", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                                    }

                                                    // Botón OBAC
                                                    Button(
                                                        onClick = {
                                                            if (isObac) {
                                                                inputObacRad = ""
                                                            } else {
                                                                inputObacRad = person.idRadial.ifEmpty { person.idRegistro }
                                                                selectedCrewMembers.removeAll { it.idRegistro == person.idRegistro }
                                                            }
                                                        },
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = if (isObac) Color(0xFFF59E0B) else Color.White.copy(alpha = 0.12f)
                                                        ),
                                                        shape = RoundedCornerShape(8.dp),
                                                        modifier = Modifier.height(34.dp),
                                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                                    ) {
                                                        Text("🎖️ OBAC", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isObac) Color.Black else Color.White)
                                                    }

                                                    // Botón TRIP
                                                    Button(
                                                        onClick = {
                                                            if (isTrip) {
                                                                selectedCrewMembers.removeAll { it.idRegistro == person.idRegistro }
                                                            } else {
                                                                if (isDriver) inputDriverRad = ""
                                                                if (isObac) inputObacRad = ""
                                                                selectedCrewMembers.add(person)
                                                            }
                                                            inputCrewCount = selectedCrewMembers.size.toString()
                                                        },
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = if (isTrip) CarPlayColors.AccentCyan else Color.White.copy(alpha = 0.12f)
                                                        ),
                                                        shape = RoundedCornerShape(8.dp),
                                                        modifier = Modifier.height(34.dp),
                                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                                    ) {
                                                        Text(
                                                            text = if (isTrip) "✓ TRIP" else "+ TRIP",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (isTrip) Color.Black else Color.White
                                                        )
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
            }
        }
    }
}

/**
 * Modal táctico para selección de centro de destino de traslado asistencial (6-15).
 * Opciones: CESFAM Placilla (-34.6393245, -71.1172894) vs Hospital San Fernando (-34.5768069, -70.9929463).
 */
@Composable
fun Tactical615DestinationDialog(
    show: Boolean,
    unitLabel: String,
    currentVehicleLat: Double?,
    currentVehicleLng: Double?,
    viewModel: CarViewModel,
    onDismiss: () -> Unit
) {
    if (!show) return
    val context = androidx.compose.ui.platform.LocalContext.current

    val CESFAM_PLACILLA_LAT = -34.6393245
    val CESFAM_PLACILLA_LNG = -71.1172894
    val HOSPITAL_SAN_FERNANDO_LAT = -34.5768069
    val HOSPITAL_SAN_FERNANDO_LNG = -70.9929463

    // Distancias aproximadas
    val distCesfamKm = remember(currentVehicleLat, currentVehicleLng) {
        if (currentVehicleLat != null && currentVehicleLat != 0.0 && currentVehicleLng != null && currentVehicleLng != 0.0) {
            val res = FloatArray(1)
            android.location.Location.distanceBetween(currentVehicleLat, currentVehicleLng, CESFAM_PLACILLA_LAT, CESFAM_PLACILLA_LNG, res)
            (res[0] / 1000f)
        } else null
    }

    val distHospitalKm = remember(currentVehicleLat, currentVehicleLng) {
        if (currentVehicleLat != null && currentVehicleLat != 0.0 && currentVehicleLng != null && currentVehicleLng != 0.0) {
            val res = FloatArray(1)
            android.location.Location.distanceBetween(currentVehicleLat, currentVehicleLng, HOSPITAL_SAN_FERNANDO_LAT, HOSPITAL_SAN_FERNANDO_LNG, res)
            (res[0] / 1000f)
        } else null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(520.dp)
                    .carPlayCard(cornerRadius = 24.dp, bgAlpha = 0.98f)
                    .border(
                        width = 1.5.dp,
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(
                                Color(0xFFFBBF24),
                                Color(0xFF0284C7).copy(alpha = 0.5f),
                                Color.Transparent
                            )
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(22.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        androidx.compose.ui.graphics.Brush.verticalGradient(
                                            listOf(Color(0xFFF59E0B), Color(0xFFD97706), Color(0xFFB45309))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("6-15", fontWeight = FontWeight.Black, fontSize = 16.sp, color = Color.Black)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "DESTINO DE TRASLADO ASISTENCIAL",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = "Unidad $unitLabel • Seleccione centro de salud para trazar ruta",
                                    color = CarPlayColors.TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.12f))
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Cerrar", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

                    // OPCIÓN 1: CESFAM PLACILLA
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    listOf(
                                        Color(0xFF0F766E).copy(alpha = 0.35f),
                                        Color(0xFF134E4A).copy(alpha = 0.65f)
                                    )
                                )
                            )
                            .border(
                                width = 1.4.dp,
                                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    listOf(Color(0xFF2DD4BF), Color(0xFF14B8A6).copy(alpha = 0.4f))
                                ),
                                shape = RoundedCornerShape(18.dp)
                            )
                            .clickable {
                                viewModel.markTraslado615(
                                    destinationName = "CESFAM PLACILLA",
                                    lat = CESFAM_PLACILLA_LAT,
                                    lng = CESFAM_PLACILLA_LNG
                                )
                                android.widget.Toast.makeText(context, "🚑 Ruta 6-15 fijada hacia CESFAM PLACILLA", android.widget.Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF14B8A6).copy(alpha = 0.25f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("🏥", fontSize = 22.sp)
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "CESFAM PLACILLA",
                                            color = Color.White,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 16.sp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFF2DD4BF).copy(alpha = 0.25f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "LOCAL",
                                                color = Color(0xFF5EEAD4),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "Centro de Salud Familiar • Placilla",
                                        color = CarPlayColors.TextSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            if (distCesfamKm != null) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "${"%.1f".format(distCesfamKm)} km",
                                        color = Color(0xFF5EEAD4),
                                        fontWeight = FontWeight.Black,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        text = "Distancia",
                                        color = CarPlayColors.TextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }

                    // OPCIÓN 2: HOSPITAL SAN FERNANDO
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    listOf(
                                        Color(0xFF1E3A8A).copy(alpha = 0.35f),
                                        Color(0xFF1E293B).copy(alpha = 0.65f)
                                    )
                                )
                            )
                            .border(
                                width = 1.4.dp,
                                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    listOf(Color(0xFF38BDF8), Color(0xFF0284C7).copy(alpha = 0.4f))
                                ),
                                shape = RoundedCornerShape(18.dp)
                            )
                            .clickable {
                                viewModel.markTraslado615(
                                    destinationName = "HOSPITAL SAN FERNANDO",
                                    lat = HOSPITAL_SAN_FERNANDO_LAT,
                                    lng = HOSPITAL_SAN_FERNANDO_LNG
                                )
                                android.widget.Toast.makeText(context, "🚑 Ruta 6-15 fijada hacia HOSPITAL SAN FERNANDO", android.widget.Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF0284C7).copy(alpha = 0.25f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("🚑", fontSize = 22.sp)
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "HOSPITAL SNFDO",
                                            color = Color.White,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 16.sp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFF38BDF8).copy(alpha = 0.25f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "HOSPITAL BASE",
                                                color = Color(0xFF7DD3FC),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "Hospital San Juan de Dios de San Fernando • Negrete #1401",
                                        color = CarPlayColors.TextSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            if (distHospitalKm != null) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "${"%.1f".format(distHospitalKm)} km",
                                        color = Color(0xFF7DD3FC),
                                        fontWeight = FontWeight.Black,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        text = "Distancia",
                                        color = CarPlayColors.TextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Botón Cancelar
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.10f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("CANCELAR", color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
