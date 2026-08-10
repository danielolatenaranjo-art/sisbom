package com.sisbom.misisbom

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import coil.compose.AsyncImage
import androidx.compose.material.icons.filled.Person

// ==========================================
// 1. TABS ACTIVIDAD (ESTADO Y EMERGENCIAS)
// ==========================================
@Composable
fun StatusButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isActive: Boolean,
    isEnabled: Boolean,
    activeColor: Color,
    inactiveTextColor: Color,
    inactiveBorderColorLight: Color,
    inactiveBorderColorDark: Color,
    inactiveBgDark: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalDarkMode.current
    val opacity = if (isEnabled) 1f else 0.4f
    
    val bgColor = when {
        isActive -> activeColor
        isDark -> inactiveBgDark
        else -> Color.White
    }
    
    val textColor = if (isActive) Color.White else inactiveTextColor
    
    val borderColor = when {
        isActive -> activeColor
        isDark -> inactiveBorderColorDark
        else -> inactiveBorderColorLight
    }
    
    Box(
        modifier = modifier
            .alpha(opacity)
            .height(110.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .border(2.dp, borderColor, RoundedCornerShape(20.dp))
            .then(if (isEnabled && !isActive) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                color = textColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = textColor.copy(alpha = 0.7f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ActividadTab(viewModel: SisBomViewModel, paddingValues: PaddingValues) {
    val user = viewModel.currentUser ?: return
    val isDark = LocalDarkMode.current
    
    val rawEstado = user.estado.trim().uppercase()
    val inService = user.enServicio.trim() != "0" && user.enServicio.trim().isNotEmpty() && !user.enServicio.trim().startsWith("-")
    val isSuspended = rawEstado.contains("SUSPENDIDO")
    val isAllowedSpecial = rawEstado == "CDS" || rawEstado.contains("LICENCIA") || rawEstado == "PERMISO"
    val isSpecial = isSuspended || isAllowedSpecial

    val activeDispatches = if (isSpecial && rawEstado != "0-9") emptyList() else viewModel.dispatchesList.filter { it.operadorFinal.isEmpty() }

    val is09Active = rawEstado == "0-9"
    val is08Active = rawEstado == "0-8" || isSuspended || isAllowedSpecial

    val is09Enabled = !inService && !isSuspended
    val is08Enabled = !inService

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = paddingValues.calculateTopPadding() + 16.dp,
            bottom = paddingValues.calculateBottomPadding() + 16.dp,
            start = 16.dp,
            end = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val isOpActive = viewModel.centralOperatorName.isNotEmpty()
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(if (isOpActive) Color(0x2610B981) else Color.Gray.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isOpActive) Icons.Filled.CheckCircle else Icons.Filled.Info,
                            contentDescription = null,
                            tint = if (isOpActive) Color(0xFF10B981) else Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "OPERADOR CENTRAL DE ALARMAS",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                        )
                        Text(
                            text = if (isOpActive) "EN CONSOLA: ${viewModel.centralOperatorName.uppercase()}" else "CENTRAL CERRADA / SIN OPERADOR",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            color = if (isDark) Color.White else Color(0xFF1E293B)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(if (isOpActive) Color(0x2610B981) else Color.Black.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isOpActive) "ACTIVO" else "CERRADA",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            color = if (isOpActive) Color(0xFF10B981) else Color.Gray
                        )
                    }
                }
            }
        }

        if (activeDispatches.isEmpty()) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(Color.White.copy(alpha = 0.15f), CircleShape)
                                .border(1.dp, if (isDark) Color(0x33FFFFFF) else Color(0x33000000), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = if (isDark) Color.White else TextDark,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Sin emergencias activas en este momento",
                            color = if (isDark) Color.White else TextDark,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(activeDispatches) { dispatch ->
                DispatchItemCard(dispatch, viewModel)
            }
        }
    }
}

