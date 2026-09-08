package com.sisbom.sisbomcrew.ui

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sisbom.sisbomcrew.CrewViewModel
import com.sisbom.sisbomcrew.R
import com.sisbom.sisbomcrew.models.AttendanceStatus
import com.sisbom.sisbomcrew.models.PersonItem
import com.sisbom.sisbomcrew.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrewDashboardScreen(
    viewModel: CrewViewModel,
    modifier: Modifier = Modifier
) {
    val licenseConfig by viewModel.licenseConfig.collectAsState()
    val personnelList by viewModel.personnelList.collectAsState()
    val filteredPersonnel = remember(personnelList, viewModel.selectedCompany.collectAsState().value, viewModel.searchQuery.collectAsState().value) {
        viewModel.getFilteredPersonnel()
    }
    val summary = remember(viewModel.attendanceMap.toMap(), personnelList) {
        viewModel.calculateSummary()
    }

    val selectedCompany by viewModel.selectedCompany.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedClave by viewModel.selectedClave.collectAsState()
    val obacName by viewModel.obacName.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    var showSubmitDialog by remember { mutableStateOf(false) }
    var showClaveDropdown by remember { mutableStateOf(false) }
    var showStatusPickerForPerson by remember { mutableStateOf<PersonItem?>(null) }
    var successToastMessage by remember { mutableStateOf<String?>(null) }

    val companies = remember(personnelList) {
        val list = mutableListOf("TODAS")
        val unique = personnelList.map { it.compania.trim() }.filter { it.isNotEmpty() }.distinct()
        list.addAll(unique)
        list
    }

    val clavesList = listOf(
        "10-0-1 (Fuego Estructural)",
        "10-0-2 (Fuego Menor)",
        "10-1-1 (Fuego Vehículo)",
        "10-2 (Fuego Pastizal / Forestal)",
        "10-3 (Salvamento / Rescate)",
        "10-4 (Rescate Vehicular)",
        "10-5 (Materiales Peligrosos)",
        "10-6 (Fuga de Gas)",
        "10-7 (Eléctrico)",
        "10-8 (No clasificado)",
        "10-9 (Otros Servicios)",
        "10-10 (Inspección / Prevención)",
        "GUARDIA NOCTURNA",
        "GUARDIA DIURNA",
        "CITACIÓN DE COMPAÑÍA",
        "ACADEMIA / EJERCICIO",
        "ACTO DEL CUERPO"
    )

    Scaffold(
        containerColor = BgDark,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Top Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Dynamic Client / App Logo
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.40f))
                                .border(1.dp, BorderDark, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (licenseConfig.logoClienteUrl.isNotEmpty()) {
                                AsyncImage(
                                    model = licenseConfig.logoClienteUrl,
                                    contentDescription = "Logo",
                                    modifier = Modifier.size(36.dp),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Image(
                                    painter = painterResource(id = R.drawable.logo_2),
                                    contentDescription = "Logo",
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }

                        Column {
                            Text(
                                text = licenseConfig.nombreCuerpo.uppercase(),
                                color = TextWhite,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(AccentEmerald))
                                Text(
                                    text = "SENTINEL CREW • EN LÍNEA",
                                    color = AccentCyan,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }

                    // Reset / Clear Button
                    IconButton(
                        onClick = { viewModel.resetAttendance() },
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(CardDark)
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reiniciar", tint = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Live Summary Counters Banner
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CardDark)
                        .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SummaryBadge("ASISTE", summary.totalDotacion, AccentEmerald)
                    SummaryBadge("FALTA", summary.faltaCount, Color(0xFF64748B))
                    SummaryBadge("CDS", summary.cdsCount, AccentCyan)
                    SummaryBadge("PERMISO", summary.permisoCount, AccentAmber)
                    SummaryBadge("L. MÉDICA", summary.licenciaCount, AccentPink)
                }
            }
        },
        bottomBar = {
            // Bottom Action Dock
            Surface(
                color = SurfaceDark,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                shadowElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Clave Selector Button
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(CardDark)
                                .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
                                .clickable { showClaveDropdown = true }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = selectedClave,
                                    color = AccentCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Clave", tint = TextMuted)
                            }

                            DropdownMenu(
                                expanded = showClaveDropdown,
                                onDismissRequest = { showClaveDropdown = false },
                                modifier = Modifier.background(SurfaceDark)
                            ) {
                                clavesList.forEach { c ->
                                    DropdownMenuItem(
                                        text = { Text(c, color = TextWhite, fontSize = 12.sp) },
                                        onClick = {
                                            viewModel.setClave(c.split(" ")[0])
                                            showClaveDropdown = false
                                        }
                                    )
                                }
                            }
                        }

                        // Submit Attendance Button
                        Button(
                            onClick = { showSubmitDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCrimson),
                            enabled = summary.totalDotacion > 0 && !isLoading
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = "Guardar", tint = TextWhite, modifier = Modifier.size(16.dp))
                                Text(
                                    text = "GUARDAR (${summary.totalDotacion})",
                                    color = TextWhite,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Filter and Search Toolbar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Search Field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    placeholder = { Text("Buscar por N° Radial, Nombre o Cargo...", color = TextMuted, fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedContainerColor = CardDark,
                        unfocusedContainerColor = CardDark
                    ),
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = "Buscar", tint = TextMuted, modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Limpiar", tint = TextMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                )

                // Company Filter Chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(companies) { comp ->
                        val isSelected = (selectedCompany == comp)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) AccentCrimson.copy(alpha = 0.25f) else CardDark)
                                .border(1.dp, if (isSelected) AccentCrimson else BorderDark, RoundedCornerShape(10.dp))
                                .clickable { viewModel.setCompany(comp) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = comp,
                                color = if (isSelected) AccentCrimson else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Firefighter List
            if (filteredPersonnel.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (personnelList.isEmpty()) "Sincronizando dotación con Firestore..." else "No se encontraron bomberos.",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredPersonnel, key = { it.idRegistro }) { person ->
                        val currentStatus = viewModel.attendanceMap[person.idRegistro] ?: AttendanceStatus.FALTA

                        FirefighterCard(
                            person = person,
                            status = currentStatus,
                            onStatusClick = { viewModel.cycleStatus(person.idRegistro) },
                            onLongClick = { showStatusPickerForPerson = person }
                        )
                    }
                }
            }
        }
    }

    // Modal para Cambiar Estado Específico
    if (showStatusPickerForPerson != null) {
        val target = showStatusPickerForPerson!!
        AlertDialog(
            onDismissRequest = { showStatusPickerForPerson = null },
            containerColor = SurfaceDark,
            title = {
                Text(
                    text = "${target.idRadial.ifEmpty { "•" }} - ${target.nombreBombero}",
                    color = TextWhite,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AttendanceStatus.values().forEach { st ->
                        Button(
                            onClick = {
                                viewModel.setStatus(target.idRegistro, st)
                                showStatusPickerForPerson = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(st.colorHex)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "${st.shortLabel} • ${st.label}",
                                color = TextWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // Modal de Confirmación y Cierre de Lista
    if (showSubmitDialog) {
        var localObac by remember { mutableStateOf(obacName) }
        var localListaPor by remember { mutableStateOf("") }
        var localLugar by remember { mutableStateOf("") }
        var localObs by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showSubmitDialog = false },
            containerColor = SurfaceDark,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.AssignmentTurnedIn, contentDescription = null, tint = AccentEmerald)
                    Text("Finalizar Asistencia", color = TextWhite, fontWeight = FontWeight.Black, fontSize = 16.sp)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Total Asistentes: ${summary.totalDotacion} voluntarios.",
                        color = AccentEmerald,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = localObac,
                        onValueChange = { localObac = it.uppercase() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("OFICIAL AL MANDO (OBAC)", color = TextMuted, fontSize = 10.sp) },
                        placeholder = { Text("EJ: 1 - COMANDANTE", color = TextMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = BorderDark,
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            focusedContainerColor = CardDark,
                            unfocusedContainerColor = CardDark
                        )
                    )

                    OutlinedTextField(
                        value = localLugar,
                        onValueChange = { localLugar = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("LUGAR / DIRECCIÓN (OPCIONAL)", color = TextMuted, fontSize = 10.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = BorderDark,
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            focusedContainerColor = CardDark,
                            unfocusedContainerColor = CardDark
                        )
                    )

                    OutlinedTextField(
                        value = localObs,
                        onValueChange = { localObs = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("OBSERVACIONES", color = TextMuted, fontSize = 10.sp) },
                        maxLines = 2,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = BorderDark,
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            focusedContainerColor = CardDark,
                            unfocusedContainerColor = CardDark
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setObac(localObac)
                        viewModel.setListaPor(localListaPor)
                        viewModel.setLocation(localLugar)
                        viewModel.setObservacion(localObs)

                        viewModel.submitAttendanceList(
                            onSuccess = { msg ->
                                showSubmitDialog = false
                                successToastMessage = msg
                            },
                            onError = { err ->
                                // Show error
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald),
                    shape = RoundedCornerShape(10.dp),
                    enabled = localObac.trim().isNotEmpty() && !isLoading
                ) {
                    Text("REGISTRAR LISTA", fontWeight = FontWeight.Black, color = TextWhite)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSubmitDialog = false }) {
                    Text("CANCELAR", color = TextMuted)
                }
            }
        )
    }

    // Toast de éxito
    if (successToastMessage != null) {
        AlertDialog(
            onDismissRequest = { successToastMessage = null },
            containerColor = SurfaceDark,
            title = {
                Text("Operación Exitosa", color = AccentEmerald, fontWeight = FontWeight.Black)
            },
            text = {
                Text(successToastMessage ?: "", color = TextWhite)
            },
            confirmButton = {
                Button(
                    onClick = { successToastMessage = null },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald)
                ) {
                    Text("ACEPTAR", color = TextWhite, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
private fun SummaryBadge(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            color = color,
            fontSize = 16.sp,
            fontWeight = FontWeight.Black
        )
        Text(
            text = label,
            color = TextMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun FirefighterCard(
    person: PersonItem,
    status: AttendanceStatus,
    onStatusClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val statusColor = Color(status.colorHex)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onStatusClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        border = androidx.compose.foundation.BorderStroke(
            width = if (status == AttendanceStatus.ASISTE || status == AttendanceStatus.CDS) 1.5.dp else 1.dp,
            color = if (status == AttendanceStatus.ASISTE) AccentEmerald.copy(alpha = 0.6f) else BorderDark
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Radial ID Pill
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .border(1.dp, if (person.idRadial.isNotEmpty()) AccentCyan.copy(alpha = 0.4f) else BorderDark, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = person.idRadial.ifEmpty { "—" },
                        color = if (person.idRadial.isNotEmpty()) AccentCyan else TextMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                // Name and Cargo
                Column {
                    Text(
                        text = person.nombreBombero,
                        color = TextWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (person.cargo.isNotEmpty()) {
                            Text(
                                text = person.cargo.uppercase(),
                                color = AccentAmber,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (person.compania.isNotEmpty()) {
                            Text(
                                text = person.compania,
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            // Big Tactile Status Button
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(statusColor)
                    .clickable { onStatusClick() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = status.shortLabel,
                    color = TextWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}
