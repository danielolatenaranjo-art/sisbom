package com.sisbom.sisbomcar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
fun CarPlaySetupWizard(
    viewModel: CarViewModel,
    onSetupCompleted: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var licenseInput by remember { mutableStateOf(viewModel.saasLicenseKey) }
    var comandanteIdInput by remember { mutableStateOf("") }
    var comandantePassInput by remember { mutableStateOf("") }
    var selectedVehicle by remember { mutableStateOf<Vehicle?>(null) }

    val isStep1Done = viewModel.isLicenseValid
    val isStep2Done = viewModel.isComandanteAuthenticated
    val logoModel = viewModel.getClientLogoModel()
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("SisBomCarPrefs", android.content.Context.MODE_PRIVATE) }
    var lastCrashTrace by remember { mutableStateOf(prefs.getString("last_crash_trace", "") ?: "") }
    var showCrashModal by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF0F172A), Color(0xFF020617)),
                    radius = 1600f
                )
            )
            .padding(14.dp)
    ) {
        val isCompactScreen = maxWidth < 640.dp

        if (isCompactScreen) {
            // VISTA VERTICAL / TELÉFONO MÓVIL (SCROLLABLE SINGLE COLUMN)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // TARJETA PASO 1 Y 2
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .carPlayCard(cornerRadius = 18.dp, bgAlpha = 0.94f)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (logoModel is Int) {
                            Icon(
                                imageVector = Icons.Filled.DirectionsCar,
                                contentDescription = "Logo",
                                tint = CarPlayColors.AccentCyan,
                                modifier = Modifier.size(32.dp)
                            )
                        } else {
                            AsyncImage(
                                model = logoModel,
                                contentDescription = "Logo",
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "SISBOM CAR",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = if (viewModel.saasClientName.isNotEmpty()) viewModel.saasClientName.uppercase() else "CONFIGURACIÓN INICIAL",
                                color = CarPlayColors.TextSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // PASO 1: LICENCIA
                    if (!isStep1Done) {
                        Text(
                            text = "PASO 1: LICENCIA CENTRAL SISBOM",
                            color = CarPlayColors.AccentCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black
                        )
                        OutlinedTextField(
                            value = licenseInput,
                            onValueChange = { licenseInput = it.uppercase() },
                            placeholder = { Text("Ej: CB-SANTIAGO", color = CarPlayColors.TextMuted, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null, tint = CarPlayColors.AccentCyan) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Black.copy(alpha = 0.4f),
                                unfocusedContainerColor = Color.Black.copy(alpha = 0.3f),
                                focusedBorderColor = CarPlayColors.AccentCyan,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (viewModel.saasActivationError.isNotEmpty()) {
                            Text(
                                text = "⚠️ ${viewModel.saasActivationError}",
                                color = CarPlayColors.PrimaryRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                viewModel.activateLicense(licenseInput) { success ->
                                    if (success) viewModel.fetchVehiclesList()
                                }
                            },
                            enabled = licenseInput.trim().isNotEmpty() && !viewModel.isActivatingLicense,
                            colors = ButtonDefaults.buttonColors(containerColor = CarPlayColors.PrimaryBlue, contentColor = Color.White),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            if (viewModel.isActivatingLicense) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("VALIDANDO...", fontWeight = FontWeight.Black, fontSize = 12.sp)
                            } else {
                                Text("VALIDAR Y CONECTAR CUERPO", fontWeight = FontWeight.Black, fontSize = 12.sp)
                            }
                        }
                    } else {
                        // Licencia OK
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(CarPlayColors.PrimaryGreen.copy(alpha = 0.15f))
                                .border(1.dp, CarPlayColors.PrimaryGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = CarPlayColors.PrimaryGreen, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(viewModel.saasClientName.uppercase(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                TextButton(onClick = { viewModel.clearLicense() }) {
                                    Text("Cambiar", fontSize = 10.sp, color = Color(0xFFFCA5A5))
                                }
                            }
                        }

                        // PASO 2: COMANDANTE
                        if (!isStep2Done) {
                            Text(
                                text = "PASO 2: AUTORIZACIÓN DE MANDO (COMANDANTE)",
                                color = CarPlayColors.AccentCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                            OutlinedTextField(
                                value = comandanteIdInput,
                                onValueChange = { comandanteIdInput = it.trim() },
                                placeholder = { Text("ID Registro o ID Radial 1", color = CarPlayColors.TextMuted, fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null, tint = CarPlayColors.AccentCyan) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.Black.copy(alpha = 0.4f),
                                    unfocusedContainerColor = Color.Black.copy(alpha = 0.3f),
                                    focusedBorderColor = CarPlayColors.AccentCyan,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = comandantePassInput,
                                onValueChange = { comandantePassInput = it },
                                placeholder = { Text("Contraseña de Comandante", color = CarPlayColors.TextMuted, fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = CarPlayColors.AccentCyan) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.Black.copy(alpha = 0.4f),
                                    unfocusedContainerColor = Color.Black.copy(alpha = 0.3f),
                                    focusedBorderColor = CarPlayColors.AccentCyan,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (viewModel.comandanteAuthError.isNotEmpty()) {
                                Text(
                                    text = "⚠️ ${viewModel.comandanteAuthError}",
                                    color = CarPlayColors.PrimaryRed,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    viewModel.verifyComandante(comandanteIdInput, comandantePassInput) { success ->
                                        if (success) viewModel.fetchVehiclesList()
                                    }
                                },
                                enabled = comandanteIdInput.isNotBlank() && comandantePassInput.isNotBlank() && !viewModel.isVerifyingComandante,
                                colors = ButtonDefaults.buttonColors(containerColor = CarPlayColors.ButtonSuccess, contentColor = Color.White),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                if (viewModel.isVerifyingComandante) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("VERIFICANDO...", fontWeight = FontWeight.Black, fontSize = 11.sp)
                                } else {
                                    Text("AUTORIZAR TABLET COMO COMANDANTE", fontWeight = FontWeight.Black, fontSize = 11.sp)
                                }
                            }
                        } else {
                            // Comandante OK
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF065F46).copy(alpha = 0.35f))
                                    .border(1.dp, CarPlayColors.PrimaryGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Security, contentDescription = null, tint = CarPlayColors.PrimaryGreen, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text("COMANDANTE AUTORIZADO", color = Color(0xFF86EFAC), fontSize = 10.sp, fontWeight = FontWeight.Black)
                                        Text(viewModel.authorizedComandanteName.uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                // TARJETA PASO 3 (FLOTA) EN VISTA MÓVIL
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .carPlayCard(cornerRadius = 18.dp, bgAlpha = 0.94f)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "PASO 3: SELECCIONAR CARRO BOMBA",
                        color = if (isStep2Done) CarPlayColors.AccentCyan else CarPlayColors.TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                    if (!isStep1Done || !isStep2Done) {
                        Text(
                            text = "🔒 Complete los pasos anteriores para seleccionar la unidad.",
                            color = CarPlayColors.TextMuted,
                            fontSize = 11.sp
                        )
                    } else {
                        viewModel.availableVehicles.forEach { vehicle ->
                            val isSelected = (selectedVehicle?.idCarro ?: viewModel.selectedUnitId) == vehicle.idCarro
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) CarPlayColors.PrimaryBlue.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.05f))
                                    .border(1.dp, if (isSelected) CarPlayColors.AccentCyan else Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                    .clickable { selectedVehicle = vehicle }
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(vehicle.idCarro, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
                                        Text("${vehicle.tipo} • ${vehicle.patente}", color = CarPlayColors.TextSecondary, fontSize = 10.sp)
                                    }
                                    if (isSelected) {
                                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = CarPlayColors.AccentCyan, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = {
                                val v = selectedVehicle ?: viewModel.availableVehicles.find { it.idCarro == viewModel.selectedUnitId }
                                if (v != null) {
                                    viewModel.selectVehicle(v.idCarro, v.idCarro, v.patente, v.tipo)
                                }
                                onSetupCompleted()
                            },
                            enabled = selectedVehicle != null || viewModel.selectedUnitId.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = CarPlayColors.PrimaryGreen, contentColor = Color.White),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("GUARDAR Y ABRIR PANTALLA TÁCTICA", fontWeight = FontWeight.Black, fontSize = 12.sp)
                        }
                    }
                }
            }
        } else {
            // VISTA LANDSCAPE (TABLET / CARPLAY WIDESCREEN)
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // COLUMNA IZQUIERDA
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .carPlayCard(cornerRadius = 20.dp, bgAlpha = 0.94f)
                        .verticalScroll(rememberScrollState())
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (logoModel is Int) {
                                Icon(
                                    imageVector = Icons.Filled.DirectionsCar,
                                    contentDescription = "Logo",
                                    tint = CarPlayColors.AccentCyan,
                                    modifier = Modifier.size(34.dp)
                                )
                            } else {
                                AsyncImage(
                                    model = logoModel,
                                    contentDescription = "Logo",
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "SISBOM CAR",
                                    color = Color.White,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = if (viewModel.saasClientName.isNotEmpty()) viewModel.saasClientName.uppercase() else "CONFIGURACIÓN INICIAL DE TABLET",
                                    color = CarPlayColors.TextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // PASO 1: LICENCIA CENTRAL
                        if (!isStep1Done) {
                            Text(
                                text = "PASO 1: LICENCIA CENTRAL SISBOM",
                                color = CarPlayColors.AccentCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Ingrese la clave de licencia de su Cuerpo de Bomberos para obtener las credenciales y base de datos.",
                                color = CarPlayColors.TextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = licenseInput,
                                onValueChange = { licenseInput = it.uppercase() },
                                placeholder = { Text("Ej: CB-SANTIAGO", color = CarPlayColors.TextMuted, fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null, tint = CarPlayColors.AccentCyan) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Characters,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.Black.copy(alpha = 0.4f),
                                    unfocusedContainerColor = Color.Black.copy(alpha = 0.3f),
                                    focusedBorderColor = CarPlayColors.AccentCyan,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (viewModel.saasActivationError.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "⚠️ ${viewModel.saasActivationError}",
                                    color = CarPlayColors.PrimaryRed,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    viewModel.activateLicense(licenseInput) { success ->
                                        if (success) {
                                            viewModel.fetchVehiclesList()
                                        }
                                    }
                                },
                                enabled = licenseInput.trim().isNotEmpty() && !viewModel.isActivatingLicense,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CarPlayColors.PrimaryBlue,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                if (viewModel.isActivatingLicense) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("VALIDANDO CON CENTRAL...", fontWeight = FontWeight.Black, fontSize = 12.sp)
                                } else {
                                    Text("VALIDAR Y CONECTAR CUERPO", fontWeight = FontWeight.Black, fontSize = 12.sp)
                                }
                            }
                        } else {
                            // Paso 1 Completado
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(CarPlayColors.PrimaryGreen.copy(alpha = 0.15f))
                                    .border(1.dp, CarPlayColors.PrimaryGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = CarPlayColors.PrimaryGreen, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text("LICENCIA ACTIVA", color = Color(0xFF86EFAC), fontSize = 11.sp, fontWeight = FontWeight.Black)
                                            Text(viewModel.saasClientName.uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    TextButton(
                                        onClick = { viewModel.clearLicense() },
                                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFCA5A5))
                                    ) {
                                        Text("Cambiar", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // PASO 2: AUTENTICACIÓN COMANDANTE (ID RADIAL 1)
                            if (!isStep2Done) {
                                Text(
                                    text = "PASO 2: AUTORIZACIÓN DE MANDO (COMANDANTE)",
                                    color = CarPlayColors.AccentCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "La tablet requiere validación del Comandante (idRadial 1) para enrolarse en la flota.",
                                    color = CarPlayColors.TextSecondary,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = comandanteIdInput,
                                    onValueChange = { comandanteIdInput = it.trim() },
                                    placeholder = { Text("ID Registro o ID Radial 1", color = CarPlayColors.TextMuted, fontSize = 12.sp) },
                                    leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null, tint = CarPlayColors.AccentCyan) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = Color.Black.copy(alpha = 0.4f),
                                        unfocusedContainerColor = Color.Black.copy(alpha = 0.3f),
                                        focusedBorderColor = CarPlayColors.AccentCyan,
                                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                OutlinedTextField(
                                    value = comandantePassInput,
                                    onValueChange = { comandantePassInput = it },
                                    placeholder = { Text("Contraseña de Comandante", color = CarPlayColors.TextMuted, fontSize = 12.sp) },
                                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = CarPlayColors.AccentCyan) },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = Color.Black.copy(alpha = 0.4f),
                                        unfocusedContainerColor = Color.Black.copy(alpha = 0.3f),
                                        focusedBorderColor = CarPlayColors.AccentCyan,
                                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                if (viewModel.comandanteAuthError.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "⚠️ ${viewModel.comandanteAuthError}",
                                        color = CarPlayColors.PrimaryRed,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Button(
                                    onClick = {
                                        focusManager.clearFocus()
                                        viewModel.verifyComandante(comandanteIdInput, comandantePassInput) { success ->
                                            if (success) {
                                                viewModel.fetchVehiclesList()
                                            }
                                        }
                                    },
                                    enabled = comandanteIdInput.isNotBlank() && comandantePassInput.isNotBlank() && !viewModel.isVerifyingComandante,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CarPlayColors.ButtonSuccess,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth().height(46.dp)
                                ) {
                                    if (viewModel.isVerifyingComandante) {
                                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("VERIFICANDO COMANDANCIA...", fontWeight = FontWeight.Black, fontSize = 11.sp)
                                    } else {
                                        Text("AUTORIZAR TABLET COMO COMANDANTE", fontWeight = FontWeight.Black, fontSize = 11.sp)
                                    }
                                }
                            } else {
                                // Paso 2 Completado
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF065F46).copy(alpha = 0.35f))
                                        .border(1.dp, CarPlayColors.PrimaryGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                        .padding(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Filled.Security, contentDescription = null, tint = CarPlayColors.PrimaryGreen, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text("COMANDANTE AUTORIZADO", color = Color(0xFF86EFAC), fontSize = 11.sp, fontWeight = FontWeight.Black)
                                            Text(viewModel.authorizedComandanteName.uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SISBOM CAR • VERSIÓN VEHICULAR NATIVA",
                            color = CarPlayColors.TextMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )

                        if (lastCrashTrace.isNotEmpty()) {
                            TextButton(
                                onClick = { showCrashModal = true },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF87171)),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("⚠️ Diagnóstico", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // COLUMNA DERECHA: PASO 3 (SELECCIONAR CARRO BOMBA)
                Column(
                    modifier = Modifier
                        .weight(1.3f)
                        .fillMaxHeight()
                        .carPlayCard(cornerRadius = 20.dp, bgAlpha = 0.94f)
                        .verticalScroll(rememberScrollState())
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "PASO 3: SELECCIONAR CARRO BOMBA",
                                    color = if (isStep2Done) CarPlayColors.AccentCyan else CarPlayColors.TextMuted,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = "Seleccione la unidad a la que pertenecerá esta pantalla táctica.",
                                    color = CarPlayColors.TextSecondary,
                                    fontSize = 11.sp
                                )
                            }

                            if (isStep1Done) {
                                IconButton(
                                    onClick = { viewModel.fetchVehiclesList() },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(Color.White.copy(alpha = 0.08f), CircleShape)
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Recargar", tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (!isStep1Done || !isStep2Done) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(240.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.Black.copy(alpha = 0.3f))
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                                    Text("🔒", fontSize = 28.sp)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = if (!isStep1Done) "Complete el Paso 1 (Licencia) para desbloquear las unidades." else "Se requiere autorización de Comandancia (Paso 2).",
                                        color = CarPlayColors.TextMuted,
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        } else if (viewModel.availableVehicles.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(240.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.Black.copy(alpha = 0.3f))
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                                    CircularProgressIndicator(color = CarPlayColors.AccentCyan, modifier = Modifier.size(28.dp))
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text("Cargando flota de vehículos...", color = CarPlayColors.TextMuted, fontSize = 12.sp)
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                viewModel.availableVehicles.forEach { vehicle ->
                                    val isSelected = (selectedVehicle?.idCarro ?: viewModel.selectedUnitId) == vehicle.idCarro
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (isSelected) CarPlayColors.PrimaryBlue.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.05f))
                                            .border(1.dp, if (isSelected) CarPlayColors.AccentCyan else Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                            .clickable { selectedVehicle = vehicle }
                                            .padding(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(36.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (isSelected) CarPlayColors.AccentCyan.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = if (isSelected) CarPlayColors.AccentCyan else Color.White, modifier = Modifier.size(20.dp))
                                                }
                                                Column {
                                                    Text(vehicle.idCarro, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
                                                    Text("${vehicle.tipo} • ${vehicle.patente}", color = CarPlayColors.TextSecondary, fontSize = 11.sp)
                                                }
                                            }
                                            if (isSelected) {
                                                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = CarPlayColors.AccentCyan, modifier = Modifier.size(22.dp))
                                            }
                                        }
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(10.dp))

                                Button(
                                    onClick = {
                                        val v = selectedVehicle ?: viewModel.availableVehicles.find { it.idCarro == viewModel.selectedUnitId }
                                        if (v != null) {
                                            viewModel.selectVehicle(v.idCarro, v.idCarro, v.patente, v.tipo)
                                        }
                                        onSetupCompleted()
                                    },
                                    enabled = selectedVehicle != null || viewModel.selectedUnitId.isNotEmpty(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CarPlayColors.PrimaryGreen,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Text("GUARDAR Y ABRIR PANTALLA TÁCTICA", fontWeight = FontWeight.Black, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showCrashModal) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { showCrashModal = false }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .fillMaxHeight(0.85f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF0F172A))
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                        .padding(20.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "📋 REGISTRO DE ERROR / DIAGNÓSTICO",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black
                                )
                                IconButton(onClick = { showCrashModal = false }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Cerrar", tint = Color.White)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = lastCrashTrace.ifEmpty { "No hay registros de error reportados." },
                                    color = Color(0xFFFCA5A5),
                                    fontSize = 11.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    prefs.edit().remove("last_crash_trace").commit()
                                    lastCrashTrace = ""
                                    showCrashModal = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CarPlayColors.ButtonDanger),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).height(46.dp)
                            ) {
                                Text("BORRAR REGISTRO", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }

                            Button(
                                onClick = { showCrashModal = false },
                                colors = ButtonDefaults.buttonColors(containerColor = CarPlayColors.PrimaryBlue),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).height(46.dp)
                            ) {
                                Text("ENTENDIDO", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
