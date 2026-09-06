package com.sisbom.sisbomtel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sisbom.sisbomtel.TelViewModel

@Composable
fun SetupWizard(
    viewModel: TelViewModel,
    onSetupCompleted: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var licenseInput by remember { mutableStateOf(viewModel.saasLicenseKey) }
    var comandanteIdInput by remember { mutableStateOf("") }
    var comandantePassInput by remember { mutableStateOf("") }

    val isStep1Done = viewModel.isLicenseValid
    val isStep2Done = viewModel.isComandanteAuthenticated

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(TelColors.SurfaceDark, TelColors.BackgroundDark),
                    radius = 1600f
                )
            )
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // HEADER
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (viewModel.saasLogoUrl.isNotEmpty()) {
                    AsyncImage(
                        model = viewModel.saasLogoUrl,
                        contentDescription = "Logo",
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                } else {
                    Icon(
                        imageVector = Icons.Filled.Phone,
                        contentDescription = "Logo",
                        tint = TelColors.PrimaryRedGlow,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Column {
                    Text(
                        text = "SENTINEL LINK",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = if (viewModel.saasClientName.isNotEmpty()) viewModel.saasClientName.uppercase() else "CENTRAL DE ALARMAS",
                        color = TelColors.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // TARJETA PASO 1: LICENCIA SAAS
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .telCard(cornerRadius = 18.dp)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Key,
                            contentDescription = "Paso 1",
                            tint = if (isStep1Done) TelColors.AccentGreen else TelColors.PrimaryRedGlow,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "PASO 1: LICENCIA CENTRAL",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    if (isStep1Done) {
                        Text(
                            text = "✓ ACTIVADA",
                            color = TelColors.AccentGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                if (!isStep1Done) {
                    Text(
                        text = "Ingrese la clave de licencia provista por SisBom para conectar este teléfono con la Central.",
                        color = TelColors.TextSecondary,
                        fontSize = 11.sp
                    )

                    OutlinedTextField(
                        value = licenseInput,
                        onValueChange = { licenseInput = it.uppercase() },
                        label = { Text("Clave de Licencia (ej: SISBOM-XXXX-XXXX)", fontSize = 12.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = TelColors.PrimaryRedGlow,
                            unfocusedBorderColor = TelColors.CardBorder,
                            focusedLabelColor = TelColors.PrimaryRedGlow,
                            unfocusedLabelColor = TelColors.TextMuted
                        )
                    )

                    if (viewModel.saasActivationError.isNotEmpty()) {
                        Text(
                            text = "⚠️ ${viewModel.saasActivationError}",
                            color = Color(0xFFEF4444),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.activateLicense(licenseInput) {}
                        },
                        enabled = !viewModel.isActivatingLicense && licenseInput.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TelColors.PrimaryRed),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (viewModel.isActivatingLicense) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("ACTIVANDO...", fontWeight = FontWeight.Bold)
                        } else {
                            Text("ACTIVAR LICENCIA", fontWeight = FontWeight.Black)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF064E3B).copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .border(1.dp, TelColors.AccentGreen.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "OK",
                            tint = TelColors.AccentGreen,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = viewModel.saasClientName.ifEmpty { "Licencia Activa" },
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "Base de datos conectada en tiempo real",
                                color = TelColors.AccentGreen,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            // TARJETA PASO 2: AUTORIZACIÓN DE COMANDANTE (ID RADIAL 1)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .telCard(cornerRadius = 18.dp)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Security,
                            contentDescription = "Paso 2",
                            tint = if (isStep2Done) TelColors.AccentGreen else TelColors.AccentAmber,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "PASO 2: AUTORIZACIÓN COMANDANCIA",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    if (isStep2Done) {
                        Text(
                            text = "✓ AUTORIZADO",
                            color = TelColors.AccentGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                if (!isStep2Done) {
                    Text(
                        text = "Ingrese el Usuario y contraseña del Comandante para habilitar el teléfono.",
                        color = TelColors.TextSecondary,
                        fontSize = 11.sp
                    )

                    OutlinedTextField(
                        value = comandanteIdInput,
                        onValueChange = { comandanteIdInput = it },
                        label = { Text("Usuario", fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = null,
                                tint = TelColors.TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        ),
                        enabled = isStep1Done,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = TelColors.AccentAmber,
                            unfocusedBorderColor = TelColors.CardBorder,
                            focusedLabelColor = TelColors.AccentAmber,
                            unfocusedLabelColor = TelColors.TextMuted
                        )
                    )

                    OutlinedTextField(
                        value = comandantePassInput,
                        onValueChange = { comandantePassInput = it },
                        label = { Text("Contraseña", fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                tint = TelColors.TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        enabled = isStep1Done,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = TelColors.AccentAmber,
                            unfocusedBorderColor = TelColors.CardBorder,
                            focusedLabelColor = TelColors.AccentAmber,
                            unfocusedLabelColor = TelColors.TextMuted
                        )
                    )

                    if (viewModel.comandanteAuthError.isNotEmpty()) {
                        Text(
                            text = "⚠️ ${viewModel.comandanteAuthError}",
                            color = Color(0xFFEF4444),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.verifyComandante(comandanteIdInput, comandantePassInput) { success ->
                                if (success) {
                                    onSetupCompleted()
                                }
                            }
                        },
                        enabled = isStep1Done && !viewModel.isVerifyingComandante && comandanteIdInput.isNotBlank() && comandantePassInput.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TelColors.AccentAmber),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (viewModel.isVerifyingComandante) {
                            CircularProgressIndicator(
                                color = Color.Black,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("VERIFICANDO...", color = Color.Black, fontWeight = FontWeight.Bold)
                        } else {
                            Text("VALIDAR COMANDANTE", color = Color.Black, fontWeight = FontWeight.Black)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF064E3B).copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .border(1.dp, TelColors.AccentGreen.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "OK",
                            tint = TelColors.AccentGreen,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = viewModel.authorizedComandanteName.ifEmpty { "Comandante Autorizado" },
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "Autorización de ID Radial 1 confirmada",
                                color = TelColors.AccentGreen,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Button(
                        onClick = onSetupCompleted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TelColors.AccentGreen),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("ENTRAR A LA CENTRAL", color = Color.Black, fontWeight = FontWeight.Black)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