@Composable
fun DispatchItemCard(dispatch: Dispatch, viewModel: SisBomViewModel) {
    val user = viewModel.currentUser ?: return
    val isAttending = user.enServicio == dispatch.idServicio
    val isDark = LocalDarkMode.current
    
    val baseState = user.estado.trim().uppercase()
    val inService = user.enServicio.trim() != "0" && user.enServicio.trim().isNotEmpty() && !user.enServicio.trim().startsWith("-")
    val isSpecial = baseState.contains("SUSPENDIDO") || baseState == "CDS" || baseState.contains("LICENCIA") || baseState == "PERMISO"
    
    val cardBorderColor = if (isAttending) {
        Color(0xFF10B981)
    } else {
        if (isDark) Color(0xFFB91C1C) else Color(0xFFEF4444).copy(alpha = 0.5f)
    }
    
    val titleText = if (isAttending) "SALIENDO A SERVICIO" else "¡DESPACHO ACTIVO!"
    val titleColor = if (isAttending) GoGreen else BomberosRedLight
    
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = cardBorderColor,
        isDarkTheme = isDark
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = titleText,
                    color = titleColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = cleanSheetPrefix(dispatch.horaDespacho).ifEmpty { "--:--" },
                    color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(if (isDark) Color(0x66000000) else Color(0x1A000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            val claveText = if (dispatch.clave == "10-12" && dispatch.claveApoyo.isNotEmpty()) {
                "${dispatch.clave} (${dispatch.claveApoyo})"
            } else {
                dispatch.clave
            }
            Text(
                text = claveText,
                color = if (isDark) Color.White else TextDark,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                lineHeight = 34.sp
            )
            Text(
                text = dispatch.lugar.ifEmpty { "Ubicación no precisada" },
                color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isDark) Color(0x4D000000) else Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
                    .border(1.dp, if (isDark) Color(0x1AFFFFFF) else Color(0x1A000000), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "PRE-INFORME:",
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = dispatch.preinforme.ifEmpty { "A la espera de pre-informe oficial de primera máquina." },
                        color = if (isDark) Color.White else TextDark,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    androidx.compose.material3.Divider(
                        color = if (isDark) Color(0x1AFFFFFF) else Color(0x1A000000),
                        thickness = 1.dp
                    )
                    Text(
                        text = "UNIDADES DESPACHADAS:",
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    val carrosList = dispatch.carros.split(Regex("[,/ ]+"))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }

                    if (carrosList.isEmpty()) {
                        Text(
                            text = "---",
                            color = if (isDark) Color(0xFFF87171) else Color(0xFFB91C1C),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black
                        )
                    } else if (carrosList.size == 1 && carrosList[0].equals("PERSONAL", ignoreCase = true)) {
                        Text(
                            text = "PERSONAL",
                            color = if (isDark) Color(0xFFF87171) else Color(0xFFB91C1C),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black
                        )
                    } else {
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            carrosList.forEach { carroName ->
                                val vehicleData = dispatch.unidades[carroName]
                                val solConductor = vehicleData?.get("solicitudConductorAt")?.toString() ?: ""
                                val solPersonal = vehicleData?.get("solicitudPersonalAt")?.toString() ?: ""

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .background(if (isDark) Color(0x1AFFFFFF) else Color(0x0D000000), RoundedCornerShape(8.dp))
                                        .border(1.dp, if (isDark) Color(0x0DFFFFFF) else Color(0x0D000000), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    // Carro rectangle
                                    Box(
                                        modifier = Modifier
                                            .background(if (isDark) Color(0xFFB91C1C) else Color(0xFFEF4444), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = carroName,
                                            color = Color.White,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 12.sp
                                        )
                                    }

                                    if (solConductor.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0xFFF59E0B).copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                                .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = "12-10 ($solConductor)",
                                                color = Color(0xFFD97706),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                        }
                                    }

                                    if (solPersonal.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0xFF3B82F6).copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                                .border(1.dp, Color(0xFF3B82F6).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = "6-6 ($solPersonal)",
                                                color = Color(0xFF2563EB),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            val clean67 = cleanSheetPrefix(dispatch.hora67)
            if (clean67.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isDark) Color(0x1A10B981) else Color(0x1A34D399), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF10B981).copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "✓ CONTROL DE EMERGENCIA (6-7)",
                            color = if (isDark) Color(0xFF34D399) else Color(0xFF059669),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = clean67,
                            color = if (isDark) Color(0xFF34D399) else Color(0xFF059669),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            val isDeclined = user.enServicio == "-${dispatch.idServicio}"

            if (isAttending) {
                Button(
                    onClick = { viewModel.attendService(dispatch.idServicio, false) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDark) Color(0x26EF4444) else Color(0x1AEF4444),
                        contentColor = Color(0xFFEF4444)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "CANCELAR ASISTENCIA",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            } else {
                val canAttend = baseState == "0-9" && !inService && !isSpecial
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.attendService(dispatch.idServicio, true) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (canAttend) GoGreen else if (isDark) Color(0x1AFFFFFF) else Color(0x1A000000),
                            contentColor = if (canAttend) Color.White else if (isDark) Color(0xFF475569) else Color(0xFF94A3B8)
                        ),
                        enabled = canAttend,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ASISTIR",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    Button(
                        onClick = { viewModel.declineService(dispatch.idServicio) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDeclined) Color(0xFFEF4444) else if (isDark) Color(0x26EF4444) else Color(0x1AEF4444),
                            contentColor = if (isDeclined) Color.White else Color(0xFFEF4444)
                        ),
                        enabled = !isDeclined && !inService && baseState == "0-9",
                        shape = RoundedCornerShape(12.dp),
                        border = if (isDeclined || !(!isDeclined && !inService && baseState == "0-9")) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isDeclined) "NO ASISTIRÉ" else "NO ASISTIR",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }
    }
}

// Helper functions for cleaning dates/hours
fun cleanSheetPrefix(s: String): String {
    var result = s.replace("\"", "").trim()
    val quoteChars = listOf("'", "´", "`", "‘", "’")
    var changed = true
    while (changed) {
        changed = false
        for (q in quoteChars) {
            if (result.startsWith(q)) {
                result = result.substring(q.length).trim()
                changed = true
            }
            if (result.endsWith(q)) {
                result = result.substring(0, result.length - q.length).trim()
                changed = true
            }
        }
    }
    return result
}

fun formatOrderDate(dateStr: String): String {
    val clean = cleanSheetPrefix(dateStr)
    val parts = clean.split("/")
    if (parts.size != 3) return clean.uppercase()
    val day = parts[0].toIntOrNull() ?: return clean.uppercase()
    val monthNum = parts[1].toIntOrNull() ?: return clean.uppercase()
    val year = parts[2]
    
    val monthName = when (monthNum) {
        1 -> "ENERO"
        2 -> "FEBRERO"
        3 -> "MARZO"
        4 -> "ABRIL"
        5 -> "MAYO"
        6 -> "JUNIO"
        7 -> "JULIO"
        8 -> "AGOSTO"
        9 -> "SEPTIEMBRE"
        10 -> "OCTUBRE"
        11 -> "NOVIEMBRE"
        12 -> "DICIEMBRE"
        else -> return clean.uppercase()
    }
    return "$day DE $monthName DEL $year"
}

@Composable
fun DespachoTab(viewModel: SisBomViewModel, paddingValues: PaddingValues) {
    val isDark = LocalDarkMode.current
    val isCentral = viewModel.isCentralActive
    
    var clave by remember { mutableStateOf("") }
    var lugar by remember { mutableStateOf("") }
    var preinforme by remember { mutableStateOf("") }
    val selectedVehicles = remember { mutableStateListOf<String>() }
    var showConfirmDialog by remember { mutableStateOf(false) }

    val clavesRápidas = listOf("10-0", "10-1", "10-2", "10-3", "10-4", "10-5", "10-6", "10-7", "10-8", "10-9", "10-10", "10-12", "10-15", "9-0")
    val lugaresSugeridos = emptyList<String>()

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(scrollState)
    ) {
        Spacer(modifier = Modifier.height(paddingValues.calculateTopPadding() + 16.dp))

        Text(
            text = "CENTRAL DE ALARMAS - DESPACHO MÓVIL",
            color = if (isDark) Color.White else TextDark,
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (!isCentral) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = BomberosRed.copy(alpha = 0.3f)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(BomberosRed.copy(alpha = 0.1f), CircleShape)
                                .border(1.dp, BomberosRed.copy(alpha = 0.3f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = BomberosRed,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "ACCESO RESTRINGIDO",
                            color = if (isDark) Color.White else TextDark,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Solo el Operador Central activo de Comandancia puede emitir despachos de emergencia.",
                            color = if (isDark) Color(0xFF94A3B8) else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        } else {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "1. SELECCIONAR CLAVE",
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        clavesRápidas.forEach { c ->
                            val isSel = clave == c
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(width = 54.dp, height = 36.dp)
                                    .background(
                                        if (isSel) BomberosRed else if (isDark) Color(0x1DFFFFFF) else Color(0x0D000000),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isSel) BomberosRed else if (isDark) Color(0x1AFFFFFF) else Color(0x1A000000),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable { clave = c }
                            ) {
                                Text(
                                    text = c,
                                    color = if (isSel) Color.White else if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "2. DETALLES DE UBICACIÓN",
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    OutlinedTextField(
                        value = lugar,
                        onValueChange = { lugar = it },
                        label = { Text("Ubicación exacta") },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BomberosRed,
                            unfocusedBorderColor = if (isDark) Color(0x1AFFFFFF) else Color(0x1A000000),
                            focusedTextColor = if (isDark) Color.White else TextDark,
                            unfocusedTextColor = if (isDark) Color.White else TextDark,
                            focusedLabelColor = BomberosRed,
                            unfocusedLabelColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(lugaresSugeridos) { l ->
                            Box(
                                modifier = Modifier
                                    .background(if (isDark) Color(0x1AFFFFFF) else Color(0x0D000000), RoundedCornerShape(12.dp))
                                    .border(1.dp, if (isDark) Color(0x0DFFFFFF) else Color(0x0D000000), RoundedCornerShape(12.dp))
                                    .clickable {
                                        lugar = if (lugar.trim().isEmpty()) l else "${lugar.trim()}, $l"
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = l.uppercase(),
                                    color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "3. PRE-INFORME INICIAL",
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    OutlinedTextField(
                        value = preinforme,
                        onValueChange = { preinforme = it },
                        label = { Text("Pre-informe preliminar") },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BomberosRed,
                            unfocusedBorderColor = if (isDark) Color(0x1AFFFFFF) else Color(0x1A000000),
                            focusedTextColor = if (isDark) Color.White else TextDark,
                            unfocusedTextColor = if (isDark) Color.White else TextDark,
                            focusedLabelColor = BomberosRed,
                            unfocusedLabelColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "4. CARROS A DESPACHAR",
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    val sortedVehicles = viewModel.vehiclesList.sortedBy { it.idCarro }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sortedVehicles.chunked(3).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowItems.forEach { v ->
                                    val isAvailable = (v.estado == "1" || v.estado == "0-9") && (v.enServicio == "0" || v.enServicio.isEmpty())
                                    val isSel = selectedVehicles.contains(v.idCarro)

                                    if (!isAvailable) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(64.dp)
                                                .background(if (isDark) Color(0x1AFFFFFF) else Color(0x0D000000), RoundedCornerShape(12.dp))
                                                .border(1.dp, if (isDark) Color(0x0DFFFFFF) else Color(0x0D000000), RoundedCornerShape(12.dp))
                                                .alpha(0.5f)
                                                .padding(4.dp)
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(v.idCarro, color = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8), fontSize = 18.sp, fontWeight = FontWeight.Black)
                                                Text("EN SERVICIO", color = if (isDark) Color(0xFF475569) else Color(0xFF94A3B8), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    } else {
                                        val bg = if (isSel) BomberosRed else if (isDark) Color(0x1DFFFFFF) else Color(0x0D000000)
                                        val borderCol = if (isSel) BomberosRed else if (isDark) Color(0x1AFFFFFF) else Color(0x1A000000)
                                        val textCol = if (isSel) Color.White else if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569)
                                        val badgeColor = if (isSel) Color(0xFFFECACA) else GoGreen

                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(64.dp)
                                                .background(bg, RoundedCornerShape(12.dp))
                                                .border(1.dp, borderCol, RoundedCornerShape(12.dp))
                                                .clickable {
                                                    if (isSel) selectedVehicles.remove(v.idCarro)
                                                    else selectedVehicles.add(v.idCarro)
                                                }
                                                .padding(4.dp)
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(v.idCarro, color = textCol, fontSize = 18.sp, fontWeight = FontWeight.Black)
                                                Box(
                                                    modifier = Modifier
                                                        .background(badgeColor.copy(alpha = 0.15f), CircleShape)
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = if (isSel) "SEL" else "DISP",
                                                        color = badgeColor,
                                                        fontSize = 7.sp,
                                                        fontWeight = FontWeight.Black
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                if (rowItems.size < 3) {
                                    for (i in 0 until (3 - rowItems.size)) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    val ready = clave.isNotEmpty() && lugar.isNotEmpty() && selectedVehicles.isNotEmpty()

                    Button(
                        onClick = { if (ready) showConfirmDialog = true },
                        enabled = ready,
                        colors = ButtonDefaults.buttonColors(containerColor = BomberosRed),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "DESPACHAR ALARMA GENERAL",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }

        if (showConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showConfirmDialog = false },
                containerColor = if (isDark) Color(0xFF1E293B) else Color.White,
                title = { Text("CONFIRMAR DESPACHO", color = if (isDark) Color.White else TextDark, fontWeight = FontWeight.Black) },
                text = {
                    Text(
                        text = "¿Seguro que desea despachar la clave $clave a $lugar con los carros ${selectedVehicles.joinToString(", ")}?",
                        color = if (isDark) Color.White else TextDark
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.dispatchFromCentral(clave, lugar, preinforme, selectedVehicles)
                            showConfirmDialog = false
                            clave = ""
                            lugar = ""
                            preinforme = ""
                            selectedVehicles.clear()
                        }
                    ) {
                        Text("SÍ, DESPACHAR", color = GoGreen, fontWeight = FontWeight.Black)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmDialog = false }) {
                        Text("CANCELAR", color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
        
        Spacer(modifier = Modifier.height(paddingValues.calculateBottomPadding() + 16.dp))
    }
}

@Composable
fun OrdenesTab(viewModel: SisBomViewModel, paddingValues: PaddingValues) {
    val ordenes = viewModel.alertsList.filter { it.tipo == "orden" }
        .sortedByDescending { it.idAlerta }
    val isDark = LocalDarkMode.current
    var showCreateOrderDialog by remember { mutableStateOf(false) }
    val currentUser = viewModel.currentUser
    val canPublishOrders = currentUser?.cargo?.trim()?.uppercase() == "COMANDANTE" || currentUser?.idRadial?.trim() == "1"

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 16.dp,
                bottom = paddingValues.calculateBottomPadding() + 80.dp,
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (ordenes.isEmpty()) {
                item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    ) {
                        Text(
                            text = "No se registran órdenes oficiales este mes.",
                            color = Color(0xFF64748B),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                val chunkedOrdenes = ordenes.chunked(2)
                items(chunkedOrdenes) { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowItems.forEach { ord ->
                            Box(modifier = Modifier.weight(1f)) {
                                OrdenItemCard(ord, viewModel)
                            }
                        }
                        if (rowItems.size < 2) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        if (canPublishOrders) {
            FloatingActionButton(
                onClick = { showCreateOrderDialog = true },
                containerColor = BomberosRed,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = paddingValues.calculateBottomPadding() + 16.dp, end = 16.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Emitir Orden")
            }
        }
    }

    if (showCreateOrderDialog) {
        var numero by remember { mutableStateOf("") }
        var fecha by remember {
            val now = Date()
            mutableStateOf(SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(now))
        }
        var cuerpo by remember { mutableStateOf("") }
        var isSaving by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        val defaultFirmaNombre = currentUser?.nombreBombero?.trim()?.uppercase() ?: ""
        val defaultFirmaCargo = currentUser?.cargo?.trim()?.uppercase() ?: ""

        var firmaNombre by remember { mutableStateOf(defaultFirmaNombre) }
        var firmaCargo by remember { mutableStateOf(defaultFirmaCargo) }

        AlertDialog(
            onDismissRequest = { if (!isSaving) showCreateOrderDialog = false },
            containerColor = if (isDark) Color(0xFF1E293B) else Color.White,
            confirmButton = {
                Button(
                    onClick = {
                        if (numero.isBlank() || cuerpo.isBlank() || fecha.isBlank() || firmaNombre.isBlank() || firmaCargo.isBlank()) {
                            errorMessage = "Complete todos los campos."
                            return@Button
                        }
                        isSaving = true
                        errorMessage = null
                        viewModel.publishOrder(
                            numero = numero,
                            fecha = fecha,
                            cuerpo = cuerpo,
                            fNombre = firmaNombre,
                            fCargo = firmaCargo,
                            onSuccess = {
                                isSaving = false
                                showCreateOrderDialog = false
                            },
                            onFailure = { err ->
                                isSaving = false
                                errorMessage = err.message ?: "Error al publicar"
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BomberosRed),
                    enabled = !isSaving
                ) {
                    if (isSaving) {
                        androidx.compose.material3.CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                    } else {
                        Text("PUBLICAR", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (!isSaving) {
                    TextButton(onClick = { showCreateOrderDialog = false }) {
                        Text("CANCELAR", color = if (isDark) Color.White else TextDark)
                    }
                }
            },
            title = {
                Text("EMITIR ORDEN DEL DÍA", fontWeight = FontWeight.Black, fontSize = 18.sp, color = if (isDark) Color.White else TextDark)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (errorMessage != null) {
                        Text(errorMessage!!, color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedTextField(
                        value = numero,
                        onValueChange = { numero = it },
                        label = { Text("Número de Orden (Ej: 01-2026)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BomberosRed,
                            focusedLabelColor = BomberosRed,
                            unfocusedBorderColor = if (isDark) Color(0x1AFFFFFF) else Color(0x1A000000),
                            focusedTextColor = if (isDark) Color.White else TextDark,
                            unfocusedTextColor = if (isDark) Color.White else TextDark,
                            unfocusedLabelColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                        )
                    )

                    OutlinedTextField(
                        value = fecha,
                        onValueChange = { fecha = it },
                        label = { Text("Fecha Orden (DD-MM-YYYY)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BomberosRed,
                            focusedLabelColor = BomberosRed,
                            unfocusedBorderColor = if (isDark) Color(0x1AFFFFFF) else Color(0x1A000000),
                            focusedTextColor = if (isDark) Color.White else TextDark,
                            unfocusedTextColor = if (isDark) Color.White else TextDark,
                            unfocusedLabelColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                        )
                    )

                    OutlinedTextField(
                        value = cuerpo,
                        onValueChange = { cuerpo = it },
                        label = { Text("Cuerpo / Mensaje de la Orden") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BomberosRed,
                            focusedLabelColor = BomberosRed,
                            unfocusedBorderColor = if (isDark) Color(0x1AFFFFFF) else Color(0x1A000000),
                            focusedTextColor = if (isDark) Color.White else TextDark,
                            unfocusedTextColor = if (isDark) Color.White else TextDark,
                            unfocusedLabelColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                        )
                    )

                    OutlinedTextField(
                        value = firmaNombre,
                        onValueChange = { firmaNombre = it },
                        label = { Text("Firma Nombre (Quien emite)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BomberosRed,
                            focusedLabelColor = BomberosRed,
                            unfocusedBorderColor = if (isDark) Color(0x1AFFFFFF) else Color(0x1A000000),
                            focusedTextColor = if (isDark) Color.White else TextDark,
                            unfocusedTextColor = if (isDark) Color.White else TextDark,
                            unfocusedLabelColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                        )
                    )

                    OutlinedTextField(
                        value = firmaCargo,
                        onValueChange = { firmaCargo = it },
                        label = { Text("Firma Cargo (Ej: COMANDANTE)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BomberosRed,
                            focusedLabelColor = BomberosRed,
                            unfocusedBorderColor = if (isDark) Color(0x1AFFFFFF) else Color(0x1A000000),
                            focusedTextColor = if (isDark) Color.White else TextDark,
                            unfocusedTextColor = if (isDark) Color.White else TextDark,
                            unfocusedLabelColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                        )
                    )
                }
            }
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OrdenItemCard(orden: Alert, viewModel: SisBomViewModel) {
    var showDialog by remember { mutableStateOf(false) }
    val isDark = LocalDarkMode.current
    val isConforme = orden.conforme.split(",").map { it.trim().uppercase() }
        .contains(viewModel.currentUser?.idRadial?.uppercase())

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                showDialog = true
                if (!isConforme) {
                    viewModel.registerConforme(orden)
                }
            },
        borderColor = if (isConforme) GoGreen.copy(alpha = 0.5f) else Color.Gray.copy(alpha = 0.3f),
        isDarkTheme = isDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Assignment,
                    contentDescription = null,
                    tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                    modifier = Modifier.size(22.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(14.dp))
            
            Text(
                text = "N°  ${cleanSheetPrefix(orden.numeroOrden)}",
                color = if (isDark) Color.White else TextDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(2.dp))
            
            Text(
                text = cleanSheetPrefix(orden.fechaOrden),
                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(14.dp))
            Divider(color = if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0), thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isConforme) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = GoGreen,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "VISTO",
                        color = GoGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        tint = BomberosRed,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "PENDIENTE",
                        color = BomberosRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }

    if (showDialog) {
        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            val view = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.SideEffect {
                val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
                window?.let { w ->
                    w.setLayout(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    w.setStatusBarColor(android.graphics.Color.TRANSPARENT)
                    w.setNavigationBarColor(android.graphics.Color.TRANSPARENT)
                    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
                    val insetsController = androidx.core.view.WindowCompat.getInsetsController(w, view)
                    insetsController.isAppearanceLightStatusBars = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        w.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isDark) NavyDeep else Color(0xFFCBD5E1))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    // Header (Full width)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isDark) NavyDark else Color(0xFF475569))
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Assignment,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ORDEN DEL DÍA ${cleanSheetPrefix(orden.numeroOrden)}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "${if (viewModel.saasClientName.isNotEmpty()) viewModel.saasClientName.uppercase() else "SISBOM"}, ${formatOrderDate(orden.fechaOrden).uppercase()}",
                                color = Color(0xFF9CA3AF),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(
                            onClick = { showDialog = false },
                            modifier = Modifier
                                .size(28.dp)
                                .background(if (isDark) Color.White.copy(alpha = 0.15f) else Color(0x33000000), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Cerrar",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(if (isDark) NavyDeep else Color(0xFFCBD5E1))
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 20.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Document Card (Always White Page for Authentic PDF Look)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(elevation = 4.dp, shape = RoundedCornerShape(8.dp))
                                .background(Color.White, RoundedCornerShape(8.dp))
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column {
                                    Text(
                                        text = "CUERPO DE BOMBEROS",
                                        color = Color(0xFF1F2937),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Text(
                                        text = "“Abnegación y Sacrificio”",
                                        color = BomberosRed,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        style = androidx.compose.ui.text.TextStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                    )
                                    Text(
                                        text = "Fundado 20-Nov-1963",
                                        color = Color(0xFF64748B),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "COMANDANCIA",
                                        color = Color(0xFF1F2937),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Text(
                                        text = if (viewModel.saasClientName.isNotEmpty()) viewModel.saasClientName.uppercase() else "PLACILLA",
                                        color = Color(0xFF1F2937),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }

                                coil.compose.AsyncImage(
                                    model = viewModel.getClientLogoModel(),
                                    contentDescription = null,
                                    placeholder = androidx.compose.ui.res.painterResource(id = R.drawable.logo),
                                    error = androidx.compose.ui.res.painterResource(id = R.drawable.logo),
                                    modifier = Modifier.size(64.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Divider(color = Color.Black, thickness = 1.5.dp)
                            Spacer(modifier = Modifier.height(12.dp))

                            // Date on the right
                            Text(
                                text = "Placilla, ${cleanSheetPrefix(orden.fechaOrden)}",
                                color = Color(0xFF374151),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.End)
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Title Centered
                            Text(
                                text = "ORDEN DEL DIA ${cleanSheetPrefix(orden.numeroOrden)}",
                                color = Color(0xFF1F2937),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            // Message Justified
                            Text(
                                text = orden.mensajeAlerta.replace("|", "\n"),
                                color = Color(0xFF374151),
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                textAlign = TextAlign.Justify,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            // Standard text
                            Text(
                                text = "TODA MODIFICACION A LA PRESENTE SE HARA DE FORMA ESCRITA O VERBAL\n\nCOMUNIQUESE Y CUMPLASE",
                                color = Color(0xFF1E293B),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            // Comandante Signature Image (Centered below COMUNIQUESE Y CUMPLASE)
                            Image(
                                painter = androidx.compose.ui.res.painterResource(id = R.drawable.firma_comandante),
                                contentDescription = "Firma Comandante",
                                modifier = Modifier
                                    .width(240.dp)
                                    .height(120.dp)
                                    .align(Alignment.CenterHorizontally)
                            )

                            Spacer(modifier = Modifier.height(20.dp))
                            Divider(color = Color.Black, thickness = 1.5.dp)
                            Spacer(modifier = Modifier.height(8.dp))

                            // Footer text
                            Text(
                                text = "MIRAFLORES Nº 697-FONO 722858606- placilla@bomberos.cl -PLACILLA VI REGION",
                                color = Color(0xFF64748B),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun AlertasTab(viewModel: SisBomViewModel, paddingValues: PaddingValues) {
    val currentUser = viewModel.currentUser
    val myRadial = currentUser?.idRadial ?: ""
    val alerts = viewModel.alertsList.filter { it.tipo != "orden" }.filter { alert ->
        val aQuien = alert.aQuienAlerta.trim().uppercase()
        if (aQuien == "TC" || aQuien == "SC") {
            true
        } else {
            val myId = currentUser?.idRegistro?.trim() ?: ""
            val ids = alert.aQuienAlerta.split(",").map { it.trim() }
            val myName = currentUser?.nombreBombero?.trim() ?: ""
            myId.isNotEmpty() && (ids.contains(myId) || (myName.isNotEmpty() && alert.quienAlerta.contains(myName)))
        }
    }.sortedWith(compareByDescending<Alert> {
        it.fijar.split(",").map { p -> p.trim().uppercase() }.contains(myRadial.uppercase())
    }.thenByDescending { it.idAlerta })
    val isDark = LocalDarkMode.current
    val canPublishAlerts = currentUser?.cargo?.trim()?.uppercase() == "COMANDANTE" || currentUser?.idRadial?.trim() == "1"
    var showCreateDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 16.dp,
                bottom = paddingValues.calculateBottomPadding() + 16.dp,
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {


            if (alerts.isEmpty()) {
                item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    ) {
                        Text(
                            text = "No se registran alertas en la cartelera.",
                            color = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                items(alerts) { alert ->
                    AlertItemCard(alert, viewModel) {
                        viewModel.activeChatAlert = alert
                    }
                }
            }
        }

        if (canPublishAlerts) {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = BomberosRed,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = paddingValues.calculateBottomPadding() + 16.dp, end = 16.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Emitir Alerta")
            }
        }
    }

    if (showCreateDialog) {
        var razon by remember { mutableStateOf("") }
        var mensaje by remember { mutableStateOf("") }
        var grado by remember { mutableStateOf("1") }
        var duracion by remember { mutableStateOf("i") }
        var aQuienTipo by remember { mutableStateOf("TC") }
        var aQuienEspecifico by remember { mutableStateOf("") }
        var isSaving by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { if (!isSaving) showCreateDialog = false },
            containerColor = if (isDark) Color(0xFF1E293B) else Color.White,
            confirmButton = {
                Button(
                    onClick = {
                        if (razon.isBlank() || mensaje.isBlank()) {
                            errorMessage = "Complete los campos obligatorios."
                            return@Button
                        }
                        val finalAQuien = if (aQuienTipo == "especifico") {
                            val radials = aQuienEspecifico.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            val registrations = radials.mapNotNull { rad ->
                                viewModel.personnelList.find { p -> p.idRadial.trim() == rad }?.idRegistro?.trim()
                            }
                            if (registrations.isEmpty()) {
                                errorMessage = "No se encontraron usuarios para los IDs radiales ingresados."
                                return@Button
                            }
                            registrations.joinToString(",")
                        } else {
                            aQuienTipo
                        }
                        isSaving = true
                        errorMessage = null
                        viewModel.publishAlert(
                            razon = razon.uppercase(),
                            mensaje = mensaje.uppercase(),
                            grado = grado,
                            duracion = duracion,
                            aQuien = finalAQuien.uppercase(),
                            onSuccess = {
                                isSaving = false
                                showCreateDialog = false
                            },
                            onFailure = { err ->
                                isSaving = false
                                errorMessage = err.message ?: "Error al publicar"
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BomberosRed),
                    enabled = !isSaving
                ) {
                    if (isSaving) {
                        androidx.compose.material3.CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                    } else {
                        Text("PUBLICAR", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (!isSaving) {
                    TextButton(onClick = { showCreateDialog = false }) {
                        Text("CANCELAR", color = if (isDark) Color.White else TextDark)
                    }
                }
            },
            title = {
                Text("EMITIR ALERTA", fontWeight = FontWeight.Black, fontSize = 18.sp, color = if (isDark) Color.White else TextDark)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (errorMessage != null) {
                        Text(errorMessage!!, color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedTextField(
                        value = razon,
                        onValueChange = { razon = it },
                        label = { Text("Motivo / Título") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BomberosRed,
                            focusedLabelColor = BomberosRed,
                            unfocusedBorderColor = if (isDark) Color(0x1AFFFFFF) else Color(0x1A000000),
                            focusedTextColor = if (isDark) Color.White else TextDark,
                            unfocusedTextColor = if (isDark) Color.White else TextDark,
                            unfocusedLabelColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                        )
                    )

                    OutlinedTextField(
                        value = mensaje,
                        onValueChange = { mensaje = it },
                        label = { Text("Mensaje") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BomberosRed,
                            focusedLabelColor = BomberosRed,
                            unfocusedBorderColor = if (isDark) Color(0x1AFFFFFF) else Color(0x1A000000),
                            focusedTextColor = if (isDark) Color.White else TextDark,
                            unfocusedTextColor = if (isDark) Color.White else TextDark,
                            unfocusedLabelColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                        )
                    )

                    Text("Grado de Alerta:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = if (isDark) Color.White else TextDark)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("1" to "G1", "2" to "G2", "3" to "G3").forEach { (valStr, label) ->
                            val selected = grado == valStr
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) BomberosRed else (if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9)))
                                    .clickable { grado = valStr }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label,
                                    color = if (selected) Color.White else (if (isDark) Color.LightGray else Color.DarkGray),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Text("Tipo de Duración:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = if (isDark) Color.White else TextDark)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("i" to "Indefinida", "C" to "Chat/Conversación").forEach { (valStr, label) ->
                            val selected = duracion == valStr
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) BomberosRed else (if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9)))
                                    .clickable { duracion = valStr }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label,
                                    color = if (selected) Color.White else (if (isDark) Color.LightGray else Color.DarkGray),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    Text("Destinatario:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = if (isDark) Color.White else TextDark)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("TC" to "Toda Cía", "especifico" to "Específico").forEach { (valStr, label) ->
                            val selected = aQuienTipo == valStr
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) BomberosRed else (if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9)))
                                    .clickable { aQuienTipo = valStr }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label,
                                    color = if (selected) Color.White else (if (isDark) Color.LightGray else Color.DarkGray),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    if (aQuienTipo == "especifico") {
                        OutlinedTextField(
                            value = aQuienEspecifico,
                            onValueChange = { aQuienEspecifico = it },
                            label = { Text("IDs radiales (separados por coma)") },
                            placeholder = { Text("Ej: 01, 12, 14") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BomberosRed,
                                focusedLabelColor = BomberosRed,
                                unfocusedBorderColor = if (isDark) Color(0x1AFFFFFF) else Color(0x1A000000),
                                focusedTextColor = if (isDark) Color.White else TextDark,
                                unfocusedTextColor = if (isDark) Color.White else TextDark,
                                unfocusedLabelColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                            )
                        )
                    }
                }
            }
        )
    }
}

@Composable
fun AlertItemCard(alert: Alert, viewModel: SisBomViewModel, onChatClick: () -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val myRadial = viewModel.currentUser?.idRadial ?: ""
    val isPinned = alert.fijar.split(",").map { it.trim().uppercase() }.contains(myRadial.uppercase())
    val isConforme = alert.conforme.split(",").map { it.trim().uppercase() }.contains(myRadial.uppercase())
    val isChat = alert.duracion.trim().uppercase() == "C"
    val isDark = LocalDarkMode.current

    val borderColor = when (alert.gradoAlerta) {
        "3" -> BomberosRed
        "2" -> AlertAmber
        else -> InfoBlue
    }

    val iconVector = if (isChat) {
        Icons.Filled.Forum
    } else {
        when (alert.gradoAlerta) {
            "3" -> Icons.Filled.Info
            "2" -> Icons.Filled.Info
            else -> Icons.Filled.Info
        }
    }

    val iconColor = if (isChat) {
        if (isDark) Color(0xFFF87171) else BomberosRed
    } else {
        when (alert.gradoAlerta) {
            "3" -> if (isDark) Color(0xFFEF4444) else BomberosRed
            "2" -> if (isDark) Color(0xFFF59E0B) else AlertAmber
            else -> if (isDark) Color(0xFF3B82F6) else InfoBlue
        }
    }

    val cardBg = if (isChat && !isConforme) {
        if (isDark) Color(0xFFEF4444).copy(alpha = 0.08f) else Color(0xFFEF4444).copy(alpha = 0.05f)
    } else if (!isChat && !isConforme) {
        if (isDark) Color(0xFF1E293B).copy(alpha = 0.4f) else Color(0x0D000000)
    } else {
        Color.Transparent
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        if (isChat) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChatClick() },
                borderColor = if (isPinned) AlertAmber else borderColor.copy(alpha = 0.3f),
                isDarkTheme = isDark
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBg, RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onChatClick),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    if (isDark) Color.Black.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.6f),
                                    CircleShape
                                )
                                .border(
                                    1.dp,
                                    if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.05f),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = iconVector,
                                contentDescription = null,
                                tint = iconColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = alert.razonAlerta,
                                color = if (!isConforme && isDark) Color(0xFFF87171) else if (!isConforme) BomberosRed else if (isDark) Color.White else TextDark,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${cleanSheetPrefix(alert.fechaAlerta)} • ${cleanSheetPrefix(alert.horaAlerta)}",
                                color = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    val previewText = run {
                        val msgs = alert.mensajeAlerta.split("|").filter { it.trim().isNotEmpty() }
                        if (msgs.isNotEmpty()) {
                            val lastMsg = msgs.last()
                            val colonIdx = findSeparatorColonIndex(lastMsg)
                            if (colonIdx != -1) {
                                val prefix = lastMsg.substring(0, colonIdx)
                                val body = lastMsg.substring(colonIdx + 1).trim()
                                val senderId = (prefix.split("/").lastOrNull() ?: "").trim()
                                val myIdReg = viewModel.currentUser?.idRegistro ?: ""
                                val senderUser = viewModel.personnelList.find {
                                    it.idRegistro.trim().uppercase() == senderId.uppercase()
                                }
                                val isMe = if (senderUser != null) {
                                    senderUser.idRegistro.trim().uppercase() == myIdReg.trim().uppercase()
                                } else {
                                    senderId.uppercase() == myIdReg.trim().uppercase()
                                }
                                val senderName = if (isMe) "Tú" else (if (senderUser != null) formatFirefighterName(senderUser.nombreBombero) else senderId)
                                "$senderName: $body"
                            } else {
                                lastMsg
                            }
                        } else {
                            "Sin mensajes en el canal."
                        }
                    }
                    
                    Text(
                        text = previewText,
                        color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 2.dp)
                            .clickable(onClick = onChatClick)
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    androidx.compose.material3.Divider(
                        color = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.05f),
                        thickness = 1.dp
                    )
                    
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Button(
                            onClick = onChatClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDark) Color(0xFFEF4444).copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.08f),
                                contentColor = if (isDark) Color(0xFFF87171) else BomberosRed
                            ),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BomberosRed.copy(alpha = 0.2f)),
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Filled.Forum, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("RESPONDER", fontSize = 10.sp, fontWeight = FontWeight.Black)
                        }
                        
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        IconButton(
                            onClick = { viewModel.toggleAlertPin(alert) },
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    if (isPinned) {
                                        if (isDark) Color(0xFFEF4444).copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.08f)
                                    } else {
                                        if (isDark) Color.White.copy(alpha = 0.03f) else Color.Black.copy(alpha = 0.03f)
                                    },
                                    RoundedCornerShape(10.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isPinned) BomberosRed.copy(alpha = 0.3f) else if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.05f),
                                    RoundedCornerShape(10.dp)
                                )
                        ) {
                            Icon(
                                imageVector = if (isPinned) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                contentDescription = "Pin",
                                tint = if (isPinned) {
                                    if (isDark) Color(0xFFF87171) else BomberosRed
                                } else {
                                    if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                                },
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        } else {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        showDialog = true
                        if (!isConforme) {
                            viewModel.registerConforme(alert)
                        }
                    },
                borderColor = if (isPinned) AlertAmber else if (isConforme) GoGreen.copy(alpha = 0.5f) else borderColor.copy(alpha = 0.3f),
                isDarkTheme = isDark
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    IconButton(
                        onClick = { viewModel.toggleAlertPin(alert) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isPinned) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            contentDescription = "Pin",
                            tint = if (isPinned) AlertAmber else if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp, horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = iconVector,
                                contentDescription = null,
                                tint = iconColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(14.dp))
                        
                        Text(
                            text = alert.razonAlerta,
                            color = if (isDark) Color.White else TextDark,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.height(2.dp))
                        
                        Text(
                            text = "${cleanSheetPrefix(alert.fechaAlerta)} • ${cleanSheetPrefix(alert.horaAlerta)}",
                            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.height(14.dp))
                        androidx.compose.material3.Divider(color = if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isConforme) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = GoGreen,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "VISTO",
                                    color = GoGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = null,
                                    tint = BomberosRed,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "PENDIENTE",
                                    color = BomberosRed,
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

    if (showDialog) {
        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            val view = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.SideEffect {
                val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
                window?.let { w ->
                    w.setLayout(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    w.setStatusBarColor(android.graphics.Color.TRANSPARENT)
                    w.setNavigationBarColor(android.graphics.Color.TRANSPARENT)
                    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
                    val insetsController = androidx.core.view.WindowCompat.getInsetsController(w, view)
                    insetsController.isAppearanceLightStatusBars = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        w.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isDark) NavyDeep else Color(0xFFCBD5E1))
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Header (Full width)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isDark) NavyDark else Color(0xFF475569))
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "COMUNICADO DE ALERTA",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "${if (viewModel.saasClientName.isNotEmpty()) viewModel.saasClientName.uppercase() else "SISBOM"}, ${cleanSheetPrefix(alert.fechaAlerta)}",
                                color = Color(0xFF9CA3AF),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(
                            onClick = { showDialog = false },
                            modifier = Modifier
                                .size(28.dp)
                                .background(if (isDark) Color.White.copy(alpha = 0.15f) else Color(0x33000000), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Cerrar",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(if (isDark) NavyDeep else Color(0xFFCBD5E1))
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 20.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Document Card (Always White Page for Authentic PDF/Document Look)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(elevation = 4.dp, shape = RoundedCornerShape(8.dp))
                                .background(Color.White, RoundedCornerShape(8.dp))
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {


                            // Date on the right
                            Text(
                                text = "Placilla, ${cleanSheetPrefix(alert.fechaAlerta)}",
                                color = Color(0xFF374151),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.End)
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Title Centered
                            Text(
                                text = alert.razonAlerta,
                                color = Color(0xFF1F2937),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            // Message Justified
                            Text(
                                text = alert.mensajeAlerta.replace("|", "\n"),
                                color = Color(0xFF374151),
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                textAlign = TextAlign.Justify,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatWindow(alert: Alert, viewModel: SisBomViewModel, onDismiss: () -> Unit) {
    val isDark = LocalDarkMode.current
    androidx.activity.compose.BackHandler(enabled = true) {
        onDismiss()
    }
    var textMessage by remember { mutableStateOf("") }
    val myIdReg = viewModel.currentUser?.idRegistro ?: ""
    val myIdRad = viewModel.currentUser?.idRadial ?: ""
    val personnel = viewModel.personnelList

    val messages = remember(alert.mensajeAlerta, personnel, myIdReg, myIdRad) {
        if (alert.mensajeAlerta.isEmpty()) emptyList<ChatMsgItem>()
        else {
            alert.mensajeAlerta.split("|").mapNotNull { raw ->
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val firstColon = findSeparatorColonIndex(trimmed)
                if (firstColon == -1) return@mapNotNull null
                val prefix = trimmed.substring(0, firstColon).trim()
                val msgText = trimmed.substring(firstColon + 1).trim()
                
                val prefixParts = prefix.split("/")
                val datePart: String
                val timePart: String
                val senderId: String
                
                when {
                    prefixParts.size >= 3 -> {
                        datePart = cleanSheetPrefix(prefixParts[0])
                        timePart = cleanSheetPrefix(prefixParts[1])
                        senderId = cleanSheetPrefix(prefixParts[2])
                    }
                    prefixParts.size == 2 -> {
                        val part0 = cleanSheetPrefix(prefixParts[0])
                        val part1 = cleanSheetPrefix(prefixParts[1])
                        if (part0.contains(":")) {
                            datePart = ""
                            timePart = part0
                            senderId = part1
                        } else {
                            datePart = part0
                            timePart = ""
                            senderId = part1
                        }
                    }
                    else -> {
                        datePart = ""
                        timePart = ""
                        senderId = cleanSheetPrefix(prefixParts.firstOrNull() ?: "")
                    }
                }
                
                if (senderId.isEmpty()) return@mapNotNull null
                
                val senderUser = personnel.find { 
                    it.idRegistro.trim().uppercase() == senderId.trim().uppercase()
                }
                val senderName = if (senderUser != null) {
                    formatFirefighterName(senderUser.nombreBombero)
                } else {
                    senderId
                }
                val isMe = if (senderUser != null) {
                    senderUser.idRegistro.trim().uppercase() == myIdReg.trim().uppercase()
                } else {
                    senderId.trim().uppercase() == myIdReg.trim().uppercase()
                }
                
                ChatMsgItem(
                    senderName = senderName,
                    senderId = senderId,
                    message = msgText,
                    time = timePart.ifEmpty { "12:00" },
                    isMe = isMe
                )
            }
        }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val isKeyboardOpen = WindowInsets.isImeVisible
    androidx.compose.runtime.LaunchedEffect(isKeyboardOpen) {
        if (isKeyboardOpen && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
            kotlinx.coroutines.delay(150)
            listState.animateScrollToItem(messages.size - 1)
            kotlinx.coroutines.delay(150)
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDark) NavyDeep else Color(0xFFF1F5F9))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header (Full width)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isDark) NavyDark else Color.White)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Atrás",
                        tint = if (isDark) Color.White else TextDark,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = alert.razonAlerta.lowercase().replaceFirstChar { it.uppercaseChar() },
                        color = if (isDark) Color.White else TextDark,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            alert.aQuienAlerta.trim().uppercase() == "TC" -> "TODA LA COMPAÑÍA"
                            alert.aQuienAlerta.trim().uppercase() == "SC" -> "SALA DE COMUNICACIÓN"
                            else -> {
                                val ids = alert.aQuienAlerta.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                val radials = ids.mapNotNull { idReg ->
                                    viewModel.personnelList.find { p -> p.idRegistro.trim() == idReg }?.idRadial?.trim()
                                }
                                if (radials.isNotEmpty()) {
                                    "ESPECÍFICO: ${radials.joinToString(", ")}"
                                } else {
                                    "ESPECÍFICO"
                                }
                            }
                        },
                        color = BomberosRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            // Messages Area with Watermark
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(if (isDark) NavyDeep else Color(0xFFF8FAFC))
            ) {
                // Watermark Logo
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(280.dp)
                        .align(Alignment.Center),
                    alpha = if (isDark) 0.04f else 0.06f
                )

                // LazyColumn for messages
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 50.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages) { msg ->
                        ChatBubble(
                            senderName = msg.senderName,
                            message = msg.message,
                            time = msg.time,
                            isMe = msg.isMe,
                            isDarkTheme = isDark
                        )
                    }
                }
            }

            val bottomInsets = if (isKeyboardOpen) {
                WindowInsets.ime
            } else {
                WindowInsets.navigationBars
            }

            // Bottom Input panel
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 8.dp)
                    .background(if (isDark) NavyDark else Color.White)
                    .windowInsetsPadding(bottomInsets)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textMessage,
                        onValueChange = { textMessage = it },
                        placeholder = { Text("Escribir mensaje...") },
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = if (isDark) Color.White else TextDark,
                            unfocusedTextColor = if (isDark) Color.White else TextDark,
                            focusedPlaceholderColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8),
                            unfocusedPlaceholderColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8),
                            focusedBorderColor = BomberosRed,
                            unfocusedBorderColor = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0)
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    IconButton(
                        onClick = {
                            if (textMessage.trim().isNotEmpty()) {
                                viewModel.sendChatMessage(alert, textMessage.trim())
                                textMessage = ""
                            }
                        },
                        modifier = Modifier
                            .background(BomberosRed, CircleShape)
                            .size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Send,
                            contentDescription = "Send",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

fun parseDateToDate(fechaStr: String): java.util.Date? {
    val clean = fechaStr.trim().replace("\"", "").replace("'", "").replace("/", "-")
    val formats = listOf("dd-MM-yyyy", "d-M-yyyy", "dd-MM-yy", "d-M-yy", "yyyy-MM-dd", "yyyy-M-d")
    for (fmt in formats) {
        try {
            val sdf = java.text.SimpleDateFormat(fmt, java.util.Locale.getDefault())
            sdf.isLenient = true
            val d = sdf.parse(clean)
            if (d != null) return d
        } catch (_: Exception) {}
    }
    return null
}

fun getCycleYear(date: java.util.Date): Int {
    val cal = java.util.Calendar.getInstance()
    cal.time = date
    val month = cal.get(java.util.Calendar.MONTH) // 0-indexed (Jan=0, Dec=11)
    val year = cal.get(java.util.Calendar.YEAR)
    return if (month == java.util.Calendar.DECEMBER) year + 1 else year
}

data class CycleStats(
    val pct: Float,
    val obligatorias: Int,
    val obligatoriasAsistidas: Int,
    val abonos: Int
)

fun isAbonoValue(value: Any?, clave: String): Boolean {
    if (value == null) return false
    if (value is Boolean) return value
    if (value is Number) return value.toInt() == 1
    val str = value.toString().uppercase().trim()
    return str == "SÍ" || str == "SI" || str == "S" || str == "TRUE" || str == "1" || clave.uppercase().contains("ABONO")
}

fun calculateCycleStats(history: List<AttendanceSheet>): CycleStats {
    val filtered = history.filter { h ->
        val st = if (h.userEstado.isEmpty()) "FALTA" else h.userEstado.uppercase().trim()
        val isAbono = isAbonoValue(h.userAbono, h.clave)
        val isPresent = st == "A" || st == "ASISTE" || st == "CDS"
        !(isAbono && !isPresent)
    }

    var obligatoriasAsistidas = 0
    var totalObligatorias = 0
    var totalAbonosAsiste = 0

    filtered.forEach { h ->
        val st = if (h.userEstado.isEmpty()) "FALTA" else h.userEstado.uppercase().trim()
        val isAbono = isAbonoValue(h.userAbono, h.clave)
        val isPresent = st == "A" || st == "ASISTE" || st == "CDS"
        if (!isAbono) {
            totalObligatorias++
            if (isPresent) {
                obligatoriasAsistidas++
            }
        } else {
            if (isPresent) {
                totalAbonosAsiste++
            }
        }
    }

    val factor = totalObligatorias.toFloat() / 100f
    val suma = (obligatoriasAsistidas + totalAbonosAsiste).toFloat()
    val pctAsist = if (factor > 0f) {
        kotlin.math.min(100f, suma / factor)
    } else {
        if (totalAbonosAsiste > 0) 100f else 0f
    }
    return CycleStats(pctAsist, totalObligatorias, obligatoriasAsistidas, totalAbonosAsiste)
}

@Composable
private fun AttendanceCycleCard(
    title: String,
    subtitle: String,
    pctAsist: Float,
    totalObligatorias: Int,
    totalAbonosAsiste: Int,
    isDark: Boolean,
    showRights: Boolean = false
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        isDarkTheme = isDark
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            AttendanceCircle(
                percentage = pctAsist,
                size = 80.dp,
                strokeWidth = 8.dp,
                isDarkTheme = isDark
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    color = if (isDark) Color.White else TextDark,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                )
                
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isDark) Color(0xFF1E1E2E).copy(alpha = 0.5f) else Color(0xFFF1F5F9))
                            .border(1.5.dp, if (isDark) Color(0xFF33334D) else Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "$totalObligatorias",
                                color = if (isDark) Color.White else TextDark,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "OBLIGATORIAS",
                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isDark) Color(0xFF1E1E2E).copy(alpha = 0.5f) else Color(0xFFF1F5F9))
                            .border(1.5.dp, if (isDark) Color(0xFF33334D) else Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "$totalAbonosAsiste",
                                color = if (isDark) Color.White else TextDark,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "ABONO",
                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }

                if (showRights) {
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val canVote = pctAsist >= 30f
                        val canHoldCargo = pctAsist >= 40f

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (canVote) {
                                        Color(0xFF10B981).copy(alpha = 0.15f)
                                    } else {
                                        Color(0xFFEF4444).copy(alpha = 0.15f)
                                    }
                                )
                                .border(
                                    1.dp,
                                    if (canVote) Color(0xFF10B981) else Color(0xFFEF4444),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (canVote) "DERECHO VOTO: SÍ" else "DERECHO VOTO: NO",
                                color = if (canVote) Color(0xFF10B981) else Color(0xFFEF4444),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (canHoldCargo) {
                                        Color(0xFF10B981).copy(alpha = 0.15f)
                                    } else {
                                        Color(0xFFEF4444).copy(alpha = 0.15f)
                                    }
                                )
                                .border(
                                    1.dp,
                                    if (canHoldCargo) Color(0xFF10B981) else Color(0xFFEF4444),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (canHoldCargo) "DERECHO CARGO: SÍ" else "DERECHO CARGO: NO",
                                color = if (canHoldCargo) Color(0xFF10B981) else Color(0xFFEF4444),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AsistenciaTab(viewModel: SisBomViewModel, paddingValues: PaddingValues) {
    val myId = viewModel.currentUser?.idRegistro ?: ""
    val isDark = LocalDarkMode.current

    val myHistory = viewModel.attendanceList.filter { row ->
        row.aprobadoPor.trim().isNotEmpty() &&
                row.anulada != 1 &&
                row.anulada.toString().uppercase() != "SI"
    }.sortedByDescending {
        it.idLista.toIntOrNull() ?: 0
    }

    val today = java.util.Date()
    val cal = java.util.Calendar.getInstance()
    cal.time = today
    val todayMonth = cal.get(java.util.Calendar.MONTH) // 0-indexed (Jan=0, Dec=11)
    val todayDay = cal.get(java.util.Calendar.DAY_OF_MONTH)
    val todayYear = cal.get(java.util.Calendar.YEAR)

    val currentCycleYear = if (todayMonth == java.util.Calendar.DECEMBER) todayYear + 1 else todayYear
    val isDecember8th = (todayMonth == java.util.Calendar.DECEMBER && todayDay == 8)

    // Map each sheet in history to its cycle year, validating that the ID starts with the cycle year prefix
    val historyWithCycle = myHistory.mapNotNull { row ->
        val date = parseDateToDate(row.fecha)
        if (date != null) {
            val cycleYear = getCycleYear(date)
            val idMatchesCycle = row.idLista.startsWith(cycleYear.toString())
            if (idMatchesCycle) {
                row to cycleYear
            } else {
                null
            }
        } else {
            if (row.idLista.startsWith(currentCycleYear.toString())) {
                row to currentCycleYear
            } else {
                null
            }
        }
    }

    // Filter lists for current cycle
    val currentCycleHistory = historyWithCycle.filter { it.second == currentCycleYear }.map { it.first }
    val currentCycleStats = calculateCycleStats(currentCycleHistory)

    // Filter lists for previous cycle
    val prevCycleHistory = historyWithCycle.filter { it.second == currentCycleYear - 1 }.map { it.first }
    val prevCycleStats = calculateCycleStats(prevCycleHistory)

    // Only display lists of current cycle
    val displayedHistory = currentCycleHistory.filter { h ->
        val st = if (h.userEstado.isEmpty()) "FALTA" else h.userEstado.uppercase().trim()
        val isAbono = isAbonoValue(h.userAbono, h.clave)
        val isPresent = st == "A" || st == "ASISTE" || st == "CDS"
        !(isAbono && !isPresent)
    }

    val chunks = displayedHistory.chunked(2)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = paddingValues.calculateTopPadding() + 16.dp,
            bottom = paddingValues.calculateBottomPadding() + 16.dp,
            start = 16.dp,
            end = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Totales de Asistencia (Sujeto a scroll)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (isDark) Color(0xFF1E293B).copy(alpha = 0.92f) else Color.White.copy(alpha = 0.92f))
                    .border(1.dp, if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AttendanceCircle(
                        percentage = currentCycleStats.pct,
                        size = 56.dp,
                        strokeWidth = 6.dp,
                        isDarkTheme = isDark
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Asistencia ${currentCycleYear}",
                            color = if (isDark) Color.White else TextDark,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "RENDIMIENTO ANUAL EN CURSO",
                            color = if (isDark) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.5f),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.3.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.04f))
                                .border(1.dp, if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                .padding(vertical = 4.dp, horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${currentCycleStats.obligatoriasAsistidas} de ${currentCycleStats.obligatorias}",
                                    color = if (isDark) Color.White else TextDark,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = "ASISTE",
                                    color = if (isDark) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.5f),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.04f))
                                .border(1.dp, if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                .padding(vertical = 4.dp, horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${currentCycleStats.abonos}",
                                    color = if (isDark) Color.White else TextDark,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = "ABONOS",
                                    color = if (isDark) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.5f),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                }
            }
        }

        if (isDecember8th) {
            item {
                val canVote = prevCycleStats.pct >= 30f
                val canHoldCargo = prevCycleStats.pct >= 40f
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (isDark) Color(0xFF1E293B).copy(alpha = 0.92f) else Color.White.copy(alpha = 0.92f))
                        .border(1.dp, if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AttendanceCircle(
                            percentage = prevCycleStats.pct,
                            size = 56.dp,
                            strokeWidth = 6.dp,
                            isDarkTheme = isDark
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Ciclo Anterior (${currentCycleYear - 1})",
                                color = if (isDark) Color.White else TextDark,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .background(if (canVote) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                        .border(1.dp, if (canVote) Color(0xFF10B981) else Color(0xFFEF4444), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (canVote) "VOTA: SÍ" else "VOTA: NO",
                                        color = if (canVote) Color(0xFF10B981) else Color(0xFFEF4444),
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .background(if (canHoldCargo) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                        .border(1.dp, if (canHoldCargo) Color(0xFF10B981) else Color(0xFFEF4444), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (canHoldCargo) "CARGO: SÍ" else "CARGO: NO",
                                        color = if (canHoldCargo) Color(0xFF10B981) else Color(0xFFEF4444),
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.04f))
                                    .border(1.dp, if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                    .padding(vertical = 4.dp, horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "${prevCycleStats.obligatoriasAsistidas} de ${prevCycleStats.obligatorias}",
                                        color = if (isDark) Color.White else TextDark,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Text(
                                        text = "ASISTE",
                                        color = if (isDark) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.5f),
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.04f))
                                    .border(1.dp, if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                    .padding(vertical = 4.dp, horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "${prevCycleStats.abonos}",
                                        color = if (isDark) Color.White else TextDark,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Text(
                                        text = "ABONOS",
                                        color = if (isDark) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.5f),
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Título e Indicador de Sincronización Manual
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HISTORIAL",
                    color = if (isDark) Color.White else TextDark,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black
                )
                if (viewModel.isSyncingAttendance) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = BomberosRed,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Row(
                        modifier = Modifier.clickable { viewModel.syncAttendanceFromFirebase(myId) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Sincronizar",
                            tint = BomberosRed,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Sincronizar",
                            color = BomberosRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (displayedHistory.isEmpty()) {
            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                ) {
                    Text(
                        text = "No registra historial de asistencias este año.",
                        color = Color(0xFF64748B),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            items(chunks) { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        AttendanceItemCard(pair[0], viewModel)
                    }
                    if (pair.size > 1) {
                        Box(modifier = Modifier.weight(1f)) {
                            AttendanceItemCard(pair[1], viewModel)
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun AttendanceItemCard(item: AttendanceSheet, viewModel: SisBomViewModel) {
    val isDark = LocalDarkMode.current
    var showDetails by remember { androidx.compose.runtime.mutableStateOf(false) }

    val cleanStatus = when (val raw = item.userEstado.uppercase().trim()) {
        "A", "ASISTE" -> "ASISTE"
        "F", "FALTA", "" -> "FALTA"
        "L", "LICENCIA" -> "LICENCIA"
        "P", "PERMISO" -> "PERMISO"
        "S", "SUSPENDIDO" -> "SUSPENDIDO"
        "C", "CDS" -> "CDS"
        else -> if (raw.isEmpty()) "FALTA" else raw
    }

    val badgeColor = when (cleanStatus) {
        "ASISTE" -> GoGreen
        "CDS" -> GoGreen
        "FALTA" -> BomberosRed
        "PERMISO" -> AlertAmber
        "LICENCIA" -> InfoBlue
        "SUSPENDIDO" -> Color(0xFF8B5CF6)
        else -> Color.Gray
    }

    val rawId = item.idLista.trim()
    val cleanListId = if (rawId.length > 4 && (rawId.startsWith("202") || rawId.startsWith("203"))) {
        "#" + rawId.substring(4)
    } else {
        "#" + rawId
    }

    val isAbono = isAbonoValue(item.userAbono, item.clave)

    val cardBgColor = if (isDark) Color(0xFF1E293B) else Color.White
    val tintAlpha = if (isDark) 0.22f else 0.15f
    val cardBorder = badgeColor.copy(alpha = 0.35f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBgColor) // Base sólida mate
            .background(badgeColor.copy(alpha = tintAlpha)) // Capa de tinte
            .border(1.dp, cardBorder, RoundedCornerShape(12.dp))
            .clickable { showDetails = true }
            .padding(10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.clave,
                    color = if (isDark) Color.White else TextDark,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isAbono) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF312E81), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "ABONO",
                            color = Color.White,
                            fontSize = 7.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = when (cleanStatus) {
                    "ASISTE" -> "✓ ASISTE"
                    "CDS" -> "✓ CDS"
                    "FALTA" -> "✗ FALTA"
                    "PERMISO" -> "✗ PERMISO"
                    "LICENCIA" -> "✗ LICENCIA"
                    else -> cleanStatus
                },
                color = badgeColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "${cleanSheetPrefix(item.fecha)} ${cleanSheetPrefix(item.hora)}",
                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF475569),
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = item.lugar.ifEmpty { "(Sin lugar)" },
                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF475569),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = {
                Text(
                    text = "Detalle de Asistencia",
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp,
                    color = if (isDark) Color.White else TextDark
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DetailRow(label = "Clave", value = item.clave, isDark = isDark)
                    DetailRow(label = "Nº Lista", value = cleanListId, isDark = isDark)
                    DetailRow(label = "Estado", value = cleanStatus, valueColor = badgeColor, isDark = isDark)
                    DetailRow(label = "Fecha", value = item.fecha, isDark = isDark)
                    DetailRow(label = "Hora", value = item.hora, isDark = isDark)
                    DetailRow(label = "Lugar", value = item.lugar, isDark = isDark)
                    DetailRow(label = "Abono", value = if (isAbono) "Sí" else "No", isDark = isDark)
                    if (item.obac.trim().isNotEmpty()) {
                        DetailRow(label = "OBAC", value = item.obac, isDark = isDark)
                    }
                    if (item.detalle.trim().isNotEmpty()) {
                        DetailRow(label = "Detalle", value = item.detalle, isDark = isDark)
                    }
                    if (item.aprobadoPor.trim().isNotEmpty()) {
                        DetailRow(label = "Aprobado por", value = item.aprobadoPor, isDark = isDark)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) {
                    Text(text = "Cerrar", color = BomberosRed, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = if (isDark) Color(0xFF1E293B) else Color.White,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun DetailRow(label: String, value: String, valueColor: Color? = null, isDark: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            color = valueColor ?: (if (isDark) Color.White else TextDark),
            fontSize = 13.sp,
            fontWeight = FontWeight.Black
        )
    }
}

fun formatSignatureText(fullName: String): String {
    val parts = fullName.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return "Comandante"
    val first = parts[0].lowercase().replaceFirstChar { it.uppercase() }
    val last = parts.getOrNull(1)?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ""
    return if (last.isNotEmpty()) "$first $last" else first
}

private fun findSeparatorColonIndex(text: String): Int {
    val firstSlash = text.indexOf('/')
    if (firstSlash == -1) return text.indexOf(':')
    val secondSlash = text.indexOf('/', firstSlash + 1)
    if (secondSlash == -1) return text.indexOf(':', firstSlash + 1)
    return text.indexOf(':', secondSlash + 1)
}

@Composable
fun DisponiblesTab(viewModel: SisBomViewModel, paddingValues: PaddingValues) {
    val isDark = LocalDarkMode.current
    val availableFirefighters = remember(viewModel.personnelList) {
        viewModel.personnelList.filter { p ->
            p.isUserActive() && p.estado.trim().uppercase() == "0-9" && p.idRadial.trim().uppercase() != "C1"
        }.sortedWith(compareBy({ p ->
            val number = p.idRadial.filter { it.isDigit() }.toIntOrNull()
            number ?: 9999
        }, { it.idRadial }))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = paddingValues.calculateTopPadding() + 16.dp,
            bottom = paddingValues.calculateBottomPadding() + 16.dp,
            start = 16.dp,
            end = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(GoGreen.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .border(1.dp, GoGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${availableFirefighters.size} ACTIVO(S)",
                        color = GoGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }

        if (availableFirefighters.isEmpty()) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                    ) {
                        Text(
                            text = "NO HAY BOMBEROS DISPONIBLES EN ESTE MOMENTO",
                            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            items(availableFirefighters.size) { index ->
                val p = availableFirefighters[index]
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9)),
                            contentAlignment = Alignment.Center
                        ) {
                            val photoModel = remember(p.foto) {
                                val clean = p.foto.trim()
                                if (clean.startsWith("data:")) {
                                    decodeBase64ToBitmap(clean)
                                } else if (clean.isNotEmpty()) {
                                    clean
                                } else {
                                    null
                                }
                            }

                            if (photoModel is android.graphics.Bitmap) {
                                androidx.compose.foundation.Image(
                                    bitmap = photoModel.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            } else if (photoModel != null) {
                                AsyncImage(
                                    model = photoModel,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Person,
                                    contentDescription = null,
                                    tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = p.nombreBombero.uppercase(),
                                color = if (isDark) Color.White else TextDark,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = (if (p.cargo.isNotEmpty()) p.cargo else "VOLUNTARIO").uppercase(),
                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Box(
                            modifier = Modifier
                                .background(if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9), RoundedCornerShape(12.dp))
                                .border(1.dp, if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = p.idRadial.uppercase(),
                                color = if (isDark) Color.White else TextDark,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }
    }
}
